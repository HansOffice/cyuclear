package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.cyuCBMclean.cyuclear.config.Settings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object CandidateChunkIndex {
    data class Key(val worldId: UUID, val x: Int, val z: Int)
    data class Selection(val candidates: Set<Key>, val fullScan: Boolean)
    data class ChunkCoord(val world: World, val x: Int, val z: Int)

    private val candidates = ConcurrentHashMap.newKeySet<Key>()
    private val scheduledCycles = AtomicLong(0L)

    fun mark(chunk: Chunk) {
        if (Settings.candidateIndexEnabled) candidates.add(key(chunk))
    }

    fun selection(origin: CleanupOrigin): Selection {
        if (!Settings.candidateIndexEnabled || origin != CleanupOrigin.SCHEDULED) {
            return Selection(emptySet(), true)
        }
        val cycle = scheduledCycles.incrementAndGet()
        val fullScan = cycle == 1L || cycle % Settings.candidateFullScanEveryCycles == 0L
        return if (fullScan) Selection(emptySet(), true) else Selection(candidates, false)
    }

    class Collector internal constructor(
        val fullScan: Boolean,
        private val worlds: List<World>,
        private val candidateKeys: List<Key>?
    ) {
        private val worldById = HashMap<UUID, World>(worlds.size)

        init {
            for (world in worlds) {
                worldById[world.uid] = world
            }
        }
        private var worldIndex = 0
        private var snapshot: Array<Chunk>? = null
        private var snapshotIndex = 0
        private var candidateIndex = 0
        private var finished = false

        fun done(): Boolean = finished

        fun poll(limit: Int): List<ChunkCoord> {
            if (finished || limit <= 0) return emptyList()
            val result = ArrayList<ChunkCoord>(limit.coerceAtMost(64))
            if (fullScan) drainFull(limit, result) else drainCandidates(limit, result)
            return result
        }

        private fun drainFull(limit: Int, result: ArrayList<ChunkCoord>) {
            while (result.size < limit) {
                val chunks = snapshot
                if (chunks == null || snapshotIndex >= chunks.size) {
                    snapshot = null
                    snapshotIndex = 0
                    if (worldIndex >= worlds.size) {
                        finished = true
                        return
                    }
                    val world = worlds[worldIndex++]
                    if (!Settings.isWorldEnabled(world.name)) continue
                    snapshot = world.loadedChunks
                    continue
                }
                val chunk = chunks[snapshotIndex++]
                result += ChunkCoord(chunk.world, chunk.x, chunk.z)
                candidates.remove(key(chunk))
            }
        }

        private fun drainCandidates(limit: Int, result: ArrayList<ChunkCoord>) {
            val keys = candidateKeys
            if (keys == null) {
                finished = true
                return
            }
            while (result.size < limit && candidateIndex < keys.size) {
                val key = keys[candidateIndex++]
                val world = worldById[key.worldId] ?: continue
                if (!Settings.isWorldEnabled(world.name)) continue
                if (!world.isChunkLoaded(key.x, key.z)) continue
                result += ChunkCoord(world, key.x, key.z)
                candidates.remove(key)
            }
            if (candidateIndex >= keys.size) finished = true
        }
    }

    fun collector(selection: Selection): Collector {
        val worlds = Bukkit.getWorlds()
        return if (selection.fullScan) {
            Collector(true, worlds, null)
        } else {
            Collector(false, worlds, selection.candidates.toList())
        }
    }

    fun fullScanCollector(): Collector = Collector(true, Bukkit.getWorlds(), null)

    fun consume(key: Key) {
        candidates.remove(key)
    }

    fun size(): Int = candidates.size

    fun reset() {
        candidates.clear()
        scheduledCycles.set(0L)
    }

    fun key(chunk: Chunk): Key = Key(chunk.world.uid, chunk.x, chunk.z)

    fun key(world: World, x: Int, z: Int): Key = Key(world.uid, x, z)
}

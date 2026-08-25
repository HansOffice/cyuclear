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
    data class Selection(val candidates: List<ChunkCoord>, val fullScan: Boolean)
    data class ChunkCoord(val world: World, val x: Int, val z: Int)

    // 64-bit 紧凑区块坐标打包算法：高32位为X坐标，低32位为Z坐标
    @JvmStatic
    fun packCoord(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    @JvmStatic
    fun unpackX(packed: Long): Int = (packed shr 32).toInt()

    @JvmStatic
    fun unpackZ(packed: Long): Int = packed.toInt()

    private val candidatesByWorld = ConcurrentHashMap<UUID, MutableSet<Long>>()
    private val scheduledCycles = AtomicLong(0L)

    fun mark(chunk: Chunk) {
        if (!Settings.candidateIndexEnabled) return
        val set = candidatesByWorld.computeIfAbsent(chunk.world.uid) { ConcurrentHashMap.newKeySet() }
        set.add(packCoord(chunk.x, chunk.z))
    }

    fun mark(world: World, x: Int, z: Int) {
        if (!Settings.candidateIndexEnabled) return
        val set = candidatesByWorld.computeIfAbsent(world.uid) { ConcurrentHashMap.newKeySet() }
        set.add(packCoord(x, z))
    }

    fun selection(origin: CleanupOrigin): Selection {
        if (!Settings.candidateIndexEnabled || origin != CleanupOrigin.SCHEDULED) {
            return Selection(emptyList(), true)
        }
        val cycle = scheduledCycles.incrementAndGet()
        val fullScan = cycle == 1L || cycle % Settings.candidateFullScanEveryCycles == 0L
        if (fullScan) {
            return Selection(emptyList(), true)
        }

        val worlds = Bukkit.getWorlds()
        val coords = ArrayList<ChunkCoord>(size().coerceAtMost(4096))
        for (world in worlds) {
            if (!Settings.isWorldEnabled(world.name)) continue
            val set = candidatesByWorld[world.uid] ?: continue
            for (packed in set) {
                val x = unpackX(packed)
                val z = unpackZ(packed)
                if (world.isChunkLoaded(x, z)) {
                    coords.add(ChunkCoord(world, x, z))
                }
            }
        }
        return Selection(coords, false)
    }

    class Collector internal constructor(
        val fullScan: Boolean,
        private val worlds: List<World>,
        private val candidateCoords: List<ChunkCoord>?
    ) {
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
                consume(chunk.world.uid, chunk.x, chunk.z)
            }
        }

        private fun drainCandidates(limit: Int, result: ArrayList<ChunkCoord>) {
            val coords = candidateCoords
            if (coords == null) {
                finished = true
                return
            }
            while (result.size < limit && candidateIndex < coords.size) {
                val coord = coords[candidateIndex++]
                if (!Settings.isWorldEnabled(coord.world.name)) continue
                if (!coord.world.isChunkLoaded(coord.x, coord.z)) continue
                result += coord
                consume(coord.world.uid, coord.x, coord.z)
            }
            if (candidateIndex >= coords.size) finished = true
        }
    }

    fun collector(selection: Selection): Collector {
        val worlds = Bukkit.getWorlds()
        return if (selection.fullScan) {
            Collector(true, worlds, null)
        } else {
            Collector(false, worlds, selection.candidates)
        }
    }

    fun fullScanCollector(): Collector = Collector(true, Bukkit.getWorlds(), null)

    fun consume(worldUid: UUID, x: Int, z: Int) {
        candidatesByWorld[worldUid]?.remove(packCoord(x, z))
    }

    fun consume(key: Key) {
        consume(key.worldId, key.x, key.z)
    }

    fun size(): Int {
        var total = 0
        for (set in candidatesByWorld.values) {
            total += set.size
        }
        return total
    }

    fun reset() {
        candidatesByWorld.clear()
        scheduledCycles.set(0L)
    }

    fun key(chunk: Chunk): Key = Key(chunk.world.uid, chunk.x, chunk.z)

    fun key(world: World, x: Int, z: Int): Key = Key(world.uid, x, z)
}

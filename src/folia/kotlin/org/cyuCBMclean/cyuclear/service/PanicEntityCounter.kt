package org.cyuCBMclean.cyuclear.service

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.entity.Entity
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.util.EntityUtils
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

object PanicEntityCounter {

    fun count(world: World, budget: Int, callback: (Int) -> Unit): PanicCountHandle {
        val limit = budget.coerceAtLeast(1)
        val estimate = world.entityCount
        val filtered = (Settings.entityMythicEnabled && Settings.mythicExcludeFromPanicCount) ||
            (Settings.entityCraftEngineEnabled && Settings.craftEngineExcludeFromPanicCount)
        if (estimate <= 0 || !filtered || estimate < limit) {
            callback(estimate.coerceAtLeast(0))
            return NoopPanicCountHandle
        }

        val chunkQueue = ArrayDeque<ChunkRef>()
        val excludeMythic = Settings.entityMythicEnabled && Settings.mythicExcludeFromPanicCount
        for (chunk in world.loadedChunks) {
            chunkQueue.addLast(ChunkRef(world, chunk.x, chunk.z, excludeMythic))
        }

        if (chunkQueue.isEmpty()) {
            callback(0)
            return NoopPanicCountHandle
        }

        val cancelled = AtomicBoolean(false)
        val total = AtomicInteger(0)
        val remaining = AtomicInteger(chunkQueue.size)
        val active = AtomicInteger(0)
        val finishQueued = AtomicBoolean(false)
        val regionScheduler = Bukkit.getRegionScheduler()
        val pumpTask = AtomicReference<ScheduledTask?>(null)

        fun tryFinish() {
            if (cancelled.get()) {
                pumpTask.get()?.cancel()
                return
            }
            if (remaining.get() > 0 || active.get() > 0) return
            if (!finishQueued.compareAndSet(false, true)) return

            Bukkit.getGlobalRegionScheduler().execute(Cyuclear.instance, Runnable {
                if (!cancelled.get()) {
                    pumpTask.get()?.cancel()
                    callback(total.get())
                }
            })
        }

        val scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            Cyuclear.instance,
            Consumer<ScheduledTask> { task ->
                if (cancelled.get()) {
                    task.cancel()
                    return@Consumer
                }
                var dispatchedThisTick = 0
                val maxDispatch = Settings.foliaDispatchChunksPerTick
                val maxActive = Settings.foliaMaxActiveRegionTasks

                while (remaining.get() > 0 && total.get() < limit && dispatchedThisTick < maxDispatch && active.get() < maxActive) {
                    val ref = chunkQueue.removeFirst()
                    remaining.decrementAndGet()
                    active.incrementAndGet()
                    dispatchedThisTick++

                    try {
                        regionScheduler.execute(Cyuclear.instance, ref.world, ref.x, ref.z, Runnable {
                            try {
                                if (!cancelled.get() && total.get() < limit) {
                                    val remainingLimit = limit - total.get()
                                    if (remainingLimit > 0) {
                                        total.addAndGet(countChunk(ref, remainingLimit))
                                    }
                                }
                            } catch (ex: Exception) {
                                Cyuclear.instance.logger.warning("实体计数失败，${ref.world.name} ${ref.x},${ref.z} - ${ex.message}")
                            } finally {
                                active.decrementAndGet()
                                tryFinish()
                            }
                        })
                    } catch (ex: Exception) {
                        active.decrementAndGet()
                        Cyuclear.instance.logger.warning("实体计数派发失败，${ref.world.name} ${ref.x},${ref.z} - ${ex.message}")
                    }
                }

                if (total.get() >= limit) {
                    remaining.set(0)
                }

                tryFinish()
            },
            1L,
            1L
        )
        pumpTask.set(scheduledTask)

        return object : PanicCountHandle {
            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    pumpTask.getAndSet(null)?.cancel()
                }
            }
        }
    }

    private fun countChunk(ref: ChunkRef, limit: Int): Int {
        if (!ref.world.isChunkLoaded(ref.x, ref.z)) return 0

        return countChunk(ref.world.getChunkAt(ref.x, ref.z), ref.excludeMythic, limit)
    }

    private fun countChunk(chunk: Chunk, excludeMythic: Boolean, limit: Int): Int {
        if (!chunk.isLoaded || limit <= 0) return 0
        if (!excludeMythic) return minOf(chunk.entities.size, limit)

        var count = 0
        for (entity in chunk.entities) {
            if (shouldCount(entity)) {
                count++
                if (count >= limit) break
            }
        }
        return count
    }

    private fun shouldCount(entity: Entity): Boolean {
        return !Settings.entityMythicEnabled ||
            !Settings.mythicExcludeFromPanicCount ||
            !EntityUtils.shouldIgnoreForPanicCount(entity)
    }

    private data class ChunkRef(val world: World, val x: Int, val z: Int, val excludeMythic: Boolean)
}

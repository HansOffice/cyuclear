package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.scheduler.BukkitTask
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.util.EntityUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object PanicEntityCounter {

    fun count(world: World, budget: Int, callback: (Int) -> Unit): PanicCountHandle {
        val limit = budget.coerceAtLeast(1)
        val chunks = world.loadedChunks
        if (chunks.isEmpty()) {
            callback(0)
            return NoopPanicCountHandle
        }

        val cancelled = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val index = AtomicInteger(0)
        val total = AtomicInteger(0)
        val excludeMythic = Settings.entityMythicEnabled && Settings.mythicExcludeFromPanicCount
        var task: BukkitTask? = null

        val runner = Runnable {
            if (cancelled.get()) {
                task?.cancel()
                return@Runnable
            }

            val startedAt = System.nanoTime()
            val maxChunks = Settings.scanMaxChunksPerTick
            val maxNanos = Settings.scanMaxMillisPerTick * 1_000_000L
            var processed = 0

            while (processed < maxChunks && index.get() < chunks.size && total.get() < limit) {
                if (processed > 0 && System.nanoTime() - startedAt >= maxNanos) break
                val chunk = chunks[index.getAndIncrement()]
                processed++
                val remaining = limit - total.get()
                if (remaining > 0) {
                    total.addAndGet(countChunk(chunk, excludeMythic, remaining))
                }
            }

            if (index.get() < chunks.size && total.get() < limit) return@Runnable
            task?.cancel()
            if (!cancelled.get() && completed.compareAndSet(false, true)) {
                callback(total.get())
            }
        }

        task = Bukkit.getScheduler().runTaskTimer(Cyuclear.instance, runner, 1L, 1L)
        return object : PanicCountHandle {
            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    task.cancel()
                }
            }
        }
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
}

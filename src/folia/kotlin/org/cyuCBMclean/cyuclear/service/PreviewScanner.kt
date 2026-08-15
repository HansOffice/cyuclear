package org.cyuCBMclean.cyuclear.service

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Item
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.bridge.pokemon.PokemonEntityHook
import org.cyuCBMclean.cyuclear.config.Settings
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

object PreviewScanner {

    private class PreviewSession(
        val generation: Long,
        val report: PreviewReport,
        val callback: (PreviewReport) -> Unit
    )

    private val chunkQueue = ConcurrentLinkedQueue<ChunkRef>()
    private var task: ScheduledTask? = null
    private val remainingQueue = AtomicInteger(0)
    private val activeTasks = AtomicInteger(0)
    private val finishQueued = AtomicBoolean(false)
    private val scanGeneration = AtomicLong(0L)
    private val pendingGeneration = AtomicLong(0L)
    private val startPending = AtomicBoolean(false)
    private val stateLock = Any()
    private var activeSession: PreviewSession? = null
    @Volatile
    private var collector: CandidateChunkIndex.Collector? = null

    @Volatile
    private var scanRunning = false

    val isRunning: Boolean
        get() = scanRunning || startPending.get()

    fun start(cleanItems: Boolean, cleanEntities: Boolean, callback: (PreviewReport) -> Unit): Boolean {
        if (!ActivationService.isActive()) return false
        val effectiveCleanItems = cleanItems && Settings.itemModuleEnabled
        val effectiveCleanEntities = cleanEntities && Settings.entityModuleEnabled
        if (!effectiveCleanItems && !effectiveCleanEntities) return false

        val generation = synchronized(stateLock) {
            if (scanRunning || activeTasks.get() > 0 || !startPending.compareAndSet(false, true)) {
                return false
            }
            scanGeneration.incrementAndGet().also { pendingGeneration.set(it) }
        }

        return try {
            Bukkit.getGlobalRegionScheduler().execute(Cyuclear.instance, Runnable {
                startOnGlobal(generation, cleanItems, cleanEntities, callback)
            })
            true
        } catch (ex: Exception) {
            clearPendingStart(generation)
            Cyuclear.instance.logger.warning("预演扫描启动失败：${ex.message}")
            false
        }
    }

    fun stop() {
        synchronized(stateLock) {
            scanGeneration.incrementAndGet()
            pendingGeneration.set(0L)
            startPending.set(false)
            task?.cancel()
            task = null
            chunkQueue.clear()
            remainingQueue.set(0)
            finishQueued.set(true)
            activeSession = null
            collector = null
            scanRunning = false
            PokemonEntityHook.endScanHold()
        }
    }

    private fun startOnGlobal(
        generation: Long,
        cleanItems: Boolean,
        cleanEntities: Boolean,
        callback: (PreviewReport) -> Unit
    ) {
        if (!isPendingStart(generation) || !ActivationService.isActive()) {
            clearPendingStart(generation)
            return
        }

        val effectiveCleanItems = cleanItems && Settings.itemModuleEnabled
        val effectiveCleanEntities = cleanEntities && Settings.entityModuleEnabled
        if (!effectiveCleanItems && !effectiveCleanEntities) {
            clearPendingStart(generation)
            return
        }

        val nextCollector = try {
            CandidateChunkIndex.fullScanCollector()
        } catch (ex: Exception) {
            clearPendingStart(generation)
            Cyuclear.instance.logger.warning("预演区块收集失败：${ex.message}")
            return
        }

        var immediate: PreviewSession? = null
        synchronized(stateLock) {
            if (!isPendingStart(generation) || !ActivationService.isActive() || scanRunning || activeTasks.get() > 0) {
                clearPendingStart(generation)
                return
            }

            chunkQueue.clear()
            remainingQueue.set(0)
            finishQueued.set(false)
            collector = nextCollector
            val session = PreviewSession(generation, PreviewReport(), callback)
            activeSession = session
            scanRunning = true
            PokemonEntityHook.beginScanHold()
            clearPendingStart(generation)

            val source = collector
            if (source == null || source.done()) {
                immediate = session
                return@synchronized
            }
            try {
                task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    Cyuclear.instance,
                    Consumer<ScheduledTask> { pump(session, effectiveCleanItems, effectiveCleanEntities) },
                    1L,
                    1L
                )
            } catch (ex: Exception) {
                task = null
                chunkQueue.clear()
                remainingQueue.set(0)
                finishQueued.set(true)
                activeSession = null
                collector = null
                scanRunning = false
                PokemonEntityHook.endScanHold()
                Cyuclear.instance.logger.warning("预演扫描调度失败：${ex.message}")
            }
        }
        immediate?.let(::finish)
    }

    private fun pump(session: PreviewSession, cleanItems: Boolean, cleanEntities: Boolean) {
        if (!isCurrent(session)) return
        fillQueue()
        var dispatchedThisTick = 0
        val regionScheduler = Bukkit.getRegionScheduler()

        while (
            isCurrent(session) &&
            dispatchedThisTick < Settings.foliaDispatchChunksPerTick &&
            activeTasks.get() < Settings.foliaMaxActiveRegionTasks
        ) {
            val ref = chunkQueue.poll() ?: break
            remainingQueue.decrementAndGet()
            activeTasks.incrementAndGet()
            dispatchedThisTick++

            try {
                regionScheduler.execute(Cyuclear.instance, ref.world, ref.x, ref.z, Runnable {
                    runRegionChunk(session, ref, cleanItems, cleanEntities)
                })
            } catch (ex: Exception) {
                synchronized(stateLock) {
                    activeTasks.decrementAndGet()
                }
                Cyuclear.instance.logger.warning("预演扫描派发失败：${ref.world.name} ${ref.x},${ref.z} - ${ex.message}")
            }
        }

        tryFinish(session)
    }

    private fun runRegionChunk(session: PreviewSession, ref: ChunkRef, cleanItems: Boolean, cleanEntities: Boolean) {
        try {
            if (!isCurrent(session)) return
            val complete = processChunk(session.report, ref, cleanItems, cleanEntities)
            if (!complete && isCurrent(session)) {
                chunkQueue.add(ref)
                remainingQueue.incrementAndGet()
            }
        } catch (ex: Exception) {
            Cyuclear.instance.logger.warning("预演区块扫描失败：${ref.world.name} ${ref.x},${ref.z} - ${ex.message}")
        } finally {
            synchronized(stateLock) {
                activeTasks.decrementAndGet()
                if (isCurrent(session)) {
                    tryFinish(session)
                }
            }
        }
    }

    private fun processChunk(report: PreviewReport, ref: ChunkRef, cleanItems: Boolean, cleanEntities: Boolean): Boolean {
        if (!ref.world.isChunkLoaded(ref.x, ref.z)) return true

        val chunk = ref.world.getChunkAt(ref.x, ref.z)
        val entities = chunk.entities
        val started = System.nanoTime()
        val budgetNanos = Settings.scanMaxMillisPerTick * 1_000_000L
        var visited = 0
        var complete = true

        for (entity in entities) {
            if (visited > 0 && System.nanoTime() - started >= budgetNanos) {
                complete = false
                break
            }
            visited++
            if (entity is Item) {
                if (!cleanItems) continue
                val decision = CleanupFilter.explainItem(entity)
                if (decision.remove) report.removeItems.incrementAndGet()
                report.record(decision)
            } else {
                if (!cleanEntities) continue
                val decision = CleanupFilter.explainEntity(entity)
                if (decision.remove) report.removeEntities.incrementAndGet()
                report.record(decision)
            }
        }

        report.scanned.addAndGet(visited)
        if (complete) report.chunks.incrementAndGet()
        return complete
    }

    private fun tryFinish(session: PreviewSession) {
        if (!isCurrent(session)) return
        val source = collector
        if ((source != null && !source.done()) || remainingQueue.get() > 0 || activeTasks.get() > 0) return
        if (!finishQueued.compareAndSet(false, true)) return
        Bukkit.getGlobalRegionScheduler().execute(Cyuclear.instance, Runnable { finish(session) })
    }

    private fun finish(session: PreviewSession) {
        val callback = synchronized(stateLock) {
            if (!isCurrent(session)) {
                null
            } else {
                task?.cancel()
                task = null
                chunkQueue.clear()
                remainingQueue.set(0)
                activeSession = null
                collector = null
                scanRunning = false
                PokemonEntityHook.endScanHold()
                session.callback
            }
        }
        callback?.invoke(session.report)
    }

    private fun fillQueue() {
        val source = collector ?: return
        if (source.done()) return
        if (remainingQueue.get() >= Settings.foliaDispatchChunksPerTick) return
        val batch = source.poll(Settings.foliaDispatchChunksPerTick)
        if (batch.isEmpty()) return
        for (coord in batch) {
            chunkQueue.add(ChunkRef(coord.world, coord.x, coord.z))
            remainingQueue.incrementAndGet()
        }
    }

    private fun isCurrent(session: PreviewSession): Boolean {
        return scanRunning && activeSession === session && ActivationService.isActive() && scanGeneration.get() == session.generation
    }

    private fun isPendingStart(generation: Long): Boolean {
        return startPending.get() && pendingGeneration.get() == generation
    }

    private fun clearPendingStart(generation: Long) {
        if (pendingGeneration.compareAndSet(generation, 0L)) {
            startPending.set(false)
        }
    }

    private data class ChunkRef(val world: World, val x: Int, val z: Int)
}

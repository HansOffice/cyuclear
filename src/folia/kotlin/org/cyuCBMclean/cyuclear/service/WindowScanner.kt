package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.World
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.bridge.pokemon.PokemonEntityHook
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.util.TimeFormat
import org.cyuCBMclean.cyuclear.cluster.ClusterManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

object WindowScanner {

    private val chunkQueue = ConcurrentLinkedQueue<ChunkRef>()
    private var foliaTask: ScheduledTask? = null
    private val remainingQueue = AtomicInteger(0)
    private val activeTasks = AtomicInteger(0)
    private val finishQueued = AtomicBoolean(false)
    private val localItems = AtomicInteger(0)
    private val localEntities = AtomicInteger(0)
    private val processedChunks = AtomicInteger(0)
    private val scannedEntities = AtomicInteger(0)
    private val processNanos = AtomicLong(0L)
    private val maxChunkNanos = AtomicLong(0L)
    private val dispatchedChunks = AtomicInteger(0)
    private val tickRounds = AtomicInteger(0)
    private val scanGeneration = AtomicLong(0L)
    private val pendingGeneration = AtomicLong(0L)
    private val startPending = AtomicBoolean(false)
    private val stateLock = Any()
    @Volatile
    private var usedFullScan = true
    @Volatile
    private var collector: CandidateChunkIndex.Collector? = null

    @Volatile
    private var scanRunning = false

    val isRunning: Boolean
        get() = scanRunning || startPending.get()

    private var clearedItems = 0
    private var clearedEntities = 0
    private var startTimeStamp = 0L
    private var queuedChunks = 0

    @Volatile
    var lastClearedItems = 0
        private set
    @Volatile
    var lastClearedEntities = 0
        private set
    @Volatile
    var lastTimeCost = 0L
        private set

    fun startScan(request: CleanupRequest): Boolean = startScan(request, null)

    fun startChunkScan(request: CleanupRequest, world: World, chunkX: Int, chunkZ: Int): Boolean {
        return startScan(request, ChunkRef(world, chunkX, chunkZ))
    }

    private fun startScan(request: CleanupRequest, target: ChunkRef?): Boolean {
        if (!ActivationService.isActive()) return false
        val effectiveCleanItems = request.cleanItems && Settings.itemModuleEnabled
        val effectiveCleanEntities = request.cleanEntities && Settings.entityModuleEnabled
        if (!effectiveCleanItems && !effectiveCleanEntities) {
            return false
        }

        val generation = synchronized(stateLock) {
            if (scanRunning || activeTasks.get() > 0 || !startPending.compareAndSet(false, true)) {
                return false
            }
            scanGeneration.incrementAndGet().also { pendingGeneration.set(it) }
        }

        return try {
            Bukkit.getGlobalRegionScheduler().execute(Cyuclear.instance, Runnable {
                startScanOnGlobal(generation, request, target)
            })
            true
        } catch (ex: Exception) {
            clearPendingStart(generation)
            Cyuclear.instance.logger.warning("清理任务启动失败：${ex.message}")
            false
        }
    }

    private fun startScanOnGlobal(generation: Long, request: CleanupRequest, target: ChunkRef?) {
        if (!isPendingStart(generation) || !ActivationService.isActive()) {
            clearPendingStart(generation)
            return
        }

        val effectiveCleanItems = request.cleanItems && Settings.itemModuleEnabled
        val effectiveCleanEntities = request.cleanEntities && Settings.entityModuleEnabled
        if (!effectiveCleanItems && !effectiveCleanEntities) {
            clearPendingStart(generation)
            return
        }

        val selection = if (target == null) CandidateChunkIndex.selection(request.origin) else null
        val queueStart = System.nanoTime()
        val nextCollector = try {
            selection?.let(CandidateChunkIndex::collector)
        } catch (ex: Exception) {
            clearPendingStart(generation)
            Cyuclear.instance.logger.warning("清理区块收集失败：${ex.message}")
            return
        }
        synchronized(stateLock) {
            if (!isPendingStart(generation) || !ActivationService.isActive() || scanRunning || activeTasks.get() > 0) {
                clearPendingStart(generation)
                return
            }

            chunkQueue.clear()
            remainingQueue.set(0)
            finishQueued.set(false)
            localItems.set(0)
            localEntities.set(0)
            processedChunks.set(0)
            scannedEntities.set(0)
            processNanos.set(0L)
            maxChunkNanos.set(0L)
            dispatchedChunks.set(0)
            tickRounds.set(0)
            usedFullScan = selection?.fullScan ?: false
            collector = nextCollector
            clearedItems = 0
            clearedEntities = 0
            startTimeStamp = System.currentTimeMillis()
            queuedChunks = 0
            if (target != null) {
                chunkQueue.add(target)
                remainingQueue.set(1)
                queuedChunks = 1
            }

            val run = CleanupRunManager.begin(request, usedFullScan)
            val collectItemsForRecovery = Settings.binEnabled && effectiveCleanItems && request.recoveryEnabled && !run.capturesRecovery
            val cleanupPass = CleanupChunkProcessor.CleanupPass(
                effectiveCleanItems,
                effectiveCleanEntities,
                collectItemsForRecovery,
                request.origin != CleanupOrigin.PANIC
            )
            val isRunCurrent = { isCurrent(generation) }
            run.updateQueuedChunks(queuedChunks)
            scanRunning = true
            PokemonEntityHook.beginScanHold()
            clearPendingStart(generation)

            CleanupNoticeManager.clearBossBar()
            if (collectItemsForRecovery) {
                VoidBinManager.beginScan()
            }
            CleanupAudit.begin()
            CleanupTimings.reset(Settings.cleanupStageTimings)
            if (Settings.cleanupStageTimings) {
                CleanupTimings.queueSince(queueStart)
            }
            SoundNoticeManager.broadcast(SoundNoticeManager.Event.CLEANUP_START)

            if (chunkQueue.isEmpty()) {
                val source = collector
                if (source == null || source.done()) {
                    finishScan(generation, collectItemsForRecovery, run)
                    return
                }
            }
            try {
                foliaTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    Cyuclear.instance,
                    Consumer<ScheduledTask> {
                        pump(
                            generation,
                            cleanupPass,
                            collectItemsForRecovery,
                            run,
                            isRunCurrent
                        )
                    },
                    1L,
                    1L
                )
            } catch (ex: Exception) {
                run.recordFailure("系统", 0, 0, ex.message)
                CleanupRunManager.cancelActive()
                scanRunning = false
                finishQueued.set(true)
                chunkQueue.clear()
                remainingQueue.set(0)
                collector = null
                PokemonEntityHook.endScanHold()
                Cyuclear.instance.logger.warning("清理任务调度失败：${ex.message}")
            }
        }
    }

    fun stop() {
        synchronized(stateLock) {
            scanGeneration.incrementAndGet()
            pendingGeneration.set(0L)
            startPending.set(false)
            CleanupRunManager.cancelActive()
            scanRunning = false
            finishQueued.set(true)
            foliaTask?.cancel()
            foliaTask = null
            chunkQueue.clear()
            remainingQueue.set(0)
            collector = null
            PokemonEntityHook.endScanHold()
        }
    }

    private fun pump(
        generation: Long,
        cleanupPass: CleanupChunkProcessor.CleanupPass,
        collectItemsForRecovery: Boolean,
        run: CleanupRunManager.RunHandle,
        isRunCurrent: () -> Boolean
    ) {
        if (!isCurrent(generation)) return
        tickRounds.incrementAndGet()
        fillQueue(run)

        var dispatchedThisTick = 0
        val maxDispatch = Settings.foliaDispatchChunksPerTick
        val maxActive = Settings.foliaMaxActiveRegionTasks
        val regionScheduler = Bukkit.getRegionScheduler()

        while (isCurrent(generation) && dispatchedThisTick < maxDispatch && activeTasks.get() < maxActive) {
            val first = chunkQueue.poll() ?: break
            remainingQueue.decrementAndGet()

            // 同一 Folia Region (8x8 chunks) 的连续区块合并为一个 Region Task 执行
            val batch = ArrayList<ChunkRef>(8)
            batch.add(first)
            val world = first.world
            val rx = first.x shr 3
            val rz = first.z shr 3

            while (batch.size < 8 && dispatchedThisTick + batch.size < maxDispatch) {
                val next = chunkQueue.peek() ?: break
                if (next.world == world && (next.x shr 3) == rx && (next.z shr 3) == rz) {
                    chunkQueue.poll()
                    remainingQueue.decrementAndGet()
                    batch.add(next)
                } else {
                    break
                }
            }

            activeTasks.incrementAndGet()
            dispatchedChunks.addAndGet(batch.size)
            dispatchedThisTick += batch.size

            try {
                regionScheduler.execute(Cyuclear.instance, world, first.x, first.z, Runnable {
                    runRegionBatch(generation, batch, cleanupPass, collectItemsForRecovery, run, isRunCurrent)
                })
            } catch (ex: Exception) {
                synchronized(stateLock) {
                    activeTasks.decrementAndGet()
                }
                if (Language.isEnglish) {
                    Cyuclear.instance.logger.warning("Chunk cleanup dispatch failed: ${world.name} ${first.x},${first.z} - ${ex.message}")
                } else {
                    Cyuclear.instance.logger.warning("区块清理派发失败：${world.name} ${first.x},${first.z} - ${ex.message}")
                }
                for (ref in batch) {
                    run.recordFailure(ref.world.name, ref.x, ref.z, ex.message)
                }
            }
        }

        tryFinish(generation, collectItemsForRecovery, run)
    }

    private fun runRegionBatch(
        generation: Long,
        batch: List<ChunkRef>,
        cleanupPass: CleanupChunkProcessor.CleanupPass,
        collectItemsForRecovery: Boolean,
        run: CleanupRunManager.RunHandle,
        isRunCurrent: () -> Boolean
    ) {
        var processedCount = 0
        try {
            for (i in batch.indices) {
                if (!isCurrent(generation)) break
                val ref = batch[i]
                try {
                    val result = processChunk(ref, cleanupPass, run, isRunCurrent)
                    localItems.addAndGet(result.items)
                    localEntities.addAndGet(result.entities)
                    scannedEntities.addAndGet(result.scannedEntities)
                } catch (ex: Exception) {
                    if (Language.isEnglish) {
                        Cyuclear.instance.logger.warning("Chunk cleanup failed: ${ref.world.name} ${ref.x},${ref.z} - ${ex.message}")
                    } else {
                        Cyuclear.instance.logger.warning("区块清理失败：${ref.world.name} ${ref.x},${ref.z} - ${ex.message}")
                    }
                    run.recordFailure(ref.world.name, ref.x, ref.z, ex.message)
                }
                processedCount++
            }
        } finally {
            synchronized(stateLock) {
                activeTasks.decrementAndGet()
                if (isCurrent(generation)) {
                    processedChunks.addAndGet(processedCount)
                    tryFinish(generation, collectItemsForRecovery, run)
                }
            }
        }
    }

    private fun processChunk(
        ref: ChunkRef,
        cleanupPass: CleanupChunkProcessor.CleanupPass,
        run: CleanupRunManager.RunHandle,
        isRunCurrent: () -> Boolean
    ): CleanupChunkProcessor.Result {
        if (!isRunCurrent()) return CleanupChunkProcessor.Result(0, 0, 0)
        if (!ref.world.isChunkLoaded(ref.x, ref.z)) {
            return CleanupChunkProcessor.Result(0, 0, 0)
        }

        val chunkStart = System.nanoTime()
        val chunk = ref.world.getChunkAt(ref.x, ref.z)
        val result = CleanupChunkProcessor.processWhileCurrent(chunk, cleanupPass, run, isRunCurrent)

        val chunkNanos = System.nanoTime() - chunkStart
        processNanos.addAndGet(chunkNanos)
        updateMax(maxChunkNanos, chunkNanos)
        HotspotTracker.recordCleanup(ref.world.name, ref.x, ref.z, result.items, result.entities, chunkNanos)
        run.recordChunk(ref.world.name, ref.x, ref.z, chunkNanos)

        return result
    }

    private fun tryFinish(generation: Long, collectItemsForRecovery: Boolean, run: CleanupRunManager.RunHandle) {
        if (!isCurrent(generation)) return
        val source = collector
        if ((source != null && !source.done()) || remainingQueue.get() > 0 || activeTasks.get() > 0) return
        if (!finishQueued.compareAndSet(false, true)) return

        Bukkit.getGlobalRegionScheduler().execute(Cyuclear.instance, Runnable {
            synchronized(stateLock) {
                if (!isCurrent(generation)) return@Runnable
                clearedItems = localItems.get()
                clearedEntities = localEntities.get()
                finishScan(generation, collectItemsForRecovery, run)
            }
        })
    }

    private fun fillQueue(run: CleanupRunManager.RunHandle) {
        val source = collector ?: return
        if (source.done()) return
        if (remainingQueue.get() >= Settings.foliaDispatchChunksPerTick) return
        val batch = source.poll(Settings.foliaDispatchChunksPerTick)
        if (batch.isEmpty()) return
        for (coord in batch) {
            chunkQueue.add(ChunkRef(coord.world, coord.x, coord.z))
            remainingQueue.incrementAndGet()
        }
        queuedChunks += batch.size
        run.updateQueuedChunks(queuedChunks)
    }

    private fun isCurrent(generation: Long): Boolean {
        return scanRunning && ActivationService.isActive() && scanGeneration.get() == generation
    }

    private fun finishScan(generation: Long, collectItemsForRecovery: Boolean, run: CleanupRunManager.RunHandle) {
        synchronized(stateLock) {
            if (!isCurrent(generation)) return
            scanRunning = false
            chunkQueue.clear()
            remainingQueue.set(0)
            collector = null
            PokemonEntityHook.endScanHold()
            foliaTask?.cancel()
            foliaTask = null

            val timeCost = System.currentTimeMillis() - startTimeStamp
            lastClearedItems = clearedItems
            lastClearedEntities = clearedEntities
            lastTimeCost = timeCost
            run.finish(CleanupRunManager.Status.COMPLETED, queuedChunks, processedChunks.get(), timeCost)

            logDetailStats(timeCost)
            CleanupAudit.flush()
            ClusterManager.completeSynchronizedScan(clearedItems, clearedEntities, timeCost)

            val summaryMessage = Language.get(
                "cleanup-summary",
                "time" to timeCost.toString(),
                "time_text" to TimeFormat.cleanupDuration(timeCost),
                "items" to clearedItems.toString(),
                "entities" to clearedEntities.toString()
            )
            SoundNoticeManager.broadcast(SoundNoticeManager.Event.CLEANUP_COMPLETE)

            if (collectItemsForRecovery && clearedItems > 0 && VoidBinManager.hasItems()) {
                VoidBinManager.openWindow(Settings.voidBinExpireSeconds)
                if (VoidBinManager.expireTime > 0L) {
                    VoidBinNoticeManager.broadcastCleanupSummary(summaryMessage, Settings.voidBinExpireSeconds)
                    return
                }
            }

            VoidBinNoticeManager.broadcastCleanupSummary(summaryMessage, null)
        }
    }

    private fun isPendingStart(generation: Long): Boolean {
        return startPending.get() && pendingGeneration.get() == generation
    }

    private fun clearPendingStart(generation: Long) {
        if (pendingGeneration.compareAndSet(generation, 0L)) {
            startPending.set(false)
        }
    }

    private fun logDetailStats(timeCost: Long) {
        if (!Settings.cleanupDetailStats) return
        val isEn = Language.isEnglish
        val header = if (isEn) "Cleanup Performance Stats" else "清理性能统计"
        Cyuclear.instance.logger.info(
            "$header: profile=${Settings.performanceProfile}, chunks=${processedChunks.get()}/$queuedChunks, " +
                "dispatched=${dispatchedChunks.get()}, entitiesScanned=${scannedEntities.get()}, " +
                "work=${TimeFormat.compactMillis(processNanos.get() / 1_000_000L)}, maxChunk=${TimeFormat.compactMillis(maxChunkNanos.get() / 1_000_000L)}, " +
                "rounds=${tickRounds.get()}, total=${TimeFormat.compactMillis(timeCost)}, activeLimit=${Settings.foliaMaxActiveRegionTasks}, " +
                "dispatchPerTick=${Settings.foliaDispatchChunksPerTick}, index=${if (usedFullScan) "full" else "candidate"}, " +
                "pendingCandidates=${CandidateChunkIndex.size()}"
        )
        if (Settings.cleanupStageTimings) {
            val stageHeader = if (isEn) "Cleanup Stage Breakdown" else "清理阶段耗时"
            Cyuclear.instance.logger.info("$stageHeader: ${CleanupTimings.text(timeCost)}")
        }
    }

    private fun updateMax(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (value <= current || target.compareAndSet(current, value)) {
                return
            }
        }
    }

    private data class ChunkRef(val world: World, val x: Int, val z: Int)

}

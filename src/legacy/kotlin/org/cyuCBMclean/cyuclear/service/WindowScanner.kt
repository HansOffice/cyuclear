package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.World
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.bridge.pokemon.PokemonEntityHook
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.util.TimeFormat
import org.cyuCBMclean.cyuclear.cluster.ClusterManager
import java.util.ArrayDeque

object WindowScanner {

    private val chunkQueue = ArrayDeque<ChunkRef>()
    @Volatile
    var isRunning: Boolean = false
        private set

    private var clearedItems = 0
    private var clearedEntities = 0
    private var startTimeStamp = 0L
    private var queuedChunks = 0
    private var processedChunks = 0
    private var scannedEntities = 0
    private var processNanos = 0L
    private var maxChunkNanos = 0L
    private var tickRounds = 0
    private var usedFullScan = true
    private var collector: CandidateChunkIndex.Collector? = null

    var lastClearedItems = 0
        private set
    var lastClearedEntities = 0
        private set
    var lastTimeCost = 0L
        private set

    private var bukkitTask: org.bukkit.scheduler.BukkitTask? = null

    fun startScan(request: CleanupRequest): Boolean = startScan(request, null)

    fun startChunkScan(request: CleanupRequest, world: World, chunkX: Int, chunkZ: Int): Boolean {
        return startScan(request, ChunkRef(world, chunkX, chunkZ))
    }

    private fun startScan(request: CleanupRequest, target: ChunkRef?): Boolean {
        if (!ActivationService.isActive()) return false
        if (isRunning) return false

        val effectiveCleanItems = request.cleanItems && Settings.itemModuleEnabled
        val effectiveCleanEntities = request.cleanEntities && Settings.entityModuleEnabled
        if (!effectiveCleanItems && !effectiveCleanEntities) {
            return false
        }

        chunkQueue.clear()
        CleanupNoticeManager.clearBossBar()
        isRunning = true
        PokemonEntityHook.beginScanHold()
        clearedItems = 0
        clearedEntities = 0
        startTimeStamp = System.currentTimeMillis()
        queuedChunks = 0
        processedChunks = 0
        scannedEntities = 0
        processNanos = 0L
        maxChunkNanos = 0L
        tickRounds = 0
        val selection = if (target == null) CandidateChunkIndex.selection(request.origin) else null
        usedFullScan = selection?.fullScan ?: false
        collector = selection?.let(CandidateChunkIndex::collector)
        val run = CleanupRunManager.begin(request, usedFullScan)
        val collectItemsForRecovery = Settings.binEnabled && effectiveCleanItems && request.recoveryEnabled && !run.capturesRecovery
        val cleanupPass = CleanupChunkProcessor.CleanupPass(
            effectiveCleanItems,
            effectiveCleanEntities,
            collectItemsForRecovery,
            request.origin != CleanupOrigin.PANIC
        )

        if (collectItemsForRecovery) {
            VoidBinManager.beginScan()
        }
        CleanupAudit.begin()
        CleanupTimings.reset(Settings.cleanupStageTimings)

        SoundNoticeManager.broadcast(SoundNoticeManager.Event.CLEANUP_START)

        if (target != null) {
            chunkQueue.addLast(target)
            queuedChunks = 1
            run.updateQueuedChunks(1)
        }

        bukkitTask = Bukkit.getScheduler().runTaskTimer(Cyuclear.instance, Runnable {
            tickRounds++
            val tickStart = System.nanoTime()
            val maxChunks = Settings.scanMaxChunksPerTick
            val budgetNanos = Settings.scanMaxMillisPerTick * 1_000_000L
            val shouldContinue = { System.nanoTime() - tickStart < budgetNanos }
            var processedThisTick = 0

            while (processedThisTick < maxChunks) {
                if (processedThisTick > 0 && !shouldContinue()) break
                if (chunkQueue.isEmpty()) {
                    fillQueue(run)
                    if (chunkQueue.isEmpty()) {
                        val source = collector
                        if (source == null || source.done()) {
                            finishScan(collectItemsForRecovery, run)
                            bukkitTask?.cancel()
                            bukkitTask = null
                            return@Runnable
                        }
                        continue
                    }
                }

                val ref = chunkQueue.removeFirst()
                processedThisTick++
                if (!ref.world.isChunkLoaded(ref.x, ref.z)) {
                    processedChunks++
                    continue
                }
                try {
                    val chunkStart = System.nanoTime()
                    val chunk = ref.world.getChunkAt(ref.x, ref.z)
                    val result = CleanupChunkProcessor.process(chunk, cleanupPass, run, shouldContinue)
                    val chunkNanos = System.nanoTime() - chunkStart
                    processNanos += chunkNanos
                    if (chunkNanos > maxChunkNanos) {
                        maxChunkNanos = chunkNanos
                    }
                    scannedEntities += result.scannedEntities
                    clearedItems += result.items
                    clearedEntities += result.entities
                    HotspotTracker.recordCleanup(ref.world.name, ref.x, ref.z, result.items, result.entities, chunkNanos)
                    run.recordChunk(ref.world.name, ref.x, ref.z, chunkNanos)
                    if (!result.complete) {
                        chunkQueue.addFirst(ref)
                        break
                    }
                    processedChunks++
                } catch (ex: Exception) {
                    processedChunks++
                    Cyuclear.instance.logger.warning("区块清理失败：${ref.world.name} ${ref.x},${ref.z} - ${ex.message}")
                    run.recordFailure(ref.world.name, ref.x, ref.z, ex.message)
                }
            }
        }, 1L, 1L)

        return true
    }

    fun stop() {
        CleanupRunManager.cancelActive()
        isRunning = false
        bukkitTask?.cancel()
        bukkitTask = null
        chunkQueue.clear()
        collector = null
        PokemonEntityHook.endScanHold()
    }

    private fun fillQueue(run: CleanupRunManager.RunHandle) {
        val source = collector ?: return
        if (source.done()) return
        val batch = source.poll(Settings.scanMaxChunksPerTick)
        if (batch.isEmpty()) return
        for (coord in batch) {
            chunkQueue.addLast(ChunkRef(coord.world, coord.x, coord.z))
        }
        queuedChunks += batch.size
        run.updateQueuedChunks(queuedChunks)
    }

    private fun finishScan(collectItemsForRecovery: Boolean, run: CleanupRunManager.RunHandle) {
        isRunning = false
        chunkQueue.clear()
        collector = null
        PokemonEntityHook.endScanHold()

        val timeCost = System.currentTimeMillis() - startTimeStamp
        lastClearedItems = clearedItems
        lastClearedEntities = clearedEntities
        lastTimeCost = timeCost
        run.finish(CleanupRunManager.Status.COMPLETED, queuedChunks, processedChunks, timeCost)

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

    private fun logDetailStats(timeCost: Long) {
        if (!Settings.cleanupDetailStats) return
        Cyuclear.instance.logger.info(
            "清理性能统计: profile=${Settings.performanceProfile}, chunks=$processedChunks/$queuedChunks, " +
                "entitiesScanned=$scannedEntities, work=${TimeFormat.compactMillis(processNanos / 1_000_000L)}, " +
                "maxChunk=${TimeFormat.compactMillis(maxChunkNanos / 1_000_000L)}, rounds=$tickRounds, total=${TimeFormat.compactMillis(timeCost)}, " +
                "index=${if (usedFullScan) "full" else "candidate"}, pendingCandidates=${CandidateChunkIndex.size()}, " +
                "maxChunksPerTick=${Settings.scanMaxChunksPerTick}, budget=${Settings.scanMaxMillisPerTick}ms"
        )
        if (Settings.cleanupStageTimings) {
            Cyuclear.instance.logger.info("清理阶段耗时: ${CleanupTimings.text(timeCost)}")
        }
    }

    private data class ChunkRef(val world: World, val x: Int, val z: Int)

}

package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.entity.Item
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.bridge.pokemon.PokemonEntityHook
import org.cyuCBMclean.cyuclear.config.Settings
import java.util.ArrayDeque

object PreviewScanner {

    @Volatile
    var isRunning: Boolean = false
        private set

    private var task: org.bukkit.scheduler.BukkitTask? = null

    fun start(cleanItems: Boolean, cleanEntities: Boolean, callback: (PreviewReport) -> Unit): Boolean {
        if (!ActivationService.isActive()) return false
        if (isRunning) return false

        val effectiveCleanItems = cleanItems && Settings.itemModuleEnabled
        val effectiveCleanEntities = cleanEntities && Settings.entityModuleEnabled
        if (!effectiveCleanItems && !effectiveCleanEntities) return false

        val chunks = ArrayDeque<Chunk>()
        val collector = CandidateChunkIndex.fullScanCollector()
        val report = PreviewReport()
        isRunning = true
        PokemonEntityHook.beginScanHold()

        task = Bukkit.getScheduler().runTaskTimer(Cyuclear.instance, Runnable {
            val tickStart = System.nanoTime()
            val maxChunks = Settings.scanMaxChunksPerTick
            val budgetNanos = Settings.scanMaxMillisPerTick * 1_000_000L
            val shouldContinue = { System.nanoTime() - tickStart < budgetNanos }
            var processedThisTick = 0

            while (processedThisTick < maxChunks) {
                if (processedThisTick > 0 && !shouldContinue()) break
                if (chunks.isEmpty()) {
                    fill(chunks, collector)
                    if (chunks.isEmpty()) {
                        if (collector.done()) {
                            finish(report, callback)
                            return@Runnable
                        }
                        continue
                    }
                }

                val chunk = chunks.removeFirst()
                processedThisTick++
                if (!chunk.isLoaded) continue
                if (!processChunk(chunk, effectiveCleanItems, effectiveCleanEntities, report, shouldContinue)) {
                    chunks.addFirst(chunk)
                    break
                }
            }
        }, 1L, 1L)

        return true
    }

    fun stop() {
        task?.cancel()
        task = null
        isRunning = false
        PokemonEntityHook.endScanHold()
    }

    private fun fill(chunks: ArrayDeque<Chunk>, collector: CandidateChunkIndex.Collector) {
        if (collector.done()) return
        for (coord in collector.poll(Settings.scanMaxChunksPerTick)) {
            if (!coord.world.isChunkLoaded(coord.x, coord.z)) continue
            chunks.addLast(coord.world.getChunkAt(coord.x, coord.z))
        }
    }

    private fun processChunk(
        chunk: Chunk,
        cleanItems: Boolean,
        cleanEntities: Boolean,
        report: PreviewReport,
        shouldContinue: () -> Boolean
    ): Boolean {
        val entities = chunk.entities
        var visited = 0
        var complete = true

        for (entity in entities) {
            if (visited > 0 && !shouldContinue()) {
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

    private fun finish(report: PreviewReport, callback: (PreviewReport) -> Unit) {
        task?.cancel()
        task = null
        isRunning = false
        PokemonEntityHook.endScanHold()
        callback(report)
    }
}

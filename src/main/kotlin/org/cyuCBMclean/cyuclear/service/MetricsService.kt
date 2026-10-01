package org.cyuCBMclean.cyuclear.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.platform.PlatformInfo
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object MetricsService {

    @Volatile
    private var server: HttpServer? = null
    @Volatile
    private var executor: ExecutorService? = null
    @Volatile
    private var boundPort: Int = 0

    @Synchronized
    fun reload() {
        if (!Settings.metricsEnabled) {
            stop()
            return
        }
        val targetPort = Settings.metricsPort
        if (server != null && boundPort == targetPort) {
            return
        }
        stop()
        start(targetPort)
    }

    @Synchronized
    fun start(port: Int) {
        if (server != null) return
        try {
            val created = HttpServer.create(InetSocketAddress(port), 0)
            created.createContext("/metrics") { exchange ->
                handleMetricsRequest(exchange)
            }
            val pool = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "CyuClear-Metrics-Worker").apply { isDaemon = true }
            }
            created.executor = pool
            created.start()
            server = created
            executor = pool
            boundPort = port
            Cyuclear.instance.logger.info("Prometheus Metrics 导出端点已启动：http://0.0.0.0:$port/metrics")
        } catch (error: Throwable) {
            Cyuclear.instance.logger.warning("无法启动 Prometheus Metrics 导出端点 (端口 $port)：${error.message}")
        }
    }

    @Synchronized
    fun stop() {
        val currentServer = server
        server = null
        boundPort = 0
        if (currentServer != null) {
            runCatching { currentServer.stop(0) }
        }
        val currentExecutor = executor
        executor = null
        if (currentExecutor != null) {
            runCatching {
                currentExecutor.shutdown()
                if (!currentExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    currentExecutor.shutdownNow()
                }
            }
        }
    }

    private fun handleMetricsRequest(exchange: HttpExchange) {
        try {
            if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return
            }
            val response = buildMetricsOutput().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        } catch (_: Throwable) {
            runCatching { exchange.close() }
        }
    }

    private fun buildMetricsOutput(): String {
        val lastRun = CleanupRunManager.list(0, 1).first.firstOrNull()
        val durationMillis = if (lastRun != null && lastRun.finishedAt >= lastRun.startedAt) {
            lastRun.finishedAt - lastRun.startedAt
        } else {
            WindowScanner.lastTimeCost
        }
        val durationMicros = durationMillis * 1000L
        val timestampSeconds = if (lastRun != null && lastRun.finishedAt > 0L) lastRun.finishedAt / 1000L else 0L
        val lastItems = lastRun?.removedItems ?: WindowScanner.lastClearedItems.toLong()
        val lastEntities = lastRun?.removedEntities ?: WindowScanner.lastClearedEntities.toLong()
        val lastScanned = lastRun?.scannedEntities ?: 0
        val lastChunks = lastRun?.processedChunks ?: 0
        val slowestChunkMillis = lastRun?.slowestChunkMillis ?: 0L
        val slowestChunkMicros = slowestChunkMillis * 1000L

        val hotspotSummary = HotspotTracker.summary()
        val voidBinItems = VoidBinManager.totalItemCount()
        val voidBinTypes = VoidBinManager.itemTypeCount()
        val candidateChunks = CandidateChunkIndex.size()
        val isRunning = if (WindowScanner.isRunning || CleanupRunManager.activeSnapshot() != null) 1 else 0

        val version = Cyuclear.instance.description.version
        val platform = PlatformInfo.id

        val sb = StringBuilder(2048)

        sb.append("# HELP cyuclear_build_info CyuClear build and version metadata\n")
        sb.append("# TYPE cyuclear_build_info gauge\n")
        sb.append("cyuclear_build_info{version=\"").append(version).append("\",platform=\"").append(platform).append("\"} 1\n\n")

        sb.append("# HELP cyuclear_scanned_chunks_total Cumulative total scanned chunks count\n")
        sb.append("# TYPE cyuclear_scanned_chunks_total counter\n")
        sb.append("cyuclear_scanned_chunks_total ").append(CleanupRunManager.totalScannedChunks()).append("\n\n")

        sb.append("# HELP cyuclear_scanned_entities_total Cumulative total scanned entities count\n")
        sb.append("# TYPE cyuclear_scanned_entities_total counter\n")
        sb.append("cyuclear_scanned_entities_total ").append(CleanupRunManager.totalScannedEntities()).append("\n\n")

        sb.append("# HELP cyuclear_cleaned_items_total Cumulative total cleaned items count\n")
        sb.append("# TYPE cyuclear_cleaned_items_total counter\n")
        sb.append("cyuclear_cleaned_items_total ").append(CleanupRunManager.totalCleanedItems()).append("\n\n")

        sb.append("# HELP cyuclear_cleaned_entities_total Cumulative total cleaned entities count\n")
        sb.append("# TYPE cyuclear_cleaned_entities_total counter\n")
        sb.append("cyuclear_cleaned_entities_total ").append(CleanupRunManager.totalCleanedEntities()).append("\n\n")

        sb.append("# HELP cyuclear_last_cleanup_duration_millis Duration of the last cleanup run in milliseconds\n")
        sb.append("# TYPE cyuclear_last_cleanup_duration_millis gauge\n")
        sb.append("cyuclear_last_cleanup_duration_millis ").append(durationMillis).append("\n\n")

        sb.append("# HELP cyuclear_last_cleanup_duration_micros Duration of the last cleanup run in microseconds\n")
        sb.append("# TYPE cyuclear_last_cleanup_duration_micros gauge\n")
        sb.append("cyuclear_last_cleanup_duration_micros ").append(durationMicros).append("\n\n")

        sb.append("# HELP cyuclear_last_cleanup_timestamp_seconds Timestamp in seconds of the last completed cleanup run\n")
        sb.append("# TYPE cyuclear_last_cleanup_timestamp_seconds gauge\n")
        sb.append("cyuclear_last_cleanup_timestamp_seconds ").append(timestampSeconds).append("\n\n")

        sb.append("# HELP cyuclear_last_cleaned_items Cleaned items count in the last cleanup run\n")
        sb.append("# TYPE cyuclear_last_cleaned_items gauge\n")
        sb.append("cyuclear_last_cleaned_items ").append(lastItems).append("\n\n")

        sb.append("# HELP cyuclear_last_cleaned_entities Cleaned entities count in the last cleanup run\n")
        sb.append("# TYPE cyuclear_last_cleaned_entities gauge\n")
        sb.append("cyuclear_last_cleaned_entities ").append(lastEntities).append("\n\n")

        sb.append("# HELP cyuclear_last_scanned_entities Scanned entities count in the last cleanup run\n")
        sb.append("# TYPE cyuclear_last_scanned_entities gauge\n")
        sb.append("cyuclear_last_scanned_entities ").append(lastScanned).append("\n\n")

        sb.append("# HELP cyuclear_last_processed_chunks Processed chunks count in the last cleanup run\n")
        sb.append("# TYPE cyuclear_last_processed_chunks gauge\n")
        sb.append("cyuclear_last_processed_chunks ").append(lastChunks).append("\n\n")

        sb.append("# HELP cyuclear_last_slowest_chunk_millis Processing duration of the slowest chunk in milliseconds\n")
        sb.append("# TYPE cyuclear_last_slowest_chunk_millis gauge\n")
        sb.append("cyuclear_last_slowest_chunk_millis ").append(slowestChunkMillis).append("\n\n")

        sb.append("# HELP cyuclear_last_slowest_chunk_micros Processing duration of the slowest chunk in microseconds\n")
        sb.append("# TYPE cyuclear_last_slowest_chunk_micros gauge\n")
        sb.append("cyuclear_last_slowest_chunk_micros ").append(slowestChunkMicros).append("\n\n")

        sb.append("# HELP cyuclear_hotspots_active_total Current active hotspot chunks count\n")
        sb.append("# TYPE cyuclear_hotspots_active_total gauge\n")
        sb.append("cyuclear_hotspots_active_total ").append(hotspotSummary.total).append("\n\n")

        sb.append("# HELP cyuclear_hotspots_breaker_total Current panic circuit-breaker chunks count\n")
        sb.append("# TYPE cyuclear_hotspots_breaker_total gauge\n")
        sb.append("cyuclear_hotspots_breaker_total ").append(hotspotSummary.breakers).append("\n\n")

        sb.append("# HELP cyuclear_void_bin_items_total Current total items count in void trashbin\n")
        sb.append("# TYPE cyuclear_void_bin_items_total gauge\n")
        sb.append("cyuclear_void_bin_items_total ").append(voidBinItems).append("\n\n")

        sb.append("# HELP cyuclear_void_bin_types_total Current total item types count in void trashbin\n")
        sb.append("# TYPE cyuclear_void_bin_types_total gauge\n")
        sb.append("cyuclear_void_bin_types_total ").append(voidBinTypes).append("\n\n")

        sb.append("# HELP cyuclear_candidate_chunks_total Current candidate active chunks count\n")
        sb.append("# TYPE cyuclear_candidate_chunks_total gauge\n")
        sb.append("cyuclear_candidate_chunks_total ").append(candidateChunks).append("\n\n")

        sb.append("# HELP cyuclear_cleanup_running Whether a cleanup run is currently in progress\n")
        sb.append("# TYPE cyuclear_cleanup_running gauge\n")
        sb.append("cyuclear_cleanup_running ").append(isRunning).append("\n")

        return sb.toString()
    }
}

package org.cyuCBMclean.cyuclear.bridge

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.ActivationService
import org.cyuCBMclean.cyuclear.service.CandidateChunkIndex
import org.cyuCBMclean.cyuclear.service.CleanupRunManager
import org.cyuCBMclean.cyuclear.service.VoidBinManager
import org.cyuCBMclean.cyuclear.service.WindowScanner
import org.cyuCBMclean.cyuclear.platform.PlatformInfo
import org.cyuCBMclean.cyuclear.cluster.ClusterManager
import org.cyuCBMclean.cyuclear.task.CountdownTask
import org.cyuCBMclean.cyuclear.util.TimeFormat
import java.util.Locale

class PapiHook : PlaceholderExpansion() {

    override fun getIdentifier(): String = "cyuclear"

    override fun getAuthor(): String = "HansOffice"

    override fun getVersion(): String = Cyuclear.instance.description.version

    override fun persist(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, params: String): String {
        val run = CleanupRunManager.activeSnapshot()
        return when (params.lowercase(Locale.ROOT)) {
            "enabled" -> Settings.enabled.toString()
            "active" -> ActivationService.isActive().toString()
            "interval_seconds" -> Settings.intervalSeconds.toString()
            "countdown" -> CountdownTask.remainingSeconds.toString()
            "countdown_text" -> secondsText(CountdownTask.remainingSeconds)
            "is_running" -> WindowScanner.isRunning.toString()

            "run_id" -> run?.id.orEmpty()
            "run_origin" -> run?.origin?.name?.lowercase(Locale.ROOT).orEmpty()
            "run_status" -> run?.status?.name?.lowercase(Locale.ROOT) ?: "idle"
            "run_queued_chunks" -> run?.queuedChunks?.toString() ?: "0"
            "run_processed_chunks" -> run?.processedChunks?.toString() ?: "0"
            "run_failed_chunks" -> run?.failedChunks?.toString() ?: "0"
            "run_scanned_entities" -> run?.scannedEntities?.toString() ?: "0"
            "run_items" -> run?.removedItems?.toString() ?: "0"
            "run_entities" -> run?.removedEntities?.toString() ?: "0"

            "last_items" -> WindowScanner.lastClearedItems.toString()
            "last_entities" -> WindowScanner.lastClearedEntities.toString()
            "last_total" -> (WindowScanner.lastClearedItems.toLong() + WindowScanner.lastClearedEntities).toString()
            "last_time" -> WindowScanner.lastTimeCost.toString()
            "last_time_text" -> TimeFormat.cleanupDuration(WindowScanner.lastTimeCost)

            "bin_enabled" -> Settings.binEnabled.toString()
            "bin_open" -> VoidBinManager.isOpen().toString()
            "bin_always_open" -> Settings.binAlwaysOpen.toString()
            "bin_stacked" -> Settings.binStackedMode.toString()
            "bin_countdown" -> VoidBinManager.getRemainingSeconds().toString()
            "bin_countdown_text" -> secondsText(VoidBinManager.getRemainingSeconds())
            "bin_claim_cooldown" -> player?.let { VoidBinManager.getClaimCooldownRemainingSeconds(it).toString() } ?: "0"
            "bin_has_items" -> VoidBinManager.hasItems().toString()
            "bin_item_types" -> VoidBinManager.itemTypeCount().toString()

            "candidate_index" -> Settings.candidateIndexEnabled.toString()
            "candidate_chunks" -> CandidateChunkIndex.size().toString()
            "profile" -> Settings.performanceProfile
            "scan_chunks_per_tick" -> Settings.scanMaxChunksPerTick.toString()
            "scan_budget_millis" -> Settings.scanMaxMillisPerTick.toString()
            "items_enabled" -> Settings.itemModuleEnabled.toString()
            "entities_enabled" -> Settings.entityModuleEnabled.toString()
            "realtime_enabled" -> Settings.entityRealtimeCleanupEnabled.toString()
            "recovery_enabled" -> Settings.recoveryEnabled.toString()
            "cluster_enabled" -> Settings.clusterEnabled.toString()
            "cluster_active" -> ClusterManager.isActive().toString()
            "rules_total" -> Settings.namedRules.ruleCount.toString()
            "rules_items" -> Settings.namedRules.itemRuleCount.toString()
            "rules_entities" -> Settings.namedRules.entityRuleCount.toString()
            "platform" -> PlatformInfo.id
            "version" -> Cyuclear.instance.description.version
            "config_version" -> Settings.configVersion.toString()
            else -> ""
        }
    }

    private fun secondsText(seconds: Int): String {
        val value = seconds.coerceAtLeast(0)
        if (value < 60) return "$value 秒"
        val minutes = value / 60
        val remain = value % 60
        return if (remain == 0) "$minutes 分钟" else "$minutes 分 $remain 秒"
    }
}

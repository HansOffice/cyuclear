package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.config.TargetRuleStats
import org.cyuCBMclean.cyuclear.bridge.CraftEngineFurnitureHook
import org.cyuCBMclean.cyuclear.bridge.MythicMobsHook
import org.cyuCBMclean.cyuclear.platform.PlatformInfo
import org.cyuCBMclean.cyuclear.util.TimeFormat

object StatusReporter {

    fun send(sender: CommandSender) {
        val running = if (WindowScanner.isRunning) "进行中" else "空闲"
        val candidate = if (Settings.candidateIndexEnabled) {
            "开 / 每${Settings.candidateFullScanEveryCycles}轮全量 / 待扫${CandidateChunkIndex.size()}"
        } else {
            "关"
        }
        val last = if (WindowScanner.lastTimeCost > 0L || WindowScanner.lastClearedItems > 0 || WindowScanner.lastClearedEntities > 0) {
            "掉落物 ${WindowScanner.lastClearedItems} · 实体 ${WindowScanner.lastClearedEntities} · ${TimeFormat.cleanupDuration(WindowScanner.lastTimeCost)}"
        } else {
            "尚无记录"
        }
        val recentRun = CleanupRunManager.list(0, 1).first.firstOrNull()
        val hotspots = HotspotTracker.summary()

        val listCounts = TargetRuleStats.listCounts()

        val mythic = hookState(Settings.entityMythicEnabled && MythicMobsHook.isAvailable())
        val cePlugin = pluginOn("CraftEngine") || pluginOn("CE")
        val ce = when {
            !Settings.entityCraftEngineEnabled -> "关闭"
            !cePlugin -> "开启(插件未装)"
            !CraftEngineFurnitureHook.isAvailable() -> "开启(API不可用)"
            Settings.entityCraftEngineProtectFurniture -> "已接入 · 家具保护开"
            else -> "已接入 · 家具保护关"
        }

        sender.sendMessage(Language.getRaw("status-header"))
        sender.sendMessage(
            Language.get(
                "status-runtime",
                "platform" to PlatformInfo.id,
                "running" to running,
                "profile" to Settings.performanceProfile
            )
        )
        sender.sendMessage(
            Language.get(
                "status-activation",
                "state" to if (ActivationService.isActive()) "已启用" else "安全关闭"
            )
        )
        sender.sendMessage(
            Language.get(
                "status-scan",
                "chunks" to Settings.scanMaxChunksPerTick.toString(),
                "budget" to Settings.scanMaxMillisPerTick.toString(),
                "candidate" to candidate
            )
        )
        if (PlatformInfo.id == "folia") {
            sender.sendMessage(
                Language.get(
                    "status-folia",
                    "active" to Settings.foliaMaxActiveRegionTasks.toString(),
                    "dispatch" to Settings.foliaDispatchChunksPerTick.toString()
                )
            )
        }
        sender.sendMessage(Language.get("status-last", "summary" to last))
        sender.sendMessage(
            Language.get(
                "status-recovery",
                "state" to onOff(Settings.recoveryEnabled),
                "run" to (recentRun?.id ?: "无"),
                "recovery" to recentRun?.let { "${it.pendingRecoveryEntries}/${it.recoveryEntries}" }.orEmpty().ifEmpty { "0/0" }
            )
        )
        sender.sendMessage(
            Language.get(
                "status-hotspots",
                "total" to hotspots.total.toString(),
                "breakers" to hotspots.breakers.toString()
            )
        )
        sender.sendMessage(
            Language.get(
                "status-modules",
                "items" to onOff(Settings.itemModuleEnabled),
                "entities" to onOff(Settings.entityModuleEnabled),
                "item_mode" to Settings.itemListModeName,
                "entity_mode" to Settings.entityListModeName
            )
        )
        sender.sendMessage(
            Language.get(
                "status-lists",
                "item_keep" to listCounts.itemKeep.toString(),
                "item_clean" to listCounts.itemClean.toString(),
                "name_keep" to listCounts.nameKeep.toString(),
                "name_clean" to listCounts.nameClean.toString(),
                "lore_keep" to listCounts.loreKeep.toString(),
                "lore_clean" to listCounts.loreClean.toString(),
                "entity_keep" to listCounts.entityKeep.toString(),
                "entity_clean" to listCounts.entityClean.toString()
            )
        )
        sender.sendMessage(
            Language.get(
                "status-named-rules",
                "total" to Settings.namedRules.ruleCount.toString(),
                "items" to Settings.namedRules.itemRuleCount.toString(),
                "entities" to Settings.namedRules.entityRuleCount.toString()
            )
        )
        sender.sendMessage(
            Language.get(
                "status-hooks",
                "mythic" to mythic,
                "craftengine" to ce,
                "pokemon" to onOff(Settings.entityPokemonEnabled)
            )
        )
        sender.sendMessage(Language.getRaw("status-footer"))
    }

    private fun onOff(value: Boolean): String = if (value) "开" else "关"

    private fun hookState(ok: Boolean): String = if (ok) "已接入" else "未接入"

    private fun pluginOn(name: String): Boolean {
        val plugin = Bukkit.getPluginManager().getPlugin(name)
        return plugin != null && plugin.isEnabled
    }
}

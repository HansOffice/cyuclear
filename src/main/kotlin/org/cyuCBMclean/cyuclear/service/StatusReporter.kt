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
        val isEn = Language.isEnglish
        val running = if (WindowScanner.isRunning) (if (isEn) "Running" else "进行中") else (if (isEn) "Idle" else "空闲")
        val candidate = if (Settings.candidateIndexEnabled) {
            if (isEn) "ON / Full scan every ${Settings.candidateFullScanEveryCycles} cycles / Pending ${CandidateChunkIndex.size()}"
            else "开 / 每${Settings.candidateFullScanEveryCycles}轮全量 / 待扫${CandidateChunkIndex.size()}"
        } else {
            if (isEn) "OFF" else "关"
        }
        val last = if (WindowScanner.lastTimeCost > 0L || WindowScanner.lastClearedItems > 0 || WindowScanner.lastClearedEntities > 0) {
            if (isEn) {
                "Items ${WindowScanner.lastClearedItems} · Entities ${WindowScanner.lastClearedEntities} · ${TimeFormat.cleanupDuration(WindowScanner.lastTimeCost)}"
            } else {
                "掉落物 ${WindowScanner.lastClearedItems} · 实体 ${WindowScanner.lastClearedEntities} · ${TimeFormat.cleanupDuration(WindowScanner.lastTimeCost)}"
            }
        } else {
            if (isEn) "No records" else "尚无记录"
        }
        val recentRun = CleanupRunManager.list(0, 1).first.firstOrNull()
        val hotspots = HotspotTracker.summary()

        val listCounts = TargetRuleStats.listCounts()

        val mythic = hookState(Settings.entityMythicEnabled && MythicMobsHook.isAvailable())
        val cePlugin = pluginOn("CraftEngine") || pluginOn("CE")
        val ce = when {
            !Settings.entityCraftEngineEnabled -> if (isEn) "Disabled" else "关闭"
            !cePlugin -> if (isEn) "Enabled (Plugin missing)" else "开启(插件未装)"
            !CraftEngineFurnitureHook.isAvailable() -> if (isEn) "Enabled (API unavailable)" else "开启(API不可用)"
            Settings.entityCraftEngineProtectFurniture -> if (isEn) "Connected · Furniture Protect ON" else "已接入 · 家具保护开"
            else -> if (isEn) "Connected · Furniture Protect OFF" else "已接入 · 家具保护关"
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
                "state" to if (ActivationService.isActive()) (if (isEn) "Active" else "已启用") else (if (isEn) "Safe-Disabled" else "安全关闭")
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
                "run" to (recentRun?.id ?: (if (isEn) "None" else "无")),
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

    private fun onOff(value: Boolean): String {
        val isEn = Language.isEnglish
        return if (value) (if (isEn) "ON" else "开") else (if (isEn) "OFF" else "关")
    }

    private fun hookState(ok: Boolean): String {
        val isEn = Language.isEnglish
        return if (ok) (if (isEn) "Connected" else "已接入") else (if (isEn) "None" else "未接入")
    }

    private fun pluginOn(name: String): Boolean {
        val plugin = Bukkit.getPluginManager().getPlugin(name)
        return plugin != null && plugin.isEnabled
    }
}

package org.cyuCBMclean.cyuclear.bootstrap

import org.bukkit.Bukkit
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.bridge.CraftEngineFurnitureHook
import org.cyuCBMclean.cyuclear.bridge.CraftEngineMenuHook
import org.cyuCBMclean.cyuclear.bridge.MythicMobsHook
import org.cyuCBMclean.cyuclear.bridge.PapiHook
import org.cyuCBMclean.cyuclear.bridge.StackerBridge
import org.cyuCBMclean.cyuclear.cluster.BuildInfo
import org.cyuCBMclean.cyuclear.command.CyuclearCommand
import org.cyuCBMclean.cyuclear.command.CyuclearTabCompleter
import org.cyuCBMclean.cyuclear.config.ConfigDoctor
import org.cyuCBMclean.cyuclear.config.ConfigFiles
import org.cyuCBMclean.cyuclear.config.ConfigUpgradeManager
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.listener.ActivationReminderListener
import org.cyuCBMclean.cyuclear.listener.BinClaimRecoveryListener
import org.cyuCBMclean.cyuclear.listener.CandidateChunkListener
import org.cyuCBMclean.cyuclear.listener.ChunkLimitListener
import org.cyuCBMclean.cyuclear.listener.DepositBufferRecoveryListener
import org.cyuCBMclean.cyuclear.listener.FailsafeListener
import org.cyuCBMclean.cyuclear.listener.RealtimeCleanupListener
import org.cyuCBMclean.cyuclear.menu.AdminMenu
import org.cyuCBMclean.cyuclear.menu.BinMenu
import org.cyuCBMclean.cyuclear.menu.CleanupRunMenu
import org.cyuCBMclean.cyuclear.menu.DepositBufferMenu
import org.cyuCBMclean.cyuclear.menu.HotspotMenu
import org.cyuCBMclean.cyuclear.menu.MenuReloadService
import org.cyuCBMclean.cyuclear.menu.RuleMenu
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import org.cyuCBMclean.cyuclear.service.ActivationService
import org.cyuCBMclean.cyuclear.service.BinNoticeManager
import org.cyuCBMclean.cyuclear.service.CandidateChunkIndex
import org.cyuCBMclean.cyuclear.service.ChunkLimitService
import org.cyuCBMclean.cyuclear.service.CleanupNoticeManager
import org.cyuCBMclean.cyuclear.service.CleanupRunManager
import org.cyuCBMclean.cyuclear.service.DepositBufferManager
import org.cyuCBMclean.cyuclear.service.HeuristicProtection
import org.cyuCBMclean.cyuclear.service.HotspotTracker
import org.cyuCBMclean.cyuclear.platform.PlatformInfo
import org.cyuCBMclean.cyuclear.service.SoundNoticeManager
import org.cyuCBMclean.cyuclear.service.TeleportService
import org.cyuCBMclean.cyuclear.service.VoidBinManager
import org.cyuCBMclean.cyuclear.util.ItemIdentity

internal object CyuclearLifecycle {

    fun enable(plugin: Cyuclear) {
        loadRuntime(plugin)
        registerEntrypoints(plugin)
        ActivationService.reload()
        printStartup(plugin, Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null)
    }

    fun disable(plugin: Cyuclear) {
        HeuristicProtection.reset()
        DepositBufferManager.shutdown()
        ActivationService.stop()
        CleanupRunManager.flush()
        CandidateChunkIndex.reset()
        ChunkLimitService.reset()
        HotspotTracker.reset()
        TeleportService.reset()
        BinNoticeManager.shutdown()
        CleanupNoticeManager.shutdown()
        CyuScheduler.cancelAll(plugin)
        printShutdown()
    }

    private fun loadRuntime(plugin: Cyuclear) {
        ConfigUpgradeManager.prepare()
        plugin.saveDefaultConfig()
        ConfigFiles.prepare()
        Language.load()
        Settings.load()
        CleanupRunManager.initialize()
        CraftEngineFurnitureHook.clearCache()
        MythicMobsHook.reset()
        ItemIdentity.reloadExternalResolvers()
        SoundNoticeManager.reload()
        val craftEnginePresent = CraftEngineMenuHook.register(MenuReloadService::reload)
        MenuReloadService.reload()
        if (craftEnginePresent) {
            CyuScheduler.runTask(plugin, Runnable { MenuReloadService.reload() })
        }
        StackerBridge.reload()
        val doctor = ConfigDoctor.inspect()
        if (!doctor.healthy || doctor.warnings > 0) {
            if (Language.isEnglish) {
                plugin.logger.warning("Configuration check found ${doctor.errors} error(s) and ${doctor.warnings} warning(s), run /cc doctor for details")
            } else {
                plugin.logger.warning("配置检查发现 ${doctor.errors} 个错误、${doctor.warnings} 项注意，可使用 /cc doctor 查看")
            }
        }
    }

    private fun registerEntrypoints(plugin: Cyuclear) {
        val pluginManager = Bukkit.getPluginManager()
        pluginManager.registerEvents(ChunkLimitListener, plugin)
        pluginManager.registerEvents(CandidateChunkListener, plugin)
        pluginManager.registerEvents(FailsafeListener, plugin)
        pluginManager.registerEvents(RealtimeCleanupListener, plugin)
        pluginManager.registerEvents(BinClaimRecoveryListener, plugin)
        pluginManager.registerEvents(DepositBufferRecoveryListener, plugin)
        pluginManager.registerEvents(ActivationReminderListener, plugin)
        pluginManager.registerEvents(TeleportService, plugin)
        registerMenus(plugin)

        val tabCompleter = CyuclearTabCompleter()
        plugin.getCommand("cyuclear")?.let {
            it.setExecutor(CyuclearCommand)
            it.setTabCompleter(tabCompleter)
        }

        if (pluginManager.getPlugin("PlaceholderAPI") != null) {
            PapiHook().register()
        }
    }

    private fun registerMenus(plugin: Cyuclear) {
        val pluginManager = Bukkit.getPluginManager()
        VoidBinManager.bindViewController(BinMenu)
        pluginManager.registerEvents(BinMenu(0), plugin)
        pluginManager.registerEvents(DepositBufferMenu, plugin)
        pluginManager.registerEvents(RuleMenu, plugin)
        pluginManager.registerEvents(AdminMenu, plugin)
        pluginManager.registerEvents(CleanupRunMenu, plugin)
        pluginManager.registerEvents(HotspotMenu, plugin)
    }

    private fun printStartup(plugin: Cyuclear, papiHooked: Boolean) {
        val isEn = Language.isEnglish
        val console = Bukkit.getConsoleSender()
        console.sendMessage("")
        console.sendMessage("§8--------------------------------------------------")
        console.sendMessage(if (isEn) "§b CyuClear §f- Lag-Free Cleanup & Void Bin Recovery" else "§b CyuClear §f- 轻量清理与虚空回收")
        console.sendMessage("§f")
        console.sendMessage(if (isEn) "§7 ▸ §fVersion §b${plugin.description.version} §8| §fPlatform §b${PlatformInfo.id}" else "§7 ▸ §f版本 §b${plugin.description.version} §8| §f平台 §b${PlatformInfo.id}")
        console.sendMessage(if (isEn) "§7 ▸ §fModules §b${moduleText()}" else "§7 ▸ §f模块 §b${moduleText()}")
        if (Settings.clusterEnabled) {
            console.sendMessage(if (isEn) {
                "§7 ▸ §fCluster §bEnabled / ${Settings.clusterId} / ${Settings.clusterServerId.ifBlank { "No Node ID" }}"
            } else {
                "§7 ▸ §f跨服 §b已开启 / ${Settings.clusterId} / ${Settings.clusterServerId.ifBlank { "未配置节点" }}"
            })
        }
        console.sendMessage(if (isEn) "§7 ▸ §fLists §b${listModeText()}" else "§7 ▸ §f名单 §b${listModeText()}")
        console.sendMessage(if (isEn) {
            "§7 ▸ §fPerformance §b${Settings.performanceProfile} §8| §fChunks §b${Settings.scanMaxChunksPerTick}/tick §8| §fBudget §b${Settings.scanMaxMillisPerTick}ms"
        } else {
            "§7 ▸ §f性能 §b${Settings.performanceProfile} §8| §f区块 §b${Settings.scanMaxChunksPerTick}/tick §8| §f预算 §b${Settings.scanMaxMillisPerTick}ms"
        })
        console.sendMessage(if (isEn) "§7 ▸ §fChunk Hard Limit §b${chunkEntityLimitModeText()}" else "§7 ▸ §f区块实体硬限制 §b${chunkEntityLimitModeText()}")
        if (PlatformInfo.id == "folia") {
            console.sendMessage(if (isEn) {
                "§7 ▸ §fFolia §bRegion Tasks ${Settings.foliaMaxActiveRegionTasks} §8| §fDispatch §b${Settings.foliaDispatchChunksPerTick}/tick"
            } else {
                "§7 ▸ §fFolia §b区域任务 ${Settings.foliaMaxActiveRegionTasks} §8| §f派发 §b${Settings.foliaDispatchChunksPerTick}/tick"
            })
            if (Settings.foliaMaxActiveRegionTasks >= 4096 || Settings.foliaDispatchChunksPerTick >= 4096) {
                plugin.logger.warning(if (isEn) {
                    "Folia cleanup dispatch settings are aggressive. Lower parameters under performance.folia if observing scheduler stress."
                } else {
                    "Folia 清理参数较激进，如遇到调度压力可先降低 performance.folia 下的两个数值"
                })
            }
        }
        val mmHooked = Settings.entityMythicEnabled && pluginEnabled("MythicMobs")
        val ceHooked = Settings.entityCraftEngineEnabled && (pluginEnabled("CraftEngine") || pluginEnabled("CE"))
        val pokeHooked = Settings.entityPokemonEnabled
        console.sendMessage(if (isEn) {
            "§7 ▸ §fHooks §fPlaceholderAPI ${if (papiHooked) "§bConnected" else "§7None"} §8| §fMythicMobs ${if (mmHooked) "§bConnected" else "§7None"} §8| §fCraftEngine ${if (ceHooked) "§bConnected" else "§7None"} §8| §fPokemon ${if (pokeHooked) "§bEnabled" else "§7Disabled"}"
        } else {
            "§7 ▸ §fHook §fPlaceholderAPI ${if (papiHooked) "§b已接入" else "§7未接入"} §8| §fMythicMobs ${if (mmHooked) "§b已接入" else "§7未接入"} §8| §fCraftEngine ${if (ceHooked) "§b已接入" else "§7未接入"} §8| §f宝可梦 ${if (pokeHooked) "§b已开启" else "§7未开启"}"
        })
        val stackerNames = StackerBridge.activeNames()
        val stackerText = if (stackerNames.isNotEmpty()) "§b" + stackerNames.joinToString(" / ") else (if (isEn) "§7None" else "§7未接入")
        console.sendMessage(if (isEn) {
            "§7 ▸ §fStacker $stackerText §8| §fGroup §b331910315"
        } else {
            "§7 ▸ §f堆叠 $stackerText §8| §f交流群 §b331910315"
        })
        console.sendMessage("§f")
        if (ActivationService.isActive()) {
            console.sendMessage(if (isEn) "§7 ▸ §fStatus §bReady" else "§7 ▸ §f状态 §b启动完成")
        } else {
            console.sendMessage(if (isEn) {
                "§7 ▸ §fStatus §7Standby §8(Disabled in config.yml, run /cc reload to enable)"
            } else {
                "§7 ▸ §f状态 §7安全就绪 §8(未开启，在 config.yml 启用 enabled 后重载)"
            })
        }
        console.sendMessage("§8--------------------------------------------------")
        console.sendMessage("")
    }

    private fun printShutdown() {
        val isEn = Language.isEnglish
        val console = Bukkit.getConsoleSender()
        console.sendMessage("")
        console.sendMessage("§8--------------------------------------------------")
        console.sendMessage(if (isEn) "§b CyuClear §f- Lag-Free Cleanup & Void Bin Recovery" else "§b CyuClear §f- 轻量清理与虚空回收")
        console.sendMessage("§f")
        console.sendMessage(if (isEn) "§7 ▸ §fStatus §7Saved and unloaded" else "§7 ▸ §f状态 §7已保存并卸载")
        console.sendMessage("§8--------------------------------------------------")
        console.sendMessage("")
    }

    private fun moduleText(): String {
        val isEn = Language.isEnglish
        val modules = ArrayList<String>()
        if (Settings.itemModuleEnabled) modules.add(if (isEn) "Items" else "掉落物")
        if (Settings.entityModuleEnabled) modules.add(if (isEn) "Entities" else "实体")
        if (Settings.binEnabled) modules.add(if (isEn) "Void Bin" else "虚空桶")
        if (Settings.panicEnabled) modules.add(if (isEn) "Panic Overload" else "过载保护")
        if (Settings.entityRealtimeCleanupEnabled) modules.add(if (isEn) "Realtime Throttling" else "实时拦截")
        return modules.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: (if (isEn) "Disabled" else "未开启")
    }

    private fun listModeText(): String {
        val isEn = Language.isEnglish
        val realtime = if (Settings.entityRealtimeCleanupEnabled) {
            if (isEn) " / Realtime ${Settings.entityRealtimeListModeName}" else " / 实时 ${Settings.entityRealtimeListModeName}"
        } else ""
        return if (isEn) {
            "Items ${Settings.itemListModeName} / Entities ${Settings.entityListModeName}$realtime"
        } else {
            "掉落物 ${Settings.itemListModeName} / 实体 ${Settings.entityListModeName}$realtime"
        }
    }

    private fun chunkEntityLimitModeText(): String {
        val isEn = Language.isEnglish
        return when (Settings.chunkEntityLimitMode) {
            Settings.ChunkEntityLimitMode.OFF -> if (isEn) "OFF" else "关闭"
            Settings.ChunkEntityLimitMode.SAFE -> if (isEn) "SAFE" else "安全模式"
            Settings.ChunkEntityLimitMode.STRICT -> if (isEn) "STRICT" else "严格模式"
        }
    }

    private fun pluginEnabled(name: String): Boolean = Bukkit.getPluginManager().isPluginEnabled(name)
}

package org.cyuCBMclean.cyuclear.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.bootstrap.RuntimeReloadService
import org.cyuCBMclean.cyuclear.cluster.BuildInfo
import org.cyuCBMclean.cyuclear.cluster.ClusterManager
import org.cyuCBMclean.cyuclear.command.sub.HelpRenderer
import org.cyuCBMclean.cyuclear.config.ConfigDoctor
import org.cyuCBMclean.cyuclear.config.ConfigSnapshotManager
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.menu.AdminMenu
import org.cyuCBMclean.cyuclear.menu.BinMenu
import org.cyuCBMclean.cyuclear.menu.CleanupRunMenu
import org.cyuCBMclean.cyuclear.menu.DepositBufferMenu
import org.cyuCBMclean.cyuclear.menu.HotspotMenu
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import org.cyuCBMclean.cyuclear.service.ActivationService
import org.cyuCBMclean.cyuclear.service.BinClaimAudit
import org.cyuCBMclean.cyuclear.service.CleanupRequests
import org.cyuCBMclean.cyuclear.service.CleanupRunManager
import org.cyuCBMclean.cyuclear.service.DepositBufferManager
import org.cyuCBMclean.cyuclear.service.HotspotTracker
import org.cyuCBMclean.cyuclear.service.InspectService
import org.cyuCBMclean.cyuclear.service.PreviewReport
import org.cyuCBMclean.cyuclear.service.PreviewScanner
import org.cyuCBMclean.cyuclear.service.SoundNoticeManager
import org.cyuCBMclean.cyuclear.service.StatusReporter
import org.cyuCBMclean.cyuclear.service.VoidBinManager
import org.cyuCBMclean.cyuclear.service.WindowScanner
import org.cyuCBMclean.cyuclear.service.TeleportService

object CyuclearCommand : CommandExecutor {

    private val helpRenderer = HelpRenderer()

    private val adminCommands = listOf(
        "items", "entities", "all", "cluster", "menu", "runs", "run", "recover", "hotspots",
        "cancel", "doctor", "validate", "snapshot", "history", "status", "metrics", "reload", "check", "inspect", "preview", "lang", "language", "tp", "teleport", "goto", "here", "back"
    )

    private fun isAdminCommand(name: String): Boolean {
        return name in adminCommands
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender, null)
            return true
        }

        if (args[0].equals("help", ignoreCase = true)) {
            val pageArg = if (args.size > 1) args[1] else null
            sendHelp(sender, pageArg)
            return true
        }

        val subCommand = args[0].lowercase()
        if (isAdminCommand(subCommand) && !requireAdmin(sender)) return true

        when (subCommand) {
            "bin" -> {
                if (!canUse(sender)) {
                    sender.sendMessage(Language.get("no-permission"))
                    return true
                }
                if (!requireActive(sender)) return true
                if (sender is Player) {
                    if (Settings.clusterEnabled && !ClusterManager.isActive()) {
                        sender.sendMessage(Language.get("bin-sync-unavailable"))
                        return true
                    }
                    if (!Settings.binEnabled || !Settings.itemModuleEnabled) {
                        sender.sendMessage(Language.get("bin-not-enabled"))
                        return true
                    }
                    if (Settings.binDepositBufferEnabled && DepositBufferManager.hasPending(sender)) {
                        DepositBufferMenu.open(sender)
                        SoundNoticeManager.play(sender, SoundNoticeManager.Event.BIN_OPEN)
                        return true
                    }
                    if (!Settings.binAlwaysOpen) {
                        if (VoidBinManager.expireTime == 0L) {
                            sender.sendMessage(Language.get("bin-empty"))
                            return true
                        }
                        if (System.currentTimeMillis() > VoidBinManager.expireTime) {
                            sender.sendMessage(Language.get("bin-expired"))
                            return true
                        }
                    }

                    val menu = BinMenu(0)
                    menu.open(sender)
                    SoundNoticeManager.play(sender, SoundNoticeManager.Event.BIN_OPEN)
                }
            }
            "items" -> {
                if (!requireActive(sender)) return true
                if (WindowScanner.isRunning) {
                    sender.sendMessage(Language.get("scan-running"))
                    return true
                }
                if (!Settings.itemModuleEnabled) {
                    sender.sendMessage(Language.get("module-items-disabled"))
                    return true
                }
                startManualCleanup(sender, cleanItems = true, cleanEntities = false)
            }
            "entities" -> {
                if (!requireActive(sender)) return true
                if (WindowScanner.isRunning) {
                    sender.sendMessage(Language.get("scan-running"))
                    return true
                }
                if (!Settings.entityModuleEnabled) {
                    sender.sendMessage(Language.get("module-entities-disabled"))
                    return true
                }
                startManualCleanup(sender, cleanItems = false, cleanEntities = true)
            }
            "all" -> {
                if (!requireActive(sender)) return true
                if (WindowScanner.isRunning) {
                    sender.sendMessage(Language.get("scan-running"))
                    return true
                }
                if (!Settings.itemModuleEnabled && !Settings.entityModuleEnabled) {
                    sender.sendMessage(Language.get("module-all-disabled"))
                    return true
                }
                startManualCleanup(
                    sender,
                    cleanItems = Settings.itemModuleEnabled,
                    cleanEntities = Settings.entityModuleEnabled
                )
            }
            "cluster" -> {
                ClusterManager.statusLines().forEach(sender::sendMessage)
            }
            "lang", "language" -> {
                if (args.size < 2) {
                    sender.sendMessage(Language.get("language-current", "lang" to Language.currentLanguageCode))
                    return true
                }
                val targetLang = args[1].trim()
                if (Language.setLanguage(targetLang, true)) {
                    sender.sendMessage(Language.get("language-changed", "lang" to Language.currentLanguageCode))
                } else {
                    sender.sendMessage(Language.get("language-invalid"))
                }
            }
            "menu" -> {
                if (sender !is Player) {
                    sender.sendMessage(Language.get("player-only"))
                    return true
                }
                AdminMenu.open(sender)
            }
            "runs" -> {
                val page = args.getOrNull(1)?.toIntOrNull()?.minus(1) ?: 0
                if (sender is Player) {
                    CleanupRunMenu.openRuns(sender, page)
                } else {
                    sendRuns(sender, page)
                }
            }
            "run" -> {
                val runId = args.getOrNull(1)?.trim().orEmpty()
                val run = CleanupRunManager.find(runId)
                if (runId.isEmpty() || run == null) {
                    sender.sendMessage(Language.get("run-not-found"))
                    return true
                }
                if (sender is Player) {
                    CleanupRunMenu.openRecovery(sender, run.id, 0)
                } else {
                    val showReasons = args.getOrNull(2)?.let { it.equals("reasons", ignoreCase = true) || it == "原因" } == true
                    sendRun(sender, run, showReasons)
                }
            }
            "recover" -> {
                if (sender !is Player) {
                    sender.sendMessage(Language.get("player-only"))
                    return true
                }
                val runId = args.getOrNull(1)?.trim().orEmpty()
                if (runId.isEmpty() || CleanupRunManager.find(runId) == null) {
                    sender.sendMessage(Language.get("run-not-found"))
                    return true
                }
                CleanupRunMenu.openRecovery(sender, runId, 0)
            }
            "hotspots" -> {
                val page = args.getOrNull(1)?.toIntOrNull()?.minus(1) ?: 0
                if (sender is Player) {
                    HotspotMenu.openList(sender, page)
                } else {
                    sendHotspots(sender, page)
                }
            }
            "cancel" -> {
                if (!WindowScanner.isRunning) {
                    sender.sendMessage(Language.get("scan-not-running"))
                    return true
                }
                WindowScanner.stop()
                sender.sendMessage(Language.get("scan-cancelled"))
            }
            "doctor", "validate" -> {
                ConfigDoctor.send(sender)
            }
            "snapshot" -> {
                val result = ConfigSnapshotManager.create("manual")
                if (result.success) sender.sendMessage(Language.get("snapshot-success", "count" to result.copiedFiles.toString()))
                else sender.sendMessage(Language.get("snapshot-failed"))
            }
            "history" -> {
                val playerFilter = args.getOrNull(1)?.takeIf { it.isNotBlank() }
                val page = args.getOrNull(2)?.toIntOrNull() ?: 1
                BinClaimAudit.read(playerFilter, page) { records, totalPages ->
                    runForSender(sender, Runnable {
                        sender.sendMessage(Language.getRaw("history-header"))
                        if (records.isEmpty()) {
                            sender.sendMessage(Language.get("history-empty"))
                        } else {
                            records.forEach { record ->
                                sender.sendMessage(
                                    Language.get(
                                        "history-entry",
                                        "time" to BinClaimAudit.formatTime(record.timeMillis),
                                        "player" to record.playerName,
                                        "server" to record.serverId,
                                        "item" to record.itemId,
                                        "amount" to record.amount.toString(),
                                        "delivery" to record.delivery
                                    )
                                )
                            }
                        }
                        sender.sendMessage(
                            Language.get(
                                "history-footer",
                                "page" to page.coerceAtLeast(1).toString(),
                                "total" to totalPages.toString()
                            )
                        )
                    })
                }
            }
            "status" -> {
                StatusReporter.send(sender)
            }
            "metrics" -> {
                sendMetricsStatus(sender)
            }
            "reload" -> {
                val result = RuntimeReloadService.reload()

                sender.sendMessage(Language.get("reload-success"))
                if (result.snapshot.success) sender.sendMessage(Language.get("reload-snapshot", "count" to result.snapshot.copiedFiles.toString()))
                else sender.sendMessage(Language.get("snapshot-failed"))
                sender.sendMessage(
                    Language.get(if (result.active) "reload-enabled" else "reload-disabled")
                )
            }
            "check", "inspect" -> {
                if (sender !is Player) {
                    sender.sendMessage(Language.get("player-only"))
                    return true
                }
                InspectService.inspect(sender)
            }
            "preview" -> {
                if (WindowScanner.isRunning || PreviewScanner.isRunning) {
                    sender.sendMessage(Language.get("scan-running"))
                    return true
                }
                val started = PreviewScanner.start(
                    cleanItems = Settings.itemModuleEnabled,
                    cleanEntities = Settings.entityModuleEnabled
                ) { report ->
                    runForSender(sender, Runnable { sendPreview(sender, report) })
                }
                if (!started) {
                    sender.sendMessage(Language.get("preview-disabled"))
                } else {
                    sender.sendMessage(Language.get("preview-start"))
                }
            }
            "back" -> {
                if (sender !is Player) {
                    sender.sendMessage(Language.get("player-only"))
                    return true
                }
                TeleportService.teleportBack(sender)
            }
            "tp", "teleport", "goto" -> {
                if (sender !is Player) {
                    sender.sendMessage(Language.get("player-only"))
                    return true
                }
                if (args.size < 2) {
                    sender.sendMessage(Language.get("teleport-usage"))
                    return true
                }
                if (args.size == 2 && (args[1].equals("back", ignoreCase = true) || args[1] == "返回")) {
                    TeleportService.teleportBack(sender)
                    return true
                }
                val worldName = args[1]
                if (args.size == 3) {
                    val x = args[2].toDoubleOrNull()
                    if (x != null) {
                        TeleportService.teleport(sender, worldName, x, null, 0.0)
                    } else {
                        sender.sendMessage(Language.get("teleport-invalid-coords"))
                    }
                } else if (args.size == 4) {
                    val cx = args[2].toIntOrNull()
                    val cz = args[3].toIntOrNull()
                    if (cx != null && cz != null) {
                        val blockX = (cx * 16 + 8).toDouble()
                        val blockZ = (cz * 16 + 8).toDouble()
                        TeleportService.teleport(sender, worldName, blockX, null, blockZ)
                    } else {
                        sender.sendMessage(Language.get("teleport-invalid-coords"))
                    }
                } else if (args.size >= 5) {
                    val x = args[2].toDoubleOrNull()
                    val y = args[3].toDoubleOrNull()
                    val z = args[4].toDoubleOrNull()
                    if (x != null && y != null && z != null) {
                        TeleportService.teleport(sender, worldName, x, y, z)
                    } else {
                        sender.sendMessage(Language.get("teleport-invalid-coords"))
                    }
                } else {
                    sender.sendMessage(Language.get("teleport-usage"))
                }
            }
            "here" -> {
                if (!requireActive(sender)) return true
                if (sender !is Player) {
                    sender.sendMessage(Language.get("player-only"))
                    return true
                }
                if (!Settings.hereCleanupEnabled) {
                    sender.sendMessage(Language.get("here-cleanup-disabled"))
                    return true
                }
                if (WindowScanner.isRunning) {
                    sender.sendMessage(Language.get("scan-running"))
                    return true
                }
                if (!Settings.isWorldEnabled(sender.world.name)) {
                    sender.sendMessage(Language.get("teleport-world-not-found", "world" to sender.world.name))
                    return true
                }

                val mode = args.getOrNull(1)?.lowercase()
                var cleanItems = when (mode) {
                    "items", "item", "i", "drop", "drops", "掉落物", "物品" -> true
                    "entities", "entity", "e", "mob", "mobs", "实体", "生物" -> false
                    else -> true
                }
                var cleanEntities = when (mode) {
                    "items", "item", "i", "drop", "drops", "掉落物", "物品" -> false
                    "entities", "entity", "e", "mob", "mobs", "实体", "生物" -> true
                    else -> true
                }

                if (cleanItems && !Settings.itemModuleEnabled) cleanItems = false
                if (cleanEntities && !Settings.entityModuleEnabled) cleanEntities = false

                if (!cleanItems && !cleanEntities) {
                    sender.sendMessage(Language.get("module-all-disabled"))
                    return true
                }

                val world = sender.world
                val chunkX = sender.location.blockX shr 4
                val chunkZ = sender.location.blockZ shr 4

                val started = WindowScanner.startChunkScan(
                    CleanupRequests.manual(cleanItems, cleanEntities),
                    world,
                    chunkX,
                    chunkZ
                )

                if (started) {
                    sender.sendMessage(
                        Language.get(
                            "here-cleanup-started",
                            "world" to world.name,
                            "x" to chunkX.toString(),
                            "z" to chunkZ.toString()
                        )
                    )
                } else {
                    sender.sendMessage(Language.get("scan-running"))
                }
            }
            else -> {
                sendHelp(sender, null)
            }
        }

        return true
    }

    private fun sendHelp(sender: CommandSender, pageArg: String? = null) {
        if (!canUse(sender)) {
            sender.sendMessage(Language.get("no-permission"))
            return
        }
        helpRenderer.render(
            sender = sender,
            rawPage = pageArg,
            pageCommand = "/cc help"
        )
    }

    private fun startManualCleanup(sender: CommandSender, cleanItems: Boolean, cleanEntities: Boolean) {
        if (!WindowScanner.startScan(CleanupRequests.manual(cleanItems, cleanEntities))) {
            if (WindowScanner.isRunning) {
                sender.sendMessage(Language.get("scan-running"))
            }
            return
        }
        sender.sendMessage(Language.get("cleanup-started"))
        if (Settings.clusterEnabled) {
            sender.sendMessage(Language.get("cluster-local-cleanup-no-bin"))
        }
    }

    private fun requireActive(sender: CommandSender): Boolean {
        if (ActivationService.isActive()) return true
        sender.sendMessage(Language.get("plugin-disabled"))
        return false
    }

    private fun requireAdmin(sender: CommandSender): Boolean {
        if (sender.hasPermission("cyuclear.admin")) return true
        sender.sendMessage(Language.get("no-permission"))
        return false
    }

    private fun canUse(sender: CommandSender): Boolean {
        return sender.hasPermission("cyuclear.use") || sender.hasPermission("cyuclear.admin")
    }

    private fun sendRuns(sender: CommandSender, requestedPage: Int) {
        val page = requestedPage.coerceAtLeast(0)
        val (runs, totalPages) = CleanupRunManager.list(page, 8)
        sender.sendMessage(Language.getRaw("runs-header"))
        if (runs.isEmpty()) {
            sender.sendMessage(Language.get("runs-empty"))
        } else {
            runs.forEach { run ->
                sender.sendMessage(
                    Language.get(
                        "runs-entry",
                        "id" to run.id,
                        "origin" to CleanupRunManager.originText(run.origin),
                        "state" to run.status.display,
                        "items" to run.removedItems.toString(),
                        "entities" to run.removedEntities.toString(),
                        "recovery" to "${run.pendingRecoveryEntries}/${run.recoveryEntries}"
                    )
                )
            }
        }
        sender.sendMessage(Language.get("runs-footer", "page" to (page.coerceAtMost(totalPages - 1) + 1).toString(), "total" to totalPages.toString()))
    }

    private fun sendRun(sender: CommandSender, run: CleanupRunManager.RunView, reasonsOnly: Boolean) {
        sender.sendMessage(Language.getRaw("run-header"))
        if (!reasonsOnly) {
            sender.sendMessage(Language.get("run-summary", "id" to run.id, "origin" to CleanupRunManager.originText(run.origin), "state" to run.status.display))
            sender.sendMessage(Language.get("run-counts", "chunks" to "${run.processedChunks}/${run.queuedChunks}", "items" to run.removedItems.toString(), "entities" to run.removedEntities.toString(), "recovery" to "${run.pendingRecoveryEntries}/${run.recoveryEntries}"))
            if (run.slowestWorld != null) {
                sender.sendMessage(
                    Language.get(
                        "run-slowest",
                        "world" to run.slowestWorld,
                        "x" to run.slowestChunkX.toString(),
                        "z" to run.slowestChunkZ.toString(),
                        "millis" to run.slowestChunkMillis.toString()
                    )
                )
            }
            if (run.failedChunks > 0) {
                sender.sendMessage(Language.get("run-failures", "count" to run.failedChunks.toString(), "message" to (run.failureMessage ?: "-")))
            }
        }
        val isEn = Language.isEnglish
        val itemPrefix = if (isEn) "Item " else "掉落物 "
        val entityPrefix = if (isEn) "Entity " else "实体 "
        val reasons = run.itemReasons.map { "$itemPrefix${it.title}" to it.count } + run.entityReasons.map { "$entityPrefix${it.title}" to it.count }
        if (reasons.isEmpty()) {
            sender.sendMessage(Language.get("run-reasons-empty"))
        } else {
            reasons.take(10).forEach { (reason, count) ->
                sender.sendMessage(Language.get("run-reason", "reason" to reason, "count" to count.toString()))
            }
        }
        sender.sendMessage(Language.getRaw("run-footer"))
    }

    private fun sendHotspots(sender: CommandSender, requestedPage: Int) {
        val page = requestedPage.coerceAtLeast(0)
        val (hotspots, totalPages) = HotspotTracker.list(page, 8)
        sender.sendMessage(Language.getRaw("hotspots-header"))
        if (hotspots.isEmpty()) {
            sender.sendMessage(Language.get("hotspots-empty"))
        } else {
            hotspots.forEachIndexed { index, hotspot ->
                sender.sendMessage(
                    Language.get(
                        "hotspots-entry",
                        "index" to (index + 1).toString(),
                        "world" to hotspot.world,
                        "x" to hotspot.chunkX.toString(),
                        "z" to hotspot.chunkZ.toString(),
                        "state" to hotspot.state.display,
                        "items" to hotspot.itemCount.toString(),
                        "entities" to hotspot.entityCount.toString(),
                        "triggers" to hotspot.triggerCount.toString()
                    )
                )
            }
        }
        sender.sendMessage(Language.get("hotspots-footer", "page" to (page.coerceAtMost(totalPages - 1) + 1).toString(), "total" to totalPages.toString()))
    }

    private fun sendPreview(sender: CommandSender, report: PreviewReport) {
        sender.sendMessage(Language.getRaw("preview-header"))
        sender.sendMessage(Language.get("preview-summary", "chunks" to report.chunks.get().toString(), "scanned" to report.scanned.get().toString()))
        sender.sendMessage(Language.get("preview-remove", "items" to report.removeItems.get().toString(), "entities" to report.removeEntities.get().toString()))
        sender.sendMessage(
            Language.get(
                "preview-protected",
                "named" to report.protectedNamed.get().toString(),
                "tamed" to report.protectedTamed.get().toString(),
                "persistent" to report.protectedPersistent.get().toString(),
                "no_despawn" to report.protectedNoDespawn.get().toString(),
                "event" to report.protectedEvent.get().toString(),
                "keep" to report.protectedKeepList.get().toString()
            )
        )
        for ((reason, count) in report.topReasons(5)) {
            sender.sendMessage(Language.get("preview-reason", "reason" to reason, "count" to count.toString()))
        }
        sender.sendMessage(Language.getRaw("preview-footer"))
    }

    private fun runForSender(sender: CommandSender, task: Runnable) {
        if (sender is Player) {
            CyuScheduler.runEntityTask(Cyuclear.instance, sender, task)
        } else {
            CyuScheduler.runTask(Cyuclear.instance, task)
        }
    }

    private fun sendMetricsStatus(sender: CommandSender) {
        val port = Settings.metricsPort.toString()
        sender.sendMessage(Language.get("metrics-title"))
        if (Settings.metricsEnabled) {
            sender.sendMessage(Language.get("metrics-enabled"))
            sender.sendMessage(Language.get("metrics-port", "port" to port))
            sender.sendMessage(Language.get("metrics-endpoint", "port" to port))
        } else {
            sender.sendMessage(Language.get("metrics-disabled"))
            sender.sendMessage(Language.get("metrics-port", "port" to port))
            sender.sendMessage(Language.get("metrics-hint-disabled"))
        }
    }
}

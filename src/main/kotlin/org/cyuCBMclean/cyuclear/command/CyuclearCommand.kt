package org.cyuCBMclean.cyuclear.command

import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.bootstrap.RuntimeReloadService
import org.cyuCBMclean.cyuclear.cluster.ClusterManager
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

object CyuclearCommand : CommandExecutor, TabCompleter {

    private val adminCommands = listOf(
        "items", "entities", "all", "cluster", "menu", "runs", "run", "recover", "hotspots",
        "cancel", "doctor", "validate", "snapshot", "history", "status", "reload", "check", "inspect", "preview"
    )

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
        if (subCommand in adminCommands && !requireAdmin(sender)) return true

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
                    sendRun(sender, run, args.getOrNull(2)?.equals("reasons", ignoreCase = true) == true)
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
            else -> {
                sendHelp(sender, null)
            }
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val subCommands = ArrayList<String>()

            if (canUse(sender)) {
                subCommands.add("help")
                subCommands.add("bin")
            }
            if (sender.hasPermission("cyuclear.admin")) {
                subCommands.addAll(
                    listOf(
                        "items", "entities", "all", "reload", "cluster", "menu", "runs", "run", "recover",
                        "hotspots", "cancel", "doctor", "validate", "snapshot", "history", "check", "preview", "status"
                    )
                )
            }

            return subCommands.filter { it.startsWith(args[0], ignoreCase = true) }
        }

        if (args.size == 2 && args[0].equals("help", ignoreCase = true)) {
            val total = getHelpTotalPages(sender)
            return (1..total).map { it.toString() }.filter { it.startsWith(args[1]) }
        }

        if (args.size == 2 && (args[0].equals("recover", ignoreCase = true) || args[0].equals("run", ignoreCase = true)) && sender.hasPermission("cyuclear.admin")) {
            return CleanupRunManager.list(0, 54).first
                .map { it.id }
                .filter { it.startsWith(args[1], ignoreCase = true) }
        }

        if (args.size == 3 && args[0].equals("run", ignoreCase = true) && sender.hasPermission("cyuclear.admin")) {
            return listOf("details", "reasons").filter { it.startsWith(args[2], ignoreCase = true) }
        }

        return emptyList()
    }

    private fun getHelpPageSize(): Int = Language.getInt("help-page-size", 8).coerceAtLeast(1)

    private data class HelpEntry(
        val command: String,
        val key: String,
        val adminOnly: Boolean
    )

    private val allHelpEntries = listOf(
        HelpEntry("/cc bin", "help-bin", false),
        HelpEntry("/cc items", "help-items", true),
        HelpEntry("/cc entities", "help-entities", true),
        HelpEntry("/cc all", "help-all", true),
        HelpEntry("/cc check", "help-check", true),
        HelpEntry("/cc preview", "help-preview", true),
        HelpEntry("/cc status", "help-status", true),
        HelpEntry("/cc reload", "help-reload", true),
        HelpEntry("/cc cluster", "help-cluster", true),
        HelpEntry("/cc menu", "help-menu", true),
        HelpEntry("/cc runs", "help-runs", true),
        HelpEntry("/cc run ", "help-run", true),
        HelpEntry("/cc recover ", "help-recover", true),
        HelpEntry("/cc hotspots", "help-hotspots", true),
        HelpEntry("/cc cancel", "help-cancel", true),
        HelpEntry("/cc doctor", "help-doctor", true),
        HelpEntry("/cc snapshot", "help-snapshot", true),
        HelpEntry("/cc history", "help-history", true)
    )

    private fun getAvailableEntries(sender: CommandSender): List<HelpEntry> {
        val hasAdmin = sender.hasPermission("cyuclear.admin")
        return allHelpEntries.filter { !it.adminOnly || hasAdmin }
    }

    private fun getHelpTotalPages(sender: CommandSender): Int {
        val count = getAvailableEntries(sender).size
        val pageSize = getHelpPageSize()
        return maxOf(1, (count + pageSize - 1) / pageSize)
    }

    private fun sendHelp(sender: CommandSender, pageArg: String? = null) {
        val hasUse = canUse(sender)
        if (!hasUse) {
            sender.sendMessage(Language.get("no-permission"))
            return
        }

        val entries = getAvailableEntries(sender)
        val pageSize = getHelpPageSize()
        val totalPages = maxOf(1, (entries.size + pageSize - 1) / pageSize)

        val parsed = pageArg?.toIntOrNull()
        if (pageArg != null && parsed == null) {
            sender.sendMessage(Language.get("help-invalid-page"))
            return
        }

        val page = if (parsed == null || parsed in 1..totalPages) parsed ?: 1 else {
            sender.sendMessage(Language.get("help-page-out-of-bounds", "total_pages" to totalPages.toString()))
            1
        }

        val start = (page - 1) * pageSize
        val end = minOf(start + pageSize, entries.size)
        val pageEntries = entries.subList(start, end)

        sender.sendMessage(Language.getRaw("help-border"))
        sender.sendMessage(Language.getRaw("help-title"))

        if (sender is Player) {
            for (entry in pageEntries) {
                val lineText = Language.getRaw(entry.key)
                val click = ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, entry.command)
                val hover = HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    TextComponent.fromLegacyText(Language.getRaw("help-json-hover-entry", "command" to entry.command))
                )
                sender.spigot().sendMessage(clickableLegacy(lineText, click, hover))
            }
            if (totalPages > 1) {
                val prevPage = if (page > 1) page - 1 else totalPages
                val nextPage = if (page < totalPages) page + 1 else 1

                val footerComp = TextComponent("")
                val prevBtn = clickableLegacy(
                    Language.getRaw("help-json-button-prev"),
                    ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cc help $prevPage"),
                    HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        TextComponent.fromLegacyText(Language.getRaw("help-json-hover-prev", "page" to prevPage.toString()))
                    )
                )
                val info = TextComponent("")
                TextComponent.fromLegacyText(
                    " " + Language.getRaw(
                        "help-json-page-info",
                        "current_page" to page.toString(),
                        "total_pages" to totalPages.toString()
                    ) + " "
                ).forEach { info.addExtra(it) }
                val nextBtn = clickableLegacy(
                    Language.getRaw("help-json-button-next"),
                    ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cc help $nextPage"),
                    HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        TextComponent.fromLegacyText(Language.getRaw("help-json-hover-next", "page" to nextPage.toString()))
                    )
                )
                footerComp.addExtra(prevBtn)
                footerComp.addExtra(info)
                footerComp.addExtra(nextBtn)
                sender.spigot().sendMessage(footerComp)
            }
        } else {
            for (entry in pageEntries) {
                sender.sendMessage(Language.getRaw(entry.key))
            }
            if (totalPages > 1) {
                sender.sendMessage(Language.getRaw("help-console-page-info", "current_page" to page.toString(), "total_pages" to totalPages.toString(), "command" to "/cc help <页码>"))
            }
        }
        sender.sendMessage(Language.getRaw("help-border"))
    }

    private fun clickableLegacy(text: String, click: ClickEvent, hover: HoverEvent): TextComponent {
        val root = TextComponent("")
        TextComponent.fromLegacyText(text).forEach { part ->
            part.clickEvent = click
            part.hoverEvent = hover
            root.addExtra(part)
        }
        return root
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
        val reasons = run.itemReasons.map { "掉落物 ${it.title}" to it.count } + run.entityReasons.map { "实体 ${it.title}" to it.count }
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
}

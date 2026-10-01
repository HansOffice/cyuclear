package org.cyuCBMclean.cyuclear.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.cyuCBMclean.cyuclear.command.sub.HelpRenderer
import org.cyuCBMclean.cyuclear.command.sub.MAIN_HELP_LINES
import org.cyuCBMclean.cyuclear.service.CleanupRunManager

class CyuclearTabCompleter(
    private val helpRenderer: HelpRenderer = HelpRenderer()
) : TabCompleter {

    private val subCommands = listOf(
        "help", "bin", "items", "entities", "all", "check", "preview", "status", "metrics",
        "reload", "lang", "cluster", "menu", "runs", "run", "recover",
        "hotspots", "here", "tp", "back", "cancel", "doctor", "snapshot", "history"
    )

    private val userCommands = setOf("help", "bin")

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.isEmpty()) return emptyList()
        val sub = args[0].lowercase()

        if (args.size == 1) {
            val hasUse = sender.hasPermission("cyuclear.use") || sender.hasPermission("cyuclear.admin")
            val hasAdmin = sender.hasPermission("cyuclear.admin")
            val available = subCommands.filter { name ->
                if (name in userCommands) hasUse else hasAdmin
            }
            return filter(available, args[0])
        }

        if (sub !in userCommands && !sender.hasPermission("cyuclear.admin")) {
            return emptyList()
        }

        return when (sub) {
            "help" -> if (args.size == 2) filter(helpRenderer.helpPages(sender, MAIN_HELP_LINES), args[1]) else emptyList()
            "here" -> if (args.size == 2) filter(smartOptions(args[1], listOf("items", "entities", "all"), listOf("掉落物", "实体", "全部")), args[1]) else emptyList()
            "tp", "teleport", "goto" -> if (args.size == 2) {
                val backOption = smartOptions(args[1], listOf("back"), listOf("返回"))
                val worlds = Bukkit.getWorlds().map { it.name }
                filter(backOption + worlds, args[1])
            } else emptyList()
            "lang", "language" -> if (args.size == 2) filter(listOf("zh_cn", "en_us", "auto"), args[1]) else emptyList()
            "run" -> when (args.size) {
                2 -> filter(CleanupRunManager.list(0, 54).first.map { it.id }, args[1])
                3 -> filter(smartOptions(args[2], listOf("details", "reasons"), listOf("详情", "原因")), args[2])
                else -> emptyList()
            }
            "recover" -> if (args.size == 2) filter(CleanupRunManager.list(0, 54).first.map { it.id }, args[1]) else emptyList()
            "runs", "hotspots" -> if (args.size == 2) filter((1..10).map(Int::toString), args[1]) else emptyList()
            "history" -> when (args.size) {
                2 -> filter(onlinePlayers() + (1..10).map(Int::toString), args[1])
                3 -> filter((1..10).map(Int::toString), args[2])
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun isEnglish(token: String): Boolean =
        token.any { it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '_' }

    private fun smartOptions(
        prefix: String,
        english: List<String>,
        chinese: List<String>
    ): List<String> = if (isEnglish(prefix)) english else chinese

    private fun onlinePlayers(): List<String> = Bukkit.getOnlinePlayers().map { it.name }

    private fun filter(list: List<String>, prefix: String): List<String> =
        list.filter { it.startsWith(prefix, ignoreCase = true) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
}

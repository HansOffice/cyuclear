package org.cyuCBMclean.cyuclear.command.sub

import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.util.ColorUtils

class HelpRenderer(
    private val pageSize: () -> Int = { Language.getInt("help-page-size", 8).coerceAtLeast(1) }
) {
    fun helpPageSize(): Int = pageSize().coerceAtLeast(1)

    fun availableHelp(sender: CommandSender, lines: List<HelpLine>): List<HelpLine> =
        lines.filter { it.permission == null || sender.hasPermission(it.permission) }

    fun helpPages(sender: CommandSender, lines: List<HelpLine>): List<String> {
        val size = helpPageSize()
        val total = maxOf(1, (availableHelp(sender, lines).size + size - 1) / size)
        return (1..total).map(Int::toString)
    }

    fun render(
        sender: CommandSender,
        titleKey: String = "help-title",
        titleFallback: String = "<gradient:#58C7FF:#7DE2B8>&lCyuclear 帮助</gradient>",
        lines: List<HelpLine> = MAIN_HELP_LINES,
        rawPage: String? = null,
        pageCommand: String = "/cc help",
    ) {
        val entries = availableHelp(sender, lines)
        val size = helpPageSize()
        val totalPages = maxOf(1, (entries.size + size - 1) / size)
        val parsed = rawPage?.toIntOrNull()
        if (rawPage != null && parsed == null) {
            sendRaw(sender, rawLang("help-invalid-page", "&#E74C3C× 页码无效，请输入数字"))
            return
        }
        val page = if (parsed == null || parsed in 1..totalPages) parsed ?: 1 else {
            sendRaw(
                sender,
                apply(
                    rawLang("help-page-out-of-bounds", "&#8A96A8帮助页码超出范围，已为你显示第 &#D7DEE81 &#8A96A8页，共 &#D7DEE8{total_pages} &#8A96A8页"),
                    arrayOf("total_pages" to totalPages.toString()),
                ),
            )
            1
        }
        val start = (page - 1) * size
        val pageEntries = entries.subList(start, minOf(start + size, entries.size))
        sendRaw(sender, rawLang("help-border", "&#3A4352&m┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈"))
        sendRaw(sender, rawLang(titleKey, titleFallback))
        if (sender is Player) {
            for (entry in pageEntries) {
                val line = ColorUtils.color(rawLang(entry.key, entry.fallback))
                val click = ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, entry.command)
                val hover = HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    TextComponent.fromLegacyText(
                        ColorUtils.color(
                            apply(
                                rawLang("help-json-hover-entry", "&#8A96A8点击在聊天框填入指令 &#D7DEE8{command}"),
                                arrayOf("command" to entry.command),
                            ),
                        ),
                    ),
                )
                sender.spigot().sendMessage(clickableLegacy(line, click, hover))
            }
            if (totalPages > 1) {
                val prevPage = if (page > 1) page - 1 else totalPages
                val nextPage = if (page < totalPages) page + 1 else 1
                val prevHover = HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(ColorUtils.color(apply(rawLang("help-json-hover-prev", "&#8A96A8点击查看第 &#D7DEE8{page} &#8A96A8页"), arrayOf("page" to prevPage.toString())))))
                val nextHover = HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(ColorUtils.color(apply(rawLang("help-json-hover-next", "&#8A96A8点击查看第 &#D7DEE8{page} &#8A96A8页"), arrayOf("page" to nextPage.toString())))))
                val prevBtn = clickableLegacy(ColorUtils.color(rawLang("help-json-button-prev", "&#7DD3FC&l‹ 上一页")), ClickEvent(ClickEvent.Action.RUN_COMMAND, "$pageCommand $prevPage"), prevHover)
                val nextBtn = clickableLegacy(ColorUtils.color(rawLang("help-json-button-next", "&#7DD3FC&l下一页 ›")), ClickEvent(ClickEvent.Action.RUN_COMMAND, "$pageCommand $nextPage"), nextHover)
                val info = TextComponent("")
                TextComponent.fromLegacyText(" " + ColorUtils.color(apply(rawLang("help-json-page-info", "&#8A96A8第 &#D7DEE8{current_page}&#8A96A8/&#D7DEE8{total_pages} &#8A96A8页"), arrayOf("current_page" to page.toString(), "total_pages" to totalPages.toString()))) + " ").forEach { info.addExtra(it) }
                val footer = TextComponent("").apply {
                    addExtra(prevBtn)
                    addExtra(info)
                    addExtra(nextBtn)
                }
                sender.spigot().sendMessage(footer)
            }
        } else {
            pageEntries.forEach { sendRaw(sender, rawLang(it.key, it.fallback)) }
            if (totalPages > 1) {
                val cmdExample = if (Language.isEnglish) "$pageCommand <page>" else "$pageCommand <页码>"
                sendRaw(
                    sender,
                    apply(
                        rawLang(
                            "help-console-page-info",
                            "&#8A96A8第 &#D7DEE8{current_page}&#8A96A8/&#D7DEE8{total_pages} &#8A96A8页 &#5B6472| &#8A96A8使用 &#D7DEE8{command} &#8A96A8翻页",
                        ),
                        arrayOf(
                            "current_page" to page.toString(),
                            "total_pages" to totalPages.toString(),
                            "command" to cmdExample,
                        ),
                    ),
                )
            }
        }
        sendRaw(sender, rawLang("help-border", "&#3A4352&m┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈"))
    }

    private fun rawLang(key: String, fallback: String): String {
        return if (Language.has(key)) Language.getRaw(key) else fallback
    }

    private fun sendRaw(sender: CommandSender, message: String) =
        sender.sendMessage(ColorUtils.color(message))

    private fun apply(raw: String, placeholders: Array<out Pair<String, String>>): String {
        var result = raw
        placeholders.forEach { (key, value) -> result = result.replace("{$key}", value) }
        return result
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
}

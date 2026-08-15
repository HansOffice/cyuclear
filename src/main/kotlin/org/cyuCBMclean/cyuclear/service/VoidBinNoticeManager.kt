package org.cyuCBMclean.cyuclear.service

import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.Bukkit
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler

object VoidBinNoticeManager {

    fun broadcastCleanupSummary(summaryMessage: String, openSeconds: Int?) {
        if (openSeconds == null) {
            PlayerMessageDispatcher.broadcast(summaryMessage)
            return
        }

        val placeholders = arrayOf("time" to openSeconds.toString())
        val clickMessage = Language.getClickMessage("bin-open-click", *placeholders)

        if (!Settings.binOpenClickEnabled || clickMessage.text.isBlank() || Settings.binOpenClickCommand.isBlank()) {
            PlayerMessageDispatcher.broadcast(summaryMessage)
            PlayerMessageDispatcher.broadcast(Language.get("bin-open-hint", *placeholders))
            return
        }

        val components = ArrayList<BaseComponent>()
        components.addAll(TextComponent.fromLegacyText(summaryMessage))
        components.add(TextComponent(" "))
        components.addAll(createOpenButton(clickMessage.text, clickMessage.hover))

        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            Bukkit.getOnlinePlayers().forEach { player ->
                CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
                    if (player.isOnline) {
                        player.spigot().sendMessage(*components.toTypedArray())
                    }
                })
            }
            Bukkit.getConsoleSender().sendMessage(summaryMessage)
            Bukkit.getConsoleSender().sendMessage(Language.get("bin-open-hint", *placeholders))
        })
    }

    fun broadcastOpenHint(seconds: Int) {
        val placeholders = arrayOf("time" to seconds.toString())
        val clickMessage = Language.getClickMessage("bin-open-click", *placeholders)

        if (!Settings.binOpenClickEnabled || clickMessage.text.isBlank() || Settings.binOpenClickCommand.isBlank()) {
            PlayerMessageDispatcher.broadcast(Language.get("bin-open-hint", *placeholders))
            return
        }

        val components = createOpenButton(clickMessage.text, clickMessage.hover)
        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            Bukkit.getOnlinePlayers().forEach { player ->
                CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
                    if (player.isOnline) {
                        player.spigot().sendMessage(*components)
                    }
                })
            }
            Bukkit.getConsoleSender().sendMessage(Language.get("bin-open-hint", *placeholders))
        })
    }

    private fun createOpenButton(text: String, hover: String): Array<BaseComponent> {
        val clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, Settings.binOpenClickCommand)
        val hoverEvent = if (hover.isNotBlank()) {
            HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText(hover)
            )
        } else {
            null
        }

        return TextComponent.fromLegacyText(text).apply {
            forEach { component ->
                component.clickEvent = clickEvent
                component.hoverEvent = hoverEvent
            }
        }
    }
}

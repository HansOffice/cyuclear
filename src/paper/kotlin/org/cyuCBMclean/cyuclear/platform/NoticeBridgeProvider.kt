package org.cyuCBMclean.cyuclear.platform

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler

object NoticeBridgeProvider {
    val bridge: NoticeBridge = PaperNoticeBridge
}

private object PaperNoticeBridge : NoticeBridge {

    override val supportsActionBar: Boolean = true

    override fun sendActionBar(plugin: Plugin, players: Collection<Player>, message: String) {
        if (message.isBlank() || players.isEmpty()) return
        val components = TextComponent.fromLegacyText(message)
        for (player in players) {
            CyuScheduler.runEntityTask(plugin, player, Runnable {
                if (player.isOnline) {
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *components)
                }
            })
        }
    }

    override fun createBossBar(title: String, color: NoticeBossBarColor, style: NoticeBossBarStyle): NoticeBossBar {
        return PaperNoticeBossBar(Bukkit.createBossBar(title, color.toBukkit(), style.toBukkit()))
    }
}

private class PaperNoticeBossBar(private val bar: BossBar) : NoticeBossBar {

    override fun update(title: String, progress: Double) {
        bar.setTitle(title)
        bar.setProgress(progress.coerceIn(0.0, 1.0))
        bar.setVisible(true)
    }

    override fun addOnlinePlayers(plugin: Plugin, players: Collection<Player>) {
        for (player in players) {
            CyuScheduler.runEntityTask(plugin, player, Runnable {
                if (player.isOnline) {
                    bar.addPlayer(player)
                }
            })
        }
    }

    override fun clear(plugin: Plugin) {
        for (player in Bukkit.getOnlinePlayers()) {
            CyuScheduler.runEntityTask(plugin, player, Runnable {
                bar.removePlayer(player)
            })
        }
        bar.setVisible(false)
    }
}

private fun NoticeBossBarColor.toBukkit(): BarColor {
    return when (this) {
        NoticeBossBarColor.PINK -> BarColor.PINK
        NoticeBossBarColor.BLUE -> BarColor.BLUE
        NoticeBossBarColor.RED -> BarColor.RED
        NoticeBossBarColor.GREEN -> BarColor.GREEN
        NoticeBossBarColor.YELLOW -> BarColor.YELLOW
        NoticeBossBarColor.PURPLE -> BarColor.PURPLE
        NoticeBossBarColor.WHITE -> BarColor.WHITE
    }
}

private fun NoticeBossBarStyle.toBukkit(): BarStyle {
    return when (this) {
        NoticeBossBarStyle.SOLID -> BarStyle.SOLID
        NoticeBossBarStyle.SEGMENTED_6 -> BarStyle.SEGMENTED_6
        NoticeBossBarStyle.SEGMENTED_10 -> BarStyle.SEGMENTED_10
        NoticeBossBarStyle.SEGMENTED_12 -> BarStyle.SEGMENTED_12
        NoticeBossBarStyle.SEGMENTED_20 -> BarStyle.SEGMENTED_20
    }
}

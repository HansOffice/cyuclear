package org.cyuCBMclean.cyuclear.platform

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object NoticeBridgeProvider {
    val bridge: NoticeBridge = FoliaNoticeBridge
}

private object FoliaNoticeBridge : NoticeBridge {

    private val legacySerializer = LegacyComponentSerializer.legacySection()

    override val supportsActionBar: Boolean = true

    override fun sendActionBar(plugin: Plugin, players: Collection<Player>, message: String) {
        if (message.isBlank() || players.isEmpty()) return
        val component = legacySerializer.deserialize(message)
        for (player in players) {
            CyuScheduler.runEntityTask(plugin, player, Runnable {
                if (player.isOnline) {
                    player.sendActionBar(component)
                }
            })
        }
    }

    override fun createBossBar(title: String, color: NoticeBossBarColor, style: NoticeBossBarStyle): NoticeBossBar {
        return FoliaNoticeBossBar(title, color.toBukkit(), style.toBukkit())
    }
}

private class FoliaNoticeBossBar(
    initialTitle: String,
    private val color: BarColor,
    private val style: BarStyle
) : NoticeBossBar {

    private val bars = ConcurrentHashMap<UUID, BossBar>()
    @Volatile
    private var title: String = initialTitle
    @Volatile
    private var progress: Double = 1.0

    override fun update(title: String, progress: Double) {
        this.title = title
        this.progress = progress.coerceIn(0.0, 1.0)
    }

    override fun addOnlinePlayers(plugin: Plugin, players: Collection<Player>) {
        val onlineIds = players.asSequence().map { it.uniqueId }.toSet()
        bars.keys.removeIf { it !in onlineIds }

        for (player in players) {
            CyuScheduler.runEntityTask(plugin, player, Runnable {
                if (!player.isOnline) {
                    bars.remove(player.uniqueId)
                    return@Runnable
                }

                val bar = bars.computeIfAbsent(player.uniqueId) {
                    Bukkit.createBossBar(title, color, style)
                }
                bar.setTitle(title)
                bar.setProgress(progress)
                bar.setVisible(true)
                bar.addPlayer(player)
            })
        }
    }

    override fun clear(plugin: Plugin) {
        val snapshot = bars.toMap()
        bars.clear()
        for ((uuid, bar) in snapshot) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            CyuScheduler.runEntityTask(plugin, player, Runnable {
                bar.removePlayer(player)
                bar.setVisible(false)
            })
        }
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

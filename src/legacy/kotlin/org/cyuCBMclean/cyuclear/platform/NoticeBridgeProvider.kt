package org.cyuCBMclean.cyuclear.platform

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler

object NoticeBridgeProvider {
    val bridge: NoticeBridge = LegacyNoticeBridge
}

private object LegacyNoticeBridge : NoticeBridge {

    override val supportsActionBar: Boolean = false

    override fun sendActionBar(plugin: Plugin, players: Collection<Player>, message: String) {
        if (message.isBlank() || players.isEmpty()) return
        for (player in players) {
            CyuScheduler.runEntityTask(plugin, player, Runnable {
                if (player.isOnline) {
                    player.sendMessage(message)
                }
            })
        }
    }

    override fun createBossBar(title: String, color: NoticeBossBarColor, style: NoticeBossBarStyle): NoticeBossBar? {
        return null
    }
}

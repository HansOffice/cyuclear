package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler

object PlayerMessageDispatcher {

    fun broadcast(message: String) {
        if (message.isBlank()) return
        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                send(player, message)
            }
        })
    }

    fun broadcast(message: String, permission: String) {
        if (message.isBlank()) return
        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                send(player, message, permission)
            }
        })
    }

    fun send(player: Player, message: String) {
        send(player, message, null)
    }

    private fun send(player: Player, message: String, permission: String?) {
        CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
            if (player.isOnline && (permission == null || player.hasPermission(permission))) {
                player.sendMessage(message)
            }
        })
    }
}

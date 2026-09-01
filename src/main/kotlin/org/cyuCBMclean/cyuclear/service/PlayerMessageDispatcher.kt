package org.cyuCBMclean.cyuclear.service

import net.md_5.bungee.api.chat.BaseComponent
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

    fun broadcast(components: Array<BaseComponent>, permission: String) {
        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
                    if (player.isOnline && player.hasPermission(permission)) {
                        player.spigot().sendMessage(*components)
                    }
                })
            }
        })
    }

    fun broadcastInteractive(plainMessage: String, adminComponents: Array<BaseComponent>, adminPermission: String = "cyuclear.admin") {
        if (plainMessage.isBlank()) return
        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
                    if (player.isOnline) {
                        if (player.hasPermission(adminPermission)) {
                            player.spigot().sendMessage(*adminComponents)
                        } else {
                            player.sendMessage(plainMessage)
                        }
                    }
                })
            }
        })
    }

    fun send(player: Player, message: String) {
        send(player, message, null)
    }

    fun sendComponents(player: Player, components: Array<BaseComponent>, permission: String? = null) {
        CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
            if (player.isOnline && (permission == null || player.hasPermission(permission))) {
                player.spigot().sendMessage(*components)
            }
        })
    }

    private fun send(player: Player, message: String, permission: String?) {
        CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
            if (player.isOnline && (permission == null || player.hasPermission(permission))) {
                player.sendMessage(message)
            }
        })
    }
}

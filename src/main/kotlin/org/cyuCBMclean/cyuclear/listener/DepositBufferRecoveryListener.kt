package org.cyuCBMclean.cyuclear.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.service.DepositBufferManager
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler

object DepositBufferRecoveryListener : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
            DepositBufferManager.recover(player)
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        DepositBufferManager.onQuit(event.player)
    }
}

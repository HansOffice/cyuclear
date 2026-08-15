package org.cyuCBMclean.cyuclear.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.cyuCBMclean.cyuclear.service.VoidBinManager

object BinClaimRecoveryListener : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        VoidBinManager.recoverPendingClaims(event.player)
    }
}

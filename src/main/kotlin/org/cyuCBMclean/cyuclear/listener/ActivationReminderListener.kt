package org.cyuCBMclean.cyuclear.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.cyuCBMclean.cyuclear.service.ActivationReminder

object ActivationReminderListener : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        ActivationReminder.notifyIfInactiveAdmin(event.player)
    }
}

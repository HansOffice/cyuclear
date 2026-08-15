package org.cyuCBMclean.cyuclear.service

import org.bukkit.entity.Player
import org.cyuCBMclean.cyuclear.config.Language
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ActivationReminder {
    private val notifiedPlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun notifyIfInactiveAdmin(player: Player) {
        if (ActivationService.isActive()) return
        if (!player.hasPermission("cyuclear.admin")) return
        if (!notifiedPlayers.add(player.uniqueId)) return
        PlayerMessageDispatcher.send(player, Language.get("startup-disabled-admin"))
    }

    fun reset() {
        notifiedPlayers.clear()
    }
}

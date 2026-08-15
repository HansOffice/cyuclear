package org.cyuCBMclean.cyuclear.menu

import org.bukkit.entity.Player

object MenuTitleBridge {
    fun send(player: Player, title: String, subtitle: String) {
        player.sendTitle(title, subtitle, 10, 70, 20)
    }
}

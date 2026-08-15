package org.cyuCBMclean.cyuclear.menu

import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player

object MenuTextResolver {

    fun resolve(player: Player?, source: String): String {
        if (source.isEmpty()) return source
        var text = source
        if (player != null) {
            text = text
                .replace("%player_name%", player.name)
                .replace("%player_uuid%", player.uniqueId.toString())
        }
        if (player == null || '%' !in text || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return text
        }
        return runCatching { PlaceholderAPI.setPlaceholders(player, text) }.getOrDefault(text)
    }
}

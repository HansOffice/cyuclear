package org.cyuCBMclean.cyuclear.platform

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

interface NoticeBridge {

    val supportsActionBar: Boolean

    fun sendActionBar(plugin: Plugin, players: Collection<Player>, message: String)

    fun createBossBar(title: String, color: NoticeBossBarColor, style: NoticeBossBarStyle): NoticeBossBar?
}

interface NoticeBossBar {

    fun update(title: String, progress: Double)

    fun addOnlinePlayers(plugin: Plugin, players: Collection<Player>)

    fun clear(plugin: Plugin)
}

enum class NoticeBossBarColor {
    PINK,
    BLUE,
    RED,
    GREEN,
    YELLOW,
    PURPLE,
    WHITE;

    companion object {
        fun parse(raw: String?): NoticeBossBarColor? {
            val value = raw?.trim()?.replace('-', '_')?.replace(' ', '_')?.uppercase()
            return values().firstOrNull { it.name == value }
        }
    }
}

enum class NoticeBossBarStyle {
    SOLID,
    SEGMENTED_6,
    SEGMENTED_10,
    SEGMENTED_12,
    SEGMENTED_20;

    companion object {
        fun parse(raw: String?): NoticeBossBarStyle? {
            val value = raw?.trim()?.replace('-', '_')?.replace(' ', '_')?.uppercase()
            return values().firstOrNull { it.name == value }
        }
    }
}

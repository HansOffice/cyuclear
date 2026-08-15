package org.cyuCBMclean.cyuclear.util

import org.bukkit.Bukkit

object ServerEnv {

    val supportsHexColors: Boolean by lazy {
        val parts = Bukkit.getBukkitVersion()
            .substringBefore('-')
            .split('.')
            .mapNotNull { it.toIntOrNull() }

        val major = parts.getOrNull(0) ?: 1
        val minor = parts.getOrNull(1) ?: 0

        major > 1 || minor >= 16
    }
}

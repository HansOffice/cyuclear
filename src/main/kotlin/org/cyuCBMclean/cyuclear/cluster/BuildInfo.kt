package org.cyuCBMclean.cyuclear.cluster

import org.bukkit.Bukkit
import org.cyuCBMclean.cyuclear.platform.PlatformInfo

object BuildInfo {
    val platformId: String
        get() = PlatformInfo.id

    val compatibilityDomain: String
        get() = if (isLegacyServer()) "legacy" else "modern"

    private fun isLegacyServer(): Boolean {
        val version = Bukkit.getBukkitVersion().substringBefore('-')
        val parts = version.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major == 1 && minor <= 12
    }
}

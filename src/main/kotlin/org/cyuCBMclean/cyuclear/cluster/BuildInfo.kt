package org.cyuCBMclean.cyuclear.cluster

import org.bukkit.Bukkit
import org.cyuCBMclean.cyuclear.platform.PlatformInfo

object BuildInfo {
    val platformId: String
        get() = PlatformInfo.id

    val compatibilityDomain: String
        get() = if (isLegacyServer()) "legacy" else "modern"

    val isEnglishEdition: Boolean
        get() = org.cyuCBMclean.cyuclear.config.Language.isEnglish

    val minecraftVersion: String by lazy {
        resolveMinecraftVersion()
    }

    private fun resolveMinecraftVersion(): String {
        val detected = runCatching {
            val method = Bukkit::class.java.getMethod("getMinecraftVersion")
            val version = method.invoke(null) as? String
            version?.takeIf { it.isNotBlank() }?.trim()
        }.getOrNull()

        val raw = detected ?: Bukkit.getBukkitVersion().substringBefore('-').trim()
        return ClusterIdentity.normalizeMinecraftVersion(raw)
    }

    private fun isLegacyServer(): Boolean {
        val parts = minecraftVersion.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major == 1 && minor <= 12
    }
}

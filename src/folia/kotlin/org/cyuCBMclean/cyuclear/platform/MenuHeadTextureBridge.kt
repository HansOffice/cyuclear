package org.cyuCBMclean.cyuclear.platform

import org.bukkit.Bukkit
import org.bukkit.inventory.meta.SkullMeta
import java.net.URL
import java.util.UUID

@Suppress("UNUSED_PARAMETER")
object MenuHeadTextureBridge {
    fun reload() = Unit

    fun apply(meta: SkullMeta, payload: String, skinUrl: String?): Boolean {
        val url = skinUrl ?: return false
        return runCatching {
            val profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "")
            val textures = profile.getTextures()
            textures.setSkin(URL(url))
            profile.setTextures(textures)
            meta.setOwnerProfile(profile)
            true
        }.getOrDefault(false)
    }
}

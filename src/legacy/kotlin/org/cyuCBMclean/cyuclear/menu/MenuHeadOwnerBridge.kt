package org.cyuCBMclean.cyuclear.menu

import org.bukkit.OfflinePlayer
import org.bukkit.inventory.meta.SkullMeta

object MenuHeadOwnerBridge {
    fun apply(meta: SkullMeta, owner: OfflinePlayer): Boolean {
        val name = owner.name ?: return false
        return meta.setOwner(name)
    }
}

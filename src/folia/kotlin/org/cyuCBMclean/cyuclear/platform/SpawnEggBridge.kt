package org.cyuCBMclean.cyuclear.platform

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SpawnEggMeta
import java.util.Locale

object SpawnEggBridge {
    fun spawnedTypeName(item: ItemStack): String? {
        val meta = item.itemMeta as? SpawnEggMeta ?: return null
        return meta.spawnedType.toString().lowercase(Locale.ROOT)
    }
}

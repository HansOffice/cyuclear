package org.cyuCBMclean.cyuclear.platform

import org.bukkit.inventory.ItemStack
import org.bukkit.material.SpawnEgg
import java.util.Locale

object SpawnEggBridge {
    fun spawnedTypeName(item: ItemStack): String? {
        val data = item.data as? SpawnEgg ?: return null
        return data.spawnedType.toString().lowercase(Locale.ROOT)
    }
}

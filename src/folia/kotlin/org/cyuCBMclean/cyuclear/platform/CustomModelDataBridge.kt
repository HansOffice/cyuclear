package org.cyuCBMclean.cyuclear.platform

import org.bukkit.inventory.meta.ItemMeta

object CustomModelDataBridge {
    fun read(meta: ItemMeta): Int? {
        return meta.getCustomModelDataComponent().getFloats().firstOrNull()?.toInt()
    }

    fun write(meta: ItemMeta, value: Int): Boolean {
        return runCatching {
            val component = meta.getCustomModelDataComponent()
            component.setFloats(listOf(value.toFloat()))
            meta.setCustomModelDataComponent(component)
            true
        }.getOrDefault(false)
    }
}

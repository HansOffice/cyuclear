package org.cyuCBMclean.cyuclear.platform

import org.bukkit.inventory.meta.ItemMeta

@Suppress("UNUSED_PARAMETER")
object CustomModelDataBridge {
    fun read(meta: ItemMeta): Int? = null

    fun write(meta: ItemMeta, value: Int): Boolean = false
}

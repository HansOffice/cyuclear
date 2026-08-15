package org.cyuCBMclean.cyuclear.platform

import org.bukkit.inventory.ItemStack

object ItemStackDataBridge {
    val supportsRaw: Boolean = true

    fun encode(item: ItemStack): ByteArray = item.serializeAsBytes()

    fun decode(bytes: ByteArray): ItemStack = ItemStack.deserializeBytes(bytes)
}

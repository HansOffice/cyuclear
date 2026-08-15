package org.cyuCBMclean.cyuclear.platform

import org.bukkit.inventory.ItemStack

@Suppress("UNUSED_PARAMETER")
object ItemStackDataBridge {
    val supportsRaw: Boolean = false

    fun encode(item: ItemStack): ByteArray? = null

    fun decode(bytes: ByteArray): ItemStack? = null
}

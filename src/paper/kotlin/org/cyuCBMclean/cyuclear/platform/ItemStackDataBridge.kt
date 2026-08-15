package org.cyuCBMclean.cyuclear.platform

import org.bukkit.inventory.ItemStack
import java.lang.reflect.Method

object ItemStackDataBridge {
    private data class Codec(val encode: Method, val decode: Method)

    private val codec = runCatching {
        Codec(
            ItemStack::class.java.getMethod("serializeAsBytes"),
            ItemStack::class.java.getMethod("deserializeBytes", ByteArray::class.java)
        )
    }.getOrNull()

    val supportsRaw: Boolean
        get() = codec != null

    fun encode(item: ItemStack): ByteArray? {
        val active = codec ?: return null
        return active.encode.invoke(item) as? ByteArray
    }

    fun decode(bytes: ByteArray): ItemStack? {
        val active = codec ?: return null
        return active.decode.invoke(null, bytes) as? ItemStack
    }
}

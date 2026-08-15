package org.cyuCBMclean.cyuclear.cluster

import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.platform.ItemStackDataBridge
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

internal object ItemStackCodec {
    private const val MAX_SERIALIZED_BYTES = 1024 * 1024
    private const val MAX_BASE64_CHARS = ((MAX_SERIALIZED_BYTES + 2) / 3) * 4
    private const val NBT_PREFIX = "nbt2:"
    private const val BUKKIT_PREFIX = "bukkit1:"

    fun encode(source: ItemStack): String {
        val item = source.clone().apply { amount = 1 }
        val raw = ItemStackDataBridge.encode(item)
        if (raw == null && ItemStackDataBridge.supportsRaw) {
            error("服务端返回了无效的物品 NBT 数据")
        }
        val bytes = raw ?: encodeBukkit(item)
        require(bytes.size <= MAX_SERIALIZED_BYTES) { "物品序列化数据超过 1 MiB，已拒绝写入跨服垃圾桶" }
        val prefix = if (raw != null) NBT_PREFIX else BUKKIT_PREFIX
        return prefix + Base64.getEncoder().encodeToString(bytes)
    }

    fun decode(encoded: String): ItemStack {
        val rawNbt = encoded.startsWith(NBT_PREFIX)
        val payload = when {
            rawNbt -> encoded.substring(NBT_PREFIX.length)
            encoded.startsWith(BUKKIT_PREFIX) -> encoded.substring(BUKKIT_PREFIX.length)
            else -> encoded
        }
        require(payload.length <= MAX_BASE64_CHARS) { "跨服垃圾桶物品数据超过 1 MiB" }
        val bytes = Base64.getDecoder().decode(payload)
        require(bytes.size <= MAX_SERIALIZED_BYTES) { "跨服垃圾桶物品数据超过 1 MiB" }
        val item = if (rawNbt) {
            if (!ItemStackDataBridge.supportsRaw) {
                error("当前服务端不支持该跨服垃圾桶物品格式")
            }
            ItemStackDataBridge.decode(bytes)
                ?: error("服务端未能恢复物品 NBT 数据")
        } else {
            decodeBukkit(bytes)
        }
        return item.clone().apply { amount = 1 }
    }

    fun encodeVerified(source: ItemStack): String {
        val item = source.clone().apply { amount = 1 }
        val encoded = encode(item)
        val decoded = decode(encoded)
        require(item.isSimilar(decoded)) { "容器内容无法通过跨服完整性校验" }
        return encoded
    }

    private fun encodeBukkit(item: ItemStack): ByteArray = ByteArrayOutputStream().use { byteOutput ->
        BukkitObjectOutputStream(byteOutput).use { objectOutput ->
            objectOutput.writeObject(item)
        }
        byteOutput.toByteArray()
    }

    private fun decodeBukkit(bytes: ByteArray): ItemStack {
        return BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { input ->
            (input.readObject() as? ItemStack)
                ?: error("跨服垃圾桶数据不是 ItemStack")
        }
    }
}

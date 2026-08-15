package org.cyuCBMclean.cyuclear.menu

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.platform.MenuHeadTextureBridge
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object MenuHeadFactory {

    private enum class SourceType {
        TEXTURE,
        PLAYER
    }

    private val unresolvedToken = Regex("%[^%\\s]+%")
    private val textureUrl = Regex("\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val warnings = ConcurrentHashMap.newKeySet<String>()
    private val ownerCache = ConcurrentHashMap<String, OfflinePlayer>()

    fun reload() {
        warnings.clear()
        ownerCache.clear()
        MenuHeadTextureBridge.reload()
    }

    fun sourceOf(section: ConfigurationSection, material: String): String? {
        firstString(section, "head-base64", "head_base64", "basehead64")?.let {
            return if (isTextureSource(it)) it else "basehead-$it"
        }
        firstString(section, "head-player", "head_player", "head-owner", "head_owner", "skull-owner", "skullOwner")?.let { return it }
        firstString(section, "head")?.let { return it }
        return material.takeIf(::isTextureSource)
    }

    fun requiresHead(section: ConfigurationSection, material: String): Boolean {
        return sourceOf(section, material) != null
    }

    fun createHead(data: Int): ItemStack {
        val material = Material.matchMaterial("PLAYER_HEAD")
            ?: Material.matchMaterial("SKULL_ITEM")
            ?: Material.STONE
        return ItemStack(material, 1, data.coerceIn(0, Short.MAX_VALUE.toInt()).toShort()).apply {
            if (type.name.equals("SKULL_ITEM", ignoreCase = true)) durability = 3
        }
    }

    fun apply(item: ItemStack, source: String?, itemKey: String): ItemStack {
        val value = source?.trim()?.takeIf { it.isNotEmpty() } ?: return item
        if (unresolvedToken.containsMatchIn(value)) return item
        val meta = item.itemMeta as? SkullMeta ?: return item
        val type = sourceType(value)
        val applied = when (type) {
            SourceType.TEXTURE -> texturePayload(value)?.let { payload ->
                MenuHeadTextureBridge.apply(meta, payload, decodeTextureUrl(payload))
            } == true
            SourceType.PLAYER -> owner(value)?.let { MenuHeadOwnerBridge.apply(meta, it) } == true
        }
        if (applied) {
            item.itemMeta = meta
        } else if (type == SourceType.TEXTURE && warnings.add(itemKey)) {
            Cyuclear.instance.logger.warning("菜单 $itemKey 的头颅纹理无法应用，请检查 head 配置")
        }
        return item
    }

    fun isDynamic(source: String?): Boolean = source?.contains('%') == true

    private fun sourceType(value: String): SourceType {
        return if (isTextureSource(value)) SourceType.TEXTURE else SourceType.PLAYER
    }

    private fun isTextureSource(value: String): Boolean {
        val text = value.trim()
        val lower = text.lowercase(Locale.ROOT)
        return lower.startsWith("basehead-") ||
            lower.startsWith("base64-") ||
            lower.startsWith("basehead64-") ||
            lower.startsWith("basehead64:") ||
            lower.startsWith("url-") ||
            lower.startsWith("texture-") ||
            lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            isTextureHash(lower) ||
            (text.length >= 48 && decodeTextureUrl(text) != null)
    }

    private fun texturePayload(source: String): String? {
        val value = source.trim()
        stripPrefix(value, "basehead-", "base64-", "basehead64-", "basehead64:")?.let { return it }
        val url = when {
            value.startsWith("url-", ignoreCase = true) -> value.substring(4)
            value.startsWith("texture-", ignoreCase = true) -> value.substring(8)
            else -> value
        }
        val normalized = normalizeTextureUrl(url) ?: return null
        val json = "{\"textures\":{\"SKIN\":{\"url\":\"$normalized\"}}}"
        return Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun stripPrefix(value: String, vararg prefixes: String): String? {
        for (prefix in prefixes) {
            if (value.startsWith(prefix, ignoreCase = true)) {
                return value.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun normalizeTextureUrl(value: String): String? {
        val text = value.trim().takeIf { it.isNotEmpty() } ?: return null
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> text
            isTextureHash(lower) -> "https://textures.minecraft.net/texture/$text"
            else -> null
        }
    }

    private fun isTextureHash(value: String): Boolean {
        return value.length in 32..128 && value.all { it in 'a'..'f' || it in '0'..'9' }
    }

    private fun owner(value: String): OfflinePlayer? {
        val text = value.trim().takeIf { it.isNotEmpty() } ?: return null
        Bukkit.getPlayerExact(text)?.let { return it }
        val key = text.lowercase(Locale.ROOT)
        return runCatching {
            ownerCache.computeIfAbsent(key) {
                val uuid = runCatching { UUID.fromString(text) }.getOrNull()
                if (uuid != null) Bukkit.getOfflinePlayer(uuid) else Bukkit.getOfflinePlayer(text)
            }
        }.getOrNull()
    }

    private fun decodeTextureUrl(value: String): String? {
        val decoded = runCatching {
            String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val url = textureUrl.find(decoded)?.groupValues?.getOrNull(1) ?: return null
        return normalizeTextureUrl(url)
    }

    private fun firstString(section: ConfigurationSection, vararg keys: String): String? {
        for (key in keys) {
            if (section.contains(key)) {
                return section.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}

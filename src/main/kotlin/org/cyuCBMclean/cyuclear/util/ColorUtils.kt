package org.cyuCBMclean.cyuclear.util

import org.bukkit.ChatColor
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

object ColorUtils {

    private val hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})")
    private val gradientPattern = Pattern.compile(
        "<gradient:#([A-Fa-f0-9]{6}):#([A-Fa-f0-9]{6})>(.*?)</gradient>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )
    private val legacyPalette = listOf(
        0x000000 to ChatColor.BLACK,
        0x0000AA to ChatColor.DARK_BLUE,
        0x00AA00 to ChatColor.DARK_GREEN,
        0x00AAAA to ChatColor.DARK_AQUA,
        0xAA0000 to ChatColor.DARK_RED,
        0xAA00AA to ChatColor.DARK_PURPLE,
        0xFFAA00 to ChatColor.GOLD,
        0xAAAAAA to ChatColor.GRAY,
        0x555555 to ChatColor.DARK_GRAY,
        0x5555FF to ChatColor.BLUE,
        0x55FF55 to ChatColor.GREEN,
        0x55FFFF to ChatColor.AQUA,
        0xFF5555 to ChatColor.RED,
        0xFF55FF to ChatColor.LIGHT_PURPLE,
        0xFFFF55 to ChatColor.YELLOW,
        0xFFFFFF to ChatColor.WHITE
    )

    @JvmStatic
    fun color(text: String): String {
        var result = applyGradients(text)
        val matcher = hexPattern.matcher(result)
        val buffer = StringBuffer()

        while (matcher.find()) {
            val hex = matcher.group(1)
            val replacement = if (ServerEnv.supportsHexColors) {
                buildHexColor(hex)
            } else {
                nearestLegacyColor(hex)
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(buffer)

        return ChatColor.translateAlternateColorCodes('&', buffer.toString())
    }

    private fun applyGradients(text: String): String {
        val matcher = gradientPattern.matcher(text)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val inner = matcher.group(3)
            val bold = inner.contains("<bold>", ignoreCase = true) || inner.contains("&l") || inner.contains("§l")
            val plain = inner
                .replace(Regex("(?i)</?bold>"), "")
                .replace("&l", "")
                .replace("§l", "")
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(renderGradient(plain, matcher.group(1), matcher.group(2), bold)))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    private fun renderGradient(text: String, startHex: String, endHex: String, bold: Boolean): String {
        if (text.isEmpty()) return ""
        val start = startHex.toInt(16)
        val end = endHex.toInt(16)
        val sr = start shr 16 and 0xFF
        val sg = start shr 8 and 0xFF
        val sb = start and 0xFF
        val er = end shr 16 and 0xFF
        val eg = end shr 8 and 0xFF
        val eb = end and 0xFF
        val chars = text.toCharArray()
        return buildString {
            for (index in chars.indices) {
                val t = if (chars.size == 1) 0.0 else index.toDouble() / (chars.size - 1)
                val r = (sr + (er - sr) * t).toInt().coerceIn(0, 255)
                val g = (sg + (eg - sg) * t).toInt().coerceIn(0, 255)
                val b = (sb + (eb - sb) * t).toInt().coerceIn(0, 255)
                append("&#")
                append("%02X%02X%02X".format(r, g, b))
                if (bold) append("&l")
                append(chars[index])
            }
        }
    }

    private fun buildHexColor(hex: String): String {
        return buildString {
            append(ChatColor.COLOR_CHAR)
            append('x')
            for (char in hex) {
                append(ChatColor.COLOR_CHAR)
                append(char)
            }
        }
    }

    private fun nearestLegacyColor(hex: String): String {
        val rgb = hex.lowercase(Locale.ROOT).toIntOrNull(16) ?: return ChatColor.WHITE.toString()
        val red = rgb shr 16 and 0xFF
        val green = rgb shr 8 and 0xFF
        val blue = rgb and 0xFF

        val nearest = legacyPalette.minByOrNull { (color, _) ->
            val dr = red - (color shr 16 and 0xFF)
            val dg = green - (color shr 8 and 0xFF)
            val db = blue - (color and 0xFF)
            dr * dr + dg * dg + db * db
        }?.second ?: ChatColor.WHITE

        return nearest.toString()
    }
}

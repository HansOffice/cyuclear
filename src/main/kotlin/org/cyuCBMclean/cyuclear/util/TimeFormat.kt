package org.cyuCBMclean.cyuclear.util

import java.util.Locale

object TimeFormat {

    fun cleanupDuration(millis: Long): String {
        val isEn = org.cyuCBMclean.cyuclear.config.Language.isEnglish
        if (millis <= 0L || millis < 1000L) return if (isEn) "under 1s" else "低于 1 秒"

        val seconds = millis / 1000.0
        if (seconds < 10.0) {
            val formatted = formatOneDecimal(seconds)
            return if (isEn) "${formatted}s" else "$formatted 秒"
        }

        if (millis < 60_000L) {
            val sec = millis / 1000L
            return if (isEn) "${sec}s" else "$sec 秒"
        }

        val minutes = millis / 60_000L
        val remainSeconds = (millis % 60_000L) / 1000L
        if (remainSeconds == 0L) {
            return if (isEn) "${minutes}m" else "${minutes} 分钟"
        }
        return if (isEn) "${minutes}m ${remainSeconds}s" else "${minutes} 分 ${remainSeconds} 秒"
    }

    fun compactMillis(millis: Long): String {
        return "${cleanupDuration(millis)} (${millis.coerceAtLeast(0L)}ms)"
    }

    private fun formatOneDecimal(value: Double): String {
        val text = String.format(Locale.ROOT, "%.1f", value)
        return text.removeSuffix(".0")
    }
}

package org.cyuCBMclean.cyuclear.util

import java.util.Locale

object TimeFormat {

    fun cleanupDuration(millis: Long): String {
        if (millis <= 0L) return "低于 1 秒"
        if (millis < 1000L) return "低于 1 秒"

        val seconds = millis / 1000.0
        if (seconds < 10.0) {
            return "${formatOneDecimal(seconds)} 秒"
        }

        if (millis < 60_000L) {
            return "${millis / 1000L} 秒"
        }

        val minutes = millis / 60_000L
        val remainSeconds = (millis % 60_000L) / 1000L
        if (remainSeconds == 0L) {
            return "${minutes} 分钟"
        }
        return "${minutes} 分 ${remainSeconds} 秒"
    }

    fun compactMillis(millis: Long): String {
        return "${cleanupDuration(millis)} (${millis.coerceAtLeast(0L)}ms)"
    }

    private fun formatOneDecimal(value: Double): String {
        val text = String.format(Locale.ROOT, "%.1f", value)
        return text.removeSuffix(".0")
    }
}

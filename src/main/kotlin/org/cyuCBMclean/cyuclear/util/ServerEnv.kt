package org.cyuCBMclean.cyuclear.util

import org.cyuCBMclean.cyuclear.cluster.BuildInfo

object ServerEnv {

    val supportsHexColors: Boolean by lazy {
        val parts = BuildInfo.minecraftVersion
            .split('.')
            .mapNotNull { it.toIntOrNull() }

        val major = parts.getOrNull(0) ?: 1
        val minor = parts.getOrNull(1) ?: 0

        major > 1 || minor >= 16
    }
}

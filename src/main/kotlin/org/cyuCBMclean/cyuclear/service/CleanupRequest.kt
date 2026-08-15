package org.cyuCBMclean.cyuclear.service

import org.cyuCBMclean.cyuclear.config.Settings

enum class CleanupOrigin {
    SCHEDULED,
    MANUAL,
    PANIC
}

data class CleanupRequest(
    val cleanItems: Boolean,
    val cleanEntities: Boolean,
    val origin: CleanupOrigin,
    val recoveryEnabled: Boolean
)

object CleanupRequests {
    fun scheduled(cleanItems: Boolean, cleanEntities: Boolean): CleanupRequest =
        CleanupRequest(cleanItems, cleanEntities, CleanupOrigin.SCHEDULED, true)

    fun manual(cleanItems: Boolean, cleanEntities: Boolean): CleanupRequest =
        CleanupRequest(cleanItems, cleanEntities, CleanupOrigin.MANUAL, !Settings.clusterEnabled)

    fun panic(cleanItems: Boolean, cleanEntities: Boolean): CleanupRequest =
        CleanupRequest(cleanItems, cleanEntities, CleanupOrigin.PANIC, !Settings.clusterEnabled)
}

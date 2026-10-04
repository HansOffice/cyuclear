package org.cyuCBMclean.cyuclear.cluster

data class ClusterIdentity(
    val pluginVersion: String,
    val platform: String,
    val minecraftVersion: String,
    val compatibilityDomain: String,
    val protocolVersion: Int,
    val serializationVersion: Int
) {
    val clusterPlatform: String
        get() = if (compatibilityDomain == "modern" && (platform == "paper" || platform == "folia")) "paper/folia" else platform

    val clusterMinecraftVersion: String
        get() = normalizeMinecraftVersion(minecraftVersion)

    fun canonical(): String = listOf(
        "plugin=$pluginVersion",
        "platform=$clusterPlatform",
        "minecraft=$clusterMinecraftVersion",
        "domain=$compatibilityDomain",
        "protocol=$protocolVersion",
        "serialization=$serializationVersion"
    ).joinToString("\n")

    fun display(): String =
        "Cyuclear $pluginVersion / 平台 $platform / Minecraft $minecraftVersion / $compatibilityDomain / 协议 $protocolVersion / 序列化 $serializationVersion"

    companion object {
        fun normalizeMinecraftVersion(raw: String): String {
            val trimmed = raw.trim()
            val beforeBuild = trimmed.replace(Regex("[._-](build|git|dev|snapshot|alpha|beta|rc)[._-]?\\d*.*$", RegexOption.IGNORE_CASE), "")
            return beforeBuild.ifBlank { trimmed }
        }

        fun describeCanonical(value: String): String {
            val fields = value.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
            return "Cyuclear ${fields["plugin"] ?: "?"} / 平台 ${fields["platform"] ?: "?"} / Minecraft ${fields["minecraft"] ?: "?"} / " +
                "${fields["domain"] ?: "?"} / 协议 ${fields["protocol"] ?: "?"} / 序列化 ${fields["serialization"] ?: "?"}"
        }
    }
}

data class ClusterCleanupCycle(
    val runId: String,
    val executeAtMillis: Long,
    val storageTimeOffsetMillis: Long,
    val leaderServerId: String
)
enum class ClusterConnectionState {
    DISABLED,
    CONNECTING,
    ACTIVE,
    REJECTED,
    DISCONNECTED,
    STOPPED
}

data class ClusterStatusSnapshot(
    val state: ClusterConnectionState,
    val message: String,
    val identity: ClusterIdentity?,
    val memberCount: Int,
    val storageLatencyMillis: Long,
    val updatedAtMillis: Long
)

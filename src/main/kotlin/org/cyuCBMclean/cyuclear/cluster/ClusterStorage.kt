package org.cyuCBMclean.cyuclear.cluster

internal interface ClusterStorage : AutoCloseable {
    fun pulse(request: ClusterPulseRequest): ClusterPulseResult
    fun claimCleanup(request: ClusterClaimRequest): Boolean
    fun releaseCleanupClaim(request: ClusterClaimRequest)
    fun saveCleanupResult(clusterId: String, runId: String, serverId: String, result: String, ttlMillis: Long)
    fun readCleanupResults(clusterId: String, runId: String): Map<String, String>

    fun beginBinCycle(clusterId: String, cycleId: String, ttlMillis: Long)
    fun addBinItems(clusterId: String, cycleId: String, items: Map<String, Int>, ttlMillis: Long): Long?
    fun openBinWindow(clusterId: String, cycleId: String, durationMillis: Long, ttlMillis: Long): Long
    fun readBinState(clusterId: String, withItems: Boolean): ClusterBinState
    fun reserveBinItem(
        clusterId: String,
        cycleId: String,
        encodedItem: String,
        amount: Int,
        claimId: String,
        playerId: String
    ): ClusterBinReservation
    fun completeBinReservation(clusterId: String, cycleId: String, claimId: String, playerId: String): Boolean
    fun releaseBinReservation(clusterId: String, cycleId: String, claimId: String, playerId: String): Long?
    fun findBinReservations(clusterId: String, playerId: String): List<ClusterBinReservation>
    fun invalidate()
}

internal data class ClusterPulseRequest(
    val clusterId: String,
    val serverId: String,
    val instanceId: String,
    val identity: String,
    val identityTimeoutMillis: Long,
    val memberTimeoutMillis: Long,
    val cleanupIntervalMillis: Long,
    val executionGraceMillis: Long
)

internal sealed class ClusterPulseResult {
    data class Accepted(
        val memberCount: Int,
        val storageNowMillis: Long,
        val leaderServerId: String,
        val runId: String,
        val executeAtMillis: Long
    ) : ClusterPulseResult()

    data class IdentityMismatch(val remoteIdentity: String) : ClusterPulseResult()
    data class ServerIdOccupied(val ownerInstanceId: String) : ClusterPulseResult()
}

internal data class ClusterClaimRequest(
    val clusterId: String,
    val runId: String,
    val serverId: String,
    val instanceId: String,
    val ttlMillis: Long
)

internal data class ClusterBinState(
    val cycleId: String,
    val expireAtMillis: Long,
    val revision: Long,
    val storageNowMillis: Long,
    val items: Map<String, Int>
)

internal data class ClusterBinReservation(
    val claimId: String,
    val cycleId: String,
    val playerId: String,
    val encodedItem: String,
    val amount: Int,
    val revision: Long
)

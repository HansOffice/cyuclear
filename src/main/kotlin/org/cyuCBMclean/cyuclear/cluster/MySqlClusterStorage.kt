package org.cyuCBMclean.cyuclear.cluster

import com.mysql.cj.jdbc.Driver
import org.cyuCBMclean.cyuclear.config.Settings
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

internal class MySqlClusterStorage : ClusterStorage {

    private companion object {
        const val BIN_CLAIM_TTL_MILLIS = 86400000L
    }

    @Volatile
    private var driverLoaded = false
    @Volatile
    private var activeConnection: Connection? = null

    override fun pulse(request: ClusterPulseRequest): ClusterPulseResult = transaction { connection ->
        ensureStateRow(connection, request.clusterId)
        val now = storageNow(connection)
        val state = readStateForUpdate(connection, request.clusterId)

        if (state.identityExpiresAtMillis > now &&
            state.identity.isNotBlank() &&
            state.identity != request.identity
        ) {
            return@transaction ClusterPulseResult.IdentityMismatch(state.identity)
        }

        val member = readMemberForUpdate(connection, request.clusterId, request.serverId)
        if (member != null &&
            member.ownerInstanceId != request.instanceId &&
            member.lastSeenMillis > now - request.memberTimeoutMillis
        ) {
            return@transaction ClusterPulseResult.ServerIdOccupied(member.ownerInstanceId)
        }

        val identityExpiresAt = now + request.identityTimeoutMillis
        upsertMember(connection, request.clusterId, request.serverId, request.instanceId, now)
        deleteExpiredMembers(connection, request.clusterId, now - request.memberTimeoutMillis)

        var leaderServerId = state.leaderServerId
        var leaderExpiresAt = state.leaderExpiresAtMillis
        if (leaderServerId.isBlank() || leaderExpiresAt <= now || leaderServerId == request.serverId) {
            leaderServerId = request.serverId
            leaderExpiresAt = now + request.memberTimeoutMillis
        }

        var runId = state.cleanupRunId
        var executeAt = state.cleanupExecuteAtMillis
        if (leaderServerId == request.serverId &&
            (runId.isBlank() || now >= executeAt + request.executionGraceMillis)
        ) {
            executeAt = nextExecutionAt(now, request.cleanupIntervalMillis)
            runId = "${request.identity}:${executeAt}"
        }

        updateClusterState(
            connection = connection,
            clusterId = request.clusterId,
            identity = request.identity,
            identityExpiresAt = identityExpiresAt,
            leaderServerId = leaderServerId,
            leaderExpiresAt = leaderExpiresAt,
            cleanupRunId = runId,
            cleanupExecuteAt = executeAt,
            now = now
        )

        ClusterPulseResult.Accepted(
            memberCount = countMembers(connection, request.clusterId),
            storageNowMillis = now,
            leaderServerId = leaderServerId,
            runId = runId,
            executeAtMillis = executeAt
        )
    }

    override fun claimCleanup(request: ClusterClaimRequest): Boolean = transaction { connection ->
        val now = storageNow(connection)
        connection.prepareStatement(
            "DELETE FROM ${table("cleanup_claims")} WHERE expires_at_millis <= ?"
        ).use { statement ->
            statement.setLong(1, now)
            statement.executeUpdate()
        }

        try {
            connection.prepareStatement(
                "INSERT INTO ${table("cleanup_claims")} " +
                    "(cluster_id, run_id, server_id, owner_instance_id, expires_at_millis) VALUES (?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, request.clusterId)
                statement.setString(2, request.runId)
                statement.setString(3, request.serverId)
                statement.setString(4, request.instanceId)
                statement.setLong(5, now + request.ttlMillis)
                statement.executeUpdate()
            }
            true
        } catch (exception: SQLException) {
            if (exception.errorCode == 1062) false else throw exception
        }
    }

    override fun releaseCleanupClaim(request: ClusterClaimRequest) {
        transaction { connection ->
            connection.prepareStatement(
                "DELETE FROM ${table("cleanup_claims")} " +
                    "WHERE cluster_id = ? AND run_id = ? AND server_id = ? AND owner_instance_id = ?"
            ).use { statement ->
                statement.setString(1, request.clusterId)
                statement.setString(2, request.runId)
                statement.setString(3, request.serverId)
                statement.setString(4, request.instanceId)
                statement.executeUpdate()
            }
        }
    }

    override fun saveCleanupResult(
        clusterId: String,
        runId: String,
        serverId: String,
        result: String,
        ttlMillis: Long
    ) {
        transaction { connection ->
            val now = storageNow(connection)
            connection.prepareStatement(
                "DELETE FROM ${table("cleanup_results")} WHERE expires_at_millis <= ?"
            ).use { statement ->
                statement.setLong(1, now)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO ${table("cleanup_results")} " +
                    "(cluster_id, run_id, server_id, result_text, expires_at_millis) VALUES (?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE result_text = VALUES(result_text), expires_at_millis = VALUES(expires_at_millis)"
            ).use { statement ->
                statement.setString(1, clusterId)
                statement.setString(2, runId)
                statement.setString(3, serverId)
                statement.setString(4, result)
                statement.setLong(5, now + ttlMillis)
                statement.executeUpdate()
            }
        }
    }

    override fun readCleanupResults(clusterId: String, runId: String): Map<String, String> = transaction { connection ->
        val results = LinkedHashMap<String, String>()
        connection.prepareStatement(
            "SELECT server_id, result_text FROM ${table("cleanup_results")} WHERE cluster_id = ? AND run_id = ? AND expires_at_millis > ?"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, runId)
            statement.setLong(3, storageNow(connection))
            statement.executeQuery().use { rows ->
                while (rows.next()) results[rows.getString(1)] = rows.getString(2)
            }
        }
        results
    }

    override fun beginBinCycle(clusterId: String, cycleId: String, ttlMillis: Long) {
        transaction { connection ->
            ensureStateRow(connection, clusterId)
            val state = readStateForUpdate(connection, clusterId)
            if (state.binCycleId != cycleId) {
                connection.prepareStatement(
                    "DELETE FROM ${table("bin_items")} WHERE cluster_id = ?"
                ).use { statement ->
                    statement.setString(1, clusterId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "DELETE FROM ${table("bin_claims")} WHERE cluster_id = ? AND created_at_millis <= ?"
                ).use { statement ->
                    statement.setString(1, clusterId)
                    statement.setLong(2, storageNow(connection) - BIN_CLAIM_TTL_MILLIS)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE ${table("cluster_state")} " +
                        "SET bin_cycle_id = ?, bin_expire_at_millis = 0, bin_revision = 0, updated_at_millis = ? " +
                        "WHERE cluster_id = ?"
                ).use { statement ->
                    statement.setString(1, cycleId)
                    statement.setLong(2, storageNow(connection))
                    statement.setString(3, clusterId)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun addBinItems(
        clusterId: String,
        cycleId: String,
        items: Map<String, Int>,
        ttlMillis: Long
    ): Long? = transaction { connection ->
        val state = readStateForUpdate(connection, clusterId)
        if (state.binCycleId != cycleId) {
            return@transaction null
        }
        if (items.isEmpty()) {
            return@transaction state.binRevision
        }

        val now = storageNow(connection)
        connection.prepareStatement(
            "INSERT INTO ${table("bin_items")} " +
                "(cluster_id, cycle_id, item_hash, encoded_item, amount, updated_at_millis) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE encoded_item = VALUES(encoded_item), " +
                "amount = amount + VALUES(amount), updated_at_millis = VALUES(updated_at_millis)"
        ).use { statement ->
            items.forEach { (encodedItem, amount) ->
                statement.setString(1, clusterId)
                statement.setString(2, cycleId)
                statement.setString(3, itemHash(encodedItem))
                statement.setString(4, encodedItem)
                statement.setInt(5, amount)
                statement.setLong(6, now)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        val revision = state.binRevision + 1
        updateBinRevision(connection, clusterId, revision, now)
        revision
    }

    override fun openBinWindow(
        clusterId: String,
        cycleId: String,
        durationMillis: Long,
        ttlMillis: Long
    ): Long = transaction { connection ->
        val now = storageNow(connection)
        val state = readStateForUpdate(connection, clusterId)
        if (state.binCycleId != cycleId) {
            return@transaction 0L
        }
        val expireAt = maxOf(state.binExpireAtMillis, now + durationMillis)
        connection.prepareStatement(
            "UPDATE ${table("cluster_state")} " +
                "SET bin_expire_at_millis = ?, updated_at_millis = ? WHERE cluster_id = ?"
        ).use { statement ->
            statement.setLong(1, expireAt)
            statement.setLong(2, now)
            statement.setString(3, clusterId)
            statement.executeUpdate()
        }
        expireAt
    }

    override fun readBinState(clusterId: String, withItems: Boolean): ClusterBinState = transaction { connection ->
        ensureStateRow(connection, clusterId)
        val state = readState(connection, clusterId)
        val items = if (withItems && state.binCycleId.isNotBlank()) {
            loadBinItems(connection, clusterId, state.binCycleId)
        } else {
            emptyMap()
        }
        ClusterBinState(
            cycleId = state.binCycleId,
            expireAtMillis = state.binExpireAtMillis,
            revision = state.binRevision,
            storageNowMillis = storageNow(connection),
            items = items
        )
    }

    override fun reserveBinItem(
        clusterId: String,
        cycleId: String,
        encodedItem: String,
        amount: Int,
        claimId: String,
        playerId: String
    ): ClusterBinReservation = transaction { connection ->
        val now = storageNow(connection)
        val state = readStateForUpdate(connection, clusterId)
        if (state.binCycleId != cycleId || state.binExpireAtMillis <= now) {
            return@transaction ClusterBinReservation("", cycleId, playerId, encodedItem, -1, state.binRevision)
        }
        val key = itemHash(encodedItem)
        val available = connection.prepareStatement(
            "SELECT amount FROM ${table("bin_items")} " +
                "WHERE cluster_id = ? AND cycle_id = ? AND item_hash = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, cycleId)
            statement.setString(3, key)
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 }
        }
        val taken = minOf(available, amount)
        if (taken <= 0) {
            return@transaction ClusterBinReservation("", cycleId, playerId, encodedItem, 0, state.binRevision)
        }
        if (available == taken) {
            connection.prepareStatement(
                "DELETE FROM ${table("bin_items")} WHERE cluster_id = ? AND cycle_id = ? AND item_hash = ?"
            ).use { statement ->
                statement.setString(1, clusterId)
                statement.setString(2, cycleId)
                statement.setString(3, key)
                statement.executeUpdate()
            }
        } else {
            connection.prepareStatement(
                "UPDATE ${table("bin_items")} SET amount = amount - ?, updated_at_millis = ? " +
                    "WHERE cluster_id = ? AND cycle_id = ? AND item_hash = ?"
            ).use { statement ->
                statement.setInt(1, taken)
                statement.setLong(2, now)
                statement.setString(3, clusterId)
                statement.setString(4, cycleId)
                statement.setString(5, key)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            "INSERT INTO ${table("bin_claims")} " +
                "(cluster_id, cycle_id, claim_id, player_id, item_hash, encoded_item, amount, created_at_millis) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, cycleId)
            statement.setString(3, claimId)
            statement.setString(4, playerId)
            statement.setString(5, key)
            statement.setString(6, encodedItem)
            statement.setInt(7, taken)
            statement.setLong(8, now)
            statement.executeUpdate()
        }
        val revision = state.binRevision + 1
        updateBinRevision(connection, clusterId, revision, now)
        ClusterBinReservation(claimId, cycleId, playerId, encodedItem, taken, revision)
    }

    override fun completeBinReservation(
        clusterId: String,
        cycleId: String,
        claimId: String,
        playerId: String
    ): Boolean = transaction { connection ->
        connection.prepareStatement(
            "DELETE FROM ${table("bin_claims")} WHERE cluster_id = ? AND cycle_id = ? AND claim_id = ? AND player_id = ?"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, cycleId)
            statement.setString(3, claimId)
            statement.setString(4, playerId)
            statement.executeUpdate() > 0
        }
    }

    override fun releaseBinReservation(
        clusterId: String,
        cycleId: String,
        claimId: String,
        playerId: String
    ): Long? = transaction { connection ->
        val now = storageNow(connection)
        val state = readStateForUpdate(connection, clusterId)
        if (state.binCycleId != cycleId || state.binExpireAtMillis <= now) {
            return@transaction null
        }
        val reservation = readBinReservationForUpdate(connection, clusterId, cycleId, claimId) ?: return@transaction null
        if (reservation.playerId != playerId) return@transaction null
        connection.prepareStatement(
            "DELETE FROM ${table("bin_claims")} WHERE cluster_id = ? AND cycle_id = ? AND claim_id = ?"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, cycleId)
            statement.setString(3, claimId)
            statement.executeUpdate()
        }

        connection.prepareStatement(
            "INSERT INTO ${table("bin_items")} " +
                "(cluster_id, cycle_id, item_hash, encoded_item, amount, updated_at_millis) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount), updated_at_millis = VALUES(updated_at_millis)"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, cycleId)
            statement.setString(3, reservation.itemHash)
            statement.setString(4, reservation.encodedItem)
            statement.setInt(5, reservation.amount)
            statement.setLong(6, now)
            statement.executeUpdate()
        }
        val revision = state.binRevision + 1
        updateBinRevision(connection, clusterId, revision, now)
        revision
    }

    override fun findBinReservations(clusterId: String, playerId: String): List<ClusterBinReservation> = transaction { connection ->
        ensureStateRow(connection, clusterId)
        val now = storageNow(connection)
        connection.prepareStatement(
            "SELECT cycle_id, claim_id, encoded_item, amount FROM ${table("bin_claims")} " +
                "WHERE cluster_id = ? AND player_id = ? AND created_at_millis > ? ORDER BY created_at_millis"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, playerId)
            statement.setLong(3, now - BIN_CLAIM_TTL_MILLIS)
            statement.executeQuery().use { result ->
                val reservations = ArrayList<ClusterBinReservation>()
                while (result.next()) {
                    reservations += ClusterBinReservation(
                        claimId = result.getString(2),
                        cycleId = result.getString(1),
                        playerId = playerId,
                        encodedItem = result.getString(3),
                        amount = result.getInt(4),
                        revision = -1L
                    )
                }
                reservations
            }
        }
    }

    override fun invalidate() {
        synchronized(this) {
            closeConnection()
        }
    }

    override fun close() {
        invalidate()
    }

    @Synchronized
    private fun <T> transaction(block: (Connection) -> T): T {
        val connection = connection()
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        return try {
            val result = block(connection)
            connection.commit()
            result
        } catch (exception: Throwable) {
            runCatching { connection.rollback() }
            if (exception is SQLException) {
                closeConnection()
            }
            throw exception
        } finally {
            if (!connection.isClosed) {
                connection.autoCommit = autoCommit
            }
        }
    }

    private fun connection(): Connection {
        val current = activeConnection
        if (current != null && runCatching { current.isValid(2) }.getOrDefault(false)) {
            return current
        }
        closeConnection()
        if (!driverLoaded) {
            Driver()
            driverLoaded = true
        }
        val url = "jdbc:mysql://${Settings.clusterMysqlHost}:${Settings.clusterMysqlPort}/" +
            "${Settings.clusterMysqlDatabase}?useUnicode=true&characterEncoding=utf8" +
            "&useSSL=${Settings.clusterMysqlSsl}&connectTimeout=${Settings.clusterMysqlConnectTimeoutMillis}" +
            "&socketTimeout=${Settings.clusterMysqlSocketTimeoutMillis}&tcpKeepAlive=true"
        return DriverManager.getConnection(url, Settings.clusterMysqlUsername, Settings.clusterMysqlPassword).also { connection ->
            activeConnection = connection
            ensureSchema(connection)
        }
    }

    private fun closeConnection() {
        runCatching { activeConnection?.close() }
        activeConnection = null
    }

    private fun ensureSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ${table("cluster_state")} (" +
                    "cluster_id VARCHAR(64) NOT NULL, identity_text TEXT NOT NULL, identity_expires_at_millis BIGINT NOT NULL, " +
                    "leader_server_id VARCHAR(64) NOT NULL, leader_expires_at_millis BIGINT NOT NULL, " +
                    "cleanup_run_id VARCHAR(255) NOT NULL, cleanup_execute_at_millis BIGINT NOT NULL, " +
                    "bin_cycle_id VARCHAR(255) NOT NULL, bin_expire_at_millis BIGINT NOT NULL, bin_revision BIGINT NOT NULL, " +
                    "updated_at_millis BIGINT NOT NULL, PRIMARY KEY (cluster_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            )
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ${table("members")} (" +
                    "cluster_id VARCHAR(64) NOT NULL, server_id VARCHAR(64) NOT NULL, owner_instance_id VARCHAR(128) NOT NULL, " +
                    "last_seen_millis BIGINT NOT NULL, PRIMARY KEY (cluster_id, server_id), KEY cluster_seen (cluster_id, last_seen_millis)) " +
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            )
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ${table("cleanup_claims")} (" +
                    "cluster_id VARCHAR(64) NOT NULL, run_id VARCHAR(255) NOT NULL, server_id VARCHAR(64) NOT NULL, " +
                    "owner_instance_id VARCHAR(128) NOT NULL, expires_at_millis BIGINT NOT NULL, " +
                    "PRIMARY KEY (cluster_id, run_id, server_id), KEY expires_at (expires_at_millis)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            )
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ${table("cleanup_results")} (" +
                    "cluster_id VARCHAR(64) NOT NULL, run_id VARCHAR(255) NOT NULL, server_id VARCHAR(64) NOT NULL, " +
                    "result_text TEXT NOT NULL, expires_at_millis BIGINT NOT NULL, " +
                    "PRIMARY KEY (cluster_id, run_id, server_id), KEY expires_at (expires_at_millis)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            )
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ${table("bin_items")} (" +
                    "cluster_id VARCHAR(64) NOT NULL, cycle_id VARCHAR(255) NOT NULL, item_hash CHAR(64) NOT NULL, " +
                    "encoded_item LONGTEXT NOT NULL, amount INT NOT NULL, updated_at_millis BIGINT NOT NULL, " +
                    "PRIMARY KEY (cluster_id, cycle_id, item_hash)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            )
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ${table("bin_claims")} (" +
                    "cluster_id VARCHAR(64) NOT NULL, cycle_id VARCHAR(255) NOT NULL, claim_id VARCHAR(64) NOT NULL, " +
                    "player_id CHAR(36) NOT NULL, item_hash CHAR(64) NOT NULL, encoded_item LONGTEXT NOT NULL, " +
                "amount INT NOT NULL, created_at_millis BIGINT NOT NULL, " +
                    "PRIMARY KEY (cluster_id, cycle_id, claim_id), KEY player_claims (cluster_id, cycle_id, player_id), " +
                    "KEY player_pending (cluster_id, player_id, created_at_millis)) " +
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            )
        }
        ensureIndex(
            connection,
            table("bin_claims"),
            "player_pending",
            "ALTER TABLE ${table("bin_claims")} ADD INDEX player_pending (cluster_id, player_id, created_at_millis)"
        )
    }

    private fun ensureIndex(connection: Connection, tableName: String, indexName: String, statementSql: String) {
        val exists = connection.metaData.getIndexInfo(connection.catalog, null, tableName, false, false).use { indexes ->
            generateSequence { if (indexes.next()) indexes else null }
                .any { indexName.equals(it.getString("INDEX_NAME"), ignoreCase = true) }
        }
        if (!exists) {
            connection.createStatement().use { it.executeUpdate(statementSql) }
        }
    }

    private fun ensureStateRow(connection: Connection, clusterId: String) {
        connection.prepareStatement(
            "INSERT INTO ${table("cluster_state")} (cluster_id, identity_text, identity_expires_at_millis, " +
                "leader_server_id, leader_expires_at_millis, cleanup_run_id, cleanup_execute_at_millis, " +
                "bin_cycle_id, bin_expire_at_millis, bin_revision, updated_at_millis) " +
                "VALUES (?, '', 0, '', 0, '', 0, '', 0, 0, 0) ON DUPLICATE KEY UPDATE cluster_id = VALUES(cluster_id)"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.executeUpdate()
        }
    }

    private fun readStateForUpdate(connection: Connection, clusterId: String): StateRow =
        readState(connection, clusterId, " FOR UPDATE")

    private fun readState(connection: Connection, clusterId: String, suffix: String = ""): StateRow {
        connection.prepareStatement(
            "SELECT identity_text, identity_expires_at_millis, leader_server_id, leader_expires_at_millis, " +
                "cleanup_run_id, cleanup_execute_at_millis, bin_cycle_id, bin_expire_at_millis, bin_revision " +
                "FROM ${table("cluster_state")} WHERE cluster_id = ?$suffix"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.executeQuery().use { result ->
                check(result.next()) { "CyuClear MySQL 集群状态行不存在" }
                return StateRow(
                    identity = result.getString(1),
                    identityExpiresAtMillis = result.getLong(2),
                    leaderServerId = result.getString(3),
                    leaderExpiresAtMillis = result.getLong(4),
                    cleanupRunId = result.getString(5),
                    cleanupExecuteAtMillis = result.getLong(6),
                    binCycleId = result.getString(7),
                    binExpireAtMillis = result.getLong(8),
                    binRevision = result.getLong(9)
                )
            }
        }
    }

    private fun readMemberForUpdate(connection: Connection, clusterId: String, serverId: String): MemberRow? {
        connection.prepareStatement(
            "SELECT owner_instance_id, last_seen_millis FROM ${table("members")} " +
                "WHERE cluster_id = ? AND server_id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, serverId)
            statement.executeQuery().use { result ->
                return if (result.next()) MemberRow(result.getString(1), result.getLong(2)) else null
            }
        }
    }

    private fun upsertMember(
        connection: Connection,
        clusterId: String,
        serverId: String,
        instanceId: String,
        now: Long
    ) {
        connection.prepareStatement(
            "INSERT INTO ${table("members")} (cluster_id, server_id, owner_instance_id, last_seen_millis) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE owner_instance_id = VALUES(owner_instance_id), last_seen_millis = VALUES(last_seen_millis)"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, serverId)
            statement.setString(3, instanceId)
            statement.setLong(4, now)
            statement.executeUpdate()
        }
    }

    private fun deleteExpiredMembers(connection: Connection, clusterId: String, cutoff: Long) {
        connection.prepareStatement(
            "DELETE FROM ${table("members")} WHERE cluster_id = ? AND last_seen_millis < ?"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setLong(2, cutoff)
            statement.executeUpdate()
        }
    }

    private fun countMembers(connection: Connection, clusterId: String): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM ${table("members")} WHERE cluster_id = ?").use { statement ->
            statement.setString(1, clusterId)
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }

    private fun updateClusterState(
        connection: Connection,
        clusterId: String,
        identity: String,
        identityExpiresAt: Long,
        leaderServerId: String,
        leaderExpiresAt: Long,
        cleanupRunId: String,
        cleanupExecuteAt: Long,
        now: Long
    ) {
        connection.prepareStatement(
            "UPDATE ${table("cluster_state")} SET identity_text = ?, identity_expires_at_millis = ?, " +
                "leader_server_id = ?, leader_expires_at_millis = ?, cleanup_run_id = ?, cleanup_execute_at_millis = ?, " +
                "updated_at_millis = ? WHERE cluster_id = ?"
        ).use { statement ->
            statement.setString(1, identity)
            statement.setLong(2, identityExpiresAt)
            statement.setString(3, leaderServerId)
            statement.setLong(4, leaderExpiresAt)
            statement.setString(5, cleanupRunId)
            statement.setLong(6, cleanupExecuteAt)
            statement.setLong(7, now)
            statement.setString(8, clusterId)
            statement.executeUpdate()
        }
    }

    private fun updateBinRevision(connection: Connection, clusterId: String, revision: Long, now: Long) {
        connection.prepareStatement(
            "UPDATE ${table("cluster_state")} SET bin_revision = ?, updated_at_millis = ? WHERE cluster_id = ?"
        ).use { statement ->
            statement.setLong(1, revision)
            statement.setLong(2, now)
            statement.setString(3, clusterId)
            statement.executeUpdate()
        }
    }

    private fun loadBinItems(connection: Connection, clusterId: String, cycleId: String): Map<String, Int> {
        connection.prepareStatement(
            "SELECT encoded_item, amount FROM ${table("bin_items")} WHERE cluster_id = ? AND cycle_id = ?"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, cycleId)
            statement.executeQuery().use { result ->
                val items = LinkedHashMap<String, Int>()
                while (result.next()) {
                    items[result.getString(1)] = result.getInt(2)
                }
                return items
            }
        }
    }

    private fun readBinReservationForUpdate(
        connection: Connection,
        clusterId: String,
        cycleId: String,
        claimId: String
    ): ReservationRow? {
        connection.prepareStatement(
            "SELECT player_id, item_hash, encoded_item, amount FROM ${table("bin_claims")} " +
                "WHERE cluster_id = ? AND cycle_id = ? AND claim_id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, clusterId)
            statement.setString(2, cycleId)
            statement.setString(3, claimId)
            statement.executeQuery().use { result ->
                return if (result.next()) {
                    ReservationRow(result.getString(1), result.getString(2), result.getString(3), result.getInt(4))
                } else {
                    null
                }
            }
        }
    }

    private fun storageNow(connection: Connection): Long =
        connection.prepareStatement("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)").use { statement ->
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }

    private fun nextExecutionAt(now: Long, intervalMillis: Long): Long =
        ((now / intervalMillis) + 1) * intervalMillis

    private fun itemHash(encodedItem: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encodedItem.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun table(suffix: String): String = Settings.clusterMysqlTablePrefix + suffix

    private data class StateRow(
        val identity: String,
        val identityExpiresAtMillis: Long,
        val leaderServerId: String,
        val leaderExpiresAtMillis: Long,
        val cleanupRunId: String,
        val cleanupExecuteAtMillis: Long,
        val binCycleId: String,
        val binExpireAtMillis: Long,
        val binRevision: Long
    )

    private data class MemberRow(val ownerInstanceId: String, val lastSeenMillis: Long)

    private data class ReservationRow(
        val playerId: String,
        val itemHash: String,
        val encodedItem: String,
        val amount: Int
    )
}

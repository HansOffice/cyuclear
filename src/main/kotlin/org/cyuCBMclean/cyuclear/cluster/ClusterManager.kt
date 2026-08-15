package org.cyuCBMclean.cyuclear.cluster

import org.bukkit.Bukkit
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.config.Settings.ClusterStorageType
import org.cyuCBMclean.cyuclear.service.CleanupNoticeManager
import org.cyuCBMclean.cyuclear.service.CleanupRequests
import org.cyuCBMclean.cyuclear.service.VoidBinManager
import org.cyuCBMclean.cyuclear.service.WindowScanner
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ClusterManager {
    const val PROTOCOL_VERSION = 3
    const val SERIALIZATION_VERSION = 2

    private const val ID_PATTERN = "^[A-Za-z0-9_.-]{1,64}$"
    private const val TABLE_PREFIX_PATTERN = "^[A-Za-z0-9_]{1,32}$"
    private const val EXECUTION_GRACE_MILLIS = 10000L
    private const val CLAIM_TTL_MILLIS = 300000L

    private val instanceId = UUID.randomUUID().toString()
    private val status = AtomicReference(
        ClusterStatusSnapshot(ClusterConnectionState.STOPPED, "尚未启动", null, 0, -1L, 0L)
    )
    private val cleanupCycle = AtomicReference<ClusterCleanupCycle?>(null)
    private val claimInFlight = AtomicBoolean(false)
    private val lastClaimedRunId = AtomicReference("")
    private val activeScanRunId = AtomicReference<String?>(null)
    private val cleanupResults = AtomicReference<Map<String, String>>(emptyMap())

    @Volatile
    private var executor: ScheduledExecutorService? = null
    @Volatile
    private var storage: ClusterStorage? = null
    @Volatile
    private var lastIdentityWarning: String = ""
    @Volatile
    private var storageScope: String? = null

    fun snapshot(): ClusterStatusSnapshot = status.get()

    fun isEnabled(): Boolean = Settings.clusterEnabled

    fun isActive(): Boolean = snapshot().state == ClusterConnectionState.ACTIVE

    fun cleanupRemainingSeconds(nowMillis: Long = System.currentTimeMillis()): Int? {
        if (!isActive()) return null
        val cycle = cleanupCycle.get() ?: return null
        val remainingMillis = cycle.executeAtMillis - (nowMillis + cycle.storageTimeOffsetMillis)
        if (remainingMillis <= 0L) return 0
        return ((remainingMillis + 999L) / 1000L).toInt()
    }

    fun tryStartDueCleanup() {
        if (!isActive() || WindowScanner.isRunning) return
        val cycle = cleanupCycle.get() ?: return
        val storageNow = System.currentTimeMillis() + cycle.storageTimeOffsetMillis
        if (storageNow < cycle.executeAtMillis || storageNow > cycle.executeAtMillis + EXECUTION_GRACE_MILLIS) return
        if (lastClaimedRunId.get() == cycle.runId || !claimInFlight.compareAndSet(false, true)) return

        val worker = executor
        val activeStorage = storage
        if (worker == null || activeStorage == null) {
            claimInFlight.set(false)
            return
        }
        worker.execute {
            try {
                val claimed = activeStorage.claimCleanup(
                    ClusterClaimRequest(
                        clusterId = Settings.clusterId,
                        runId = cycle.runId,
                        serverId = Settings.clusterServerId,
                        instanceId = instanceId,
                        ttlMillis = CLAIM_TTL_MILLIS
                    )
                )
                if (!claimed) {
                    lastClaimedRunId.set(cycle.runId)
                    return@execute
                }

                lastClaimedRunId.set(cycle.runId)
                CyuScheduler.runTask(Cyuclear.instance, Runnable {
                    activeScanRunId.set(cycle.runId)
                    if (!isActive() || cleanupCycle.get()?.runId != cycle.runId) {
                        releaseClaim(cycle)
                        return@Runnable
                    }
                    val started = WindowScanner.startScan(
                        CleanupRequests.scheduled(
                            cleanItems = Settings.itemModuleEnabled,
                            cleanEntities = Settings.entityModuleEnabled
                        )
                    )
                    if (!started) {
                        activeScanRunId.compareAndSet(cycle.runId, null)
                        releaseClaim(cycle)
                    }
                })
            } catch (error: Throwable) {
                activeStorage.invalidate()
                lastClaimedRunId.compareAndSet(cycle.runId, "")
                handleIoFailure(error)
            } finally {
                claimInFlight.set(false)
            }
        }
    }

    fun currentSynchronizedScanRunId(): String? = activeScanRunId.get()

    fun createManualBinCycleId(): String = "manual-${Settings.clusterServerId}-${System.currentTimeMillis()}"

    fun currentSharedBinCycleId(): String = ClusterBinCoordinator.currentCycleId()

    fun beginSharedBinCycle(cycleId: String) {
        if (isActive()) ClusterBinCoordinator.beginCycle(cycleId)
    }

    fun queueSharedBinItem(
        cycleId: String,
        item: org.bukkit.inventory.ItemStack,
        amount: Int = item.amount,
        encodedItem: String? = null
    ) {
        ClusterBinCoordinator.queueAdd(cycleId, item, amount, encodedItem)
    }

    fun openSharedBin(cycleId: String, durationSeconds: Int) {
        ClusterBinCoordinator.openWindow(cycleId, durationSeconds)
    }

    internal fun reserveSharedBinItem(
        playerId: UUID,
        item: org.bukkit.inventory.ItemStack,
        amount: Int,
        callback: (ClusterBinReservation) -> Unit
    ) {
        if (!isActive()) {
            callback(ClusterBinReservation("", "", playerId.toString(), "", -1, -1L))
            return
        }
        ClusterBinCoordinator.reserve(playerId, item, amount, callback)
    }

    internal fun completeSharedBinReservation(reservation: ClusterBinReservation) {
        ClusterBinCoordinator.complete(reservation)
    }

    internal fun releaseSharedBinReservation(reservation: ClusterBinReservation) {
        ClusterBinCoordinator.release(reservation)
    }

    internal fun findSharedBinReservations(playerId: UUID, callback: (List<ClusterBinReservation>?) -> Unit) {
        if (!isActive()) {
            callback(null)
            return
        }
        ClusterBinCoordinator.findPlayerReservations(playerId, callback)
    }

    fun completeSynchronizedScan(clearedItems: Int, clearedEntities: Int, timeCostMillis: Long) {
        val runId = activeScanRunId.getAndSet(null) ?: return
        val result = "items=$clearedItems;entities=$clearedEntities;time=$timeCostMillis"
        submitStorage { activeStorage, clusterId ->
            activeStorage.saveCleanupResult(clusterId, runId, Settings.clusterServerId, result, 86400000L)
        }
    }

    internal fun submitStorage(operation: (ClusterStorage, String) -> Unit): Boolean {
        val worker = executor ?: return false
        val activeStorage = storage ?: return false
        if (!isActive()) return false
        return try {
            worker.execute {
                try {
                    operation(activeStorage, Settings.clusterId)
                } catch (error: Throwable) {
                    handleIoFailure(error)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun start() {
        val nextStorageScope = configuredStorageScope()
        val preserveBinState = Settings.clusterEnabled && storageScope == nextStorageScope && (executor != null || storage != null)
        val preservedActiveScanRunId = activeScanRunId.get().takeIf { preserveBinState }
        stopInternal(ClusterConnectionState.STOPPED, "正在重新初始化", !preserveBinState)
        cleanupCycle.set(null)
        claimInFlight.set(false)
        lastClaimedRunId.set("")
        activeScanRunId.set(preservedActiveScanRunId)
        cleanupResults.set(emptyMap())

        if (!Settings.clusterEnabled) {
            storageScope = null
            updateStatus(ClusterConnectionState.DISABLED, "配置未开启", null, 0, -1L)
            return
        }

        val validationError = validateConfiguration()
        if (validationError != null) {
            storageScope = null
            updateStatus(ClusterConnectionState.REJECTED, validationError, currentIdentity(), 0, -1L)
            Cyuclear.instance.logger.severe("跨服同步未启动：$validationError")
            return
        }

        val libraryError = ClusterDependencies.verify(Settings.clusterStorageType)
        if (libraryError != null) {
            storageScope = null
            updateStatus(ClusterConnectionState.REJECTED, libraryError, currentIdentity(), 0, -1L)
            Cyuclear.instance.logger.severe("跨服同步未启动：$libraryError")
            return
        }

        storageScope = nextStorageScope
        storage = createStorage()
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Cyuclear-Cluster").apply { isDaemon = true }
        }.also { worker ->
            updateStatus(
                ClusterConnectionState.CONNECTING,
                "正在连接${storageDisplayName()}并校验集群身份",
                currentIdentity(),
                0,
                -1L
            )
            worker.scheduleWithFixedDelay(::pulseSafely, 0L, Settings.clusterHeartbeatSeconds.toLong(), TimeUnit.SECONDS)
        }
    }

    @Synchronized
    fun stop() {
        stopInternal(ClusterConnectionState.STOPPED, "已停止", true)
        storageScope = null
    }

    fun statusLines(): List<String> {
        val current = snapshot()
        val identityText = current.identity?.display() ?: "尚未生成"
        val cycle = cleanupCycle.get()
        val cycleText = if (cycle == null) "尚未建立" else "${cycle.runId} / ${cleanupRemainingSeconds() ?: 0}秒"
        return listOf(
            "§8[§bCyuclear§8] §f跨服状态：§b${stateText(current.state)}",
            "§8[§bCyuclear§8] §f本服身份：§7$identityText",
            "§8[§bCyuclear§8] §f集群：§7${Settings.clusterId} §8| §f节点：§7${Settings.clusterServerId.ifBlank { "未填写" }}",
            "§8[§bCyuclear§8] §f成员：§7${current.memberCount} §8| §f${storageDisplayName()}延迟：§7${if (current.storageLatencyMillis >= 0) "${current.storageLatencyMillis}ms" else "不可用"}",
            "§8[§bCyuclear§8] §f清理周期：§7$cycleText",
            "§8[§bCyuclear§8] §f节点清理：§7${cleanupResultText()}",
            "§8[§bCyuclear§8] §f说明：§7${current.message}"
        )
    }

    private fun pulseSafely() {
        try {
            pulse()
        } catch (error: Throwable) {
            handleIoFailure(error)
        }
    }

    private fun pulse() {
        val identity = currentIdentity()
        val memberTimeoutMillis = Settings.clusterMemberTimeoutSeconds * 1000L
        val identityTimeoutMillis = maxOf(memberTimeoutMillis * 3L, 30000L)
        val startedAt = System.nanoTime()
        val result = storage!!.pulse(
            ClusterPulseRequest(
                clusterId = Settings.clusterId,
                serverId = Settings.clusterServerId,
                instanceId = instanceId,
                identity = identity.canonical(),
                identityTimeoutMillis = identityTimeoutMillis,
                memberTimeoutMillis = memberTimeoutMillis,
                cleanupIntervalMillis = Settings.intervalSeconds.coerceAtLeast(10) * 1000L,
                executionGraceMillis = EXECUTION_GRACE_MILLIS
            )
        )
        when (result) {
            is ClusterPulseResult.IdentityMismatch -> rejectIdentity(identity, result.remoteIdentity, startedAt)
            is ClusterPulseResult.ServerIdOccupied -> {
                cleanupCycle.set(null)
                updateStatus(
                    ClusterConnectionState.REJECTED,
                    "server-id ${Settings.clusterServerId} 已被另一个实例占用",
                    identity,
                    0,
                    elapsedMillis(startedAt)
                )
            }
            is ClusterPulseResult.Accepted -> acceptPulse(identity, result, startedAt)
        }
    }

    private fun acceptPulse(identity: ClusterIdentity, result: ClusterPulseResult.Accepted, startedAt: Long) {
        val wasActive = status.get().state == ClusterConnectionState.ACTIVE
        lastIdentityWarning = ""
        if (result.runId.isNotBlank() && result.executeAtMillis > 0L) {
            cleanupCycle.set(
                ClusterCleanupCycle(
                    runId = result.runId,
                    executeAtMillis = result.executeAtMillis,
                    storageTimeOffsetMillis = result.storageNowMillis - System.currentTimeMillis(),
                    leaderServerId = result.leaderServerId
                )
            )
        } else {
            cleanupCycle.set(null)
        }
        updateStatus(
            ClusterConnectionState.ACTIVE,
            "身份校验通过，清理周期已由${storageDisplayName()}对齐",
            identity,
            result.memberCount,
            elapsedMillis(startedAt)
        )
        ClusterBinCoordinator.pulse(storage ?: return, Settings.clusterId)
        if (result.runId.isNotBlank()) {
            val latest = storage?.readCleanupResults(Settings.clusterId, result.runId).orEmpty()
            if (latest.isNotEmpty()) cleanupResults.set(latest)
        }
        if (!wasActive) {
            CyuScheduler.runTask(Cyuclear.instance, Runnable {
                Bukkit.getOnlinePlayers().forEach(VoidBinManager::recoverPendingClaims)
            })
        }
    }

    private fun rejectIdentity(identity: ClusterIdentity, remoteCanonical: String, startedAt: Long) {
        cleanupCycle.set(null)
        val localText = identity.display()
        val remoteText = ClusterIdentity.describeCanonical(remoteCanonical)
        val warningKey = "$localText|$remoteText"
        updateStatus(ClusterConnectionState.REJECTED, "身份不匹配，本服：$localText；集群：$remoteText", identity, 0, elapsedMillis(startedAt))
        if (lastIdentityWarning != warningKey) {
            lastIdentityWarning = warningKey
            Cyuclear.instance.logger.severe("拒绝加入 Cyuclear 集群。")
            Cyuclear.instance.logger.severe("本服身份：$localText")
            Cyuclear.instance.logger.severe("集群身份：$remoteText")
        }
    }

    private fun cleanupResultText(): String {
        val results = cleanupResults.get()
        if (results.isEmpty()) return "暂无结果"
        var items = 0L
        var entities = 0L
        var slowest = 0L
        for (value in results.values) {
            val fields = value.split(';').mapNotNull {
                val separator = it.indexOf('=')
                if (separator <= 0) null else it.substring(0, separator) to it.substring(separator + 1)
            }.toMap()
            items += fields["items"]?.toLongOrNull() ?: 0L
            entities += fields["entities"]?.toLongOrNull() ?: 0L
            slowest = maxOf(slowest, fields["time"]?.toLongOrNull() ?: 0L)
        }
        return "${results.size} 节点 / 掉落物 $items / 实体 $entities / 最慢 ${slowest}ms"
    }

    private fun releaseClaim(cycle: ClusterCleanupCycle) {
        lastClaimedRunId.compareAndSet(cycle.runId, "")
        val request = ClusterClaimRequest(
            clusterId = Settings.clusterId,
            runId = cycle.runId,
            serverId = Settings.clusterServerId,
            instanceId = instanceId,
            ttlMillis = CLAIM_TTL_MILLIS
        )
        executor?.execute {
            runCatching { storage?.releaseCleanupClaim(request) }
        }
    }

    private fun handleIoFailure(error: Throwable) {
        storage?.invalidate()
        cleanupCycle.set(null)
        val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        updateStatus(
            ClusterConnectionState.DISCONNECTED,
            "${storageDisplayName()}连接中断，已暂停跨服行为：$message",
            currentIdentity(),
            0,
            -1L
        )
        CleanupNoticeManager.clearBossBar()
    }

    private fun currentIdentity(): ClusterIdentity = ClusterIdentity(
        pluginVersion = Cyuclear.instance.description.version,
        platform = BuildInfo.platformId,
        minecraftVersion = Bukkit.getBukkitVersion(),
        compatibilityDomain = BuildInfo.compatibilityDomain,
        protocolVersion = PROTOCOL_VERSION,
        serializationVersion = SERIALIZATION_VERSION
    )

    private fun validateConfiguration(): String? {
        if (!Settings.clusterStorageValid) return "cluster.storage 只能填写 redis 或 mysql"
        if (!Regex(ID_PATTERN).matches(Settings.clusterId)) return "cluster.id 只能包含字母、数字、点、下划线和短横线，长度为 1-64"
        if (!Regex(ID_PATTERN).matches(Settings.clusterServerId)) return "cluster.server-id 必须填写唯一值，并且只能包含字母、数字、点、下划线和短横线"
        return when (Settings.clusterStorageType) {
            ClusterStorageType.REDIS -> if (Settings.clusterRedisHost.isBlank()) "cluster.redis.host 不能为空" else null
            ClusterStorageType.MYSQL -> when {
                Settings.clusterMysqlHost.isBlank() -> "cluster.mysql.host 不能为空"
                Settings.clusterMysqlDatabase.isBlank() -> "cluster.mysql.database 不能为空"
                Settings.clusterMysqlUsername.isBlank() -> "cluster.mysql.username 不能为空"
                !Regex(TABLE_PREFIX_PATTERN).matches(Settings.clusterMysqlTablePrefix) -> "cluster.mysql.table-prefix 只能包含字母、数字和下划线，长度为 1-32"
                else -> null
            }
        }
    }

    private fun createStorage(): ClusterStorage = when (Settings.clusterStorageType) {
        ClusterStorageType.REDIS -> RedisClusterStorage(RedisGateway())
        ClusterStorageType.MYSQL -> MySqlClusterStorage()
    }

    private fun storageDisplayName(): String = when (Settings.clusterStorageType) {
        ClusterStorageType.REDIS -> "Redis"
        ClusterStorageType.MYSQL -> "MySQL"
    }

    private fun configuredStorageScope(): String = when (Settings.clusterStorageType) {
        ClusterStorageType.REDIS -> listOf(
            "redis",
            Settings.clusterId,
            Settings.clusterRedisHost,
            Settings.clusterRedisPort,
            Settings.clusterRedisUsername,
            Settings.clusterRedisPassword,
            Settings.clusterRedisDatabase,
            Settings.clusterRedisSsl
        ).joinToString("\u0000")
        ClusterStorageType.MYSQL -> listOf(
            "mysql",
            Settings.clusterId,
            Settings.clusterMysqlHost,
            Settings.clusterMysqlPort,
            Settings.clusterMysqlDatabase,
            Settings.clusterMysqlUsername,
            Settings.clusterMysqlPassword,
            Settings.clusterMysqlSsl,
            Settings.clusterMysqlTablePrefix
        ).joinToString("\u0000")
    }

    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun updateStatus(
        state: ClusterConnectionState,
        message: String,
        identity: ClusterIdentity?,
        memberCount: Int,
        latencyMillis: Long
    ) {
        status.set(ClusterStatusSnapshot(state, message, identity, memberCount, latencyMillis, System.currentTimeMillis()))
    }

    @Synchronized
    private fun stopInternal(state: ClusterConnectionState, message: String, clearBinState: Boolean) {
        executor?.shutdownNow()
        executor = null
        storage?.close()
        storage = null
        cleanupCycle.set(null)
        activeScanRunId.set(null)
        if (clearBinState) ClusterBinCoordinator.reset()
        lastIdentityWarning = ""
        updateStatus(state, message, runCatching { currentIdentity() }.getOrNull(), 0, -1L)
    }

    private fun stateText(state: ClusterConnectionState): String = when (state) {
        ClusterConnectionState.DISABLED -> "未开启"
        ClusterConnectionState.CONNECTING -> "连接中"
        ClusterConnectionState.ACTIVE -> "已连接"
        ClusterConnectionState.REJECTED -> "已拒绝"
        ClusterConnectionState.DISCONNECTED -> "已断开"
        ClusterConnectionState.STOPPED -> "已停止"
    }
}

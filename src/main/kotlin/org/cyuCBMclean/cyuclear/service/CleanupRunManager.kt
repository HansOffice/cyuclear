package org.cyuCBMclean.cyuclear.service

import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import org.cyuCBMclean.cyuclear.storage.CleanupRunStore
import org.cyuCBMclean.cyuclear.cluster.ItemStackCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

object CleanupRunManager {
    enum class Status(val display: String) {
        RUNNING("进行中"),
        COMPLETED("已完成"),
        PARTIAL("部分完成"),
        CANCELLED("已停止"),
        FAILED("已中断")
    }

    data class RunView(
        val id: String,
        val origin: CleanupOrigin,
        val status: Status,
        val startedAt: Long,
        val finishedAt: Long,
        val queuedChunks: Int,
        val processedChunks: Int,
        val failedChunks: Int,
        val scannedEntities: Int,
        val removedItems: Long,
        val removedEntities: Long,
        val recoveryEntries: Int,
        val pendingRecoveryEntries: Int,
        val skippedRecoveryEntries: Long,
        val fullScan: Boolean,
        val slowestWorld: String?,
        val slowestChunkX: Int,
        val slowestChunkZ: Int,
        val slowestChunkMillis: Long,
        val failureMessage: String?,
        val itemReasons: List<ReasonCount>,
        val entityReasons: List<ReasonCount>
    )

    data class ActiveSnapshot(
        val id: String,
        val origin: CleanupOrigin,
        val status: Status,
        val queuedChunks: Int,
        val processedChunks: Int,
        val failedChunks: Int,
        val scannedEntities: Int,
        val removedItems: Long,
        val removedEntities: Long
    )

    data class ReasonCount(
        val title: String,
        val count: Long
    )

    data class RecoveryView(
        val index: Int,
        val item: ItemStack?,
        val itemId: String,
        val amount: Int,
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val reason: String,
        val claimedBy: String?,
        val claimedAt: Long
    ) {
        val claimed: Boolean
            get() = claimedBy != null
    }

    data class RecoveryPage(
        val entries: List<RecoveryView>,
        val page: Int,
        val totalPages: Int
    )

    enum class ClaimStatus {
        CLAIMED,
        NOT_FOUND,
        NOT_READY,
        EXPIRED,
        ALREADY_CLAIMED,
        INVALID_ITEM,
        NO_SPACE,
        SAVE_FAILED
    }

    data class ClaimResult(
        val status: ClaimStatus,
        val amount: Int = 0
    )

    class RunHandle internal constructor(
        private val record: RunRecord
    ) {
        internal val capturesRecovery: Boolean = record.captureRecovery

        internal fun batch(): ChunkBatch = ChunkBatch(record)

        internal fun finish(
            status: Status,
            queuedChunks: Int,
            processedChunks: Int,
            durationMillis: Long
        ) {
            CleanupRunManager.finish(record, status, queuedChunks, processedChunks, durationMillis)
        }

        internal fun updateQueuedChunks(value: Int) {
            record.queuedChunks = value.coerceAtLeast(0)
        }

        internal fun recordChunk(world: String, chunkX: Int, chunkZ: Int, processNanos: Long) {
            record.completedChunks.incrementAndGet()
            if (processNanos <= record.slowestNanos.get()) return
            synchronized(record.slowestLock) {
                if (processNanos <= record.slowestNanos.get()) return
                record.slowestNanos.set(processNanos)
                record.slowestWorld = world
                record.slowestChunkX = chunkX
                record.slowestChunkZ = chunkZ
            }
        }

        internal fun recordFailure(world: String, chunkX: Int, chunkZ: Int, message: String?) {
            record.failedChunks.incrementAndGet()
            synchronized(record.lock) {
                if (record.failureMessage == null) {
                    val detail = message?.trim()?.takeIf { it.isNotEmpty() } ?: "未知错误"
                    record.failureMessage = "$world $chunkX,$chunkZ - $detail".take(240)
                }
            }
        }
    }

    class ChunkBatch internal constructor(
        private val record: RunRecord
    ) {
        private var itemReasons: LongArray? = null
        private var entityReasons: LongArray? = null
        private var touchedReasons: BooleanArray? = null
        private var touchedReasonIndexes: IntArray? = null
        private var capturedEntries: ArrayList<RecoveryEntry>? = null
        private var removedItems = 0L
        private var removedEntities = 0L
        private var scannedEntities = 0
        private var skippedRecoveryEntries = 0L
        private var touchedReasonCount = 0
        private var committed = false

        fun scanned(value: Int) {
            scannedEntities = value.coerceAtLeast(0)
        }

        fun item(decision: CleanupFilter.FilterDecision, amount: Int) {
            val safeAmount = amount.coerceAtLeast(0).toLong()
            removedItems += safeAmount
            val index = decision.reasonKey.ordinal
            ensureReasonStorage()
            itemReasons!![index] += safeAmount
            touch(index)
        }

        fun entity(decision: CleanupFilter.FilterDecision, amount: Int) {
            val safeAmount = amount.coerceAtLeast(0).toLong()
            removedEntities += safeAmount
            val index = decision.reasonKey.ordinal
            ensureReasonStorage()
            entityReasons!![index] += safeAmount
            touch(index)
        }

        fun capture(item: Item, decision: CleanupFilter.FilterDecision, amount: Int) {
            if (!record.captureRecovery) return
            if (!record.reserveRecoveryEntry()) {
                skippedRecoveryEntries++
                return
            }
            val location = item.location
            val encoded = runCatching { ItemStackCodec.encode(item.itemStack) }.getOrElse {
                record.releaseRecoveryEntry()
                skippedRecoveryEntries++
                Cyuclear.instance.logger.warning("无法保存清理恢复物品 ${decision.id}：${it.message}")
                return
            }
            val entries = capturedEntries ?: ArrayList<RecoveryEntry>().also { capturedEntries = it }
            entries += RecoveryEntry(
                encodedItem = encoded,
                itemId = decision.id,
                amount = amount.coerceAtLeast(1),
                world = item.world.name,
                x = location.blockX,
                y = location.blockY,
                z = location.blockZ,
                reason = decision.reason
            )
        }

        fun commit() {
            if (committed) return
            committed = true
            val captured = capturedEntries
            if (record.status != Status.RUNNING) {
                repeat(captured?.size ?: 0) { record.releaseRecoveryEntry() }
                return
            }
            record.removedItems.addAndGet(removedItems)
            record.removedEntities.addAndGet(removedEntities)
            record.scannedEntities.addAndGet(scannedEntities)
            record.skippedRecoveryEntries.addAndGet(skippedRecoveryEntries)
            if (captured != null) {
                record.recoveryEntries.addAll(captured)
                record.recoveryEntryCount.addAndGet(captured.size)
            }
            val itemReasonValues = itemReasons
            val entityReasonValues = entityReasons
            val touchedIndexes = touchedReasonIndexes
            if (itemReasonValues != null && entityReasonValues != null && touchedIndexes != null) {
                for (offset in 0 until touchedReasonCount) {
                    val index = touchedIndexes[offset]
                    if (itemReasonValues[index] > 0L) record.itemReasons.addAndGet(index, itemReasonValues[index])
                    if (entityReasonValues[index] > 0L) record.entityReasons.addAndGet(index, entityReasonValues[index])
                }
            }
        }

        private fun touch(index: Int) {
            if (touchedReasons!![index]) return
            touchedReasons!![index] = true
            touchedReasonIndexes!![touchedReasonCount++] = index
        }

        private fun ensureReasonStorage() {
            if (itemReasons != null) return
            val size = CleanupFilter.ReasonKey.values().size
            itemReasons = LongArray(size)
            entityReasons = LongArray(size)
            touchedReasons = BooleanArray(size)
            touchedReasonIndexes = IntArray(size)
        }
    }

    internal data class RecoveryEntry(
        val encodedItem: String,
        val itemId: String,
        val amount: Int,
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val reason: String,
        var claimedBy: String? = null,
        var claimedAt: Long = 0L
    )

    internal class RunRecord(
        val id: String,
        val origin: CleanupOrigin,
        val startedAt: Long,
        val fullScan: Boolean,
        val captureRecovery: Boolean,
        val maxRecoveryEntries: Int,
        val recoveryExpiresAt: Long
    ) {
        val lock = Any()
        val removedItems = AtomicLong(0L)
        val removedEntities = AtomicLong(0L)
        val scannedEntities = AtomicInteger(0)
        val completedChunks = AtomicInteger(0)
        val failedChunks = AtomicInteger(0)
        val itemReasons = AtomicLongArray(CleanupFilter.ReasonKey.values().size)
        val entityReasons = AtomicLongArray(CleanupFilter.ReasonKey.values().size)
        val recoveryEntries = ConcurrentLinkedQueue<RecoveryEntry>()
        val reservedRecoveryEntries = AtomicInteger(0)
        val recoveryEntryCount = AtomicInteger(0)
        val claimedRecoveryEntries = AtomicInteger(0)
        val skippedRecoveryEntries = AtomicLong(0L)
        val revision = AtomicLong(0L)
        val slowestLock = Any()
        val slowestNanos = AtomicLong(0L)
        @Volatile var status = Status.RUNNING
        @Volatile var queuedChunks = 0
        @Volatile var processedChunks = 0
        @Volatile var finishedAt = 0L
        @Volatile var durationMillis = 0L
        @Volatile var slowestWorld: String? = null
        @Volatile var slowestChunkX = 0
        @Volatile var slowestChunkZ = 0
        @Volatile var failureMessage: String? = null

        fun reserveRecoveryEntry(): Boolean {
            if (maxRecoveryEntries <= 0) return false
            while (true) {
                val current = reservedRecoveryEntries.get()
                if (current >= maxRecoveryEntries) {
                    return false
                }
                if (reservedRecoveryEntries.compareAndSet(current, current + 1)) return true
            }
        }

        fun releaseRecoveryEntry() {
            reservedRecoveryEntries.decrementAndGet()
        }
    }

    private data class InventoryChange(
        val slot: Int,
        val before: ItemStack?,
        val after: ItemStack
    )

    private data class SlowestView(
        val world: String?,
        val chunkX: Int,
        val chunkZ: Int,
        val millis: Long
    )

    private data class InventoryPlan(
        val changes: List<InventoryChange>
    )

    private val records = ConcurrentHashMap<String, RunRecord>()
    private val recordOrder = ArrayList<String>()
    private val orderLock = Any()
    @Volatile
    private var active: RunRecord? = null

    fun initialize() {
        records.clear()
        synchronized(orderLock) {
            recordOrder.clear()
        }
        active = null
        for (record in CleanupRunStore.loadRecent(Settings.recoveryRecentLimit)) {
            records[record.id] = record
            synchronized(orderLock) {
                recordOrder += record.id
            }
        }
    }

    fun begin(request: CleanupRequest, fullScan: Boolean): RunHandle {
        val startedAt = System.currentTimeMillis()
        val record = RunRecord(
            id = UUID.randomUUID().toString().substring(0, 8),
            origin = request.origin,
            startedAt = startedAt,
            fullScan = fullScan,
            captureRecovery = Settings.recoveryCaptureEnabled(request.origin),
            maxRecoveryEntries = Settings.recoveryMaxEntriesPerRun,
            recoveryExpiresAt = startedAt + Settings.recoveryExpireHours * 3_600_000L
        )
        records[record.id] = record
        synchronized(orderLock) {
            recordOrder.remove(record.id)
            recordOrder.add(0, record.id)
            while (recordOrder.size > Settings.recoveryRecentLimit) {
                records.remove(recordOrder.removeAt(recordOrder.lastIndex))
            }
        }
        active = record
        return RunHandle(record)
    }

    fun activeView(): RunView? = active?.let(::viewOf)

    fun activeSnapshot(): ActiveSnapshot? {
        val record = active ?: return null
        return ActiveSnapshot(
            id = record.id,
            origin = record.origin,
            status = record.status,
            queuedChunks = record.queuedChunks,
            processedChunks = if (record.status == Status.RUNNING) record.completedChunks.get() else record.processedChunks,
            failedChunks = record.failedChunks.get(),
            scannedEntities = record.scannedEntities.get(),
            removedItems = record.removedItems.get(),
            removedEntities = record.removedEntities.get()
        )
    }

    fun list(page: Int, pageSize: Int): Pair<List<RunView>, Int> {
        val size = pageSize.coerceIn(1, 54)
        val ids = synchronized(orderLock) { recordOrder.toList() }
        val totalPages = maxOf(1, (ids.size + size - 1) / size)
        val normalizedPage = page.coerceIn(0, totalPages - 1)
        val from = normalizedPage * size
        val to = minOf(ids.size, from + size)
        val pageEntries = if (from >= to) emptyList() else ids.subList(from, to)
            .mapNotNull(records::get)
            .map(::viewOf)
        return pageEntries to totalPages
    }

    fun find(id: String): RunView? = records[id]?.let(::viewOf)

    fun recoveryPage(id: String, requestedPage: Int, pageSize: Int): RecoveryPage? {
        val record = records[id] ?: return null
        val size = pageSize.coerceIn(1, 54)
        val totalEntries = record.recoveryEntryCount.get()
        val totalPages = maxOf(1, (totalEntries + size - 1) / size)
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val from = page * size
        val to = minOf(totalEntries, from + size)
        val entries = ArrayList<RecoveryView>(to - from)
        synchronized(record.lock) {
            var index = 0
            for (entry in record.recoveryEntries) {
                if (index >= to) break
                if (index >= from) entries += recoveryView(index, entry)
                index++
            }
        }
        return RecoveryPage(entries, page, totalPages)
    }

    fun claim(player: Player, runId: String, entryIndex: Int): ClaimResult {
        val record = records[runId] ?: return ClaimResult(ClaimStatus.NOT_FOUND)
        synchronized(record.lock) {
            if (record.status != Status.COMPLETED && record.status != Status.PARTIAL && record.status != Status.CANCELLED) {
                return ClaimResult(ClaimStatus.NOT_READY)
            }
            if (record.recoveryExpiresAt > 0L && System.currentTimeMillis() > record.recoveryExpiresAt) {
                return ClaimResult(ClaimStatus.EXPIRED)
            }
            val entry = record.recoveryEntries.elementAtOrNull(entryIndex) ?: return ClaimResult(ClaimStatus.NOT_FOUND)
            if (entry.claimedBy != null) return ClaimResult(ClaimStatus.ALREADY_CLAIMED)
            val item = runCatching { ItemStackCodec.decode(entry.encodedItem) }.getOrNull()
                ?: return ClaimResult(ClaimStatus.INVALID_ITEM)
            val plan = planInventoryAddition(player, item, entry.amount)
                ?: return ClaimResult(ClaimStatus.NO_SPACE)
            entry.claimedBy = player.name
            entry.claimedAt = System.currentTimeMillis()
            record.revision.incrementAndGet()
            if (!CleanupRunStore.save(record)) {
                entry.claimedBy = null
                entry.claimedAt = 0L
                record.revision.incrementAndGet()
                return ClaimResult(ClaimStatus.SAVE_FAILED)
            }
            return try {
                applyInventoryPlan(player, plan)
                record.claimedRecoveryEntries.incrementAndGet()
                saveIndex()
                ClaimResult(ClaimStatus.CLAIMED, entry.amount)
            } catch (error: Throwable) {
                entry.claimedBy = null
                entry.claimedAt = 0L
                record.revision.incrementAndGet()
                if (!CleanupRunStore.save(record)) {
                    Cyuclear.instance.logger.warning("恢复清理批次 ${record.id}#$entryIndex 的领取状态失败")
                }
                Cyuclear.instance.logger.warning("发放清理恢复物品 ${record.id}#$entryIndex 失败：${error.message}")
                ClaimResult(ClaimStatus.SAVE_FAILED)
            }
        }
    }

    fun formatTime(time: Long): String {
        if (time <= 0L) return "-"
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))
    }

    fun originText(origin: CleanupOrigin): String = when (origin) {
        CleanupOrigin.SCHEDULED -> "定时清理"
        CleanupOrigin.MANUAL -> "手动清理"
        CleanupOrigin.PANIC -> "紧急清理"
    }

    fun flush() {
        records.values.forEach { record ->
            synchronized(record.lock) {
                CleanupRunStore.save(record)
            }
        }
        saveIndex()
    }

    fun cancelActive() {
        val record = active ?: return
        finish(
            record,
            Status.CANCELLED,
            record.queuedChunks,
            maxOf(record.processedChunks, record.completedChunks.get()),
            System.currentTimeMillis() - record.startedAt
        )
    }

    private fun finish(
        record: RunRecord,
        status: Status,
        queuedChunks: Int,
        processedChunks: Int,
        durationMillis: Long
    ) {
        synchronized(record.lock) {
            if (record.status != Status.RUNNING) return
            record.status = if (status == Status.COMPLETED && record.failedChunks.get() > 0) Status.PARTIAL else status
            record.queuedChunks = queuedChunks.coerceAtLeast(0)
            record.processedChunks = processedChunks.coerceAtLeast(0)
            record.durationMillis = durationMillis.coerceAtLeast(0L)
            record.finishedAt = System.currentTimeMillis()
            record.revision.incrementAndGet()
        }
        if (active === record) active = null
        saveAsync(record)
    }

    private fun saveAsync(record: RunRecord) {
        val requestedRevision = record.revision.get()
        CyuScheduler.runTaskAsynchronously(Cyuclear.instance, Runnable {
            synchronized(record.lock) {
                if (record.revision.get() != requestedRevision) return@Runnable
                if (CleanupRunStore.save(record)) saveIndex()
            }
        })
    }

    private fun saveIndex() {
        val ids = synchronized(orderLock) { recordOrder.toList() }
        CleanupRunStore.saveIndex(ids)
    }

    private fun viewOf(record: RunRecord): RunView {
        val recoveryEntries = record.recoveryEntryCount.get()
        val slowest = synchronized(record.slowestLock) {
            SlowestView(
                record.slowestWorld,
                record.slowestChunkX,
                record.slowestChunkZ,
                record.slowestNanos.get() / 1_000_000L
            )
        }
        return RunView(
            id = record.id,
            origin = record.origin,
            status = record.status,
            startedAt = record.startedAt,
            finishedAt = record.finishedAt,
            queuedChunks = record.queuedChunks,
            processedChunks = if (record.status == Status.RUNNING) record.completedChunks.get() else record.processedChunks,
            failedChunks = record.failedChunks.get(),
            scannedEntities = record.scannedEntities.get(),
            removedItems = record.removedItems.get(),
            removedEntities = record.removedEntities.get(),
            recoveryEntries = recoveryEntries,
            pendingRecoveryEntries = (recoveryEntries - record.claimedRecoveryEntries.get()).coerceAtLeast(0),
            skippedRecoveryEntries = record.skippedRecoveryEntries.get(),
            fullScan = record.fullScan,
            slowestWorld = slowest.world,
            slowestChunkX = slowest.chunkX,
            slowestChunkZ = slowest.chunkZ,
            slowestChunkMillis = slowest.millis,
            failureMessage = record.failureMessage,
            itemReasons = reasonCounts(record.itemReasons),
            entityReasons = reasonCounts(record.entityReasons)
        )
    }

    private fun recoveryView(index: Int, entry: RecoveryEntry): RecoveryView {
        return RecoveryView(
            index = index,
            item = runCatching { ItemStackCodec.decode(entry.encodedItem) }.getOrNull(),
            itemId = entry.itemId,
            amount = entry.amount,
            world = entry.world,
            x = entry.x,
            y = entry.y,
            z = entry.z,
            reason = entry.reason,
            claimedBy = entry.claimedBy,
            claimedAt = entry.claimedAt
        )
    }

    private fun reasonCounts(values: AtomicLongArray): List<ReasonCount> {
        return CleanupFilter.ReasonKey.values().mapNotNull { reason ->
            values.get(reason.ordinal).takeIf { it > 0L }?.let { count -> ReasonCount(reason.title, count) }
        }.sortedByDescending(ReasonCount::count)
    }

    private fun planInventoryAddition(player: Player, source: ItemStack, amount: Int): InventoryPlan? {
        var remaining = amount.coerceAtLeast(0)
        if (remaining <= 0) return null
        val changes = ArrayList<InventoryChange>()
        for (slot in 0 until 36) {
            if (remaining <= 0) break
            val current = player.inventory.getItem(slot)
            val maximum = source.maxStackSize.coerceAtLeast(1)
            val capacity = when {
                current == null || current.type == Material.AIR -> maximum
                current.isSimilar(source) -> (maximum - current.amount).coerceAtLeast(0)
                else -> 0
            }
            if (capacity <= 0) continue
            val accepted = minOf(capacity, remaining)
            val after = if (current == null || current.type == Material.AIR) {
                source.clone().apply { this.amount = accepted }
            } else {
                current.clone().apply { this.amount += accepted }
            }
            changes += InventoryChange(slot, current?.clone(), after)
            remaining -= accepted
        }
        return if (remaining == 0) InventoryPlan(changes) else null
    }

    private fun applyInventoryPlan(player: Player, plan: InventoryPlan) {
        val applied = ArrayList<InventoryChange>(plan.changes.size)
        try {
            for (change in plan.changes) {
                player.inventory.setItem(change.slot, change.after.clone())
                applied += change
            }
        } catch (error: Throwable) {
            for (change in applied.asReversed()) {
                player.inventory.setItem(change.slot, change.before?.clone() ?: ItemStack(Material.AIR))
            }
            throw error
        }
    }

}

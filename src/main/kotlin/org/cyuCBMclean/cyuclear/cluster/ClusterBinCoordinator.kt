package org.cyuCBMclean.cyuclear.cluster

import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.service.VoidBinManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

internal object ClusterBinCoordinator {
    private const val MAX_FLUSH_ITEMS = 1000
    private const val DATA_TTL_MILLIS = 86400000L

    private data class PendingAdd(
        val cycleId: String,
        val item: ItemStack,
        val amount: Int,
        val encodedItem: String?
    )

    private val pendingAdds = ConcurrentLinkedQueue<PendingAdd>()
    private val pendingCompletions = ConcurrentLinkedQueue<ClusterBinReservation>()
    private val pendingReleases = ConcurrentLinkedQueue<ClusterBinReservation>()
    private val loadedRevision = AtomicLong(-1L)
    private val pendingOpenUntil = AtomicLong(0L)
    private val pendingOpenCycleId = AtomicReference("")
    private val pendingBeginCycleId = AtomicReference("")
    private val encodedByItem = ConcurrentHashMap<ItemStack, String>()

    @Volatile
    private var currentCycleId: String = ""
    @Volatile
    private var currentExpireAt: Long = 0L
    @Volatile
    private var loadedCycleId: String = ""

    fun reset() {
        pendingAdds.clear()
        pendingCompletions.clear()
        pendingReleases.clear()
        pendingOpenUntil.set(0L)
        pendingOpenCycleId.set("")
        pendingBeginCycleId.set("")
        currentCycleId = ""
        currentExpireAt = 0L
        loadedCycleId = ""
        loadedRevision.set(-1L)
        encodedByItem.clear()
    }

    fun currentCycleId(): String = currentCycleId

    fun beginCycle(cycleId: String) {
        val previousCycleId = currentCycleId
        currentCycleId = cycleId
        pendingBeginCycleId.set(cycleId)
        currentExpireAt = 0L
        loadedCycleId = ""
        loadedRevision.set(-1L)
        encodedByItem.clear()
        if (previousCycleId != cycleId && pendingOpenCycleId.get() != cycleId) {
            pendingOpenCycleId.set("")
            pendingOpenUntil.set(0L)
        }
        ClusterManager.submitStorage { storage, clusterId ->
            if (previousCycleId.isNotBlank() && previousCycleId != cycleId) {
                flushPending(storage, clusterId, previousCycleId)
            }
            storage.beginBinCycle(clusterId, cycleId, DATA_TTL_MILLIS)
            pendingBeginCycleId.compareAndSet(cycleId, "")
        }
    }

    fun queueAdd(cycleId: String, item: ItemStack, amount: Int = item.amount, encodedItem: String? = null) {
        if (cycleId.isBlank()) return
        val clone = item.clone()
        val addedAmount = amount.coerceAtLeast(1)
        clone.amount = 1
        pendingAdds.add(PendingAdd(cycleId, clone, addedAmount, encodedItem))
    }

    fun openWindow(cycleId: String, durationSeconds: Int) {
        if (cycleId.isBlank() || durationSeconds <= 0) return
        val requestedUntil = System.currentTimeMillis() + durationSeconds * 1000L
        pendingOpenCycleId.set(cycleId)
        pendingOpenUntil.updateAndGet { current -> maxOf(current, requestedUntil) }
        ClusterManager.submitStorage { storage, clusterId ->
            flushPending(storage, clusterId, cycleId)
            openPendingWindow(storage, clusterId, cycleId)
        }
    }

    fun reserve(playerId: UUID, item: ItemStack, requestedAmount: Int, callback: (ClusterBinReservation) -> Unit) {
        val cycleId = currentCycleId
        if (cycleId.isBlank() || requestedAmount <= 0) {
            callback(unavailableReservation(cycleId, playerId))
            return
        }
        val normalizedItem = item.clone().apply { amount = 1 }
        val encoded = encodedByItem[normalizedItem] ?: runCatching { ItemStackCodec.encode(normalizedItem) }.getOrElse {
            Cyuclear.instance.logger.warning("跨服垃圾桶物品编码失败：${it.message}")
            callback(unavailableReservation(cycleId, playerId))
            return
        }
        val claimId = UUID.randomUUID().toString()
        if (!ClusterManager.submitStorage { storage, clusterId ->
                try {
                    val result = storage.reserveBinItem(
                        clusterId,
                        cycleId,
                        encoded,
                        requestedAmount,
                        claimId,
                        playerId.toString()
                    )
                    if (result.amount > 0) {
                        loadedRevision.set(result.revision)
                        VoidBinManager.applyClusterClaim(item, result.amount)
                    }
                    callback(result)
                } catch (error: Throwable) {
                    callback(unavailableReservation(cycleId, playerId))
                    throw error
                }
            }) {
            callback(unavailableReservation(cycleId, playerId))
        }
    }

    fun complete(reservation: ClusterBinReservation) {
        if (reservation.amount <= 0 || reservation.claimId.isBlank()) return
        pendingCompletions.add(reservation)
        ClusterManager.submitStorage { storage, clusterId ->
            flushPendingCompletions(storage, clusterId)
        }
    }

    fun release(reservation: ClusterBinReservation) {
        if (reservation.amount <= 0 || reservation.claimId.isBlank()) return
        pendingReleases.add(reservation)
        ClusterManager.submitStorage { storage, clusterId ->
            flushPendingReleases(storage, clusterId)
        }
    }

    fun findPlayerReservations(playerId: UUID, callback: (List<ClusterBinReservation>?) -> Unit) {
        if (!ClusterManager.submitStorage { storage, clusterId ->
                try {
                    callback(storage.findBinReservations(clusterId, playerId.toString()))
                } catch (error: Throwable) {
                    callback(null)
                    throw error
                }
            }) {
            callback(null)
        }
    }

    fun pulse(storage: ClusterStorage, clusterId: String) {
        flushPendingCompletions(storage, clusterId)
        flushPendingReleases(storage, clusterId)
        beginPendingCycle(storage, clusterId)
        val preferredCycle = currentCycleId
        if (preferredCycle.isNotBlank()) flushPending(storage, clusterId, preferredCycle)
        val pendingOpenCycle = pendingOpenCycleId.get()
        if (pendingOpenCycle.isNotBlank()) openPendingWindow(storage, clusterId, pendingOpenCycle)

        val state = storage.readBinState(clusterId, withItems = false)
        val cycleId = state.cycleId
        val expireAt = state.expireAtMillis
        val revision = state.revision
        currentCycleId = cycleId
        currentExpireAt = expireAt
        val localExpireAt = if (expireAt > 0L) expireAt - (state.storageNowMillis - System.currentTimeMillis()) else 0L

        if (cycleId.isBlank() || (expireAt > 0L && expireAt <= state.storageNowMillis)) {
            if (loadedCycleId != cycleId || loadedRevision.get() != revision || VoidBinManager.expireTime > 0L) {
                loadedCycleId = cycleId
                loadedRevision.set(revision)
                VoidBinManager.replaceClusterSnapshot(emptyMap(), 0L)
            }
            return
        }

        if (loadedCycleId == cycleId && loadedRevision.get() == revision) {
            if (expireAt > 0L) {
                VoidBinManager.applyClusterExpiry(localExpireAt, ((expireAt - state.storageNowMillis + 999L) / 1000L).toInt())
            }
            return
        }

        val snapshotState = storage.readBinState(clusterId, withItems = true)
        if (snapshotState.cycleId != cycleId || snapshotState.revision != revision) return
        val snapshot = LinkedHashMap<ItemStack, Int>()
        val snapshotEncoded = LinkedHashMap<ItemStack, String>()
        snapshotState.items.forEach { (encoded, amount) ->
            if (encoded.isNotEmpty() && amount > 0) {
                runCatching { ItemStackCodec.decode(encoded) }
                    .onSuccess { item ->
                        snapshot.merge(item, amount) { old, added -> old + added }
                        snapshotEncoded[item] = encoded
                    }
                    .onFailure { error -> Cyuclear.instance.logger.warning("跳过无法读取的跨服垃圾桶物品：${error.message}") }
            }
        }
        encodedByItem.clear()
        encodedByItem.putAll(snapshotEncoded)
        loadedCycleId = cycleId
        loadedRevision.set(revision)
        VoidBinManager.replaceClusterSnapshot(snapshot, localExpireAt)
    }

    private fun beginPendingCycle(storage: ClusterStorage, clusterId: String) {
        val cycleId = pendingBeginCycleId.get()
        if (cycleId.isBlank()) return
        storage.beginBinCycle(clusterId, cycleId, DATA_TTL_MILLIS)
        pendingBeginCycleId.compareAndSet(cycleId, "")
    }

    private fun openPendingWindow(storage: ClusterStorage, clusterId: String, cycleId: String) {
        val requestedUntil = pendingOpenUntil.get()
        if (requestedUntil <= 0L) return
        val remainingMillis = requestedUntil - System.currentTimeMillis()
        if (remainingMillis <= 0L) {
            pendingOpenUntil.compareAndSet(requestedUntil, 0L)
            pendingOpenCycleId.compareAndSet(cycleId, "")
            return
        }
        val durationMillis = ((remainingMillis + 999L) / 1000L) * 1000L
        val expireAt = storage.openBinWindow(clusterId, cycleId, durationMillis, DATA_TTL_MILLIS)
        if (expireAt > 0L) {
            currentExpireAt = expireAt
            pendingOpenUntil.compareAndSet(requestedUntil, 0L)
            pendingOpenCycleId.compareAndSet(cycleId, "")
        }
    }

    private fun flushPending(storage: ClusterStorage, clusterId: String, cycleId: String) {
        val drained = ArrayList<PendingAdd>()
        val deferred = ArrayList<PendingAdd>()
        val aggregated = LinkedHashMap<String, Int>()
        var inspected = 0
        while (inspected < MAX_FLUSH_ITEMS) {
            val pending = pendingAdds.poll() ?: break
            inspected++
            if (pending.cycleId != cycleId) {
                deferred.add(pending)
                continue
            }
            val encoded = runCatching { pending.encodedItem ?: ItemStackCodec.encode(pending.item) }
                .onFailure { error -> Cyuclear.instance.logger.warning("跳过无法写入跨服垃圾桶的物品：${error.message}") }
                .getOrNull()
                ?: continue
            drained.add(pending)
            encodedByItem[pending.item] = encoded
            aggregated.merge(encoded, pending.amount) { old, added -> old + added }
        }
        try {
            if (aggregated.isEmpty()) return
            val revision = storage.addBinItems(clusterId, cycleId, aggregated, DATA_TTL_MILLIS)
            if (revision == null) {
                drained.forEach(pendingAdds::add)
            } else {
                loadedRevision.set(revision)
            }
        } catch (error: Throwable) {
            drained.forEach(pendingAdds::add)
            throw error
        } finally {
            deferred.forEach(pendingAdds::add)
        }
    }

    private fun flushPendingCompletions(storage: ClusterStorage, clusterId: String) {
        val drained = ArrayList<ClusterBinReservation>()
        while (drained.size < MAX_FLUSH_ITEMS) {
            val reservation = pendingCompletions.poll() ?: break
            drained += reservation
        }
        if (drained.isEmpty()) return
        try {
            for (reservation in drained) {
                storage.completeBinReservation(clusterId, reservation.cycleId, reservation.claimId, reservation.playerId)
            }
        } catch (error: Throwable) {
            drained.forEach(pendingCompletions::add)
            throw error
        }
    }

    private fun flushPendingReleases(storage: ClusterStorage, clusterId: String) {
        val drained = ArrayList<ClusterBinReservation>()
        while (drained.size < MAX_FLUSH_ITEMS) {
            val reservation = pendingReleases.poll() ?: break
            drained += reservation
        }
        if (drained.isEmpty()) return
        try {
            var changed = false
            for (reservation in drained) {
                if (storage.releaseBinReservation(
                        clusterId,
                        reservation.cycleId,
                        reservation.claimId,
                        reservation.playerId
                    ) != null
                ) {
                    changed = true
                }
            }
            if (changed) loadedRevision.set(-1L)
        } catch (error: Throwable) {
            drained.forEach(pendingReleases::add)
            throw error
        }
    }

    private fun unavailableReservation(cycleId: String, playerId: UUID): ClusterBinReservation =
        ClusterBinReservation("", cycleId, playerId.toString(), "", -1, loadedRevision.get())
}

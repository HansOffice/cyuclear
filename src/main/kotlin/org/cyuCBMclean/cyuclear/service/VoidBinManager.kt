package org.cyuCBMclean.cyuclear.service

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.BinEntryRules
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import org.cyuCBMclean.cyuclear.cluster.ClusterBinReservation
import org.cyuCBMclean.cyuclear.cluster.ClusterManager
import org.cyuCBMclean.cyuclear.cluster.ItemStackCodec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object VoidBinManager {

    data class MenuSnapshot(
        val stackedItems: List<Pair<ItemStack, Int>>,
        val flatItems: List<ItemStack>
    )

    data class RecoveryItem(val item: ItemStack, val encodedItem: String?)

    private data class AggregatedRecovery(var amount: Int, val encodedItem: String?)

    private val storage = ConcurrentHashMap<ItemStack, Int>()
    private val manualItemKeys = ConcurrentHashMap.newKeySet<ItemStack>()
    private val claimCooldownUntil = ConcurrentHashMap<UUID, Long>()
    private val claimsInFlight = ConcurrentHashMap.newKeySet<UUID>()
    private val menuRefreshQueued = AtomicBoolean(false)
    private val lastIntegrityWarningAt = AtomicLong(0L)

    @Volatile
    private var viewController: VoidBinViewController? = null

    @Volatile
    private var sharedCycleId: String = ""

    @Volatile
    var expireTime: Long = 0L
        private set
    @Volatile
    var activeDurationSeconds: Int = 0
        private set

    fun bindViewController(controller: VoidBinViewController) {
        viewController = controller
    }

    fun beginScan() {
        clearLocalStorage(closeMenus = true)
        if (!Settings.clusterEnabled || !ClusterManager.isActive()) {
            sharedCycleId = ""
            return
        }

        val cycleId = ClusterManager.currentSynchronizedScanRunId()
            ?: ClusterManager.createManualBinCycleId()
        sharedCycleId = cycleId
        ClusterManager.beginSharedBinCycle(cycleId)
    }

    fun storeManual(item: ItemStack): Boolean {
        return storeManualBatch(listOf(item to item.amount))
    }

    fun storeManualBatch(items: Collection<Pair<ItemStack, Int>>): Boolean {
        if (Settings.clusterEnabled || items.isEmpty()) return false
        val normalizedItems = LinkedHashMap<ItemStack, Int>()
        for ((item, amount) in items) {
            if (item.type == org.bukkit.Material.AIR || amount <= 0) continue
            val key = normalized(item)
            val existing = normalizedItems.keys.firstOrNull { it.isSimilar(key) }
            if (existing == null) {
                normalizedItems[key] = amount
            } else {
                normalizedItems[existing] = (normalizedItems[existing] ?: 0).toLong()
                    .plus(amount.toLong())
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }
        }
        if (normalizedItems.isEmpty()) return false
        synchronized(manualItemKeys) {
            val newKeys = normalizedItems.keys.count { key -> !manualItemKeys.any { it.isSimilar(key) } }
            val limit = BinEntryRules.playerDepositMaxUniqueItems
            if (limit > 0 && manualItemKeys.size + newKeys > limit) return false
            for ((key, amount) in normalizedItems) {
                val manualKey = manualItemKeys.firstOrNull { it.isSimilar(key) } ?: key
                manualItemKeys.add(manualKey)
                storage.merge(key, amount) { old, added ->
                    (old.toLong() + added.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
            }
        }
        queueMenuRefresh()
        return true
    }

    fun prepareRecovery(item: ItemStack, amount: Int): RecoveryItem? {
        val snapshot = item.clone().apply { this.amount = amount.coerceAtLeast(1) }
        if (!Settings.clusterEnabled || !ClusterManager.isActive() || !isContainerItem(snapshot)) {
            return RecoveryItem(snapshot, null)
        }

        val encoded = runCatching { ItemStackCodec.encodeVerified(snapshot) }.getOrElse { error ->
            warnIntegrityFailure(error)
            return null
        }
        return RecoveryItem(snapshot, encoded)
    }

    fun storeBatch(items: Collection<RecoveryItem>) {
        if (items.isEmpty()) return

        val aggregated = HashMap<ItemStack, AggregatedRecovery>()
        for (recovery in items) {
            val key = normalized(recovery.item)
            val amount = recovery.item.amount.coerceAtLeast(1)
            val existing = aggregated[key]
            if (existing == null) {
                aggregated[key] = AggregatedRecovery(amount, recovery.encodedItem)
            } else {
                existing.amount += amount
            }
        }

        val cycleId = sharedCycleId.ifBlank { ClusterManager.currentSharedBinCycleId() }
        for ((item, recovery) in aggregated) {
            storage.merge(item, recovery.amount) { old, added -> old + added }
            if (cycleId.isNotBlank() && Settings.clusterEnabled) {
                ClusterManager.queueSharedBinItem(cycleId, item, recovery.amount, recovery.encodedItem)
            }
        }
        queueMenuRefresh()
    }

    fun take(item: ItemStack): Boolean = takeAmount(item, item.amount) == item.amount

    fun takeAmount(item: ItemStack, amount: Int): Int {
        val key = normalized(item)
        var taken = 0
        storage.computeIfPresent(key) { _, currentAmount ->
            taken = minOf(currentAmount, amount.coerceAtLeast(0))
            val remain = currentAmount - taken
            if (remain <= 0) null else remain
        }
        if (!storage.containsKey(key)) manualItemKeys.remove(key)
        if (taken > 0) queueMenuRefresh()
        return taken
    }

    internal fun takeAmountAsync(player: Player, item: ItemStack, amount: Int, callback: (ClusterBinReservation) -> Unit) {
        if (Settings.clusterEnabled) {
            ClusterManager.reserveSharedBinItem(player.uniqueId, item, amount, callback)
        } else {
            callback(ClusterBinReservation("", "", player.uniqueId.toString(), "", takeAmount(item, amount), 0L))
        }
    }

    internal fun completeClaim(reservation: ClusterBinReservation) {
        if (reservation.amount <= 0 || !Settings.clusterEnabled) return
        ClusterManager.completeSharedBinReservation(reservation)
    }

    internal fun releaseClaim(reservation: ClusterBinReservation, item: ItemStack) {
        if (reservation.amount <= 0) return
        if (Settings.clusterEnabled) {
            ClusterManager.releaseSharedBinReservation(reservation)
        } else {
            val key = normalized(item)
            storage.merge(key, reservation.amount) { old, added -> old + added }
            queueMenuRefresh()
        }
    }

    fun recoverPendingClaims(player: Player) {
        if (!Settings.clusterEnabled || !ClusterManager.isActive() || !claimsInFlight.add(player.uniqueId)) return
        ClusterManager.findSharedBinReservations(player.uniqueId) { reservations ->
            if (reservations == null || reservations.isEmpty()) {
                claimsInFlight.remove(player.uniqueId)
                return@findSharedBinReservations
            }
            CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
                try {
                    if (!player.isOnline) return@Runnable
                    var recoveredAmount = 0
                    for (reservation in reservations) {
                        val decoded = runCatching { ItemStackCodec.decode(reservation.encodedItem) }
                        if (decoded.isFailure) {
                            Cyuclear.instance.logger.warning(
                                "无法恢复跨服垃圾桶预约 ${reservation.claimId}：${decoded.exceptionOrNull()?.message}"
                            )
                            continue
                        }
                        val item = decoded.getOrThrow()
                        item.amount = reservation.amount
                        val leftover = player.inventory.addItem(item)
                        leftover.forEach { (_, remaining) -> player.world.dropItem(player.location, remaining) }
                        recoveredAmount += reservation.amount
                        completeClaim(reservation)
                    }
                    if (recoveredAmount > 0) {
                        player.sendMessage(Language.get("bin-claim-recovered", "amount" to recoveredAmount.toString()))
                    }
                } finally {
                    claimsInFlight.remove(player.uniqueId)
                }
            }, Runnable {
                claimsInFlight.remove(player.uniqueId)
            })
        }
    }

    fun beginClaim(player: Player): Boolean = claimsInFlight.add(player.uniqueId)

    fun finishClaim(player: Player) {
        claimsInFlight.remove(player.uniqueId)
    }

    fun getAmount(item: ItemStack): Int = storage[normalized(item)] ?: 0

    fun hasItems(): Boolean = storage.isNotEmpty()

    fun itemTypeCount(): Int = storage.size

    fun isOpen(): Boolean = Settings.binEnabled && (Settings.binAlwaysOpen || getRemainingSeconds() > 0)

    fun getStorageList(): List<Pair<ItemStack, Int>> = storage.map { (item, amount) -> item.clone() to amount }

    fun buildPages(): List<ItemStack> {
        val result = ArrayList<ItemStack>()
        for ((baseItem, totalAmount) in storage) {
            val maxSize = baseItem.maxStackSize.coerceAtLeast(1)
            var remaining = totalAmount
            while (remaining > 0) {
                val stack = baseItem.clone()
                stack.amount = remaining.coerceAtMost(maxSize)
                result.add(stack)
                remaining -= stack.amount
            }
        }
        return result
    }

    fun createMenuSnapshot(): MenuSnapshot = if (Settings.binStackedMode) {
        MenuSnapshot(getStorageList(), emptyList())
    } else {
        MenuSnapshot(emptyList(), buildPages())
    }

    fun openWindow(durationSeconds: Int) {
        if (durationSeconds <= 0) {
            clear()
            return
        }
        if (Settings.clusterEnabled) {
            val cycleId = sharedCycleId
            if (cycleId.isBlank()) {
                clearLocalStorage(closeMenus = true)
                return
            }
            activeDurationSeconds = durationSeconds
            expireTime = System.currentTimeMillis() + durationSeconds * 1000L
            ClusterManager.openSharedBin(cycleId, durationSeconds)
            return
        }

        activeDurationSeconds = durationSeconds
        expireTime = System.currentTimeMillis() + durationSeconds * 1000L
    }

    fun applyClusterExpiry(expireAtMillis: Long, durationSeconds: Int) {
        if (!Settings.clusterEnabled || expireAtMillis <= 0L) return
        expireTime = expireAtMillis
        activeDurationSeconds = maxOf(activeDurationSeconds, durationSeconds.coerceAtLeast(0))
    }

    fun replaceClusterSnapshot(snapshot: Map<ItemStack, Int>, expireAtMillis: Long) {
        if (!Settings.clusterEnabled) return
        storage.clear()
        manualItemKeys.clear()
        for ((item, amount) in snapshot) {
            if (amount > 0) storage[normalized(item)] = amount
        }
        expireTime = expireAtMillis.coerceAtLeast(0L)
        activeDurationSeconds = maxOf(activeDurationSeconds, getRemainingSeconds())
        if (expireTime == 0L) {
            BinNoticeManager.clearBossBar()
            closeOpenMenus()
        } else {
            queueMenuRefresh()
        }
    }

    fun applyClusterClaim(item: ItemStack, amount: Int) {
        if (amount <= 0) return
        takeAmount(item, amount)
    }

    fun getRemainingSeconds(now: Long = System.currentTimeMillis()): Int {
        if (expireTime == 0L) return 0
        return ((expireTime - now + 999L) / 1000L).toInt().coerceAtLeast(0)
    }

    fun getClaimCooldownRemainingSeconds(player: Player, now: Long = System.currentTimeMillis()): Int {
        if (!isClaimCooldownActiveFor(player)) return 0
        val until = claimCooldownUntil[player.uniqueId] ?: return 0
        val remainingMillis = until - now
        if (remainingMillis <= 0L) {
            claimCooldownUntil.remove(player.uniqueId, until)
            return 0
        }
        return ((remainingMillis + 999L) / 1000L).toInt()
    }

    fun startClaimCooldown(player: Player, now: Long = System.currentTimeMillis()): Int {
        if (!isClaimCooldownActiveFor(player)) {
            claimCooldownUntil.remove(player.uniqueId)
            return 0
        }
        val seconds = Settings.binClaimCooldownSeconds
        claimCooldownUntil[player.uniqueId] = now + seconds * 1000L
        return seconds
    }

    fun clear() {
        sharedCycleId = ""
        clearLocalStorage(closeMenus = true)
    }

    private fun clearLocalStorage(closeMenus: Boolean) {
        DepositBufferManager.returnAll("bin reset")
        storage.clear()
        manualItemKeys.clear()
        expireTime = 0L
        activeDurationSeconds = 0
        claimsInFlight.clear()
        BinNoticeManager.clearBossBar()
        if (closeMenus) closeOpenMenus()
    }

    private fun closeOpenMenus() {
        val controller = viewController ?: return
        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            controller.closeOpenMenus()
        })
    }

    private fun normalized(item: ItemStack): ItemStack = item.clone().apply { amount = 1 }

    private fun isContainerItem(item: ItemStack): Boolean {
        val material = item.type.name
        return material == "BUNDLE" || material.endsWith("_BUNDLE") || material.endsWith("_SHULKER_BOX")
    }

    private fun warnIntegrityFailure(error: Throwable) {
        val now = System.currentTimeMillis()
        val previous = lastIntegrityWarningAt.get()
        if (now - previous < 10000L || !lastIntegrityWarningAt.compareAndSet(previous, now)) return
        Cyuclear.instance.logger.warning(
            "跨服垃圾桶无法安全保存潜影盒或收纳袋，已保留原掉落物：${error.message}"
        )
    }

    private fun isClaimCooldownActiveFor(player: Player): Boolean {
        if (!Settings.binClaimCooldownEnabled || Settings.binClaimCooldownSeconds <= 0) return false
        val bypassPermission = Settings.binClaimCooldownBypassPermission
        return bypassPermission.isBlank() || !player.hasPermission(bypassPermission)
    }

    private fun queueMenuRefresh() {
        if (!menuRefreshQueued.compareAndSet(false, true)) return
        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            menuRefreshQueued.set(false)
            viewController?.refreshOpenMenus(createMenuSnapshot())
        })
    }
}

package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import org.cyuCBMclean.cyuclear.cluster.ItemStackCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DepositBufferManager {

    enum class StageStatus {
        STAGED,
        LIMIT,
        PERSIST_FAILED,
        INVALID
    }

    enum class ConfirmStatus {
        COMMITTED,
        NO_SESSION,
        EMPTY,
        DISABLED,
        CLUSTER_DISABLED,
        DENIED,
        LIMIT
    }

    data class ConfirmResult(
        val status: ConfirmStatus,
        val decision: PlayerDepositService.Decision? = null
    )

    data class ReturnResult(
        val returnedAmount: Int = 0,
        val remainingAmount: Int = 0,
        val complete: Boolean = true
    )

    enum class EntryReturnStatus {
        RETURNED,
        NO_SESSION,
        INVALID_ENTRY,
        NO_SPACE,
        PERSIST_FAILED
    }

    data class EntryReturnResult(
        val status: EntryReturnStatus,
        val returnedAmount: Int = 0,
        val remainingAmount: Int = 0,
        val bufferEmpty: Boolean = false
    )

    data class Snapshot(
        val sessionId: UUID,
        val items: List<Pair<ItemStack, Int>>
    )

    private enum class State {
        PENDING,
        COMMITTED
    }

    private class Session(
        val playerId: UUID,
        val sessionId: UUID,
        val items: LinkedHashMap<ItemStack, Int>,
        var state: State = State.PENDING,
        var suppressClose: Int = 0
    )

    private data class InventoryChange(
        val slot: Int,
        val before: ItemStack?,
        val after: ItemStack
    )

    private data class InventoryPlan(
        val changes: List<InventoryChange>,
        val acceptedAmount: Int
    )

    private const val DIRECTORY = "deposit-buffer"
    private const val FORMAT_VERSION = 1
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val locks = ConcurrentHashMap<UUID, Any>()

    fun stage(player: Player, source: ItemStack): StageStatus {
        if (source.type == Material.AIR || source.amount <= 0) return StageStatus.INVALID
        val playerId = player.uniqueId
        synchronized(lockFor(playerId)) {
            val session = sessionOrCreateLocked(playerId) ?: return StageStatus.PERSIST_FAILED
            if (session.state != State.PENDING) return StageStatus.PERSIST_FAILED

            val key = source.clone().apply { amount = 1 }
            val existing = session.items.keys.firstOrNull { it.isSimilar(key) }
            if (existing == null) {
                val limit = org.cyuCBMclean.cyuclear.config.BinEntryRules.playerDepositMaxUniqueItems
                if (limit > 0 && session.items.size >= limit) return StageStatus.LIMIT
            }

            val backup = copyItems(session.items)
            if (existing == null) {
                session.items[key] = source.amount.coerceAtLeast(1)
            } else {
                session.items[existing] = safeAdd(session.items[existing] ?: 0, source.amount)
            }
            if (!persistLocked(session)) {
                session.items.clear()
                session.items.putAll(backup)
                return StageStatus.PERSIST_FAILED
            }
            return StageStatus.STAGED
        }
    }

    fun confirm(player: Player, sessionId: UUID): ConfirmResult {
        val playerId = player.uniqueId
        synchronized(lockFor(playerId)) {
            val session = sessions[playerId] ?: return ConfirmResult(ConfirmStatus.NO_SESSION)
            if (session.sessionId != sessionId) return ConfirmResult(ConfirmStatus.NO_SESSION)
            if (session.state == State.COMMITTED) return ConfirmResult(ConfirmStatus.COMMITTED)
            if (!Settings.enabled || !Settings.binEnabled || !Settings.itemModuleEnabled || !Settings.binDepositBufferEnabled) {
                return ConfirmResult(ConfirmStatus.DISABLED)
            }
            if (Settings.clusterEnabled) return ConfirmResult(ConfirmStatus.CLUSTER_DISABLED)
            if (session.items.isEmpty()) return ConfirmResult(ConfirmStatus.EMPTY)

            for (item in session.items.keys) {
                val decision = PlayerDepositService.check(player, item)
                if (!decision.allowed) return ConfirmResult(ConfirmStatus.DENIED, decision)
            }

            val entries = session.items.map { it.key.clone() to it.value }
            if (!VoidBinManager.storeManualBatch(entries)) return ConfirmResult(ConfirmStatus.LIMIT)

            session.state = State.COMMITTED
            persistLocked(session)
            return ConfirmResult(ConfirmStatus.COMMITTED)
        }
    }

    fun cancel(player: Player, sessionId: UUID): ReturnResult {
        val playerId = player.uniqueId
        synchronized(lockFor(playerId)) {
            val session = sessions[playerId] ?: return ReturnResult()
            if (session.sessionId != sessionId) return ReturnResult()
            session.suppressClose++
            if (session.state == State.COMMITTED) {
                finishCommittedLocked(session)
                return ReturnResult()
            }
            return returnToInventoryLocked(player, session)
        }
    }

    fun snapshot(player: Player, sessionId: UUID): Snapshot? {
        val playerId = player.uniqueId
        synchronized(lockFor(playerId)) {
            val session = sessions[playerId] ?: return null
            if (session.sessionId != sessionId || session.state != State.PENDING) return null
            return Snapshot(session.sessionId, session.items.map { it.key.clone() to it.value })
        }
    }

    fun returnEntry(
        player: Player,
        sessionId: UUID,
        entryIndex: Int,
        expectedItem: ItemStack,
        requestedAmount: Int
    ): EntryReturnResult {
        if (!player.isOnline || requestedAmount <= 0) {
            return EntryReturnResult(EntryReturnStatus.NO_SESSION)
        }

        val playerId = player.uniqueId
        synchronized(lockFor(playerId)) {
            val session = sessions[playerId] ?: return EntryReturnResult(EntryReturnStatus.NO_SESSION)
            if (session.sessionId != sessionId || session.state != State.PENDING) {
                return EntryReturnResult(EntryReturnStatus.NO_SESSION)
            }

            val entry = session.items.entries.elementAtOrNull(entryIndex)
                ?: return EntryReturnResult(EntryReturnStatus.INVALID_ENTRY)
            if (!entry.key.isSimilar(expectedItem)) {
                return EntryReturnResult(EntryReturnStatus.INVALID_ENTRY)
            }

            val availableAmount = entry.value.coerceAtLeast(0)
            if (availableAmount <= 0) return EntryReturnResult(EntryReturnStatus.INVALID_ENTRY)

            val amountToReturn = minOf(availableAmount, requestedAmount)
            val plan = planInventoryAddition(player, entry.key, amountToReturn)
            if (plan.acceptedAmount <= 0) {
                return EntryReturnResult(
                    status = EntryReturnStatus.NO_SPACE,
                    remainingAmount = availableAmount
                )
            }

            val backup = copyItems(session.items)
            val remainingAmount = availableAmount - plan.acceptedAmount
            if (remainingAmount <= 0) {
                session.items.remove(entry.key)
            } else {
                session.items[entry.key] = remainingAmount
            }

            val applied = ArrayList<InventoryChange>(plan.changes.size)
            return try {
                for (change in plan.changes) {
                    player.inventory.setItem(change.slot, change.after.clone())
                    applied += change
                }
                if (!persistLocked(session)) {
                    restoreInventory(player, applied)
                    session.items.clear()
                    session.items.putAll(backup)
                    return EntryReturnResult(
                        status = EntryReturnStatus.PERSIST_FAILED,
                        remainingAmount = availableAmount
                    )
                }

                val bufferEmpty = session.items.isEmpty()
                if (bufferEmpty) {
                    sessions.remove(session.playerId, session)
                    deleteFile(bufferFile(session.playerId))
                }
                EntryReturnResult(
                    status = EntryReturnStatus.RETURNED,
                    returnedAmount = plan.acceptedAmount,
                    remainingAmount = remainingAmount,
                    bufferEmpty = bufferEmpty
                )
            } catch (_: Throwable) {
                restoreInventory(player, applied)
                session.items.clear()
                session.items.putAll(backup)
                persistLocked(session)
                EntryReturnResult(
                    status = EntryReturnStatus.PERSIST_FAILED,
                    remainingAmount = availableAmount
                )
            }
        }
    }

    fun hasPending(player: Player): Boolean {
        val playerId = player.uniqueId
        synchronized(lockFor(playerId)) {
            val session = sessionOrLoadLocked(playerId) ?: return false
            return session.state == State.PENDING && session.items.isNotEmpty()
        }
    }

    fun sessionId(player: Player): UUID? {
        val playerId = player.uniqueId
        synchronized(lockFor(playerId)) {
            val session = sessionOrLoadLocked(playerId) ?: return null
            return session.takeIf { it.state == State.PENDING && it.items.isNotEmpty() }?.sessionId
        }
    }

    fun pageCount(player: Player, sessionId: UUID, pageSize: Int): Int {
        val snapshot = snapshot(player, sessionId) ?: return 1
        val size = pageSize.coerceAtLeast(1)
        return maxOf(1, (snapshot.items.size + size - 1) / size)
    }

    fun prepareTransition(player: Player, sessionId: UUID) {
        synchronized(lockFor(player.uniqueId)) {
            val session = sessions[player.uniqueId] ?: return
            if (session.sessionId == sessionId && session.state == State.PENDING) session.suppressClose++
        }
    }

    fun onMenuClosed(player: Player, sessionId: UUID): ReturnResult {
        val playerId = player.uniqueId
        synchronized(lockFor(playerId)) {
            val session = sessions[playerId] ?: return ReturnResult()
            if (session.sessionId != sessionId) return ReturnResult()
            if (session.suppressClose > 0) {
                session.suppressClose--
                return ReturnResult()
            }
            if (session.state == State.COMMITTED) {
                finishCommittedLocked(session)
                return ReturnResult()
            }
            return returnToInventoryLocked(player, session)
        }
    }

    fun recover(player: Player) {
        val playerId = player.uniqueId
        val result = synchronized(lockFor(playerId)) {
            val session = sessionOrLoadLocked(playerId)
            if (session == null) {
                if (bufferFile(playerId).exists()) {
                    Cyuclear.instance.logger.warning("无法读取玩家 ${player.name} 的个人投放缓冲区，文件已保留：${bufferFile(playerId).path}")
                    player.sendMessage(org.cyuCBMclean.cyuclear.config.Language.get("bin-buffer-recovery-failed"))
                }
                return@synchronized ReturnResult()
            }
            if (session.state == State.COMMITTED) {
                finishCommittedLocked(session)
                return@synchronized ReturnResult()
            }
            return@synchronized returnToInventoryLocked(player, session)
        }
        if (result.returnedAmount > 0) {
            player.sendMessage(
                org.cyuCBMclean.cyuclear.config.Language.get(
                    "bin-buffer-recovered",
                    "amount" to result.returnedAmount.toString()
                )
            )
        }
        if (result.remainingAmount > 0) {
            player.sendMessage(org.cyuCBMclean.cyuclear.config.Language.get("bin-buffer-return-full"))
        }
    }

    fun onQuit(player: Player) {
        synchronized(lockFor(player.uniqueId)) {
            val session = sessionOrLoadLocked(player.uniqueId) ?: return
            persistLocked(session)
        }
    }

    fun onSettingsReload() {
        returnAll("settings")
    }

    fun returnAll(reason: String) {
        for ((playerId, session) in sessions) {
            synchronized(lockFor(playerId)) {
                persistLocked(session)
            }
        }
        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
                    returnForReset(player, reason)
                })
            }
        })
    }

    fun shutdown() {
        for ((playerId, session) in sessions) {
            synchronized(lockFor(playerId)) {
                persistLocked(session)
            }
        }
    }

    private fun returnForReset(player: Player, reason: String) {
        var bufferOpen = false
        val result = synchronized(lockFor(player.uniqueId)) {
            val session = sessions[player.uniqueId] ?: return@synchronized ReturnResult()
            val openView = player.openInventory.topInventory.holder as? DepositBufferSessionView
            bufferOpen = openView?.let { it.playerId == player.uniqueId && it.sessionId == session.sessionId } == true
            if (bufferOpen) {
                session.suppressClose++
            }
            val returned = if (session.state == State.COMMITTED) {
                finishCommittedLocked(session)
                ReturnResult()
            } else {
                returnToInventoryLocked(player, session)
            }
            returned
        }
        if (player.isOnline && bufferOpen) {
            player.closeInventory()
        }
        if (result.remainingAmount > 0) {
            player.sendMessage(org.cyuCBMclean.cyuclear.config.Language.get("bin-buffer-return-full"))
        } else if (result.returnedAmount > 0 && reason != "settings") {
            player.sendMessage(
                org.cyuCBMclean.cyuclear.config.Language.get(
                    "bin-buffer-recovered",
                    "amount" to result.returnedAmount.toString()
                )
            )
        }
    }

    private fun sessionOrCreateLocked(playerId: UUID): Session? {
        val existing = sessionOrLoadLocked(playerId)
        if (existing != null) return existing
        if (bufferFile(playerId).exists()) return null
        return Session(playerId, UUID.randomUUID(), LinkedHashMap()).also { sessions[playerId] = it }
    }

    private fun sessionOrLoadLocked(playerId: UUID): Session? {
        sessions[playerId]?.let { return it }
        val file = bufferFile(playerId)
        if (!file.exists()) return null
        val loaded = loadLocked(playerId, file) ?: return null
        sessions.putIfAbsent(playerId, loaded)
        return sessions[playerId]
    }

    private fun loadLocked(playerId: UUID, file: File): Session? {
        val config = runCatching { YamlConfiguration.loadConfiguration(file) }.getOrElse { error ->
            Cyuclear.instance.logger.warning("无法读取个人投放缓冲区 ${file.path}：${error.message}")
            return null
        }
        val state = config.getString("state", "pending")?.trim()?.lowercase()
        if (state == "committed") {
            deleteFile(file)
            return null
        }
        val sessionId = config.getString("session-id")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID()
        val items = LinkedHashMap<ItemStack, Int>()
        for (entry in config.getMapList("items")) {
            val encoded = entry["item"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val amount = amountOf(entry["amount"]) ?: return null
            val decoded = runCatching { ItemStackCodec.decode(encoded) }.getOrElse { error ->
                Cyuclear.instance.logger.warning("无法恢复个人投放缓冲区物品：${error.message}")
                return null
            }
            val key = decoded.clone().apply { this.amount = 1 }
            val existing = items.keys.firstOrNull { it.isSimilar(key) }
            if (existing == null) items[key] = amount else items[existing] = safeAdd(items[existing] ?: 0, amount)
        }
        if (items.isEmpty()) {
            deleteFile(file)
            return null
        }
        return Session(playerId, sessionId, items)
    }

    private fun planInventoryAddition(player: Player, item: ItemStack, requestedAmount: Int): InventoryPlan {
        var remaining = requestedAmount.coerceAtLeast(0)
        val changes = ArrayList<InventoryChange>()
        for (slot in 0 until 36) {
            if (remaining <= 0) break

            val current = player.inventory.getItem(slot)
            val capacity = when {
                current == null || current.type == Material.AIR -> item.maxStackSize.coerceAtLeast(1)
                current.isSimilar(item) -> (current.maxStackSize - current.amount).coerceAtLeast(0)
                else -> 0
            }
            if (capacity <= 0) continue

            val amount = minOf(capacity, remaining)
            val after = if (current == null || current.type == Material.AIR) {
                item.clone().apply { this.amount = amount }
            } else {
                current.clone().apply { this.amount += amount }
            }
            changes += InventoryChange(slot, current?.clone(), after)
            remaining -= amount
        }
        return InventoryPlan(changes, requestedAmount.coerceAtLeast(0) - remaining)
    }

    private fun restoreInventory(player: Player, changes: List<InventoryChange>) {
        for (change in changes.asReversed()) {
            player.inventory.setItem(change.slot, change.before?.clone() ?: ItemStack(Material.AIR))
        }
    }

    private fun returnToInventoryLocked(player: Player, session: Session): ReturnResult {
        if (!player.isOnline) {
            val remaining = totalAmount(session.items)
            persistLocked(session)
            return ReturnResult(0, remaining, false)
        }
        val before = totalAmount(session.items)
        val leftovers = ArrayList<ItemStack>()
        for ((item, amount) in session.items) {
            val give = item.clone().apply { this.amount = amount }
            leftovers += player.inventory.addItem(give).values
        }
        val remainingItems = LinkedHashMap<ItemStack, Int>()
        for (leftover in leftovers) {
            if (leftover.type == Material.AIR || leftover.amount <= 0) continue
            val key = leftover.clone().apply { amount = 1 }
            val existing = remainingItems.keys.firstOrNull { it.isSimilar(key) }
            if (existing == null) remainingItems[key] = leftover.amount
            else remainingItems[existing] = safeAdd(remainingItems[existing] ?: 0, leftover.amount)
        }
        session.items.clear()
        session.items.putAll(remainingItems)
        val remaining = totalAmount(remainingItems)
        if (remaining <= 0) {
            sessions.remove(session.playerId, session)
            deleteFile(bufferFile(session.playerId))
        } else {
            persistLocked(session)
        }
        return ReturnResult((before - remaining).coerceAtLeast(0), remaining, remaining <= 0)
    }

    private fun finishCommittedLocked(session: Session) {
        if (!persistLocked(session)) return
        sessions.remove(session.playerId, session)
        deleteFile(bufferFile(session.playerId))
    }

    private fun persistLocked(session: Session): Boolean {
        val directory = File(Cyuclear.instance.dataFolder, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return false
        val target = bufferFile(session.playerId)
        val temporary = File(directory, ".${session.playerId}.${session.sessionId}.tmp")
        return runCatching {
            val config = YamlConfiguration()
            config.set("format-version", FORMAT_VERSION)
            config.set("session-id", session.sessionId.toString())
            config.set("state", if (session.state == State.COMMITTED) "committed" else "pending")
            config.set(
                "items",
                session.items.map { (item, amount) ->
                    linkedMapOf<String, Any>(
                        "item" to ItemStackCodec.encode(item),
                        "amount" to amount
                    )
                }
            )
            config.save(temporary)
            moveAtomically(temporary, target)
            true
        }.getOrElse { error ->
            temporary.delete()
            Cyuclear.instance.logger.warning("保存个人投放缓冲区失败：${error.message}")
            false
        }
    }

    private fun bufferFile(playerId: UUID): File = File(File(Cyuclear.instance.dataFolder, DIRECTORY), "$playerId.yml")

    private fun deleteFile(file: File) {
        runCatching { Files.deleteIfExists(file.toPath()) }
    }

    private fun moveAtomically(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun lockFor(playerId: UUID): Any = locks.computeIfAbsent(playerId) { Any() }

    private fun copyItems(source: Map<ItemStack, Int>): LinkedHashMap<ItemStack, Int> = LinkedHashMap<ItemStack, Int>().also { target ->
        source.forEach { (item, amount) -> target[item.clone()] = amount }
    }

    private fun totalAmount(items: Map<ItemStack, Int>): Int {
        var total = 0L
        for (amount in items.values) total = (total + amount.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong())
        return total.toInt()
    }

    private fun amountOf(value: Any?): Int? {
        val amount = when (value) {
            is Number -> value.toInt()
            else -> value?.toString()?.toIntOrNull()
        } ?: return null
        return amount.takeIf { it > 0 }
    }

    private fun safeAdd(first: Int, second: Int): Int {
        return (first.toLong() + second.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

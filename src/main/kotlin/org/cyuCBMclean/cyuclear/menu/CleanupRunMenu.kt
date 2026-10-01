package org.cyuCBMclean.cyuclear.menu

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.service.CleanupRunManager
import org.cyuCBMclean.cyuclear.util.ColorUtils

object CleanupRunMenu : Listener {
    private enum class Screen {
        RUNS,
        RECOVERY
    }

    private class Holder(
        val screen: Screen,
        val runId: String? = null,
        val page: Int = 0,
        val confirmIndex: Int? = null,
        val confirmUntil: Long = 0L
    ) : InventoryHolder {
        lateinit var menuInventory: Inventory
        val indexes = HashMap<Int, Int>()
        override fun getInventory(): Inventory = menuInventory
    }

    private lateinit var runsTemplate: ConfiguredMenu
    private lateinit var recoveryTemplate: ConfiguredMenu

    fun load() {
        runsTemplate = ConfiguredMenu.load("menu/runs.yml")
        recoveryTemplate = ConfiguredMenu.load("menu/recovery.yml")
    }

    fun openRuns(player: Player, requestedPage: Int) {
        val contentSlots = runsTemplate.slots('*')
        val pageSize = maxOf(1, contentSlots.size)
        val (runs, totalPages) = CleanupRunManager.list(requestedPage, pageSize)
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val title = runsTemplate.title
            .replace("{page}", (page + 1).toString())
            .replace("{total}", totalPages.toString())
        val holder = Holder(Screen.RUNS, page = page)
        val inventory = Bukkit.createInventory(holder, runsTemplate.size, title)
        holder.menuInventory = inventory
        drawTemplate(inventory, runsTemplate, player)
        runs.forEachIndexed { index, run ->
            val slot = contentSlots.getOrNull(index) ?: return@forEachIndexed
            inventory.setItem(slot, runItem(run))
            holder.indexes[slot] = index
        }
        drawRunButtons(inventory, page, totalPages, runs.isEmpty())
        player.openInventory(inventory)
    }

    fun openRecovery(
        player: Player,
        runId: String,
        requestedPage: Int,
        confirmIndex: Int? = null,
        confirmUntil: Long = 0L
    ) {
        val contentSlots = recoveryTemplate.slots('*')
        val pageSize = maxOf(1, contentSlots.size)
        val recoveryPage = CleanupRunManager.recoveryPage(runId, requestedPage, pageSize)
        if (recoveryPage == null) {
            player.sendMessage(Language.get("run-not-found"))
            openRuns(player, 0)
            return
        }
        val entries = recoveryPage.entries
        val totalPages = recoveryPage.totalPages
        val page = recoveryPage.page
        val holder = Holder(Screen.RECOVERY, runId, page, confirmIndex, confirmUntil)
        val title = recoveryTemplate.title
            .replace("{run}", runId)
            .replace("{page}", (page + 1).toString())
            .replace("{total}", totalPages.toString())
        val inventory = Bukkit.createInventory(holder, recoveryTemplate.size, title)
        holder.menuInventory = inventory
        drawTemplate(inventory, recoveryTemplate, player)
        entries.forEachIndexed { offset, entry ->
            val slot = contentSlots[offset]
            inventory.setItem(slot, recoveryItem(entry, entry.index == confirmIndex && System.currentTimeMillis() <= confirmUntil))
            holder.indexes[slot] = entry.index
        }
        drawRecoveryButtons(inventory, page, totalPages, entries.isEmpty())
        player.openInventory(inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        if (event.action == InventoryAction.COLLECT_TO_CURSOR || event.click == ClickType.DOUBLE_CLICK) {
            event.isCancelled = true
            return
        }
        if (event.clickedInventory == event.view.bottomInventory) {
            if (event.isShiftClick) {
                event.isCancelled = true
            }
            return
        }
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        if (!player.hasPermission("cyuclear.admin")) {
            player.closeInventory()
            player.sendMessage(Language.get("no-permission"))
            return
        }
        when (holder.screen) {
            Screen.RUNS -> clickRuns(player, holder, event.rawSlot)
            Screen.RECOVERY -> clickRecovery(player, holder, event.rawSlot)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder && event.rawSlots.any { it < event.view.topInventory.size }) {
            event.isCancelled = true
        }
    }

    private fun clickRuns(player: Player, holder: Holder, slot: Int) {
        val entry = holder.indexes[slot]
        if (entry != null) {
            val pageSize = maxOf(1, runsTemplate.slots('*').size)
            val (runs) = CleanupRunManager.list(holder.page, pageSize)
            val selected = runs.getOrNull(entry)
            if (selected == null) {
                openRuns(player, holder.page)
                return
            }
            openRecovery(player, selected.id, 0)
            return
        }
        if (runsTemplate.dispatch(
                player,
                slot,
                MenuActionBindings(
                    refresh = { target -> openRuns(target, holder.page) },
                    openPreviousPage = { target -> openRuns(target, holder.page - 1) },
                    openNextPage = { target -> openRuns(target, holder.page + 1) },
                    defaultClick = { target -> clickRunsDefault(target, holder.page, slot) }
                )
            )
        ) return
        clickRunsDefault(player, holder.page, slot)
    }

    private fun clickRunsDefault(player: Player, page: Int, slot: Int) {
        when {
            slot in runsTemplate.slots('P') -> openRuns(player, page - 1)
            slot in runsTemplate.slots('N') -> openRuns(player, page + 1)
            slot in runsTemplate.slots('B') -> AdminMenu.open(player)
            slot in runsTemplate.slots('X') -> player.closeInventory()
        }
    }

    private fun clickRecovery(player: Player, holder: Holder, slot: Int) {
        val runId = holder.runId ?: return
        val index = holder.indexes[slot]
        if (index != null) {
            val now = System.currentTimeMillis()
            if (holder.confirmIndex != index || now > holder.confirmUntil) {
                player.sendMessage(Language.get("recovery-confirm-hint"))
                openRecovery(player, runId, holder.page, index, now + 10_000L)
                return
            }
            val result = CleanupRunManager.claim(player, runId, index)
            when (result.status) {
                CleanupRunManager.ClaimStatus.CLAIMED -> player.sendMessage(Language.get("recovery-claimed", "amount" to result.amount.toString()))
                CleanupRunManager.ClaimStatus.NOT_READY -> player.sendMessage(Language.get("recovery-not-ready"))
                CleanupRunManager.ClaimStatus.EXPIRED -> player.sendMessage(Language.get("recovery-expired"))
                CleanupRunManager.ClaimStatus.ALREADY_CLAIMED -> player.sendMessage(Language.get("recovery-claimed-before"))
                CleanupRunManager.ClaimStatus.INVALID_ITEM -> player.sendMessage(Language.get("recovery-invalid-item"))
                CleanupRunManager.ClaimStatus.NO_SPACE -> player.sendMessage(Language.get("recovery-no-space"))
                CleanupRunManager.ClaimStatus.SAVE_FAILED -> player.sendMessage(Language.get("recovery-save-failed"))
                CleanupRunManager.ClaimStatus.NOT_FOUND -> player.sendMessage(Language.get("run-not-found"))
            }
            openRecovery(player, runId, holder.page)
            return
        }
        if (recoveryTemplate.dispatch(
                player,
                slot,
                MenuActionBindings(
                    refresh = { target -> openRecovery(target, runId, holder.page) },
                    openPreviousPage = { target -> openRecovery(target, runId, holder.page - 1) },
                    openNextPage = { target -> openRecovery(target, runId, holder.page + 1) },
                    defaultClick = { target -> clickRecoveryDefault(target, runId, holder.page, slot) }
                )
            )
        ) return
        clickRecoveryDefault(player, runId, holder.page, slot)
    }

    private fun clickRecoveryDefault(player: Player, runId: String, page: Int, slot: Int) {
        when {
            slot in recoveryTemplate.slots('P') -> openRecovery(player, runId, page - 1)
            slot in recoveryTemplate.slots('N') -> openRecovery(player, runId, page + 1)
            slot in recoveryTemplate.slots('B') -> openRuns(player, 0)
            slot in recoveryTemplate.slots('X') -> player.closeInventory()
        }
    }

    private fun runItem(run: CleanupRunManager.RunView): ItemStack {
        val isEn = Language.isEnglish
        val material = when {
            run.status == CleanupRunManager.Status.RUNNING -> Material.matchMaterial("CLOCK")
            run.pendingRecoveryEntries > 0 -> Material.matchMaterial("CHEST")
            else -> Material.matchMaterial("HOPPER")
        } ?: Material.STONE
        val delimiter = if (isEn) ", " else "、"
        val itemReasons = run.itemReasons.take(2).joinToString(delimiter) { "${it.title} ${it.count}" }
        val entityReasons = run.entityReasons.take(2).joinToString(delimiter) { "${it.title} ${it.count}" }
        return ItemStack(material).apply {
            itemMeta = itemMeta?.also { meta ->
                meta.setDisplayName(ColorUtils.color("&#38BDF8${CleanupRunManager.originText(run.origin)} &#8A96A8#&#D7DEE8${run.id}"))
                meta.lore = if (isEn) {
                    listOf(
                        "&#8A96A8Status &#D7DEE8${run.status.display}",
                        "&#8A96A8Time &#D7DEE8${CleanupRunManager.formatTime(run.startedAt)}",
                        "&#8A96A8Chunks &#D7DEE8${run.processedChunks}/${run.queuedChunks}",
                        "&#8A96A8Cleaned &#8A96A8Items &#D7DEE8${run.removedItems} &#5B6472| &#8A96A8Entities &#D7DEE8${run.removedEntities}",
                        "&#8A96A8Recovery &#D7DEE8${run.pendingRecoveryEntries}/${run.recoveryEntries}",
                        if (run.slowestWorld != null) "&#8A96A8Slowest Chunk &#D7DEE8${run.slowestWorld} ${run.slowestChunkX},${run.slowestChunkZ} &#5B6472| &#D7DEE8${run.slowestChunkMillis}ms" else "",
                        if (run.failedChunks > 0) "&#F87171Failed Chunks &#D7DEE8${run.failedChunks}" else "",
                        run.failureMessage?.let { "&#F87171Exception &#D7DEE8${it.take(72)}" } ?: "",
                        if (itemReasons.isNotEmpty()) "&#8A96A8Item Reasons &#D7DEE8$itemReasons" else "",
                        if (entityReasons.isNotEmpty()) "&#8A96A8Entity Reasons &#D7DEE8$entityReasons" else "",
                        if (run.skippedRecoveryEntries > 0L) "&#FBBF24Skipped Entries &#D7DEE8${run.skippedRecoveryEntries}" else "",
                        "",
                        "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                        "&#D7DEE8› &#8A96A8Left-Click to inspect"
                    )
                } else {
                    listOf(
                        "&#8A96A8状态 &#D7DEE8${run.status.display}",
                        "&#8A96A8时间 &#D7DEE8${CleanupRunManager.formatTime(run.startedAt)}",
                        "&#8A96A8区块 &#D7DEE8${run.processedChunks}/${run.queuedChunks}",
                        "&#8A96A8清理 &#8A96A8掉落物 &#D7DEE8${run.removedItems} &#5B6472| &#8A96A8实体 &#D7DEE8${run.removedEntities}",
                        "&#8A96A8恢复物品 &#D7DEE8${run.pendingRecoveryEntries}/${run.recoveryEntries}",
                        if (run.slowestWorld != null) "&#8A96A8最慢区块 &#D7DEE8${run.slowestWorld} ${run.slowestChunkX},${run.slowestChunkZ} &#5B6472| &#D7DEE8${run.slowestChunkMillis}ms" else "",
                        if (run.failedChunks > 0) "&#F87171失败区块 &#D7DEE8${run.failedChunks}" else "",
                        run.failureMessage?.let { "&#F87171异常 &#D7DEE8${it.take(72)}" } ?: "",
                        if (itemReasons.isNotEmpty()) "&#8A96A8掉落原因 &#D7DEE8$itemReasons" else "",
                        if (entityReasons.isNotEmpty()) "&#8A96A8实体原因 &#D7DEE8$entityReasons" else "",
                        if (run.skippedRecoveryEntries > 0L) "&#FBBF24未记录条目 &#D7DEE8${run.skippedRecoveryEntries}" else "",
                        "",
                        "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                        "&#D7DEE8› &#8A96A8左键查看"
                    )
                }.filter { it.isNotEmpty() }.map(ColorUtils::color)
            }
        }
    }

    private fun recoveryItem(entry: CleanupRunManager.RecoveryView, confirming: Boolean): ItemStack {
        val isEn = Language.isEnglish
        val item = if (entry.claimed) {
            ItemStack(Material.matchMaterial("GRAY_DYE") ?: Material.STONE)
        } else {
            entry.item?.clone() ?: ItemStack(Material.matchMaterial("PAPER") ?: Material.STONE)
        }
        if (!entry.claimed && entry.item != null) item.amount = minOf(entry.amount, item.maxStackSize.coerceAtLeast(1))
        val meta = item.itemMeta
        if (meta != null) {
            if (entry.claimed) meta.setDisplayName(ColorUtils.color(if (isEn) "&#5B6472Claimed · ${entry.itemId}" else "&#5B6472已领取 · ${entry.itemId}"))
            meta.lore = if (isEn) {
                listOf(
                    "&#8A96A8Amount &#D7DEE8${entry.amount}",
                    "&#8A96A8Location &#D7DEE8${entry.world} ${entry.x}, ${entry.y}, ${entry.z}",
                    "&#8A96A8Reason &#D7DEE8${entry.reason}",
                    if (entry.claimed) "&#8A96A8Claimed By &#D7DEE8${entry.claimedBy}" else "",
                    "",
                    "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                    if (confirming) "&#FBBF24› Click again to confirm claim" else if (!entry.claimed) "&#D7DEE8› &#8A96A8Left-Click to claim to inventory" else ""
                )
            } else {
                listOf(
                    "&#8A96A8数量 &#D7DEE8${entry.amount}",
                    "&#8A96A8来源 &#D7DEE8${entry.world} ${entry.x}, ${entry.y}, ${entry.z}",
                    "&#8A96A8原因 &#D7DEE8${entry.reason}",
                    if (entry.claimed) "&#8A96A8领取人 &#D7DEE8${entry.claimedBy}" else "",
                    "",
                    "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                    if (confirming) "&#FBBF24› 再次左键确认领取" else if (!entry.claimed) "&#D7DEE8› &#8A96A8左键领取到背包" else ""
                )
            }.filter { it.isNotEmpty() }.map(ColorUtils::color)
            item.itemMeta = meta
        }
        return item
    }

    private fun drawTemplate(inventory: Inventory, template: ConfiguredMenu, player: Player) {
        for ((row, line) in template.layout.withIndex()) {
            for (column in 0 until minOf(9, line.length)) {
                val symbol = line[column]
                if (symbol == '*' || symbol == ' ') continue
                val item = template.item(symbol, player) ?: continue
                inventory.setItem(row * 9 + column, item)
            }
        }
    }

    private fun drawRunButtons(inventory: Inventory, page: Int, totalPages: Int, empty: Boolean) {
        val isEn = Language.isEnglish
        setLore(inventory, runsTemplate.slots('P'), if (page > 0) listOf(if (isEn) "&#D7DEE8› &#8A96A8Left-Click for prev page" else "&#D7DEE8› &#8A96A8左键上一页") else listOf(if (isEn) "&#5B6472First page" else "&#5B6472已经是第一页"))
        setLore(inventory, runsTemplate.slots('N'), if (page < totalPages - 1) listOf(if (isEn) "&#D7DEE8› &#8A96A8Left-Click for next page" else "&#D7DEE8› &#8A96A8左键下一页") else listOf(if (isEn) "&#5B6472Last page" else "&#5B6472已经是最后一页"))
        if (empty) setEmpty(inventory, runsTemplate.slots('*').firstOrNull(), if (isEn) "No cleanup runs recorded" else "当前还没有清理批次")
    }

    private fun drawRecoveryButtons(inventory: Inventory, page: Int, totalPages: Int, empty: Boolean) {
        val isEn = Language.isEnglish
        setLore(inventory, recoveryTemplate.slots('P'), if (page > 0) listOf(if (isEn) "&#D7DEE8› &#8A96A8Left-Click for prev page" else "&#D7DEE8› &#8A96A8左键上一页") else listOf(if (isEn) "&#5B6472First page" else "&#5B6472已经是第一页"))
        setLore(inventory, recoveryTemplate.slots('N'), if (page < totalPages - 1) listOf(if (isEn) "&#D7DEE8› &#8A96A8Left-Click for next page" else "&#D7DEE8› &#8A96A8左键下一页") else listOf(if (isEn) "&#5B6472Last page" else "&#5B6472已经是最后一页"))
        if (empty) setEmpty(inventory, recoveryTemplate.slots('*').firstOrNull(), if (isEn) "No recovery items captured for this run" else "这个批次没有保存恢复物品")
    }

    private fun setEmpty(inventory: Inventory, slot: Int?, text: String) {
        if (slot == null) return
        val item = ItemStack(Material.matchMaterial("PAPER") ?: Material.STONE)
        item.itemMeta = item.itemMeta?.also { meta -> meta.setDisplayName(ColorUtils.color("&#8A96A8$text")) }
        inventory.setItem(slot, item)
    }

    private fun setLore(inventory: Inventory, slots: List<Int>, lines: List<String>) {
        for (slot in slots) {
            val item = inventory.getItem(slot) ?: continue
            val meta = item.itemMeta ?: continue
            meta.lore = lines.map(ColorUtils::color)
            item.itemMeta = meta
        }
    }
}

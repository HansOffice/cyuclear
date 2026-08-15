package org.cyuCBMclean.cyuclear.menu

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.DepositBufferManager
import org.cyuCBMclean.cyuclear.service.DepositBufferSessionView
import org.cyuCBMclean.cyuclear.service.PlayerDepositService
import org.cyuCBMclean.cyuclear.service.SoundNoticeManager
import java.util.UUID

object DepositBufferMenu : Listener {

    class Holder(
        override val playerId: UUID,
        override val sessionId: UUID,
        val page: Int
    ) : InventoryHolder, DepositBufferSessionView {
        val entryItems = HashMap<Int, ItemStack>()
        lateinit var menuInventory: Inventory
        override fun getInventory(): Inventory = menuInventory
    }

    private lateinit var template: ConfiguredMenu

    fun load() {
        template = ConfiguredMenu.load("menu/deposit-buffer.yml")
    }

    fun open(player: Player, requestedPage: Int = 0) {
        val sessionId = DepositBufferManager.sessionId(player)
        if (sessionId == null) {
            player.sendMessage(Language.get("bin-buffer-empty"))
            return
        }
        open(player, sessionId, requestedPage)
    }

    private fun open(player: Player, sessionId: UUID, requestedPage: Int) {
        val contentSlots = template.slots('*')
        val totalPages = DepositBufferManager.pageCount(player, sessionId, contentSlots.size)
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val holder = Holder(player.uniqueId, sessionId, page)
        val title = template.title
            .replace("{page}", (page + 1).toString())
            .replace("{total}", totalPages.toString())
        val inventory = Bukkit.createInventory(holder, template.size, title)
        holder.menuInventory = inventory
        drawTemplate(inventory, player)
        render(holder, player)
        player.openInventory(inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return

        if (event.clickedInventory == event.view.bottomInventory) {
            stageFromInventory(event, player, holder)
            return
        }
        if (event.clickedInventory != event.view.topInventory) return
        val slot = event.rawSlot
        if (template.dispatch(
                player,
                slot,
                MenuActionBindings(
                    refresh = { target -> refresh(target, holder) },
                    openPreviousPage = { target -> navigate(target, holder, holder.page - 1) },
                    openNextPage = { target -> navigate(target, holder, holder.page + 1) },
                    defaultClick = { target -> clickDefault(target, holder, slot, event.click) }
                )
            )
        ) return
        clickDefault(player, holder, slot, event.click)
    }

    private fun clickDefault(player: Player, holder: Holder, slot: Int, click: ClickType) {
        when {
            slot in template.slots('P') -> navigate(player, holder, holder.page - 1)
            slot in template.slots('N') -> navigate(player, holder, holder.page + 1)
            slot in template.slots('C') -> confirm(player, holder)
            slot in template.slots('X') -> cancel(player, holder)
            slot in template.slots('*') -> returnEntry(player, holder, slot, click)
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? Holder ?: return
        val player = event.player as? Player ?: return
        val result = DepositBufferManager.onMenuClosed(player, holder.sessionId)
        if (result.returnedAmount > 0) {
            player.sendMessage(
                Language.get("bin-buffer-returned", "amount" to result.returnedAmount.toString())
            )
        }
        if (result.remainingAmount > 0) {
            player.sendMessage(Language.get("bin-buffer-return-full"))
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    private fun stageFromInventory(event: InventoryClickEvent, player: Player, holder: Holder) {
        if (event.click != ClickType.LEFT && event.click != ClickType.RIGHT &&
            event.click != ClickType.SHIFT_LEFT && event.click != ClickType.SHIFT_RIGHT
        ) return
        val cursor: ItemStack? = event.cursor
        if (cursor != null && cursor.type != Material.AIR) return
        if (Settings.clusterEnabled) {
            if (!org.cyuCBMclean.cyuclear.cluster.ClusterManager.isActive()) {
                player.sendMessage(Language.get("bin-sync-unavailable"))
            } else {
                player.sendMessage(Language.get("bin-deposit-cluster-disabled"))
            }
            return
        }
        val item = event.currentItem ?: return
        if (item.type == Material.AIR) return
        val decision = PlayerDepositService.check(player, item)
        if (!decision.allowed) {
            val reason = if (decision.messageKey == "bin-deposit-denied") decision.reason else ""
            player.sendMessage(Language.get(decision.messageKey, "reason" to reason))
            return
        }
        when (DepositBufferManager.stage(player, item.clone())) {
            DepositBufferManager.StageStatus.STAGED -> {
                event.clickedInventory?.setItem(event.slot, ItemStack(Material.AIR))
                if (Settings.binDepositFeedbackEnabled) {
                    player.sendMessage(Language.get("bin-deposit-buffer-staged"))
                }
                SoundNoticeManager.play(player, SoundNoticeManager.Event.BIN_DEPOSIT)
                render(holder, player)
            }
            DepositBufferManager.StageStatus.LIMIT -> player.sendMessage(Language.get("bin-deposit-limit"))
            DepositBufferManager.StageStatus.PERSIST_FAILED -> player.sendMessage(Language.get("bin-buffer-persist-failed"))
            DepositBufferManager.StageStatus.INVALID -> Unit
        }
    }

    private fun navigate(player: Player, holder: Holder, page: Int) {
        val total = DepositBufferManager.pageCount(player, holder.sessionId, template.slots('*').size)
        if (page !in 0 until total) return
        DepositBufferManager.prepareTransition(player, holder.sessionId)
        open(player, holder.sessionId, page)
    }

    private fun confirm(player: Player, holder: Holder) {
        val result = DepositBufferManager.confirm(player, holder.sessionId)
        when (result.status) {
            DepositBufferManager.ConfirmStatus.COMMITTED -> {
                player.sendMessage(Language.get("bin-buffer-confirmed"))
                SoundNoticeManager.play(player, SoundNoticeManager.Event.BIN_DEPOSIT)
                player.closeInventory()
            }
            DepositBufferManager.ConfirmStatus.DENIED -> {
                val decision = result.decision
                val reason = decision?.reason.orEmpty()
                player.sendMessage(Language.get(decision?.messageKey ?: "bin-buffer-confirm-failed", "reason" to reason))
            }
            DepositBufferManager.ConfirmStatus.LIMIT -> player.sendMessage(Language.get("bin-deposit-limit"))
            DepositBufferManager.ConfirmStatus.DISABLED -> player.sendMessage(Language.get("bin-buffer-disabled"))
            DepositBufferManager.ConfirmStatus.CLUSTER_DISABLED -> player.sendMessage(Language.get("bin-deposit-cluster-disabled"))
            DepositBufferManager.ConfirmStatus.EMPTY,
            DepositBufferManager.ConfirmStatus.NO_SESSION -> {
                player.sendMessage(Language.get("bin-buffer-empty"))
                player.closeInventory()
            }
        }
    }

    private fun cancel(player: Player, holder: Holder) {
        val result = DepositBufferManager.cancel(player, holder.sessionId)
        if (result.remainingAmount > 0) {
            player.sendMessage(Language.get("bin-buffer-return-full"))
        } else if (result.returnedAmount > 0) {
            player.sendMessage(Language.get("bin-buffer-cancelled"))
        }
        player.closeInventory()
    }

    private fun returnEntry(player: Player, holder: Holder, slot: Int, click: ClickType) {
        val requestedAmount = requestedAmount(holder.entryItems[slot], click) ?: return
        val expectedItem = holder.entryItems[slot] ?: return
        val contentSlots = template.slots('*')
        val position = contentSlots.indexOf(slot)
        if (position < 0) return

        val pageSize = contentSlots.size.coerceAtLeast(1)
        val result = DepositBufferManager.returnEntry(
            player = player,
            sessionId = holder.sessionId,
            entryIndex = holder.page * pageSize + position,
            expectedItem = expectedItem,
            requestedAmount = requestedAmount
        )
        when (result.status) {
            DepositBufferManager.EntryReturnStatus.RETURNED -> {
                if (result.bufferEmpty) {
                    player.sendMessage(Language.get("bin-buffer-item-returned", "returned" to result.returnedAmount.toString()))
                    player.closeInventory()
                    return
                }
                val messageKey = if (result.remainingAmount > 0) {
                    "bin-buffer-item-returned-partial"
                } else {
                    "bin-buffer-item-returned"
                }
                player.sendMessage(
                    Language.get(
                        messageKey,
                        "returned" to result.returnedAmount.toString(),
                        "remaining" to result.remainingAmount.toString()
                    )
                )
                refresh(player, holder)
            }
            DepositBufferManager.EntryReturnStatus.NO_SPACE -> {
                player.sendMessage(Language.get("bin-buffer-item-return-full"))
            }
            DepositBufferManager.EntryReturnStatus.PERSIST_FAILED -> {
                player.sendMessage(Language.get("bin-buffer-item-persist-failed"))
            }
            DepositBufferManager.EntryReturnStatus.INVALID_ENTRY -> {
                player.sendMessage(Language.get("bin-buffer-item-gone"))
                refresh(player, holder)
            }
            DepositBufferManager.EntryReturnStatus.NO_SESSION -> {
                player.sendMessage(Language.get("bin-buffer-empty"))
                player.closeInventory()
            }
        }
    }

    private fun requestedAmount(item: ItemStack?, click: ClickType): Int? {
        val maxStackSize = item?.maxStackSize?.coerceAtLeast(1) ?: return null
        return when (click) {
            ClickType.LEFT -> 1
            ClickType.RIGHT -> maxOf(1, maxStackSize / 2)
            ClickType.SHIFT_LEFT -> maxStackSize
            ClickType.SHIFT_RIGHT -> Int.MAX_VALUE
            else -> null
        }
    }

    private fun refresh(player: Player, holder: Holder) {
        val total = DepositBufferManager.pageCount(player, holder.sessionId, template.slots('*').size)
        val page = holder.page.coerceIn(0, total - 1)
        DepositBufferManager.prepareTransition(player, holder.sessionId)
        open(player, holder.sessionId, page)
    }

    private fun render(holder: Holder, player: Player) {
        val snapshot = DepositBufferManager.snapshot(player, holder.sessionId) ?: return
        val contentSlots = template.slots('*')
        holder.entryItems.clear()
        for (slot in contentSlots) holder.menuInventory.setItem(slot, ItemStack(Material.AIR))
        val pageSize = contentSlots.size.coerceAtLeast(1)
        val start = holder.page * pageSize
        val end = minOf(start + pageSize, snapshot.items.size)
        for (index in start until end) {
            val slot = contentSlots[index - start]
            val (baseItem, amount) = snapshot.items[index]
            val display = baseItem.clone().apply { this.amount = amount.coerceAtMost(maxStackSize.coerceAtLeast(1)) }
            val meta = display.itemMeta
            if (meta != null) {
                val lore = meta.lore?.toMutableList() ?: ArrayList()
                lore.add(Language.getRaw("bin-buffer-lore-amount", "amount" to amount.toString()))
                lore.add(Language.getRaw("bin-buffer-lore-left"))
                lore.add(Language.getRaw("bin-buffer-lore-right"))
                lore.add(Language.getRaw("bin-buffer-lore-shift-stack"))
                lore.add(Language.getRaw("bin-buffer-lore-shift-all"))
                lore.add(Language.getRaw("bin-buffer-lore-hint"))
                meta.lore = lore
                display.itemMeta = meta
            }
            holder.menuInventory.setItem(slot, display)
            holder.entryItems[slot] = baseItem.clone()
        }
    }

    private fun drawTemplate(inventory: Inventory, player: Player) {
        for ((row, line) in template.layout.withIndex()) {
            for (column in 0 until minOf(9, line.length)) {
                val symbol = line[column]
                if (symbol == '*' || symbol == ' ') continue
                val item = template.item(symbol, player) ?: continue
                inventory.setItem(row * 9 + column, item)
            }
        }
    }
}

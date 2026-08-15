package org.cyuCBMclean.cyuclear.menu

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.BinClaimAudit
import org.cyuCBMclean.cyuclear.service.DepositBufferManager
import org.cyuCBMclean.cyuclear.service.PlayerDepositService
import org.cyuCBMclean.cyuclear.service.SoundNoticeManager
import org.cyuCBMclean.cyuclear.service.VoidBinManager
import org.cyuCBMclean.cyuclear.service.VoidBinViewController
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import org.cyuCBMclean.cyuclear.cluster.ClusterManager
import kotlin.math.ceil

class BinMenu(requestedPage: Int = 0) : InventoryHolder, Listener {

    private val realItemMap = HashMap<Int, ItemStack>()
    private val trashSlots = ArrayList<Int>()
    private val customButtonSlots = HashMap<Int, BinMenuDefinition.Button>()
    private val menuDefinition = BinMenuDefinition.snapshot()
    private val inventory: Inventory
    val totalPages: Int
    val page: Int

    companion object : VoidBinViewController {
        fun refreshOpenMenus() {
            CyuScheduler.runTask(Cyuclear.instance, Runnable {
                refreshOpenMenus(VoidBinManager.createMenuSnapshot())
            })
        }

        override fun refreshOpenMenus(snapshot: VoidBinManager.MenuSnapshot) {
            for (player in Bukkit.getOnlinePlayers()) {
                CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
                    val holder = player.openInventory.topInventory.holder as? BinMenu
                    if (holder != null) {
                        holder.redrawContent(snapshot)
                        holder.renderButtons(player)
                        player.updateInventory()
                    }
                })
            }
        }

        override fun closeOpenMenus() {
            for (player in Bukkit.getOnlinePlayers()) {
                CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
                    if (player.openInventory.topInventory.holder is BinMenu) player.closeInventory()
                })
            }
        }
    }

    init {
        var trashCapacity = 0
        for ((rowIndex, rowStr) in menuDefinition.layout.withIndex()) {
            for ((colIndex, char) in rowStr.withIndex()) {
                if (colIndex >= 9) break
                val slot = rowIndex * 9 + colIndex
                if (char == '*') {
                    trashSlots.add(slot)
                    trashCapacity++
                } else {
                    val customButton = menuDefinition.button(char)
                    if (customButton != null) {
                        customButtonSlots[slot] = customButton
                    }
                }
            }
        }

        val itemsPerPage = maxOf(1, trashCapacity)
        val snapshot = VoidBinManager.createMenuSnapshot()

        totalPages = if (Settings.binStackedMode) {
            maxOf(1, ceil(snapshot.stackedItems.size.toDouble() / itemsPerPage).toInt())
        } else {
            maxOf(1, ceil(snapshot.flatItems.size.toDouble() / itemsPerPage).toInt())
        }
        page = requestedPage.coerceIn(0, totalPages - 1)

        val parsedTitle = menuDefinition.title
            .replace("{page}", (page + 1).toString())
            .replace("{total}", totalPages.toString())

        inventory = org.bukkit.Bukkit.createInventory(this, menuDefinition.height * 9, parsedTitle)

        redrawContent(snapshot)
    }

    private fun redrawContent(snapshot: VoidBinManager.MenuSnapshot) {
        realItemMap.clear()
        for (slot in trashSlots) {
            inventory.setItem(slot, ItemStack(Material.AIR))
        }

        val itemsPerPage = maxOf(1, trashSlots.size)
        val startIndex = page * itemsPerPage
        var trashSlotIndex = 0

        if (Settings.binStackedMode) {
            val endIndex = minOf(startIndex + itemsPerPage, snapshot.stackedItems.size)
            for (i in startIndex until endIndex) {
                if (trashSlotIndex < trashSlots.size) {
                    val pair = snapshot.stackedItems[i]
                    val slot = trashSlots[trashSlotIndex]
                    updateSlot(slot, pair.first, pair.second)
                    trashSlotIndex++
                }
            }
            return
        }

        val endIndex = minOf(startIndex + itemsPerPage, snapshot.flatItems.size)
        for (i in startIndex until endIndex) {
            if (trashSlotIndex < trashSlots.size) {
                val slot = trashSlots[trashSlotIndex]
                val item = snapshot.flatItems[i]
                inventory.setItem(slot, item.clone())
                realItemMap[slot] = item
                trashSlotIndex++
            }
        }
    }

    fun updateSlot(slot: Int, baseItem: ItemStack, amount: Int = VoidBinManager.getAmount(baseItem)) {
        if (amount <= 0) {
            inventory.setItem(slot, ItemStack(Material.AIR))
            realItemMap.remove(slot)
        } else {
            val displayItem = baseItem.clone()
            val meta = displayItem.itemMeta
            val lore = meta?.lore?.toMutableList() ?: ArrayList()

            lore.add(Language.get("bin-lore-amount", "amount" to amount.toString()))
            lore.add(Language.get("bin-lore-left"))
            lore.add(Language.get("bin-lore-right"))
            lore.add(Language.get("bin-lore-shift"))

            meta?.lore = lore
            displayItem.itemMeta = meta
            inventory.setItem(slot, displayItem)
            realItemMap[slot] = baseItem
        }
    }

    override fun getInventory(): Inventory = inventory

    fun open(player: Player) {
        renderButtons(player)
        player.openInventory(inventory)
    }

    private fun renderButtons(player: Player) {
        for ((slot, button) in customButtonSlots) {
            inventory.setItem(slot, button.render(player))
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder !is BinMenu) return

        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return

        if (event.clickedInventory == event.view.bottomInventory) {
            if (event.click != ClickType.LEFT && event.click != ClickType.RIGHT && event.click != ClickType.SHIFT_LEFT && event.click != ClickType.SHIFT_RIGHT) return
            val cursor: ItemStack? = event.cursor
            if (cursor != null && cursor.type != Material.AIR) return

            if (Settings.clusterEnabled) {
                if (!ClusterManager.isActive()) {
                    player.sendMessage(Language.get("bin-sync-unavailable"))
                } else {
                    player.sendMessage(Language.get("bin-deposit-cluster-disabled"))
                }
                return
            }
            val clickedItem = event.currentItem ?: return
            if (clickedItem.type == Material.AIR) return

            val decision = PlayerDepositService.check(player, clickedItem)
            if (!decision.allowed) {
                val reason = if (decision.messageKey == "bin-deposit-denied") decision.reason else ""
                player.sendMessage(Language.get(decision.messageKey, "reason" to reason))
                return
            }
            if (Settings.binDepositBufferEnabled) {
                when (DepositBufferManager.stage(player, clickedItem.clone())) {
                    DepositBufferManager.StageStatus.STAGED -> {
                        event.clickedInventory?.setItem(event.slot, ItemStack(Material.AIR))
                        if (Settings.binDepositFeedbackEnabled) {
                            player.sendMessage(Language.get("bin-deposit-buffer-staged"))
                        }
                        SoundNoticeManager.play(player, SoundNoticeManager.Event.BIN_DEPOSIT)
                        DepositBufferMenu.open(player)
                    }
                    DepositBufferManager.StageStatus.LIMIT -> player.sendMessage(Language.get("bin-deposit-limit"))
                    DepositBufferManager.StageStatus.PERSIST_FAILED -> player.sendMessage(Language.get("bin-buffer-persist-failed"))
                    DepositBufferManager.StageStatus.INVALID -> Unit
                }
                return
            }
            if (!VoidBinManager.storeManual(clickedItem.clone())) {
                player.sendMessage(Language.get("bin-deposit-limit"))
                return
            }
            event.clickedInventory?.setItem(event.slot, ItemStack(Material.AIR))
            if (Settings.binDepositFeedbackEnabled) {
                player.sendMessage(Language.get("bin-deposit-success"))
            }
            SoundNoticeManager.play(player, SoundNoticeManager.Event.BIN_DEPOSIT)

            return
        }

        val slot = event.rawSlot
        if (slot < 0 || slot >= holder.inventory.size) return

        if (holder.customButtonSlots.containsKey(slot)) {
            val button = holder.customButtonSlots[slot]!!
            MenuActionExecutor.execute(
                player,
                button.actions,
                MenuActionBindings(
                    refresh = { target -> MenuActionExecutor.openBin(target, holder.page) },
                    openPreviousPage = if (holder.page > 0) {
                        { target -> MenuActionExecutor.openBin(target, holder.page - 1) }
                    } else {
                        null
                    },
                    openNextPage = if (holder.page < holder.totalPages - 1) {
                        { target -> MenuActionExecutor.openBin(target, holder.page + 1) }
                    } else {
                        null
                    }
                )
            )
            return
        }

        if (holder.trashSlots.contains(slot)) {
            val cursor: ItemStack? = event.cursor
            if (cursor != null && cursor.type != Material.AIR) return
            if (holder.isClaimCoolingDown(player)) return

            val clickedItem = event.currentItem ?: return
            if (clickedItem.type == Material.AIR) return

            val baseItem: ItemStack
            val requestedAmount: Int
            val clearSlotOnSuccess: Boolean
            if (Settings.binStackedMode) {
                baseItem = holder.realItemMap[slot] ?: run {
                    player.sendMessage(Language.get("bin-item-gone"))
                    refreshOpenMenus()
                    return
                }
                requestedAmount = when (event.click) {
                    ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT -> baseItem.maxStackSize.coerceAtLeast(1)
                    ClickType.RIGHT -> maxOf(1, baseItem.maxStackSize.coerceAtLeast(1) / 2)
                    ClickType.LEFT -> 1
                    else -> return
                }
                clearSlotOnSuccess = false
            } else {
                if (event.click != ClickType.LEFT && event.click != ClickType.RIGHT && event.click != ClickType.SHIFT_LEFT && event.click != ClickType.SHIFT_RIGHT) return
                baseItem = holder.realItemMap[slot]?.clone() ?: run {
                    player.sendMessage(Language.get("bin-item-gone"))
                    refreshOpenMenus()
                    return
                }
                requestedAmount = baseItem.amount.coerceAtLeast(1)
                baseItem.amount = 1
                clearSlotOnSuccess = true
            }

            if (!VoidBinManager.beginClaim(player)) {
                player.sendMessage(Language.get("bin-claim-pending"))
                return
            }
            holder.claimItem(player, baseItem, requestedAmount, slot, clearSlotOnSuccess)
        }
    }

    private fun claimItem(player: Player, baseItem: ItemStack, requestedAmount: Int, slot: Int, clearSlotOnSuccess: Boolean) {
        VoidBinManager.takeAmountAsync(player, baseItem, requestedAmount) { reservation ->
            CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
                VoidBinManager.finishClaim(player)
                val takenAmount = reservation.amount
                if (!player.isOnline) {
                    VoidBinManager.releaseClaim(reservation, baseItem)
                    return@Runnable
                }
                if (takenAmount < 0) {
                    player.sendMessage(Language.get("bin-sync-unavailable"))
                    return@Runnable
                }
                if (takenAmount == 0) {
                    player.sendMessage(Language.get("bin-item-gone"))
                    refreshOpenMenus()
                    return@Runnable
                }

                val giveItem = baseItem.clone()
                giveItem.amount = takenAmount
                if (clearSlotOnSuccess) inventory.setItem(slot, ItemStack(Material.AIR))
                val leftover = player.inventory.addItem(giveItem)
                leftover.forEach { (_, item) -> player.world.dropItem(player.location, item) }
                BinClaimAudit.record(player, baseItem, takenAmount, reservation, if (leftover.isEmpty()) "inventory" else "inventory_and_drop")
                VoidBinManager.completeClaim(reservation)
                startClaimCooldown(player)
            }, Runnable {
                VoidBinManager.finishClaim(player)
                VoidBinManager.releaseClaim(reservation, baseItem)
            })
        }
    }
    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val holder = event.inventory.holder
        if (holder is BinMenu) {
            event.isCancelled = true
        }
    }

    private fun isClaimCoolingDown(player: Player): Boolean {
        val remainingSeconds = VoidBinManager.getClaimCooldownRemainingSeconds(player)
        if (remainingSeconds <= 0) {
            return false
        }

        player.sendMessage(Language.get("bin-claim-cooldown", "time" to remainingSeconds.toString()))
        return true
    }

    private fun startClaimCooldown(player: Player) {
        val seconds = VoidBinManager.startClaimCooldown(player)
        if (seconds > 0) {
            player.sendMessage(Language.get("bin-claim-success", "time" to seconds.toString()))
        }
    }
}

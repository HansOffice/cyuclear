package org.cyuCBMclean.cyuclear.menu

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.ConfigDoctor
import org.cyuCBMclean.cyuclear.config.ConfigSnapshotManager
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.service.CleanupRunManager
import org.cyuCBMclean.cyuclear.service.HotspotTracker
import org.cyuCBMclean.cyuclear.service.StatusReporter
import org.cyuCBMclean.cyuclear.service.WindowScanner
import org.cyuCBMclean.cyuclear.util.ColorUtils

object AdminMenu : Listener {
    private enum class ConfirmAction {
        CLEANUP,
        CANCEL
    }

    private class Holder(
        val confirmAction: ConfirmAction? = null,
        val confirmUntil: Long = 0L
    ) : InventoryHolder {
        lateinit var menuInventory: Inventory
        override fun getInventory(): Inventory = menuInventory
    }

    private lateinit var template: ConfiguredMenu

    fun load() {
        template = ConfiguredMenu.load("menu/admin.yml")
    }

    fun open(player: Player) {
        open(player, null, 0L)
    }

    private fun open(player: Player, confirmAction: ConfirmAction?, confirmUntil: Long) {
        val holder = Holder(confirmAction, confirmUntil)
        val inventory = Bukkit.createInventory(holder, template.size, template.title)
        holder.menuInventory = inventory
        drawTemplate(inventory, player)
        drawState(inventory)
        player.openInventory(inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        if (!player.hasPermission("cyuclear.admin")) {
            player.closeInventory()
            player.sendMessage(Language.get("no-permission"))
            return
        }
        val slot = event.rawSlot
        if (template.dispatch(
                player,
                slot,
                MenuActionBindings(
                    refresh = { target -> open(target) },
                    defaultClick = { target -> clickDefault(target, holder, slot) }
                )
            )
        ) return
        clickDefault(player, holder, slot)
    }

    private fun clickDefault(player: Player, holder: Holder, slot: Int) {
        when (slot) {
            in template.slots('R') -> RuleMenu.open(player)
            in template.slots('H') -> CleanupRunMenu.openRuns(player, 0)
            in template.slots('T') -> HotspotMenu.openList(player, 0)
            in template.slots('P') -> {
                player.closeInventory()
                player.performCommand("cc preview")
            }
            in template.slots('A') -> confirm(player, holder, ConfirmAction.CLEANUP)
            in template.slots('K') -> confirm(player, holder, ConfirmAction.CANCEL)
            in template.slots('S') -> StatusReporter.send(player)
            in template.slots('D') -> ConfigDoctor.send(player)
            in template.slots('C') -> createSnapshot(player)
            in template.slots('X') -> player.closeInventory()
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    private fun drawTemplate(inventory: Inventory, player: Player) {
        for ((row, line) in template.layout.withIndex()) {
            for (column in 0 until minOf(9, line.length)) {
                val symbol = line[column]
                if (symbol == ' ') continue
                val item = template.item(symbol, player) ?: continue
                inventory.setItem(row * 9 + column, item)
            }
        }
    }

    private fun drawState(inventory: Inventory) {
        val active = CleanupRunManager.activeView()
        val latest = CleanupRunManager.list(0, 1).first.firstOrNull()
        val hotspots = HotspotTracker.summary()
        setLore(
            inventory,
            template.slots('H'),
            listOf(
                "&7当前清理: &f${if (WindowScanner.isRunning) "进行中" else "空闲"}",
                "&7最近批次: &f${active?.id ?: latest?.id ?: "无"}",
                "",
                "&f左键查看记录与恢复物品"
            )
        )
        setLore(
            inventory,
            template.slots('T'),
            listOf(
                "&7热点区块: &f${hotspots.total}",
                "&7正在熔断: &f${hotspots.breakers}",
                "",
                "&f左键查看热点"
            )
        )
        setLore(
            inventory,
            template.slots('A'),
            listOf(
                "&7立即清理所有已启用目标",
                "",
                if (holderConfirming(inventory, ConfirmAction.CLEANUP)) "&c再次左键确认清理" else "&f左键开始"
            )
        )
        setLore(
            inventory,
            template.slots('K'),
            listOf(
                "&7停止当前清理任务",
                "",
                if (holderConfirming(inventory, ConfirmAction.CANCEL)) "&c再次左键确认停止" else "&f左键停止"
            )
        )
        setLore(
            inventory,
            template.slots('D'),
            listOf(
                "&7检查配置、规则与菜单",
                "",
                "&f左键检查配置"
            )
        )
    }

    private fun setLore(inventory: Inventory, slots: List<Int>, lines: List<String>) {
        for (slot in slots) {
            val item = inventory.getItem(slot) ?: continue
            val meta = item.itemMeta ?: continue
            meta.lore = lines.map(ColorUtils::color)
            item.itemMeta = meta
        }
    }

    private fun createSnapshot(player: Player) {
        val result = ConfigSnapshotManager.create("menu")
        if (result.success) {
            player.sendMessage(Language.get("snapshot-success", "count" to result.copiedFiles.toString()))
        } else {
            Cyuclear.instance.logger.warning("管理员菜单创建配置快照失败：${result.error}")
            player.sendMessage(Language.get("snapshot-failed"))
        }
    }

    private fun confirm(player: Player, holder: Holder, action: ConfirmAction) {
        val now = System.currentTimeMillis()
        if (holder.confirmAction != action || now > holder.confirmUntil) {
            player.sendMessage(Language.get(if (action == ConfirmAction.CLEANUP) "admin-cleanup-confirm" else "admin-cancel-confirm"))
            open(player, action, now + 10_000L)
            return
        }
        player.closeInventory()
        player.performCommand(if (action == ConfirmAction.CLEANUP) "cc all" else "cc cancel")
    }

    private fun holderConfirming(inventory: Inventory, action: ConfirmAction): Boolean {
        val holder = inventory.holder as? Holder ?: return false
        return holder.confirmAction == action && System.currentTimeMillis() <= holder.confirmUntil
    }
}

package org.cyuCBMclean.cyuclear.menu

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.ActivationService
import org.cyuCBMclean.cyuclear.service.CleanupRequests
import org.cyuCBMclean.cyuclear.service.HotspotTracker
import org.cyuCBMclean.cyuclear.service.ChunkLimitService
import org.cyuCBMclean.cyuclear.service.WindowScanner
import org.cyuCBMclean.cyuclear.util.ColorUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HotspotMenu : Listener {
    private enum class Screen {
        LIST,
        DETAIL
    }

    private enum class ConfirmAction {
        CLEANUP,
        RELEASE
    }

    private data class EntryKey(
        val world: String,
        val chunkX: Int,
        val chunkZ: Int
    )

    private class Holder(
        val screen: Screen,
        val page: Int = 0,
        val entry: EntryKey? = null,
        val confirmAction: ConfirmAction? = null,
        val confirmUntil: Long = 0L
    ) : InventoryHolder {
        lateinit var menuInventory: Inventory
        val entries = HashMap<Int, EntryKey>()
        override fun getInventory(): Inventory = menuInventory
    }

    private lateinit var listTemplate: ConfiguredMenu
    private lateinit var detailTemplate: ConfiguredMenu

    fun load() {
        listTemplate = ConfiguredMenu.load("menu/hotspots.yml")
        detailTemplate = ConfiguredMenu.load("menu/hotspot-detail.yml")
    }

    fun openList(player: Player, requestedPage: Int) {
        val contentSlots = listTemplate.slots('*')
        val pageSize = maxOf(1, contentSlots.size)
        val (hotspots, totalPages) = HotspotTracker.list(requestedPage, pageSize)
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val title = listTemplate.title
            .replace("{page}", (page + 1).toString())
            .replace("{total}", totalPages.toString())
        val holder = Holder(Screen.LIST, page = page)
        val inventory = Bukkit.createInventory(holder, listTemplate.size, title)
        holder.menuInventory = inventory
        drawTemplate(inventory, listTemplate, player)
        hotspots.forEachIndexed { index, hotspot ->
            val slot = contentSlots.getOrNull(index) ?: return@forEachIndexed
            inventory.setItem(slot, listItem(hotspot))
            holder.entries[slot] = EntryKey(hotspot.world, hotspot.chunkX, hotspot.chunkZ)
        }
        drawListButtons(inventory, page, totalPages, hotspots.isEmpty())
        player.openInventory(inventory)
    }

    private fun openDetail(
        player: Player,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        page: Int = 0,
        confirmAction: ConfirmAction? = null,
        confirmUntil: Long = 0L
    ) {
        val hotspot = HotspotTracker.find(world, chunkX, chunkZ)
        if (hotspot == null) {
            player.sendMessage(Language.get("hotspot-not-found"))
            openList(player, page)
            return
        }
        val entry = EntryKey(world, chunkX, chunkZ)
        val holder = Holder(Screen.DETAIL, page, entry, confirmAction, confirmUntil)
        val title = detailTemplate.title
            .replace("{world}", world)
            .replace("{x}", chunkX.toString())
            .replace("{z}", chunkZ.toString())
        val inventory = Bukkit.createInventory(holder, detailTemplate.size, title)
        holder.menuInventory = inventory
        drawTemplate(inventory, detailTemplate, player)
        detailTemplate.slots('I').forEach { inventory.setItem(it, detailItem(hotspot)) }
        drawDetailButtons(inventory, hotspot, if (System.currentTimeMillis() <= confirmUntil) confirmAction else null)
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
        when (holder.screen) {
            Screen.LIST -> clickList(player, holder, event.rawSlot)
            Screen.DETAIL -> clickDetail(player, holder, event.rawSlot)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    private fun clickList(player: Player, holder: Holder, slot: Int) {
        val entry = holder.entries[slot]
        if (entry != null) {
            openDetail(player, entry.world, entry.chunkX, entry.chunkZ, holder.page)
            return
        }
        if (listTemplate.dispatch(
                player,
                slot,
                MenuActionBindings(
                    refresh = { target -> openList(target, holder.page) },
                    openPreviousPage = { target -> openList(target, holder.page - 1) },
                    openNextPage = { target -> openList(target, holder.page + 1) },
                    defaultClick = { target -> clickListDefault(target, holder.page, slot) }
                )
            )
        ) return
        clickListDefault(player, holder.page, slot)
    }

    private fun clickListDefault(player: Player, page: Int, slot: Int) {
        when {
            slot in listTemplate.slots('P') -> openList(player, page - 1)
            slot in listTemplate.slots('N') -> openList(player, page + 1)
            slot in listTemplate.slots('B') -> AdminMenu.open(player)
            slot in listTemplate.slots('X') -> player.closeInventory()
        }
    }

    private fun clickDetail(player: Player, holder: Holder, slot: Int) {
        val entry = holder.entry ?: return
        if (detailTemplate.dispatch(
                player,
                slot,
                MenuActionBindings(
                    refresh = { target -> openDetail(target, entry.world, entry.chunkX, entry.chunkZ, holder.page) },
                    defaultClick = { target -> clickDetailDefault(target, holder, entry, slot) }
                )
            )
        ) return
        clickDetailDefault(player, holder, entry, slot)
    }

    private fun clickDetailDefault(player: Player, holder: Holder, entry: EntryKey, slot: Int) {
        when {
            slot in detailTemplate.slots('C') -> cleanup(player, holder, entry)
            slot in detailTemplate.slots('R') -> release(player, holder, entry)
            slot in detailTemplate.slots('B') -> openList(player, holder.page)
            slot in detailTemplate.slots('X') -> player.closeInventory()
        }
    }

    private fun release(player: Player, holder: Holder, entry: EntryKey) {
        val hotspot = HotspotTracker.find(entry.world, entry.chunkX, entry.chunkZ)
        if (hotspot == null) {
            player.sendMessage(Language.get("hotspot-not-found"))
            openList(player, holder.page)
            return
        }
        if (hotspot.state != HotspotTracker.State.BREAKER) {
            player.sendMessage(Language.get("hotspot-no-breaker"))
            openDetail(player, entry.world, entry.chunkX, entry.chunkZ, holder.page)
            return
        }
        val now = System.currentTimeMillis()
        if (holder.confirmAction != ConfirmAction.RELEASE || now > holder.confirmUntil) {
            player.sendMessage(Language.get("hotspot-release-confirm"))
            openDetail(player, entry.world, entry.chunkX, entry.chunkZ, holder.page, ConfirmAction.RELEASE, now + 10_000L)
            return
        }
        if (ChunkLimitService.releaseHotspot(entry.world, entry.chunkX, entry.chunkZ)) {
            player.sendMessage(Language.get("hotspot-released", "world" to entry.world, "x" to entry.chunkX.toString(), "z" to entry.chunkZ.toString()))
        } else {
            player.sendMessage(Language.get("hotspot-not-found"))
        }
        openDetail(player, entry.world, entry.chunkX, entry.chunkZ, holder.page)
    }

    private fun cleanup(player: Player, holder: Holder, entry: EntryKey) {
        if (HotspotTracker.find(entry.world, entry.chunkX, entry.chunkZ) == null) {
            player.sendMessage(Language.get("hotspot-not-found"))
            openList(player, holder.page)
            return
        }
        val now = System.currentTimeMillis()
        if (holder.confirmAction != ConfirmAction.CLEANUP || now > holder.confirmUntil) {
            player.sendMessage(Language.get("hotspot-cleanup-confirm"))
            openDetail(player, entry.world, entry.chunkX, entry.chunkZ, holder.page, ConfirmAction.CLEANUP, now + 10_000L)
            return
        }
        if (WindowScanner.isRunning) {
            player.sendMessage(Language.get("scan-running"))
            openDetail(player, entry.world, entry.chunkX, entry.chunkZ, holder.page)
            return
        }
        if (!ActivationService.isActive()) {
            player.sendMessage(Language.get("plugin-disabled"))
            openDetail(player, entry.world, entry.chunkX, entry.chunkZ, holder.page)
            return
        }
        if (!Settings.itemModuleEnabled && !Settings.entityModuleEnabled) {
            player.sendMessage(Language.get("module-all-disabled"))
            openDetail(player, entry.world, entry.chunkX, entry.chunkZ, holder.page)
            return
        }
        val world = Bukkit.getWorld(entry.world)
        if (world == null) {
            player.sendMessage(Language.get("hotspot-not-found"))
            openList(player, holder.page)
            return
        }
        if (!WindowScanner.startChunkScan(CleanupRequests.manual(true, true), world, entry.chunkX, entry.chunkZ)) {
            player.sendMessage(Language.get("scan-running"))
            openDetail(player, entry.world, entry.chunkX, entry.chunkZ, holder.page)
            return
        }
        player.sendMessage(
            Language.get(
                "hotspot-cleanup-started",
                "world" to entry.world,
                "x" to entry.chunkX.toString(),
                "z" to entry.chunkZ.toString()
            )
        )
        player.closeInventory()
    }

    private fun listItem(hotspot: HotspotTracker.HotspotView): ItemStack {
        val material = when (hotspot.state) {
            HotspotTracker.State.BREAKER -> Material.matchMaterial("REDSTONE_BLOCK")
            HotspotTracker.State.THROTTLED -> Material.matchMaterial("BLAZE_POWDER")
            HotspotTracker.State.WARNING -> Material.matchMaterial("YELLOW_TERRACOTTA") ?: Material.matchMaterial("HARD_CLAY")
            HotspotTracker.State.OBSERVING -> Material.matchMaterial("CLOCK")
        } ?: Material.STONE
        return ItemStack(material).apply {
            itemMeta = itemMeta?.also { meta ->
                meta.setDisplayName(ColorUtils.color("&b${hotspot.world} &f${hotspot.chunkX}, ${hotspot.chunkZ}"))
                meta.lore = listOf(
                    "&7状态: &f${hotspot.state.display}",
                    "&7最近数量: &f掉落物 ${hotspot.itemCount} &8| &f实体 ${hotspot.entityCount}",
                    "&7近期触发: &f掉落物 ${hotspot.itemTriggerRate}/秒 &8| &f实体 ${hotspot.entityTriggerRate}/秒",
                    "&7触发次数: &f${hotspot.triggerCount}",
                    "&7最近活动: &f${formatTime(hotspot.lastSeenAt)}",
                    "",
                    "&f左键查看"
                ).map(ColorUtils::color)
            }
        }
    }

    private fun detailItem(hotspot: HotspotTracker.HotspotView): ItemStack {
        val material = when (hotspot.state) {
            HotspotTracker.State.BREAKER -> Material.matchMaterial("REDSTONE_BLOCK")
            HotspotTracker.State.THROTTLED -> Material.matchMaterial("BLAZE_POWDER")
            HotspotTracker.State.WARNING -> Material.matchMaterial("YELLOW_TERRACOTTA") ?: Material.matchMaterial("HARD_CLAY")
            HotspotTracker.State.OBSERVING -> Material.matchMaterial("CLOCK")
        } ?: Material.STONE
        return ItemStack(material).apply {
            itemMeta = itemMeta?.also { meta ->
                meta.setDisplayName(ColorUtils.color("&b${hotspot.world} &f${hotspot.chunkX}, ${hotspot.chunkZ}"))
                meta.lore = listOf(
                    "&7状态: &f${hotspot.state.display}",
                    "&7最近数量: &f掉落物 ${hotspot.itemCount} &8| &f实体 ${hotspot.entityCount}",
                    "&7近期触发: &f掉落物 ${hotspot.itemTriggerRate}/秒 &8| &f实体 ${hotspot.entityTriggerRate}/秒",
                    if (hotspot.itemSubject.isNotEmpty()) "&7掉落物触发对象: &f${hotspot.itemSubject}" else "",
                    if (hotspot.entitySubject.isNotEmpty()) "&7实体触发对象: &f${hotspot.entitySubject}" else "",
                    "&7首次记录: &f${formatTime(hotspot.firstSeenAt)}",
                    "&7最近活动: &f${formatTime(hotspot.lastSeenAt)}",
                    "&7清理记录: &f${hotspot.cleanupRuns} 次 &8| &f掉落物 ${hotspot.cleanedItems} &8| &f实体 ${hotspot.cleanedEntities}",
                    "&7最近处理: &f${hotspot.lastProcessMillis}ms",
                    "&7触发次数: &f${hotspot.triggerCount}"
                ).filter { it.isNotEmpty() }.map(ColorUtils::color)
            }
        }
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

    private fun drawListButtons(inventory: Inventory, page: Int, totalPages: Int, empty: Boolean) {
        setLore(inventory, listTemplate.slots('P'), if (page > 0) listOf("&e左键上一页") else listOf("&8已经是第一页"))
        setLore(inventory, listTemplate.slots('N'), if (page < totalPages - 1) listOf("&e左键下一页") else listOf("&8已经是最后一页"))
        if (empty) setEmpty(inventory, listTemplate.slots('*').firstOrNull(), "当前没有热点区块")
    }

    private fun drawDetailButtons(
        inventory: Inventory,
        hotspot: HotspotTracker.HotspotView,
        confirmAction: ConfirmAction?
    ) {
        val cleanupText = if (confirmAction == ConfirmAction.CLEANUP) {
            "&c再次左键确认清理"
        } else {
            "&e左键清理当前区块"
        }
        setLore(inventory, detailTemplate.slots('C'), listOf(cleanupText))
        val releaseText = when {
            confirmAction == ConfirmAction.RELEASE -> "&c再次左键解除熔断"
            hotspot.state == HotspotTracker.State.BREAKER -> "&e左键解除熔断"
            else -> "&8当前没有熔断"
        }
        setLore(inventory, detailTemplate.slots('R'), listOf(releaseText))
    }

    private fun setEmpty(inventory: Inventory, slot: Int?, text: String) {
        if (slot == null) return
        val item = ItemStack(Material.matchMaterial("PAPER") ?: Material.STONE)
        item.itemMeta = item.itemMeta?.also { meta -> meta.setDisplayName(ColorUtils.color("&7$text")) }
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

    private fun formatTime(value: Long): String {
        if (value <= 0L) return "-"
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(value))
    }
}

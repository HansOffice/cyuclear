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
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.ActivationService
import org.cyuCBMclean.cyuclear.service.CleanupRequests
import org.cyuCBMclean.cyuclear.service.HotspotTracker
import org.cyuCBMclean.cyuclear.service.ChunkLimitService
import org.cyuCBMclean.cyuclear.service.TeleportService
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
            Screen.LIST -> clickList(player, holder, event.rawSlot)
            Screen.DETAIL -> clickDetail(player, holder, event.rawSlot)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder && event.rawSlots.any { it < event.view.topInventory.size }) {
            event.isCancelled = true
        }
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
            slot in detailTemplate.slots('T') -> teleportToChunk(player, entry)
            slot in detailTemplate.slots('C') -> cleanup(player, holder, entry)
            slot in detailTemplate.slots('R') -> release(player, holder, entry)
            slot in detailTemplate.slots('B') -> openList(player, holder.page)
            slot in detailTemplate.slots('X') -> player.closeInventory()
        }
    }

    private fun teleportToChunk(player: Player, entry: EntryKey) {
        val blockX = (entry.chunkX * 16 + 8).toDouble()
        val blockZ = (entry.chunkZ * 16 + 8).toDouble()
        player.closeInventory()
        TeleportService.teleport(player, entry.world, blockX, null, blockZ)
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
        val isEn = Language.isEnglish
        val material = when (hotspot.state) {
            HotspotTracker.State.BREAKER -> Material.matchMaterial("REDSTONE_BLOCK")
            HotspotTracker.State.THROTTLED -> Material.matchMaterial("BLAZE_POWDER")
            HotspotTracker.State.WARNING -> Material.matchMaterial("YELLOW_TERRACOTTA") ?: Material.matchMaterial("HARD_CLAY")
            HotspotTracker.State.OBSERVING -> Material.matchMaterial("CLOCK")
        } ?: Material.STONE
        return ItemStack(material).apply {
            itemMeta = itemMeta?.also { meta ->
                meta.setDisplayName(ColorUtils.color("&#38BDF8${hotspot.world} &#D7DEE8${hotspot.chunkX}, ${hotspot.chunkZ}"))
                meta.lore = if (isEn) {
                    listOf(
                        "&#8A96A8Status &#D7DEE8${hotspot.state.display}",
                        "&#8A96A8Recent Count &#8A96A8Items &#D7DEE8${hotspot.itemCount} &#5B6472| &#8A96A8Entities &#D7DEE8${hotspot.entityCount}",
                        "&#8A96A8Trigger Rate &#8A96A8Items &#D7DEE8${hotspot.itemTriggerRate}/s &#5B6472| &#8A96A8Entities &#D7DEE8${hotspot.entityTriggerRate}/s",
                        "&#8A96A8Trigger Count &#D7DEE8${hotspot.triggerCount}",
                        "&#8A96A8Last Seen &#D7DEE8${formatTime(hotspot.lastSeenAt)}",
                        "",
                        "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                        "&#D7DEE8› &#8A96A8Left-Click to inspect"
                    )
                } else {
                    listOf(
                        "&#8A96A8状态 &#D7DEE8${hotspot.state.display}",
                        "&#8A96A8最近数量 &#8A96A8掉落物 &#D7DEE8${hotspot.itemCount} &#5B6472| &#8A96A8实体 &#D7DEE8${hotspot.entityCount}",
                        "&#8A96A8近期触发 &#8A96A8掉落物 &#D7DEE8${hotspot.itemTriggerRate}/秒 &#5B6472| &#8A96A8实体 &#D7DEE8${hotspot.entityTriggerRate}/秒",
                        "&#8A96A8触发次数 &#D7DEE8${hotspot.triggerCount}",
                        "&#8A96A8最近活动 &#D7DEE8${formatTime(hotspot.lastSeenAt)}",
                        "",
                        "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                        "&#D7DEE8› &#8A96A8左键查看详情"
                    )
                }.map(ColorUtils::color)
            }
        }
    }

    private fun detailItem(hotspot: HotspotTracker.HotspotView): ItemStack {
        val isEn = Language.isEnglish
        val material = when (hotspot.state) {
            HotspotTracker.State.BREAKER -> Material.matchMaterial("REDSTONE_BLOCK")
            HotspotTracker.State.THROTTLED -> Material.matchMaterial("BLAZE_POWDER")
            HotspotTracker.State.WARNING -> Material.matchMaterial("YELLOW_TERRACOTTA") ?: Material.matchMaterial("HARD_CLAY")
            HotspotTracker.State.OBSERVING -> Material.matchMaterial("CLOCK")
        } ?: Material.STONE
        return ItemStack(material).apply {
            itemMeta = itemMeta?.also { meta ->
                meta.setDisplayName(ColorUtils.color("&#38BDF8${hotspot.world} &#D7DEE8${hotspot.chunkX}, ${hotspot.chunkZ}"))
                meta.lore = if (isEn) {
                    listOf(
                        "&#8A96A8Status &#D7DEE8${hotspot.state.display}",
                        "&#8A96A8Recent Count &#8A96A8Items &#D7DEE8${hotspot.itemCount} &#5B6472| &#8A96A8Entities &#D7DEE8${hotspot.entityCount}",
                        "&#8A96A8Trigger Rate &#8A96A8Items &#D7DEE8${hotspot.itemTriggerRate}/s &#5B6472| &#8A96A8Entities &#D7DEE8${hotspot.entityTriggerRate}/s",
                        if (hotspot.itemSubject.isNotEmpty()) "&#8A96A8Item Subject &#D7DEE8${hotspot.itemSubject}" else "",
                        if (hotspot.entitySubject.isNotEmpty()) "&#8A96A8Entity Subject &#D7DEE8${hotspot.entitySubject}" else "",
                        "&#8A96A8First Seen &#D7DEE8${formatTime(hotspot.firstSeenAt)}",
                        "&#8A96A8Last Seen &#D7DEE8${formatTime(hotspot.lastSeenAt)}",
                        "&#8A96A8Cleanup Stats &#D7DEE8${hotspot.cleanupRuns} runs &#5B6472| &#8A96A8Items &#D7DEE8${hotspot.cleanedItems} &#5B6472| &#8A96A8Entities &#D7DEE8${hotspot.cleanedEntities}",
                        "&#8A96A8Last Process &#D7DEE8${hotspot.lastProcessMillis}ms",
                        "&#8A96A8Trigger Count &#D7DEE8${hotspot.triggerCount}"
                    )
                } else {
                    listOf(
                        "&#8A96A8状态 &#D7DEE8${hotspot.state.display}",
                        "&#8A96A8最近数量 &#8A96A8掉落物 &#D7DEE8${hotspot.itemCount} &#5B6472| &#8A96A8实体 &#D7DEE8${hotspot.entityCount}",
                        "&#8A96A8近期触发 &#8A96A8掉落物 &#D7DEE8${hotspot.itemTriggerRate}/秒 &#5B6472| &#8A96A8实体 &#D7DEE8${hotspot.entityTriggerRate}/秒",
                        if (hotspot.itemSubject.isNotEmpty()) "&#8A96A8掉落物触发对象 &#D7DEE8${hotspot.itemSubject}" else "",
                        if (hotspot.entitySubject.isNotEmpty()) "&#8A96A8实体触发对象 &#D7DEE8${hotspot.entitySubject}" else "",
                        "&#8A96A8首次记录 &#D7DEE8${formatTime(hotspot.firstSeenAt)}",
                        "&#8A96A8最近活动 &#D7DEE8${formatTime(hotspot.lastSeenAt)}",
                        "&#8A96A8清理记录 &#D7DEE8${hotspot.cleanupRuns} 次 &#5B6472| &#8A96A8掉落物 &#D7DEE8${hotspot.cleanedItems} &#5B6472| &#8A96A8实体 &#D7DEE8${hotspot.cleanedEntities}",
                        "&#8A96A8最近处理 &#D7DEE8${hotspot.lastProcessMillis}ms",
                        "&#8A96A8触发次数 &#D7DEE8${hotspot.triggerCount}"
                    )
                }.filter { it.isNotEmpty() }.map(ColorUtils::color)
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
        val isEn = Language.isEnglish
        setLore(inventory, listTemplate.slots('P'), if (page > 0) listOf(if (isEn) "&#D7DEE8› &#8A96A8Left-Click for prev page" else "&#D7DEE8› &#8A96A8左键上一页") else listOf(if (isEn) "&#5B6472First page" else "&#5B6472已经是第一页"))
        setLore(inventory, listTemplate.slots('N'), if (page < totalPages - 1) listOf(if (isEn) "&#D7DEE8› &#8A96A8Left-Click for next page" else "&#D7DEE8› &#8A96A8左键下一页") else listOf(if (isEn) "&#5B6472Last page" else "&#5B6472已经是最后一页"))
        if (empty) setEmpty(inventory, listTemplate.slots('*').firstOrNull(), if (isEn) "No hotspot chunks tracked" else "当前没有热点区块")
    }

    private fun drawDetailButtons(
        inventory: Inventory,
        hotspot: HotspotTracker.HotspotView,
        confirmAction: ConfirmAction?
    ) {
        val isEn = Language.isEnglish
        val cleanupText = if (confirmAction == ConfirmAction.CLEANUP) {
            if (isEn) "&#FBBF24› Click again to confirm sweep" else "&#FBBF24› 再次左键确认清理"
        } else {
            if (isEn) "&#D7DEE8› &#8A96A8Left-Click to sweep this chunk" else "&#D7DEE8› &#8A96A8左键清理当前区块"
        }
        setLore(inventory, detailTemplate.slots('C'), listOf(cleanupText))
        val releaseText = when {
            confirmAction == ConfirmAction.RELEASE -> if (isEn) "&#F87171› Click again to confirm release" else "&#F87171› 再次左键解除熔断"
            hotspot.state == HotspotTracker.State.BREAKER -> if (isEn) "&#D7DEE8› &#8A96A8Left-Click to release breaker" else "&#D7DEE8› &#8A96A8左键解除熔断"
            else -> if (isEn) "&#5B6472No active breaker" else "&#5B6472当前没有熔断"
        }
        setLore(inventory, detailTemplate.slots('R'), listOf(releaseText))
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

    private fun formatTime(value: Long): String {
        if (value <= 0L) return "-"
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(value))
    }
}

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
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.menu.RuleConfigEditor.ListDomain
import org.cyuCBMclean.cyuclear.menu.RuleConfigEditor.ListKind
import org.cyuCBMclean.cyuclear.menu.RuleConfigEditor.Target
import org.cyuCBMclean.cyuclear.util.ColorUtils
import kotlin.math.ceil

object RuleMenu : Listener {
    private enum class Screen {
        MAIN,
        TARGET,
        LIST
    }

    private class Holder(
        val screen: Screen,
        val target: Target? = null,
        val domain: ListDomain = ListDomain.MATERIAL,
        val kind: ListKind? = null,
        val page: Int = 0
    ) : InventoryHolder {
        lateinit var menuInventory: Inventory
        val entryIndexes = HashMap<Int, Int>()
        val entryValues = HashMap<Int, String>()
        override fun getInventory(): Inventory = menuInventory
    }

    private lateinit var mainTemplate: ConfiguredMenu
    private lateinit var targetTemplate: ConfiguredMenu
    private lateinit var listTemplate: ConfiguredMenu

    fun load() {
        mainTemplate = ConfiguredMenu.load("menu/main.yml")
        targetTemplate = ConfiguredMenu.load("menu/target.yml")
        listTemplate = ConfiguredMenu.load("menu/list.yml")
    }

    fun open(player: Player) {
        val holder = Holder(Screen.MAIN)
        val inventory = Bukkit.createInventory(holder, mainTemplate.size, mainTemplate.title)
        holder.menuInventory = inventory
        drawTemplate(inventory, mainTemplate, player)
        drawMainState(inventory)
        player.openInventory(inventory)
    }

    private fun openTarget(player: Player, target: Target) {
        val holder = Holder(Screen.TARGET, target)
        val title = targetTemplate.title.replace("{target}", target.display)
        val inventory = Bukkit.createInventory(holder, targetTemplate.size, title)
        holder.menuInventory = inventory
        drawTemplate(inventory, targetTemplate, player)
        drawTargetState(inventory, target)
        player.openInventory(inventory)
    }

    private fun openList(player: Player, target: Target, domain: ListDomain, kind: ListKind, requestedPage: Int) {
        val values = RuleConfigEditor.list(target, domain, kind)
        val contentSlots = listTemplate.slots('*')
        val pageSize = maxOf(1, contentSlots.size)
        val totalPages = maxOf(1, ceil(values.size.toDouble() / pageSize).toInt())
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val holder = Holder(Screen.LIST, target, domain, kind, page)
        val title = listTemplate.title
            .replace("{target}", target.display)
            .replace("{list}", RuleConfigEditor.listTitle(domain, kind))
            .replace("{match-mode}", RuleConfigEditor.matchMode(target, domain, kind))
            .replace("{page}", (page + 1).toString())
            .replace("{total}", totalPages.toString())
        val inventory = Bukkit.createInventory(holder, listTemplate.size, title)
        holder.menuInventory = inventory
        drawTemplate(inventory, listTemplate, player)
        val start = page * pageSize
        val end = minOf(start + pageSize, values.size)
        for (index in start until end) {
            val slot = contentSlots[index - start]
            val id = values[index]
            val item = RuleConfigEditor.displayItem(target, domain, id)
            val meta = item.itemMeta
            if (meta != null) {
                meta.setDisplayName(ColorUtils.color("&#D7DEE8$id"))
                meta.lore = listOf(ColorUtils.color(if (Language.isEnglish) "&#F87171› Left-Click to remove this entry" else "&#F87171› 左键移除此项"))
                item.itemMeta = meta
            }
            inventory.setItem(slot, item)
            holder.entryIndexes[slot] = index
            holder.entryValues[slot] = id
        }
        drawListState(inventory, target, domain, kind, page, totalPages, values.isEmpty())
        player.openInventory(inventory)
        player.sendMessage(Language.get(listHintKey(target, domain)))
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        if (event.action == InventoryAction.COLLECT_TO_CURSOR || event.click == ClickType.DOUBLE_CLICK) {
            event.isCancelled = true
            return
        }
        val player = event.whoClicked as? Player ?: return
        if (!player.hasPermission("cyuclear.admin")) {
            event.isCancelled = true
            player.closeInventory()
            player.sendMessage(Language.get("no-permission"))
            return
        }
        runCatching {
            if (event.clickedInventory == event.view.bottomInventory) {
                if (event.isShiftClick) {
                    event.isCancelled = true
                    if (holder.screen == Screen.LIST) {
                        val item = event.currentItem
                        if (item != null && item.type != Material.AIR) {
                            addFromInventory(player, holder, item)
                        }
                    }
                }
                return@runCatching
            }
            event.isCancelled = true
            if (event.clickedInventory != event.view.topInventory) return@runCatching
            val slot = event.rawSlot
            when (holder.screen) {
                Screen.MAIN -> clickMain(player, slot)
                Screen.TARGET -> clickTarget(player, holder, slot, event.click)
                Screen.LIST -> clickList(player, holder, slot)
            }
        }.onFailure {
            Cyuclear.instance.logger.warning("保存清理规则失败：${it.message}")
            player.sendMessage(Language.get("menu-save-failed"))
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder && event.rawSlots.any { it < event.view.topInventory.size }) {
            event.isCancelled = true
        }
    }

    private fun clickMain(player: Player, slot: Int) {
        if (mainTemplate.dispatch(
                player,
                slot,
                MenuActionBindings(
                    refresh = { target -> open(target) },
                    defaultClick = { target -> clickMainDefault(target, slot) }
                )
            )
        ) return
        clickMainDefault(player, slot)
    }

    private fun clickMainDefault(player: Player, slot: Int) {
        when {
            slot in mainTemplate.slots('I') -> openTarget(player, Target.ITEMS)
            slot in mainTemplate.slots('E') -> openTarget(player, Target.ENTITIES)
            slot in mainTemplate.slots('R') -> openTarget(player, Target.REALTIME)
            slot in mainTemplate.slots('X') -> player.closeInventory()
        }
    }

    private fun clickTarget(player: Player, holder: Holder, slot: Int, click: ClickType) {
        val target = holder.target ?: return
        if (targetTemplate.dispatch(
                player,
                slot,
                MenuActionBindings(
                    refresh = { targetPlayer -> openTarget(targetPlayer, target) },
                    defaultClick = { targetPlayer -> clickTargetDefault(targetPlayer, target, slot, click) }
                )
            )
        ) return
        clickTargetDefault(player, target, slot, click)
    }

    private fun clickTargetDefault(player: Player, target: Target, slot: Int, click: ClickType) {
        when {
            slot in targetTemplate.slots('T') -> {
                RuleConfigEditor.toggle(target)
                openTarget(player, target)
            }
            slot in targetTemplate.slots('M') -> {
                RuleConfigEditor.cycleMode(target)
                openTarget(player, target)
            }
            slot in targetTemplate.slots('K') -> handleListButton(player, target, ListDomain.MATERIAL, ListKind.KEEP, click)
            slot in targetTemplate.slots('C') -> handleListButton(player, target, ListDomain.MATERIAL, ListKind.CLEAN, click)
            slot in targetTemplate.slots('A') -> handleListButton(player, target, ListDomain.NAME, ListKind.KEEP, click)
            slot in targetTemplate.slots('D') -> handleListButton(player, target, ListDomain.NAME, ListKind.CLEAN, click)
            slot in targetTemplate.slots('G') -> handleListButton(player, target, ListDomain.LORE, ListKind.KEEP, click)
            slot in targetTemplate.slots('H') -> handleListButton(player, target, ListDomain.LORE, ListKind.CLEAN, click)
            slot in targetTemplate.slots('B') -> open(player)
        }
    }

    private fun handleListButton(player: Player, target: Target, domain: ListDomain, kind: ListKind, click: ClickType) {
        if (domain != ListDomain.MATERIAL && target != Target.ITEMS) {
            player.sendMessage(Language.get("menu-list-items-only"))
            return
        }
        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            RuleConfigEditor.cycleMatchMode(target, domain, kind)
            player.sendMessage(
                Language.get(
                    "menu-match-mode-changed",
                    "list" to RuleConfigEditor.listTitle(domain, kind),
                    "mode" to RuleConfigEditor.matchMode(target, domain, kind)
                )
            )
            openTarget(player, target)
            return
        }
        if (click == ClickType.SHIFT_LEFT && domain != ListDomain.MATERIAL) {
            RuleConfigEditor.toggleDomain(target, domain)
            val isEn = Language.isEnglish
            val stateText = if (RuleConfigEditor.domainEnabled(target, domain)) (if (isEn) "enabled" else "开启") else (if (isEn) "disabled" else "关闭")
            player.sendMessage(
                Language.get(
                    "menu-rule-toggled",
                    "rule" to domain.display,
                    "state" to stateText
                )
            )
            openTarget(player, target)
            return
        }
        openList(player, target, domain, kind, 0)
    }

    private fun clickList(player: Player, holder: Holder, slot: Int) {
        val target = holder.target ?: return
        val kind = holder.kind ?: return
        val domain = holder.domain
        val entryIndex = holder.entryIndexes[slot]
        if (entryIndex != null) {
            val expected = holder.entryValues[slot]
            if (RuleConfigEditor.remove(target, domain, kind, entryIndex, expected)) {
                player.sendMessage(Language.get("menu-list-removed"))
                openList(player, target, domain, kind, holder.page)
            } else {
                player.sendMessage(Language.get("menu-list-changed"))
                openList(player, target, domain, kind, holder.page)
            }
            return
        }
        if (listTemplate.dispatch(
                player,
                slot,
                MenuActionBindings(
                    refresh = { targetPlayer -> openList(targetPlayer, target, domain, kind, holder.page) },
                    openPreviousPage = { targetPlayer -> openList(targetPlayer, target, domain, kind, holder.page - 1) },
                    openNextPage = { targetPlayer -> openList(targetPlayer, target, domain, kind, holder.page + 1) },
                    defaultClick = { targetPlayer -> clickListDefault(targetPlayer, target, domain, kind, holder.page, slot) }
                )
            )
        ) return
        clickListDefault(player, target, domain, kind, holder.page, slot)
    }

    private fun clickListDefault(
        player: Player,
        target: Target,
        domain: ListDomain,
        kind: ListKind,
        page: Int,
        slot: Int
    ) {
        when {
            slot in listTemplate.slots('P') -> {
                if (page > 0) openList(player, target, domain, kind, page - 1)
                else player.sendMessage(Language.get("menu-list-first-page"))
            }
            slot in listTemplate.slots('N') -> {
                val values = RuleConfigEditor.list(target, domain, kind)
                val pageSize = maxOf(1, listTemplate.slots('*').size)
                val totalPages = maxOf(1, ceil(values.size.toDouble() / pageSize).toInt())
                if (page < totalPages - 1) openList(player, target, domain, kind, page + 1)
                else player.sendMessage(Language.get("menu-list-last-page"))
            }
            slot in listTemplate.slots('B') -> openTarget(player, target)
        }
    }

    private fun addFromInventory(player: Player, holder: Holder, item: ItemStack) {
        val target = holder.target ?: return
        val kind = holder.kind ?: return
        val domain = holder.domain
        when (val result = RuleConfigEditor.add(target, domain, kind, item)) {
            is RuleConfigEditor.AddResult.Added -> {
                player.sendMessage(Language.get("menu-list-added"))
                openList(player, target, domain, kind, Int.MAX_VALUE)
            }
            is RuleConfigEditor.AddResult.AddedMulti -> {
                player.sendMessage(Language.get("menu-list-added-multi", "count" to result.count.toString()))
                openList(player, target, domain, kind, Int.MAX_VALUE)
            }
            is RuleConfigEditor.AddResult.Duplicate -> player.sendMessage(Language.get("menu-list-duplicate"))
            is RuleConfigEditor.AddResult.InvalidEntityItem -> player.sendMessage(Language.get("menu-list-spawn-egg-only"))
            is RuleConfigEditor.AddResult.NoDisplayName -> player.sendMessage(Language.get("menu-list-no-display-name"))
            is RuleConfigEditor.AddResult.NoLore -> player.sendMessage(Language.get("menu-list-no-lore"))
            is RuleConfigEditor.AddResult.ItemsOnly -> player.sendMessage(Language.get("menu-list-items-only"))
        }
    }

    private fun listHintKey(target: Target, domain: ListDomain): String {
        return when {
            target.entityInput -> "menu-list-entity-hint"
            domain == ListDomain.NAME -> "menu-list-name-hint"
            domain == ListDomain.LORE -> "menu-list-lore-hint"
            else -> "menu-list-item-hint"
        }
    }

    private fun drawMainState(inventory: Inventory) {
        val isEn = Language.isEnglish
        setLore(inventory, mainTemplate.slots('I'), if (isEn) {
            listOf(
                "&#8A96A8State ${enabledText(RuleConfigEditor.enabled(Target.ITEMS))}",
                "&#8A96A8Item IDs &#8A96A8Keep &#D7DEE8${count(Target.ITEMS, ListDomain.MATERIAL, ListKind.KEEP)} &#5B6472| &#8A96A8Clean &#D7DEE8${count(Target.ITEMS, ListDomain.MATERIAL, ListKind.CLEAN)}",
                "&#8A96A8Names &#8A96A8Keep &#D7DEE8${count(Target.ITEMS, ListDomain.NAME, ListKind.KEEP)} &#5B6472| &#8A96A8Clean &#D7DEE8${count(Target.ITEMS, ListDomain.NAME, ListKind.CLEAN)}",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8Left-Click to configure"
            )
        } else {
            listOf(
                "&#8A96A8当前状态 ${enabledText(RuleConfigEditor.enabled(Target.ITEMS))}",
                "&#8A96A8物品 ID &#8A96A8保留 &#D7DEE8${count(Target.ITEMS, ListDomain.MATERIAL, ListKind.KEEP)} &#5B6472| &#8A96A8清理 &#D7DEE8${count(Target.ITEMS, ListDomain.MATERIAL, ListKind.CLEAN)}",
                "&#8A96A8展示名 &#8A96A8保留 &#D7DEE8${count(Target.ITEMS, ListDomain.NAME, ListKind.KEEP)} &#5B6472| &#8A96A8清理 &#D7DEE8${count(Target.ITEMS, ListDomain.NAME, ListKind.CLEAN)}",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8左键进入设置"
            )
        })
        setLore(inventory, mainTemplate.slots('E'), if (isEn) {
            listOf(
                "&#8A96A8State ${enabledText(RuleConfigEditor.enabled(Target.ENTITIES))}",
                "&#8A96A8Entity IDs &#8A96A8Keep &#D7DEE8${count(Target.ENTITIES, ListDomain.MATERIAL, ListKind.KEEP)} &#5B6472| &#8A96A8Clean &#D7DEE8${count(Target.ENTITIES, ListDomain.MATERIAL, ListKind.CLEAN)}",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8Left-Click to configure"
            )
        } else {
            listOf(
                "&#8A96A8当前状态 ${enabledText(RuleConfigEditor.enabled(Target.ENTITIES))}",
                "&#8A96A8实体 ID &#8A96A8保留 &#D7DEE8${count(Target.ENTITIES, ListDomain.MATERIAL, ListKind.KEEP)} &#5B6472| &#8A96A8清理 &#D7DEE8${count(Target.ENTITIES, ListDomain.MATERIAL, ListKind.CLEAN)}",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8左键进入设置"
            )
        })
        setLore(inventory, mainTemplate.slots('R'), if (isEn) {
            listOf(
                "&#8A96A8State ${enabledText(RuleConfigEditor.enabled(Target.REALTIME))}",
                "&#8A96A8Mode &#D7DEE8${RuleConfigEditor.mode(Target.REALTIME)}",
                "&#8A96A8Lists &#8A96A8Keep &#D7DEE8${count(Target.REALTIME, ListDomain.MATERIAL, ListKind.KEEP)} &#5B6472| &#8A96A8Intercept &#D7DEE8${count(Target.REALTIME, ListDomain.MATERIAL, ListKind.CLEAN)}",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8Left-Click to configure"
            )
        } else {
            listOf(
                "&#8A96A8当前状态 ${enabledText(RuleConfigEditor.enabled(Target.REALTIME))}",
                "&#8A96A8名单模式 &#D7DEE8${RuleConfigEditor.mode(Target.REALTIME)}",
                "&#8A96A8实体名单 &#8A96A8保留 &#D7DEE8${count(Target.REALTIME, ListDomain.MATERIAL, ListKind.KEEP)} &#5B6472| &#8A96A8拦截 &#D7DEE8${count(Target.REALTIME, ListDomain.MATERIAL, ListKind.CLEAN)}",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8左键进入设置"
            )
        })
    }

    private fun drawTargetState(inventory: Inventory, target: Target) {
        val isEn = Language.isEnglish
        setLore(inventory, targetTemplate.slots('T'), if (isEn) {
            listOf(
                "&#8A96A8State ${enabledText(RuleConfigEditor.enabled(target))}",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8Left-Click to toggle"
            )
        } else {
            listOf(
                "&#8A96A8当前状态 ${enabledText(RuleConfigEditor.enabled(target))}",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8左键切换状态"
            )
        })
        setLore(inventory, targetTemplate.slots('M'), if (isEn) {
            listOf(
                "&#8A96A8Mode &#D7DEE8${RuleConfigEditor.mode(target)}",
                "&#5B6472Lists can configure match modes individually",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8Left-Click to switch mode"
            )
        } else {
            listOf(
                "&#8A96A8名单模式 &#D7DEE8${RuleConfigEditor.mode(target)}",
                "&#5B6472各名单可单独配置匹配方式",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8左键切换模式"
            )
        })

        setLore(inventory, targetTemplate.slots('S'), summaryLore(target))
        setListButtonLore(inventory, targetTemplate.slots('K'), target, ListDomain.MATERIAL, ListKind.KEEP, if (isEn) "&#34D399Kept, will not be cleaned" else "&#34D399保留，不会被清理")
        setListButtonLore(inventory, targetTemplate.slots('C'), target, ListDomain.MATERIAL, ListKind.CLEAN, if (isEn) "&#F87171Cleaned on match" else "&#F87171命中后会被清理")
        if (target == Target.ITEMS) {
            setListButtonLore(inventory, targetTemplate.slots('A'), target, ListDomain.NAME, ListKind.KEEP, if (isEn) "&#34D399Kept when name matches" else "&#34D399展示名命中后保留")
            setListButtonLore(inventory, targetTemplate.slots('D'), target, ListDomain.NAME, ListKind.CLEAN, if (isEn) "&#F87171Cleaned when name matches" else "&#F87171展示名命中后清理")
            setListButtonLore(inventory, targetTemplate.slots('G'), target, ListDomain.LORE, ListKind.KEEP, if (isEn) "&#34D399Kept when lore matches" else "&#34D399Lore 命中后保留")
            setListButtonLore(inventory, targetTemplate.slots('H'), target, ListDomain.LORE, ListKind.CLEAN, if (isEn) "&#F87171Cleaned when lore matches" else "&#F87171Lore 命中后清理")
        } else {
            val unavailable = listOf(if (isEn) "&#5B6472Available for Item Rules only" else "&#5B6472仅掉落物规则可用")
            setLore(inventory, targetTemplate.slots('A'), unavailable)
            setLore(inventory, targetTemplate.slots('D'), unavailable)
            setLore(inventory, targetTemplate.slots('G'), unavailable)
            setLore(inventory, targetTemplate.slots('H'), unavailable)
        }
    }

    private fun drawListState(
        inventory: Inventory,
        target: Target,
        domain: ListDomain,
        kind: ListKind,
        page: Int,
        totalPages: Int,
        empty: Boolean
    ) {
        val isEn = Language.isEnglish
        val mode = RuleConfigEditor.matchMode(target, domain, kind)
        setLore(inventory, listTemplate.slots('P'), if (page > 0) {
            if (isEn) listOf("&#8A96A8Page &#D7DEE8${page + 1}", "", "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈", "&#D7DEE8› &#8A96A8Left-Click for prev page")
            else listOf("&#8A96A8当前第 &#D7DEE8${page + 1} &#8A96A8页", "", "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈", "&#D7DEE8› &#8A96A8左键上一页")
        } else {
            listOf(if (isEn) "&#5B6472First page" else "&#5B6472已经是第一页")
        })
        setLore(inventory, listTemplate.slots('N'), if (page < totalPages - 1) {
            if (isEn) listOf("&#8A96A8Page &#D7DEE8${page + 1}", "", "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈", "&#D7DEE8› &#8A96A8Left-Click for next page")
            else listOf("&#8A96A8当前第 &#D7DEE8${page + 1} &#8A96A8页", "", "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈", "&#D7DEE8› &#8A96A8左键下一页")
        } else {
            listOf(if (isEn) "&#5B6472Last page" else "&#5B6472已经是最后一页")
        })
        setLore(inventory, listTemplate.slots('B'), if (isEn) {
            listOf("&#8A96A8Back to ${target.display} Settings", "", "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈", "&#D7DEE8› &#8A96A8Left-Click to return")
        } else {
            listOf("&#8A96A8返回 ${target.display} 设置", "", "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈", "&#D7DEE8› &#8A96A8左键返回")
        })
        if (empty) {
            val slot = listTemplate.slots('*').firstOrNull() ?: return
            val material = Material.matchMaterial("PAPER") ?: Material.STONE
            val item = ItemStack(material)
            val meta = item.itemMeta
            if (meta != null) {
                meta.setDisplayName(ColorUtils.color(if (isEn) "&#8A96A8List is currently empty" else "&#8A96A8当前名单为空"))
                meta.lore = listOf(
                    ColorUtils.color(if (isEn) "&#8A96A8Match Mode &#D7DEE8$mode" else "&#8A96A8匹配方式 &#D7DEE8$mode"),
                    ColorUtils.color(""),
                    ColorUtils.color("&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈"),
                    ColorUtils.color(if (isEn) "&#D7DEE8› &#8A96A8Shift-Click inventory item to add" else "&#D7DEE8› &#8A96A8Shift+点击背包物品添加")
                )
                item.itemMeta = meta
            }
            inventory.setItem(slot, item)
        }
    }

    private fun summaryLore(target: Target): List<String> {
        val isEn = Language.isEnglish
        val material = if (isEn) {
            "&#8A96A8IDs &#8A96A8Keep &#D7DEE8${count(target, ListDomain.MATERIAL, ListKind.KEEP)} &#5B6472| &#8A96A8Clean &#D7DEE8${count(target, ListDomain.MATERIAL, ListKind.CLEAN)}"
        } else {
            "&#8A96A8ID &#8A96A8保留 &#D7DEE8${count(target, ListDomain.MATERIAL, ListKind.KEEP)} &#5B6472| &#8A96A8清理 &#D7DEE8${count(target, ListDomain.MATERIAL, ListKind.CLEAN)}"
        }
        if (target != Target.ITEMS) return listOf(material)
        return if (isEn) {
            listOf(
                material,
                "&#8A96A8Names &#8A96A8Keep &#D7DEE8${count(target, ListDomain.NAME, ListKind.KEEP)} &#5B6472| &#8A96A8Clean &#D7DEE8${count(target, ListDomain.NAME, ListKind.CLEAN)}",
                "&#8A96A8Lore &#8A96A8Keep &#D7DEE8${count(target, ListDomain.LORE, ListKind.KEEP)} &#5B6472| &#8A96A8Clean &#D7DEE8${count(target, ListDomain.LORE, ListKind.CLEAN)}"
            )
        } else {
            listOf(
                material,
                "&#8A96A8展示名 &#8A96A8保留 &#D7DEE8${count(target, ListDomain.NAME, ListKind.KEEP)} &#5B6472| &#8A96A8清理 &#D7DEE8${count(target, ListDomain.NAME, ListKind.CLEAN)}",
                "&#8A96A8Lore &#8A96A8保留 &#D7DEE8${count(target, ListDomain.LORE, ListKind.KEEP)} &#5B6472| &#8A96A8清理 &#D7DEE8${count(target, ListDomain.LORE, ListKind.CLEAN)}"
            )
        }
    }

    private fun setListButtonLore(
        inventory: Inventory,
        slots: List<Int>,
        target: Target,
        domain: ListDomain,
        kind: ListKind,
        description: String
    ) {
        val isEn = Language.isEnglish
        setLore(inventory, slots, if (isEn) {
            listOf(
                description,
                "&#8A96A8Count &#D7DEE8${count(target, domain, kind)}",
                "&#8A96A8Match &#D7DEE8${RuleConfigEditor.matchMode(target, domain, kind)}",
                if (domain == ListDomain.MATERIAL || target != Target.ITEMS) "" else "&#8A96A8Rule ${enabledText(RuleConfigEditor.domainEnabled(target, domain))}",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8Left-Click to edit list",
                "&#D7DEE8› &#8A96A8Right-Click to change match mode",
                if (domain == ListDomain.MATERIAL || target != Target.ITEMS) "" else "&#D7DEE8› &#8A96A8Shift+Left-Click to toggle rule"
            ).filter { it.isNotEmpty() }
        } else {
            listOf(
                description,
                "&#8A96A8数量 &#D7DEE8${count(target, domain, kind)}",
                "&#8A96A8匹配 &#D7DEE8${RuleConfigEditor.matchMode(target, domain, kind)}",
                if (domain == ListDomain.MATERIAL || target != Target.ITEMS) "" else "&#8A96A8规则 ${enabledText(RuleConfigEditor.domainEnabled(target, domain))}",
                "",
                "&#3A4352┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈",
                "&#D7DEE8› &#8A96A8左键编辑名单",
                "&#D7DEE8› &#8A96A8右键切换匹配方式",
                if (domain == ListDomain.MATERIAL || target != Target.ITEMS) "" else "&#D7DEE8› &#8A96A8Shift+左键开关规则"
            ).filter { it.isNotEmpty() }
        })
    }

    private fun count(target: Target, domain: ListDomain, kind: ListKind): Int =
        RuleConfigEditor.list(target, domain, kind).size

    private fun enabledText(enabled: Boolean): String {
        val isEn = Language.isEnglish
        return if (enabled) (if (isEn) "&#34D399ON" else "&#34D399开启") else (if (isEn) "&#F87171OFF" else "&#F87171关闭")
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

    private fun setLore(inventory: Inventory, slots: List<Int>, lines: List<String>) {
        for (slot in slots) {
            val item = inventory.getItem(slot) ?: continue
            val meta = item.itemMeta ?: continue
            meta.lore = lines.map(ColorUtils::color)
            item.itemMeta = meta
        }
    }
}

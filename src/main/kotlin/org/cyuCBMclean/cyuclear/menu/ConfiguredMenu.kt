package org.cyuCBMclean.cyuclear.menu

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.util.ColorUtils
import java.io.File

class ConfiguredMenu(
    val title: String,
    val layout: List<String>,
    private val items: Map<Char, MenuItemTemplate>,
    private val buttonActions: Map<Char, List<String>>
) {
    private val slotsBySymbol: Map<Char, List<Int>> = indexLayout(layout)

    val size: Int
        get() = layout.size * 9

    fun slots(symbol: Char): List<Int> = slotsBySymbol[symbol].orEmpty()

    fun item(symbol: Char, player: Player): ItemStack? = items[symbol]?.render(player)

    fun actions(symbol: Char): List<String> = buttonActions[symbol] ?: emptyList()

    fun dispatch(player: Player, slot: Int, bindings: MenuActionBindings): Boolean {
        val symbol = symbolAt(slot) ?: return false
        if (symbol == '*') return false
        val actions = actions(symbol)
        if (actions.isEmpty()) return false
        MenuActionExecutor.execute(player, actions, bindings)
        return true
    }

    private fun symbolAt(slot: Int): Char? {
        if (slot < 0 || slot >= size) return null
        return layout[slot / 9].getOrNull(slot % 9)
    }

    companion object {
        fun load(path: String): ConfiguredMenu {
            val plugin = Cyuclear.instance
            val file = File(plugin.dataFolder, path)
            if (!file.exists()) plugin.saveResource(path.replace('\\', '/'), false)
            val config = YamlConfiguration.loadConfiguration(file)
            val layout = config.getStringList("layout").take(6).map { it.padEnd(9).take(9) }
                .ifEmpty { listOf("         ") }
            val items = LinkedHashMap<Char, MenuItemTemplate>()
            val buttonActions = LinkedHashMap<Char, List<String>>()
            val section = config.getConfigurationSection("items")
            if (section != null) {
                for (key in section.getKeys(false)) {
                    val symbol = key.firstOrNull() ?: continue
                    val itemSection = section.getConfigurationSection(key) ?: continue
                    val itemKey = "$path:items.$key"
                    val actions = itemSection.getStringList("actions")
                    val usableActions = if (symbol == '*' && actions.isNotEmpty()) {
                        plugin.logger.warning("菜单 $itemKey 的 * 是内容格，不能配置 actions")
                        emptyList()
                    } else {
                        MenuActionExecutor.validate(actions, itemKey)
                        actions
                    }
                    items[symbol] = MenuItemTemplate.from(itemSection, itemKey)
                    buttonActions[symbol] = usableActions
                }
            }
            return ConfiguredMenu(
                ColorUtils.color(config.getString("title", "&8CYUCLEAR") ?: "&8CYUCLEAR"),
                layout,
                items,
                buttonActions
            )
        }

        private fun indexLayout(layout: List<String>): Map<Char, List<Int>> {
            val slots = LinkedHashMap<Char, MutableList<Int>>()
            for ((row, line) in layout.withIndex()) {
                for (column in line.indices) {
                    slots.getOrPut(line[column]) { ArrayList() }.add(row * 9 + column)
                }
            }
            return slots
        }
    }
}

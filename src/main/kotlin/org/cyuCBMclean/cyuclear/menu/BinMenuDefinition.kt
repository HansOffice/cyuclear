package org.cyuCBMclean.cyuclear.menu

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.util.ColorUtils
import java.io.File

object BinMenuDefinition {

    data class Definition internal constructor(
        val title: String,
        val layout: List<String>,
        private val buttons: Map<Char, Button>
    ) {
        val height: Int
            get() = layout.size

        fun button(symbol: Char): Button? = buttons[symbol]
    }

    @Volatile
    private var definition = Definition("", emptyList(), emptyMap())

    fun snapshot(): Definition = definition

    fun load() {
        MenuIconFactory.reload()

        val file = File(Cyuclear.instance.dataFolder, "menu/bin.yml")
        if (!file.exists()) {
            val oldFile = File(Cyuclear.instance.dataFolder, "bin-menu.yml")
            if (oldFile.exists()) {
                file.parentFile.mkdirs()
                oldFile.copyTo(file, overwrite = false)
                Cyuclear.instance.logger.info("已将 bin-menu.yml 迁移到 menu/bin.yml，旧文件仍保留")
            } else {
                Cyuclear.instance.saveResource("menu/bin.yml", false)
            }
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val title = ColorUtils.color(config.getString("title", "&8CYUCLEAR | 虚空垃圾桶 ({page}/{total})")!!)
        if (config.contains("rows")) {
            Cyuclear.instance.logger.warning("menu/bin.yml 的 rows 已停止使用，菜单高度现在由 layout 行数决定，请删除 rows")
        }

        val loadedLayout = config.getStringList("layout")
        val layout = when {
            loadedLayout.isEmpty() -> {
                Cyuclear.instance.logger.warning("menu/bin.yml 缺少 layout，已临时使用一行空菜单")
                listOf("         ")
            }
            else -> {
                if (loadedLayout.size > 6) {
                    Cyuclear.instance.logger.warning("menu/bin.yml 的 layout 超过 6 行，只会读取前 6 行")
                }
                loadedLayout.take(6)
            }
        }

        val loadedButtons = LinkedHashMap<Char, Button>()
        val itemsSection = config.getConfigurationSection("items")
        if (itemsSection != null) {
            for (key in itemsSection.getKeys(false)) {
                val symbol = key.firstOrNull() ?: continue
                val section = itemsSection.getConfigurationSection(key) ?: continue
                val actions = section.getStringList("actions")
                val itemKey = "menu/bin.yml:items.$key"
                MenuActionExecutor.validate(actions, itemKey)
                loadedButtons[symbol] = Button(MenuItemTemplate.from(section, itemKey), actions)
            }
        }
        definition = Definition(title, layout, loadedButtons)
    }

    class Button internal constructor(
        private val template: MenuItemTemplate,
        val actions: List<String>
    ) {
        fun render(player: Player) = template.render(player)
    }
}

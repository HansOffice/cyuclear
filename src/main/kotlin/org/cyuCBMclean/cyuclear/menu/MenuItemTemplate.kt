package org.cyuCBMclean.cyuclear.menu

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.util.ColorUtils

class MenuItemTemplate private constructor(
    private val baseItem: ItemStack,
    private val name: String?,
    private val lore: List<String>,
    private val headSource: String?,
    private val itemKey: String
) {

    private val dynamic = MenuHeadFactory.isDynamic(headSource) ||
        name?.contains('%') == true ||
        lore.any { it.contains('%') }

    private val staticItem: ItemStack? = if (dynamic) null else renderInternal(null)

    fun render(player: Player): ItemStack {
        return staticItem?.clone() ?: renderInternal(player)
    }

    private fun renderInternal(player: Player?): ItemStack {
        val item = baseItem.clone()
        val meta = item.itemMeta
        if (meta != null) {
            if (!name.isNullOrEmpty()) {
                meta.setDisplayName(ColorUtils.color(MenuTextResolver.resolve(player, name)))
            }
            if (lore.isNotEmpty()) {
                meta.lore = lore.map { ColorUtils.color(MenuTextResolver.resolve(player, it)) }
            }
            item.itemMeta = meta
        }
        return MenuHeadFactory.apply(item, headSource?.let { MenuTextResolver.resolve(player, it) }, itemKey)
    }

    companion object {
        fun from(section: ConfigurationSection, itemKey: String): MenuItemTemplate {
            val material = section.getString("material", "STONE") ?: "STONE"
            return MenuItemTemplate(
                MenuIconFactory.create(section, itemKey),
                section.getString("name"),
                section.getStringList("lore"),
                MenuHeadFactory.sourceOf(section, material),
                itemKey
            )
        }
    }
}

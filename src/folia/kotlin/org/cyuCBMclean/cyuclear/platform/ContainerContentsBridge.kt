package org.cyuCBMclean.cyuclear.platform

import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta

object ContainerContentsBridge {
    fun isNonEmpty(stack: ItemStack): Boolean {
        val material = stack.type.name
        val meta = stack.itemMeta ?: return false
        return when {
            material == "BUNDLE" || material.endsWith("_BUNDLE") -> {
                (meta as? BundleMeta)?.items?.isNotEmpty() ?: true
            }
            material.endsWith("_SHULKER_BOX") -> shulkerIsNonEmpty(meta as? BlockStateMeta)
            else -> false
        }
    }

    private fun shulkerIsNonEmpty(meta: BlockStateMeta?): Boolean {
        if (meta == null) return true
        return runCatching {
            val state = meta.blockState as? Container ?: return@runCatching true
            state.inventory.contents.any(::occupied)
        }.getOrDefault(true)
    }

    private fun occupied(item: ItemStack?): Boolean {
        return item != null && item.type != Material.AIR && item.amount > 0
    }
}

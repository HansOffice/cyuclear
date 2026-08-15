package org.cyuCBMclean.cyuclear.platform

import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

object ContainerContentsBridge {
    private val bundleItems = ConcurrentHashMap<Class<*>, Optional<Method>>()

    fun isNonEmpty(stack: ItemStack): Boolean {
        val material = stack.type.name
        return when {
            material == "BUNDLE" || material.endsWith("_BUNDLE") -> bundleIsNonEmpty(stack)
            material.endsWith("_SHULKER_BOX") -> shulkerIsNonEmpty(stack)
            else -> false
        }
    }

    private fun bundleIsNonEmpty(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        val method = bundleItems.computeIfAbsent(meta.javaClass) { type ->
            Optional.ofNullable(runCatching { type.getMethod("getItems") }.getOrNull())
        }
        if (!method.isPresent) return true
        return runCatching {
            (method.get().invoke(meta) as? Collection<*>)?.isNotEmpty() ?: true
        }.getOrDefault(true)
    }

    private fun shulkerIsNonEmpty(stack: ItemStack): Boolean {
        val meta = stack.itemMeta as? BlockStateMeta ?: return true
        return runCatching {
            val state = meta.blockState as? Container ?: return@runCatching true
            state.inventory.contents.any(::occupied)
        }.getOrDefault(true)
    }

    private fun occupied(item: ItemStack?): Boolean {
        return item != null && item.type != Material.AIR && item.amount > 0
    }
}

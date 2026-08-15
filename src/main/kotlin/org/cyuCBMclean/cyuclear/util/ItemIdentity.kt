package org.cyuCBMclean.cyuclear.util

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.platform.CustomModelDataBridge
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object ItemIdentity {

    @Volatile
    private var externalResolvers: List<ExternalResolver> = emptyList()

    private val vanillaMatchIds = ConcurrentHashMap<Material, List<String>>()

    fun reloadExternalResolvers() {
        val loaded = ArrayList<ExternalResolver>()
        register(loaded, ExternalSource.ITEMS_ADDER, findEnabledPlugin("ItemsAdder"), ::createItemsAdderResolver)
        register(loaded, ExternalSource.ORAXEN, findEnabledPlugin("Oraxen"), ::createOraxenResolver)
        register(loaded, ExternalSource.CRAFT_ENGINE, findEnabledPlugin("CraftEngine", "CE"), ::createCraftEngineResolver)
        externalResolvers = loaded
    }

    fun baseId(material: Material): String = IdCompat.materialId(material)

    fun customModelData(stack: ItemStack): Int? {
        if (!stack.hasItemMeta()) return null
        val meta = stack.itemMeta ?: return null
        return CustomModelDataBridge.read(meta)?.takeIf { it > 0 }
    }

    fun ruleId(stack: ItemStack): String = matchIds(stack).first()

    fun matchIds(stack: ItemStack): List<String> {
        val resolvers = externalResolvers
        if (resolvers.isEmpty() && !stack.hasItemMeta()) {
            return vanillaMatchIds.computeIfAbsent(stack.type) { material -> listOf(baseId(material)) }
        }

        val ids = LinkedHashSet<String>()
        for (resolver in resolvers) {
            val customId = resolver.resolve(stack) ?: continue
            ids.addAll(resolver.source.ids(customId))
            break
        }

        val base = baseId(stack.type)
        val cmd = customModelData(stack)
        if (cmd != null) ids.add(format(base, cmd))
        ids.add(base)
        return ids.toList()
    }

    fun matches(matcher: IdMatcher, stack: ItemStack): Boolean {
        return matcher.matchesAnyNormalized(matchIds(stack))
    }

    fun applyCustomModelData(stack: ItemStack, value: Int): Boolean {
        if (value <= 0) return false
        val meta = stack.itemMeta ?: return false
        if (!CustomModelDataBridge.write(meta, value)) return false
        stack.itemMeta = meta
        return true
    }

    fun parseRuleId(raw: String): Pair<String, Int?> {
        val trimmed = raw.trim()
        val hash = trimmed.lastIndexOf('#')
        if (hash <= 0 || hash >= trimmed.lastIndex) {
            return trimmed.lowercase(Locale.ROOT) to null
        }
        val base = trimmed.substring(0, hash).trim().lowercase(Locale.ROOT)
        val suffix = trimmed.substring(hash + 1).trim().lowercase(Locale.ROOT)
        val cmd = when {
            suffix.startsWith("cmd=") -> suffix.removePrefix("cmd=").toIntOrNull()
            else -> suffix.toIntOrNull()
        }
        return if (cmd != null && cmd > 0) base to cmd else trimmed.lowercase(Locale.ROOT) to null
    }

    private fun format(base: String, cmd: Int): String = "$base#$cmd"

    private enum class ExternalSource(
        val displayName: String,
        private val shortPrefix: String,
        private val longPrefix: String
    ) {
        ITEMS_ADDER("ItemsAdder", "ia", "itemsadder"),
        ORAXEN("Oraxen", "ox", "oraxen"),
        CRAFT_ENGINE("CraftEngine", "ce", "craftengine");

        fun ids(rawId: String): List<String> {
            val id = rawId.trim().lowercase(Locale.ROOT)
            if (id.isEmpty()) return emptyList()
            return listOf("$shortPrefix:$id", "$longPrefix:$id")
        }
    }

    private interface ExternalLookup {
        fun resolve(stack: ItemStack): String?
    }

    private class ExternalResolver(
        val source: ExternalSource,
        private val lookup: ExternalLookup
    ) {
        private val active = AtomicBoolean(true)

        fun resolve(stack: ItemStack): String? {
            if (!active.get()) return null
            return try {
                lookup.resolve(stack)?.trim()?.takeIf { it.isNotEmpty() }
            } catch (ex: Exception) {
                disable(ex)
                null
            } catch (ex: LinkageError) {
                disable(ex)
                null
            }
        }

        private fun disable(ex: Throwable) {
            if (active.compareAndSet(true, false)) {
                Cyuclear.instance.logger.warning(
                    "${source.displayName} 物品身份适配器运行失败，本次运行已停用：${errorText(ex)}"
                )
            }
        }
    }

    private fun register(
        target: MutableList<ExternalResolver>,
        source: ExternalSource,
        plugin: Plugin?,
        factory: (Plugin) -> ExternalLookup
    ) {
        if (plugin == null) return
        try {
            target.add(ExternalResolver(source, factory(plugin)))
        } catch (ex: Exception) {
            logAdapterFailure(source, ex)
        } catch (ex: LinkageError) {
            logAdapterFailure(source, ex)
        }
    }

    private fun createItemsAdderResolver(plugin: Plugin): ExternalLookup {
        val customStackClass = load(plugin, "dev.lone.itemsadder.api.CustomStack")
        val byItemStack = customStackClass.getMethod("byItemStack", ItemStack::class.java)
        val getNamespacedId = customStackClass.getMethod("getNamespacedID")
        return object : ExternalLookup {
            override fun resolve(stack: ItemStack): String? {
                val customStack = byItemStack.invoke(null, stack) ?: return null
                return getNamespacedId.invoke(customStack)?.toString()
            }
        }
    }

    private fun createOraxenResolver(plugin: Plugin): ExternalLookup {
        val apiClass = load(plugin, "io.th0rgal.oraxen.api.OraxenItems")
        val getIdByItem = apiClass.getMethod("getIdByItem", ItemStack::class.java)
        return object : ExternalLookup {
            override fun resolve(stack: ItemStack): String? = getIdByItem.invoke(null, stack) as? String
        }
    }

    private fun createCraftEngineResolver(plugin: Plugin): ExternalLookup {
        val apiClass = load(plugin, "net.momirealms.craftengine.bukkit.api.CraftEngineItems")
        val getCustomItemId = apiClass.getMethod("getCustomItemId", ItemStack::class.java)
        return object : ExternalLookup {
            override fun resolve(stack: ItemStack): String? = getCustomItemId.invoke(null, stack)?.toString()
        }
    }

    private fun findEnabledPlugin(vararg names: String): Plugin? {
        val manager = Cyuclear.instance.server.pluginManager
        for (name in names) {
            val plugin = manager.getPlugin(name)
            if (plugin != null && plugin.isEnabled) return plugin
        }
        return null
    }

    private fun load(plugin: Plugin, className: String): Class<*> {
        return Class.forName(className, false, plugin.javaClass.classLoader)
    }

    private fun logAdapterFailure(source: ExternalSource, ex: Throwable) {
        Cyuclear.instance.logger.warning(
            "检测到 ${source.displayName}，但物品身份适配器初始化失败：${errorText(ex)}"
        )
    }

    private fun errorText(ex: Throwable): String {
        val cause = ex.cause ?: ex
        return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
    }

}

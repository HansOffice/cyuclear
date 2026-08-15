package org.cyuCBMclean.cyuclear.menu

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.util.ItemIdentity
import java.lang.reflect.Method
import java.util.Locale

object MenuIconFactory {

    private enum class Source(val displayName: String) {
        ITEMS_ADDER("ItemsAdder"),
        ORAXEN("Oraxen"),
        CRAFT_ENGINE("CraftEngine")
    }

    private data class ExternalSpec(val source: Source, val id: String)

    private interface ExternalProvider {
        fun create(id: String): ItemStack?
    }

    private var providers: Map<Source, ExternalProvider> = emptyMap()

    fun reload() {
        val loaded = HashMap<Source, ExternalProvider>()
        register(loaded, Source.ITEMS_ADDER, findEnabledPlugin("ItemsAdder"), ::createItemsAdderProvider)
        register(loaded, Source.ORAXEN, findEnabledPlugin("Oraxen"), ::createOraxenProvider)
        register(loaded, Source.CRAFT_ENGINE, findEnabledPlugin("CraftEngine", "CE"), ::createCraftEngineProvider)
        providers = loaded
        MenuHeadFactory.reload()
    }

    fun create(section: ConfigurationSection, itemKey: String): ItemStack {
        val rawMaterial = section.getString("material", "STONE")!!.trim().ifEmpty { "STONE" }
        val fallbackMaterial = firstString(section, "fallback-material", "fallback_material") ?: "STONE"
        val data = section.getInt("data", 0).coerceIn(0, Short.MAX_VALUE.toInt())

        val item = if (MenuHeadFactory.requiresHead(section, rawMaterial)) {
            MenuHeadFactory.createHead(data)
        } else {
            val externalSpec = parseExternal(rawMaterial)
            if (externalSpec == null) {
                createVanilla(rawMaterial, data, itemKey, fallbackMaterial)
            } else {
                createExternal(externalSpec, fallbackMaterial, data, itemKey)
            }
        }

        val customModelData = firstInt(section, "custom-model-data", "custom_model_data")
        if (customModelData != null && !ItemIdentity.applyCustomModelData(item, customModelData)) {
            Cyuclear.instance.logger.warning(
                "菜单 items.$itemKey 配置了 custom-model-data，" +
                    "但当前服务端版本不支持该物品数据，已忽略"
            )
        }

        item.amount = 1
        return item
    }

    private fun createExternal(
        spec: ExternalSpec,
        fallbackMaterial: String,
        data: Int,
        itemKey: String
    ): ItemStack {
        val provider = providers[spec.source]
        if (provider == null) {
            Cyuclear.instance.logger.warning(
                "菜单 items.$itemKey 使用 ${spec.source.displayName} 图标，" +
                    "但对应插件未启用或适配器不可用，已回退到 $fallbackMaterial"
            )
            return createVanilla(fallbackMaterial, data, itemKey)
        }

        return try {
            provider.create(spec.id)?.clone() ?: run {
                Cyuclear.instance.logger.warning(
                    "菜单 items.$itemKey 未找到 ${spec.source.displayName} 物品 '${spec.id}'，" +
                        "已回退到 $fallbackMaterial"
                )
                createVanilla(fallbackMaterial, data, itemKey)
            }
        } catch (ex: Exception) {
            externalFailure(spec, fallbackMaterial, data, itemKey, ex)
        } catch (ex: LinkageError) {
            externalFailure(spec, fallbackMaterial, data, itemKey, ex)
        }
    }

    private fun externalFailure(
        spec: ExternalSpec,
        fallbackMaterial: String,
        data: Int,
        itemKey: String,
        ex: Throwable
    ): ItemStack {
        Cyuclear.instance.logger.warning(
            "菜单 items.$itemKey 创建 ${spec.source.displayName} 物品 '${spec.id}' 失败，" +
                "已回退到 $fallbackMaterial：${errorText(ex)}"
        )
        return createVanilla(fallbackMaterial, data, itemKey)
    }

    private fun createVanilla(raw: String, data: Int, itemKey: String, fallback: String = "STONE"): ItemStack {
        val primary = matchMaterialName(raw)
        if (primary != null) {
            return ItemStack(primary, 1, data.toShort())
        }

        val fallbackMaterial = matchMaterialName(fallback)
        if (fallbackMaterial != null) {
            return ItemStack(fallbackMaterial, 1, data.toShort())
        }

        Cyuclear.instance.logger.warning(
            "菜单 items.$itemKey 使用了未知原版材质 '$raw'（fallback=$fallback），已回退到 STONE"
        )
        return ItemStack(Material.STONE, 1, data.toShort())
    }

    private fun matchMaterialName(raw: String): Material? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val normalized = (if (trimmed.startsWith("minecraft:", ignoreCase = true)) {
            trimmed.substring("minecraft:".length)
        } else {
            trimmed
        }).uppercase(Locale.ROOT)
        return Material.matchMaterial(normalized)
    }

    private fun parseExternal(raw: String): ExternalSpec? {
        val separator = raw.indexOf(':')
        if (separator <= 0 || separator >= raw.lastIndex) {
            return null
        }

        val prefix = raw.substring(0, separator).lowercase(Locale.ROOT)
        val source = when (prefix) {
            "ia", "itemsadder" -> Source.ITEMS_ADDER
            "ox", "oraxen" -> Source.ORAXEN
            "ce", "craftengine" -> Source.CRAFT_ENGINE
            else -> return null
        }
        return ExternalSpec(source, raw.substring(separator + 1).trim())
    }

    private fun register(
        target: MutableMap<Source, ExternalProvider>,
        source: Source,
        plugin: Plugin?,
        factory: (Plugin) -> ExternalProvider
    ) {
        if (plugin == null) {
            return
        }

        try {
            target[source] = factory(plugin)
        } catch (ex: Exception) {
            logAdapterFailure(source, ex)
        } catch (ex: LinkageError) {
            logAdapterFailure(source, ex)
        }
    }

    private fun logAdapterFailure(source: Source, ex: Throwable) {
        Cyuclear.instance.logger.warning(
            "检测到 ${source.displayName}，但菜单图标适配器初始化失败：${errorText(ex)}"
        )
    }

    private fun createItemsAdderProvider(plugin: Plugin): ExternalProvider {
        val customStackClass = load(plugin, "dev.lone.itemsadder.api.CustomStack")
        val getInstance = customStackClass.getMethod("getInstance", String::class.java)
        val getItemStack = customStackClass.getMethod("getItemStack")

        return object : ExternalProvider {
            override fun create(id: String): ItemStack? {
                val customStack = getInstance.invoke(null, id.trim()) ?: return null
                return getItemStack.invoke(customStack) as? ItemStack
            }
        }
    }

    private fun createOraxenProvider(plugin: Plugin): ExternalProvider {
        val oraxenItemsClass = load(plugin, "io.th0rgal.oraxen.api.OraxenItems")
        val getItemById = oraxenItemsClass.getMethod("getItemById", String::class.java)
        val build = getItemById.returnType.getMethod("build")

        return object : ExternalProvider {
            override fun create(id: String): ItemStack? {
                val builder = getItemById.invoke(null, id.trim()) ?: return null
                return build.invoke(builder) as? ItemStack
            }
        }
    }

    private fun createCraftEngineProvider(plugin: Plugin): ExternalProvider {
        val apiClass = load(plugin, "net.momirealms.craftengine.bukkit.api.CraftEngineItems")
        val stringLookup = apiClass.methods.firstOrNull {
            it.name == "byId" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        }

        if (stringLookup != null) {
            return CraftEngineProvider(stringLookup, null)
        }

        val keyClass = load(plugin, "net.momirealms.craftengine.core.util.Key")
        val keyOf = keyClass.getMethod("of", String::class.java)
        val keyLookup = apiClass.getMethod("byId", keyClass)
        return CraftEngineProvider(keyLookup, keyOf)
    }

    private class CraftEngineProvider(
        private val lookup: Method,
        private val keyOf: Method?
    ) : ExternalProvider {
        @Volatile
        private var buildBukkitItem: Method? = null

        override fun create(id: String): ItemStack? {
            val argument = keyOf?.invoke(null, id.trim()) ?: id.trim()
            val definition = lookup.invoke(null, argument) ?: return null
            var build = buildBukkitItem
            if (build == null) {
                synchronized(this) {
                    build = buildBukkitItem
                    if (build == null) {
                        build = definition.javaClass.getMethod("buildBukkitItem")
                        buildBukkitItem = build
                    }
                }
            }
            return build!!.invoke(definition) as? ItemStack
        }
    }

    private fun findEnabledPlugin(vararg names: String): Plugin? {
        val manager = Cyuclear.instance.server.pluginManager
        for (name in names) {
            val plugin = manager.getPlugin(name)
            if (plugin != null && plugin.isEnabled) {
                return plugin
            }
        }
        return null
    }

    private fun load(plugin: Plugin, className: String): Class<*> {
        return Class.forName(className, false, plugin.javaClass.classLoader)
    }

    private fun firstString(section: ConfigurationSection, vararg keys: String): String? {
        for (key in keys) {
            if (section.contains(key)) {
                return section.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun firstInt(section: ConfigurationSection, vararg keys: String): Int? {
        for (key in keys) {
            if (section.contains(key)) {
                return section.getInt(key)
            }
        }
        return null
    }

    private fun errorText(ex: Throwable): String {
        val cause = ex.cause ?: ex
        return cause.message ?: cause.javaClass.simpleName
    }
}

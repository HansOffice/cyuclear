package org.cyuCBMclean.cyuclear.bridge

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object StackerBridge {
    internal data class Provider(
        val api: Any?,
        val itemLookup: Method?,
        val entityLookup: Method?,
        val itemAmount: Method?,
        val entityAmount: Method?,
        val itemRemove: Method?,
        val entityRemove: Method?
    )

    internal class Prepared internal constructor(
        private val entity: Entity,
        private val resolved: Resolved?
    ) {
        fun quantity(): Int = StackerBridge.stackQuantity(entity, resolved)

        fun remove() {
            StackerBridge.removeStack(entity, resolved)
        }
    }

    @Volatile
    private var providers = emptyList<Provider>()
    private val wrapperMethods = ConcurrentHashMap<Class<*>, Pair<Method?, Method?>>()

    fun reload() {
        wrapperMethods.clear()
        providers = listOfNotNull(
            loadProvider("RoseStacker", "dev.rosewood.rosestacker.api.RoseStackerAPI"),
            loadProvider("WildStacker", "com.bgsoftware.wildstacker.api.WildStackerAPI")
        )
    }

    internal fun prepare(entity: Entity): Prepared = Prepared(entity, resolve(entity))

    fun quantity(entity: Entity): Int = stackQuantity(entity, resolve(entity))

    private fun stackQuantity(entity: Entity, resolved: Resolved?): Int {
        val provider = resolved?.provider
        val directAmount = if (entity is Item) provider?.itemAmount else provider?.entityAmount
        if (provider != null && directAmount != null) {
            val value = runCatching { directAmount.invoke(provider.api, entity) as? Number }.getOrNull()
            if (value != null) return value.toInt().coerceAtLeast(1)
        }
        val wrapper = resolved?.wrapper ?: return vanillaQuantity(entity)
        val methods = wrapperMethods.computeIfAbsent(wrapper.javaClass) { type ->
            val amount = listOf("getStackSize", "getStackAmount", "getAmount", "getSize")
                .firstNotNullOfOrNull { name -> type.methods.firstOrNull { it.name == name && it.parameterCount == 0 } }
            val remove = listOf("remove", "removeStack", "destroy")
                .firstNotNullOfOrNull { name -> type.methods.firstOrNull { it.name == name && it.parameterCount == 0 } }
            amount to remove
        }
        return runCatching { (methods.first?.invoke(wrapper) as? Number)?.toInt() ?: 1 }.getOrDefault(1).coerceAtLeast(1)
    }

    fun remove(entity: Entity) {
        removeStack(entity, resolve(entity))
    }

    private fun removeStack(entity: Entity, resolved: Resolved?) {
        val wrapper = resolved?.wrapper
        if (wrapper != null) {
            val apiRemove = if (entity is Item) resolved.provider.itemRemove else resolved.provider.entityRemove
            if (apiRemove != null && runCatching { apiRemove.invoke(resolved.provider.api, wrapper); true }.getOrDefault(false)) return
            val remove = wrapperMethods.computeIfAbsent(wrapper.javaClass) { type ->
                val amount = listOf("getStackSize", "getStackAmount", "getAmount", "getSize")
                    .firstNotNullOfOrNull { name -> type.methods.firstOrNull { it.name == name && it.parameterCount == 0 } }
                val removeMethod = listOf("remove", "removeStack", "destroy")
                    .firstNotNullOfOrNull { name -> type.methods.firstOrNull { it.name == name && it.parameterCount == 0 } }
                amount to removeMethod
            }.second
            if (remove != null && runCatching { remove.invoke(wrapper); true }.getOrDefault(false)) return
        }
        entity.remove()
    }

    fun activeNames(): List<String> = listOf("RoseStacker", "WildStacker").filter { Bukkit.getPluginManager().isPluginEnabled(it) }

    internal data class Resolved(val provider: Provider, val wrapper: Any)

    private fun resolve(entity: Entity): Resolved? {
        for (provider in providers) {
            val method = if (entity is Item) provider.itemLookup else provider.entityLookup
            if (method != null) {
                val result = runCatching { method.invoke(provider.api, entity) }.getOrNull()
                val unwrapped = unwrap(result)
                if (unwrapped != null) return Resolved(provider, unwrapped)
            }
        }
        return null
    }

    private fun vanillaQuantity(entity: Entity): Int = if (entity is Item) entity.itemStack.amount.coerceAtLeast(1) else 1

    private fun unwrap(value: Any?): Any? {
        if (value == null) return null
        if (value is java.util.Optional<*>) return value.orElse(null)
        return value
    }

    private fun loadProvider(pluginName: String, className: String): Provider? {
        if (!Bukkit.getPluginManager().isPluginEnabled(pluginName)) return null
        return runCatching {
            val type = Class.forName(className)
            val instanceMethod = type.methods.firstOrNull { it.name == "getInstance" && java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
            val api = instanceMethod?.invoke(null)
            Provider(
                api = api,
                itemLookup = type.singleArgumentMethod("getStackedItem"),
                entityLookup = type.singleArgumentMethod("getStackedEntity"),
                itemAmount = type.singleArgumentMethod("getItemAmount"),
                entityAmount = type.singleArgumentMethod("getEntityAmount"),
                itemRemove = type.singleArgumentMethod("removeItemStack"),
                entityRemove = type.singleArgumentMethod("removeEntityStack")
            )
        }.onFailure {
            Bukkit.getLogger().warning("[Cyuclear] 无法接入 $pluginName：${it.message}")
        }.getOrNull()
    }

    private fun Class<*>.singleArgumentMethod(name: String): Method? = methods.firstOrNull { it.name == name && it.parameterCount == 1 }
}

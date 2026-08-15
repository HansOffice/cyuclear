package org.cyuCBMclean.cyuclear.platform

import org.bukkit.inventory.meta.ItemMeta
import java.lang.reflect.Method

object CustomModelDataBridge {
    private interface Accessor {
        fun read(meta: ItemMeta): Int?

        fun write(meta: ItemMeta, value: Int): Boolean
    }

    private val accessor = componentAccessor() ?: legacyAccessor() ?: UnsupportedAccessor

    fun read(meta: ItemMeta): Int? = accessor.read(meta)

    fun write(meta: ItemMeta, value: Int): Boolean = accessor.write(meta, value)

    private fun componentAccessor(): Accessor? {
        return runCatching {
            val getComponent = ItemMeta::class.java.getMethod("getCustomModelDataComponent")
            val componentType = getComponent.returnType
            val getFloats = componentType.getMethod("getFloats")
            val setFloats = componentType.getMethod("setFloats", List::class.java)
            val setComponent = ItemMeta::class.java.getMethod("setCustomModelDataComponent", componentType)
            ComponentAccessor(getComponent, getFloats, setFloats, setComponent)
        }.getOrNull()
    }

    private fun legacyAccessor(): Accessor? {
        return runCatching {
            val has = ItemMeta::class.java.getMethod("hasCustomModelData")
            val get = ItemMeta::class.java.getMethod("getCustomModelData")
            val set = ItemMeta::class.java.getMethod("setCustomModelData", Int::class.javaObjectType)
            LegacyAccessor(has, get, set)
        }.getOrNull()
    }

    private class ComponentAccessor(
        private val getComponent: Method,
        private val getFloats: Method,
        private val setFloats: Method,
        private val setComponent: Method
    ) : Accessor {
        override fun read(meta: ItemMeta): Int? {
            return runCatching {
                val component = getComponent.invoke(meta) ?: return@runCatching null
                val floats = getFloats.invoke(component) as? List<*> ?: return@runCatching null
                (floats.firstOrNull() as? Number)?.toInt()
            }.getOrNull()
        }

        override fun write(meta: ItemMeta, value: Int): Boolean {
            return runCatching {
                val component = getComponent.invoke(meta) ?: return@runCatching false
                setFloats.invoke(component, listOf(value.toFloat()))
                setComponent.invoke(meta, component)
                true
            }.getOrDefault(false)
        }
    }

    private class LegacyAccessor(
        private val has: Method,
        private val get: Method,
        private val set: Method
    ) : Accessor {
        override fun read(meta: ItemMeta): Int? {
            return runCatching {
                if (has.invoke(meta) != true) return@runCatching null
                (get.invoke(meta) as? Number)?.toInt()
            }.getOrNull()
        }

        override fun write(meta: ItemMeta, value: Int): Boolean {
            return runCatching {
                set.invoke(meta, value)
                true
            }.getOrDefault(false)
        }
    }

    private object UnsupportedAccessor : Accessor {
        override fun read(meta: ItemMeta): Int? = null

        override fun write(meta: ItemMeta, value: Int): Boolean = false
    }
}

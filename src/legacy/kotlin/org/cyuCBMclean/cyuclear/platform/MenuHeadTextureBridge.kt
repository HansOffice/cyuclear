package org.cyuCBMclean.cyuclear.platform

import org.bukkit.inventory.meta.SkullMeta
import java.lang.reflect.Field
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("UNUSED_PARAMETER")
object MenuHeadTextureBridge {
    private val profileFields = ConcurrentHashMap<Class<*>, Optional<Field>>()

    @Volatile
    private var writerResolved = false

    @Volatile
    private var writer: Writer? = null

    fun reload() {
        profileFields.clear()
        writer = null
        writerResolved = false
    }

    fun apply(meta: SkullMeta, payload: String, skinUrl: String?): Boolean {
        return writer()?.apply(meta, payload) == true
    }

    private fun writer(): Writer? {
        if (writerResolved) return writer
        synchronized(this) {
            if (!writerResolved) {
                writer = createWriter()
                writerResolved = true
            }
            return writer
        }
    }

    private fun createWriter(): Writer? {
        return try {
            val profileClass = Class.forName("com.mojang.authlib.GameProfile")
            val propertyClass = Class.forName("com.mojang.authlib.properties.Property")
            val profileConstructor = profileClass.getConstructor(UUID::class.java, String::class.java)
            val propertyConstructor = propertyClass.getConstructor(String::class.java, String::class.java)
            val properties = profileClass.getMethod("getProperties")
            object : Writer {
                override fun apply(meta: SkullMeta, payload: String): Boolean {
                    return runCatching {
                        val profile = profileConstructor.newInstance(UUID.randomUUID(), null)
                        val property = propertyConstructor.newInstance("textures", payload)
                        val map = properties.invoke(profile)
                        val put = map.javaClass.methods.firstOrNull { method ->
                            method.name == "put" && method.parameterCount == 2
                        } ?: return@runCatching false
                        put.invoke(map, "textures", property)
                        profileField(meta).set(meta, profile)
                        true
                    }.getOrDefault(false)
                }
            }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun profileField(meta: SkullMeta): Field {
        return profileFields.computeIfAbsent(meta.javaClass, ::findProfileField)
            .orElseThrow { NoSuchFieldException("profile") }
    }

    private fun findProfileField(source: Class<*>): Optional<Field> {
        var type: Class<*>? = source
        while (type != null) {
            val current = type
            val field = runCatching { current.getDeclaredField("profile") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return Optional.of(field)
            }
            type = current.superclass
        }
        return Optional.empty()
    }

    private interface Writer {
        fun apply(meta: SkullMeta, payload: String): Boolean
    }
}

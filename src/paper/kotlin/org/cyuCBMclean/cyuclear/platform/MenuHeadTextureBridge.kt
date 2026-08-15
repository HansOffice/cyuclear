package org.cyuCBMclean.cyuclear.platform

import org.bukkit.Bukkit
import org.bukkit.inventory.meta.SkullMeta
import java.lang.reflect.Field
import java.net.URL
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object MenuHeadTextureBridge {
    private interface Writer {
        fun apply(meta: SkullMeta, payload: String, skinUrl: String?): Boolean
    }

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
        return writer()?.apply(meta, payload, skinUrl) == true
    }

    private fun writer(): Writer? {
        if (writerResolved) return writer
        synchronized(this) {
            if (!writerResolved) {
                writer = profileWriter() ?: gameProfileWriter()
                writerResolved = true
            }
            return writer
        }
    }

    private fun profileWriter(): Writer? {
        return try {
            val profileClass = Class.forName("org.bukkit.profile.PlayerProfile")
            val texturesClass = Class.forName("org.bukkit.profile.PlayerTextures")
            val createProfile = Bukkit.getServer().javaClass.getMethod(
                "createPlayerProfile",
                UUID::class.java,
                String::class.java
            )
            val getTextures = profileClass.getMethod("getTextures")
            val setTextures = profileClass.getMethod("setTextures", texturesClass)
            val setSkin = texturesClass.getMethod("setSkin", URL::class.java)
            val setProfile = SkullMeta::class.java.methods.firstOrNull { method ->
                method.parameterCount == 1 &&
                    (method.name == "setPlayerProfile" || method.name == "setOwnerProfile") &&
                    method.parameterTypes[0].isAssignableFrom(profileClass)
            } ?: return null
            object : Writer {
                override fun apply(meta: SkullMeta, payload: String, skinUrl: String?): Boolean {
                    val url = skinUrl ?: return false
                    return runCatching {
                        val profile = createProfile.invoke(Bukkit.getServer(), UUID.randomUUID(), "")
                        val textures = getTextures.invoke(profile)
                        setSkin.invoke(textures, URL(url))
                        setTextures.invoke(profile, textures)
                        setProfile.invoke(meta, profile)
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

    private fun gameProfileWriter(): Writer? {
        return try {
            val profileClass = Class.forName("com.mojang.authlib.GameProfile")
            val propertyClass = Class.forName("com.mojang.authlib.properties.Property")
            val profileConstructor = profileClass.getConstructor(UUID::class.java, String::class.java)
            val propertyConstructor = propertyClass.getConstructor(String::class.java, String::class.java)
            val properties = profileClass.getMethod("getProperties")
            object : Writer {
                override fun apply(meta: SkullMeta, payload: String, skinUrl: String?): Boolean {
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
}

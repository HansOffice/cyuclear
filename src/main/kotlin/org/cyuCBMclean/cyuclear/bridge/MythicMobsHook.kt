package org.cyuCBMclean.cyuclear.bridge

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import java.lang.reflect.Method

object MythicMobsHook {

    private data class Api(
        val helper: Any,
        val isMythicMobMethod: Method,
        val getMythicMobInstanceMethod: Method,
        val getTypeMethod: Method,
        val getInternalNameMethod: Method
    )

    @Volatile
    private var api: Api? = null
    @Volatile
    private var unavailable: Boolean = false

    fun reset() {
        api = null
        unavailable = false
    }

    fun isAvailable(): Boolean {
        return resolveApi() != null
    }

    fun isMythicMob(entity: Entity): Boolean {
        val api = resolveApi() ?: return false
        return try {
            api.isMythicMobMethod.invoke(api.helper, entity) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun getInternalName(entity: Entity): String? {
        val api = resolveApi() ?: return null
        return try {
            val activeMob = api.getMythicMobInstanceMethod.invoke(api.helper, entity) ?: return null
            val mythicType = api.getTypeMethod.invoke(activeMob) ?: return null
            val internalName = api.getInternalNameMethod.invoke(mythicType) as? String ?: return null
            internalName.trim().lowercase().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveApi(): Api? {
        if (unavailable) return null
        api?.let { return it }
        if (!isPluginEnabled()) {
            unavailable = true
            return null
        }

        synchronized(this) {
            api?.let { return it }
            val resolved = createApi()
            if (resolved == null) {
                unavailable = true
                return null
            }
            api = resolved
            return resolved
        }
    }

    private fun createApi(): Api? {
        return try {
            val helperClass = Class.forName("io.lumine.mythic.bukkit.BukkitAPIHelper")
            val activeMobClass = Class.forName("io.lumine.mythic.core.mobs.ActiveMob")
            val mythicMobClass = Class.forName("io.lumine.mythic.api.mobs.MythicMob")

            val helper = helperClass.getDeclaredConstructor().newInstance()
            val isMythicMobMethod = helperClass.getMethod("isMythicMob", Entity::class.java)
            val getMythicMobInstanceMethod = helperClass.getMethod("getMythicMobInstance", Entity::class.java)
            val getTypeMethod = activeMobClass.getMethod("getType")
            val getInternalNameMethod = mythicMobClass.getMethod("getInternalName")

            Api(
                helper = helper,
                isMythicMobMethod = isMythicMobMethod,
                getMythicMobInstanceMethod = getMythicMobInstanceMethod,
                getTypeMethod = getTypeMethod,
                getInternalNameMethod = getInternalNameMethod
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun isPluginEnabled(): Boolean {
        return Bukkit.getPluginManager().getPlugin("MythicMobs")?.isEnabled == true
    }
}

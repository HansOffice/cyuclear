package org.cyuCBMclean.cyuclear.util

import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.bridge.CraftEngineFurnitureHook
import org.cyuCBMclean.cyuclear.bridge.MythicMobsHook
import org.cyuCBMclean.cyuclear.bridge.pokemon.PokemonEntityHook
import org.cyuCBMclean.cyuclear.platform.EntityStateBridge

object EntityUtils {

    @JvmStatic
    fun getNamespaceId(entity: Entity): String {
        return IdCompat.entityTypeId(entity.type)
    }

    @JvmStatic
    fun isModded(entity: Entity): Boolean {
        return IdCompat.namespace(getNamespaceId(entity)) != "minecraft"
    }

    @JvmStatic
    fun getMythicInternalName(entity: Entity): String? {
        if (!Settings.entityMythicEnabled) return null
        return MythicMobsHook.getInternalName(entity)
    }

    @JvmStatic
    fun craftEngineLookup(entity: Entity): CraftEngineFurnitureHook.Lookup {
        if (!Settings.entityCraftEngineEnabled) {
            return CraftEngineFurnitureHook.emptyLookup()
        }
        return CraftEngineFurnitureHook.lookup(entity)
    }

    @JvmStatic
    fun getCraftEngineFurnitureId(entity: Entity): String? = craftEngineLookup(entity).id

    @JvmStatic
    fun isCraftEngineFurniture(entity: Entity): Boolean = craftEngineLookup(entity).related

    @JvmStatic
    fun getFilterIds(entity: Entity, mythicInternalName: String? = getMythicInternalName(entity)): Set<String> {
        val ids = LinkedHashSet<String>()
        val namespaceId = getNamespaceId(entity)
        val ce = craftEngineLookup(entity)
        if (mythicInternalName != null) {
            ids.add("mythic:$mythicInternalName")
            if (!Settings.entityMythicIdOnly) {
                ids.add(namespaceId)
            }
        } else if (ce.related) {
            val ceId = ce.id
            if (ceId != null) {
                ids.add("ce:$ceId")
                ids.add("craftengine:$ceId")
            }
            if (!Settings.entityCraftEngineIdOnly || ceId == null) {
                ids.add(namespaceId)
            }
        } else {
            ids.add(namespaceId)
        }
        ids.addAll(PokemonEntityHook.getFilterIds(entity, namespaceId))
        return ids
    }

    @JvmStatic
    fun shouldIgnoreForChunkLimit(entity: Entity): Boolean {
        if (Settings.entityCraftEngineEnabled && Settings.craftEngineExcludeFromChunkLimit && isCraftEngineFurniture(entity)) {
            return true
        }
        if (!Settings.entityMythicEnabled || !Settings.mythicExcludeFromChunkLimit) return false
        return MythicMobsHook.isMythicMob(entity)
    }

    @JvmStatic
    fun shouldIgnoreForPanicCount(entity: Entity): Boolean {
        if (Settings.entityCraftEngineEnabled && Settings.craftEngineExcludeFromPanicCount && isCraftEngineFurniture(entity)) {
            return true
        }
        if (!Settings.entityMythicEnabled || !Settings.mythicExcludeFromPanicCount) return false
        return MythicMobsHook.isMythicMob(entity)
    }

    @JvmStatic
    fun isProtectedState(entity: Entity, ignoreNamed: Boolean, ignoreTamed: Boolean): Boolean {
        if (entity is Player) return true
        if (entity.hasMetadata("NPC")) return true
        if (ignoreNamed && entity.customName != null) return true
        if (Settings.entityIgnorePersistent && EntityStateBridge.isPersistent(entity)) return true
        if (Settings.entityIgnoreNoDespawn && entity is LivingEntity && !entity.removeWhenFarAway) return true
        if (ignoreTamed && entity is Tameable && entity.isTamed) return true
        return false
    }
}

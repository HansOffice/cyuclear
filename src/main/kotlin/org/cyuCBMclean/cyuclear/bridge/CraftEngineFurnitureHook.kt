package org.cyuCBMclean.cyuclear.bridge

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CraftEngineFurnitureHook {

    data class Lookup(
        val related: Boolean,
        val id: String?
    )

    private data class Api(
        val isFurniture: Method,
        val isCollisionEntity: Method,
        val isSeat: Method,
        val byMeta: Method,
        val bySeat: Method,
        val byCollider: Method,
        val idMethod: Method,
        val keyAsString: Method?
    )

    private data class CacheEntry(
        val lookup: Lookup,
        val expiresAt: Long
    )

    private val emptyLookup = Lookup(false, null)
    private val cache = ConcurrentHashMap<UUID, CacheEntry>()
    private const val CACHE_TTL_MS = 3_000L
    private const val CACHE_MAX = 4_096

    @Volatile
    private var api: Api? = null
    @Volatile
    private var unavailable: Boolean = false

    fun isAvailable(): Boolean = resolveApi() != null

    fun isFurnitureRelated(entity: Entity): Boolean = lookup(entity).related

    fun getFurnitureId(entity: Entity): String? = lookup(entity).id

    fun emptyLookup(): Lookup = emptyLookup

    fun lookup(entity: Entity): Lookup {
        val api = resolveApi() ?: return emptyLookup
        val now = System.currentTimeMillis()
        val cached = cache[entity.uniqueId]
        if (cached != null && cached.expiresAt > now) {
            return cached.lookup
        }

        val resolved = resolveLookup(api, entity)
        putCache(entity.uniqueId, resolved, now)
        return resolved
    }

    fun clearCache() {
        cache.clear()
        api = null
        unavailable = false
    }

    private fun resolveLookup(api: Api, entity: Entity): Lookup {
        return try {
            val furniture = api.byMeta.invoke(null, entity)
                ?: api.bySeat.invoke(null, entity)
                ?: api.byCollider.invoke(null, entity)
            if (furniture != null) {
                return Lookup(true, extractId(api, furniture))
            }

            val related = (api.isFurniture.invoke(null, entity) as? Boolean == true) ||
                (api.isCollisionEntity.invoke(null, entity) as? Boolean == true) ||
                (api.isSeat.invoke(null, entity) as? Boolean == true)
            if (related) Lookup(true, null) else emptyLookup
        } catch (_: Throwable) {
            emptyLookup
        }
    }

    private fun extractId(api: Api, furniture: Any): String? {
        return try {
            val key = api.idMethod.invoke(furniture) ?: return null
            val text = if (api.keyAsString != null) {
                api.keyAsString.invoke(key)?.toString()
            } else {
                key.toString()
            }
            text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun putCache(id: UUID, lookup: Lookup, now: Long) {
        if (cache.size >= CACHE_MAX) {
            cache.clear()
        }
        cache[id] = CacheEntry(lookup, now + CACHE_TTL_MS)
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
            val furnitureApi = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture")
            val furnitureClass = Class.forName("net.momirealms.craftengine.core.entity.furniture.Furniture")
            val idMethod = furnitureClass.getMethod("id")
            val keyClass = idMethod.returnType
            val keyAsString = runCatching { keyClass.getMethod("asString") }.getOrNull()
            Api(
                isFurniture = furnitureApi.getMethod("isFurniture", Entity::class.java),
                isCollisionEntity = furnitureApi.getMethod("isCollisionEntity", Entity::class.java),
                isSeat = furnitureApi.getMethod("isSeat", Entity::class.java),
                byMeta = furnitureApi.getMethod("getLoadedFurnitureByMetaEntity", Entity::class.java),
                bySeat = furnitureApi.getMethod("getLoadedFurnitureBySeat", Entity::class.java),
                byCollider = furnitureApi.getMethod("getLoadedFurnitureByCollider", Entity::class.java),
                idMethod = idMethod,
                keyAsString = keyAsString
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun isPluginEnabled(): Boolean {
        val plugin = Bukkit.getPluginManager().getPlugin("CraftEngine")
            ?: Bukkit.getPluginManager().getPlugin("CE")
        return plugin != null && plugin.isEnabled
    }
}

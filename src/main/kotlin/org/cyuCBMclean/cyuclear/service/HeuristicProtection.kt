package org.cyuCBMclean.cyuclear.service

import org.bukkit.entity.Entity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Villager
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.platform.HeuristicBridge
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object HeuristicProtection {

    data class Decision(
        val protected: Boolean,
        val reason: String,
        val reasonKey: CleanupFilter.ReasonKey
    )

    private data class CacheEntry(
        val decision: Decision?,
        val expiresAt: Long
    )

    private val cache = ConcurrentHashMap<UUID, CacheEntry>()
    private val lastPruneTime = AtomicLong(0L)
    private const val MAX_CACHE_SIZE = 16_384
    private const val NEGATIVE_CACHE_TTL_MILLIS = 3_000L

    fun evaluate(entity: Entity, displayId: String): CleanupFilter.FilterDecision? {
        if (!Settings.heuristicsEnabled) return null
        if (entity !is Villager && entity !is Minecart) return null

        val now = System.currentTimeMillis()
        pruneExpired(now)

        val cached = cache[entity.uniqueId]
        if (cached != null && cached.expiresAt > now) {
            val decision = cached.decision ?: return null
            return CleanupFilter.FilterDecision(
                remove = false,
                id = displayId,
                reason = decision.reason,
                reasonKey = decision.reasonKey
            )
        }

        val decision = computeDecision(entity)
        val ttl = if (decision != null) {
            Settings.heuristicCacheMillis.coerceIn(1000L, 3_600_000L)
        } else {
            NEGATIVE_CACHE_TTL_MILLIS
        }
        cache[entity.uniqueId] = CacheEntry(decision, now + ttl)

        if (decision == null) return null
        return CleanupFilter.FilterDecision(
            remove = false,
            id = displayId,
            reason = decision.reason,
            reasonKey = decision.reasonKey
        )
    }

    fun reset() {
        cache.clear()
        lastPruneTime.set(0L)
    }

    private fun computeDecision(entity: Entity): Decision? {
        if (entity is Villager) {
            if (Settings.heuristicTradedVillager && HeuristicBridge.isTradedVillager(entity)) {
                return Decision(
                    protected = true,
                    reason = if (Language.isEnglish) "Heuristic: Traded Mature Villager" else "启发式免死保护：交易成熟村民",
                    reasonKey = CleanupFilter.ReasonKey.HEURISTIC_TRADED_VILLAGER
                )
            }
            if (Settings.heuristicWorkstationVillager && HeuristicBridge.isWorkstationVillager(entity)) {
                return Decision(
                    protected = true,
                    reason = if (Language.isEnglish) "Heuristic: Workstation Villager" else "启发式免死保护：工位工作村民",
                    reasonKey = CleanupFilter.ReasonKey.HEURISTIC_WORKSTATION_VILLAGER
                )
            }
            if (Settings.heuristicIronFarmVillager && HeuristicBridge.isIronFarmVillager(entity)) {
                return Decision(
                    protected = true,
                    reason = if (Language.isEnglish) "Heuristic: Iron Farm Core" else "启发式免死保护：刷铁机核心",
                    reasonKey = CleanupFilter.ReasonKey.HEURISTIC_IRON_FARM_VILLAGER
                )
            }
        } else if (entity is Minecart) {
            if (Settings.heuristicPoweredVehicle && HeuristicBridge.isPoweredVehicle(entity)) {
                return Decision(
                    protected = true,
                    reason = if (Language.isEnglish) "Heuristic: Powered Vehicle" else "启发式免死保护：动力运输载具",
                    reasonKey = CleanupFilter.ReasonKey.HEURISTIC_POWERED_VEHICLE
                )
            }
        }
        return null
    }

    private fun pruneExpired(now: Long) {
        val previous = lastPruneTime.get()
        if (now - previous < 30_000L || !lastPruneTime.compareAndSet(previous, now)) {
            if (cache.size > MAX_CACHE_SIZE) cache.clear()
            return
        }
        cache.entries.removeIf { it.value.expiresAt <= now }
    }
}

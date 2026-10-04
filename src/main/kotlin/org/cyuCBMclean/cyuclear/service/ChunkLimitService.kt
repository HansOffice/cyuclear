package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.util.EntityUtils
import org.cyuCBMclean.cyuclear.util.ItemIdentity
import org.cyuCBMclean.cyuclear.bridge.StackerBridge
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.cyuCBMclean.cyuclear.util.ColorUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object ChunkLimitService {

    private enum class LimitKind {
        ITEM,
        ITEM_TYPE,
        ENTITY,
        ENTITY_TYPE
    }

    private data class ChunkLimitKey(
        val worldName: String,
        val chunkX: Int,
        val chunkZ: Int,
        val kind: LimitKind,
        val targetId: String = ""
    )

    private data class CountSnapshot(
        val count: Int,
        val expiresAt: Long
    )

    private data class SpawnSnapshot(
        val count: Int,
        val expiresAt: Long
    )

    private data class ResidentSnapshot(
        val count: Int,
        val candidateIncluded: Boolean
    )

    private data class SpawnAdmission(
        val worldName: String,
        val chunkX: Int,
        val chunkZ: Int,
        val expiresAt: Long
    )

    private val warningCooldownMap = ConcurrentHashMap<ChunkLimitKey, Long>()
    private val overloadUntilMap = ConcurrentHashMap<ChunkLimitKey, Long>()
    private val countCacheMap = ConcurrentHashMap<ChunkLimitKey, CountSnapshot>()
    private val spawnPressureMap = ConcurrentHashMap<ChunkLimitKey, SpawnSnapshot>()
    private val strictSpawnAdmissionMap = ConcurrentHashMap<java.util.UUID, SpawnAdmission>()
    private val lastPruneAt = AtomicLong(0L)

    fun onItemSpawn(event: ItemSpawnEvent) {
        if (!ActivationService.isActive()) return
        if (!Settings.itemModuleEnabled) return
        val threshold = Settings.chunkItemThreshold
        val specificThresholds = Settings.itemSpecificThresholds
        if (threshold <= 0 && specificThresholds.isEmpty()) return

        val loc = event.location
        val world = loc.world ?: return
        if (!Settings.isWorldEnabled(world.name)) return

        val chunkX = loc.blockX shr 4
        val chunkZ = loc.blockZ shr 4
        val worldName = world.name

        val specificLimit = if (specificThresholds.isNotEmpty()) findSpecificItemLimit(event.entity) else null
        if (specificLimit != null && shouldCancelItemSpawn(event, world, chunkX, chunkZ, specificLimit.first, specificLimit.second)) {
            return
        }

        if (threshold <= 0) return
        val key = ChunkLimitKey(worldName, chunkX, chunkZ, LimitKind.ITEM)

        if (shouldCancelFromCache(key)) {
            event.isCancelled = true
            notifyOverload(worldName, chunkX, chunkZ, loc.blockX, loc.blockY, loc.blockZ, key, -1, threshold, HotspotTracker.State.BREAKER)
            return
        }

        val count = estimateOrCount(key, threshold, world, chunkX, chunkZ, LimitKind.ITEM)
        if (count >= threshold) {
            event.isCancelled = true
            markOverloaded(key)
            notifyOverload(worldName, chunkX, chunkZ, loc.blockX, loc.blockY, loc.blockZ, key, count, threshold, HotspotTracker.State.BREAKER)
        } else if (Settings.chunkItemSoftThreshold > 0 && count >= Settings.chunkItemSoftThreshold) {
            val decision = CleanupFilter.explainItem(event.entity, honorGrace = false)
            if (decision.remove) {
                event.isCancelled = true
                notifyOverload(worldName, chunkX, chunkZ, loc.blockX, loc.blockY, loc.blockZ, key, count, Settings.chunkItemSoftThreshold, HotspotTracker.State.THROTTLED)
            } else {
                HotspotTracker.recordPressure(
                    worldName,
                    chunkX,
                    chunkZ,
                    HotspotTracker.SubjectKind.ITEM,
                    decision.id,
                    count,
                    HotspotTracker.State.WARNING
                )
            }
        }
    }

    private fun shouldCancelItemSpawn(
        event: ItemSpawnEvent,
        world: World,
        chunkX: Int,
        chunkZ: Int,
        targetId: String,
        threshold: Int
    ): Boolean {
        val key = ChunkLimitKey(world.name, chunkX, chunkZ, LimitKind.ITEM_TYPE, targetId)

        if (shouldCancelFromCache(key)) {
            event.isCancelled = true
            notifyOverload(world.name, chunkX, chunkZ, event.location.blockX, event.location.blockY, event.location.blockZ, key, -1, threshold, HotspotTracker.State.BREAKER)
            return true
        }

        val count = estimateOrCount(key, threshold, world, chunkX, chunkZ, LimitKind.ITEM_TYPE, targetId)
        if (count >= threshold) {
            event.isCancelled = true
            markOverloaded(key)
            notifyOverload(world.name, chunkX, chunkZ, event.location.blockX, event.location.blockY, event.location.blockZ, key, count, threshold, HotspotTracker.State.BREAKER)
            return true
        }

        return false
    }

    fun onEntitySpawn(event: EntitySpawnEvent) {
        if (!ActivationService.isActive()) return
        if (!Settings.entityModuleEnabled) return
        if (Settings.chunkEntityLimitMode == Settings.ChunkEntityLimitMode.OFF) return
        val entityThreshold = Settings.chunkEntityThreshold
        val specificThresholds = Settings.entitySpecificThresholds
        if (entityThreshold <= 0 && specificThresholds.isEmpty()) return

        if (event.entity is Item) return
        if (EntityUtils.shouldIgnoreForChunkLimit(event.entity)) return
        val loc = event.location
        val world = loc.world ?: return
        if (!Settings.isWorldEnabled(world.name)) return

        val chunkX = loc.blockX shr 4
        val chunkZ = loc.blockZ shr 4

        val specificLimit = if (specificThresholds.isNotEmpty()) findSpecificEntityLimit(event.entity) else null
        if (specificLimit != null && shouldCancelEntitySpawn(
                event = event,
                world = world,
                chunkX = chunkX,
                chunkZ = chunkZ,
                kind = LimitKind.ENTITY_TYPE,
                targetId = specificLimit.first,
                threshold = specificLimit.second
            )
        ) {
            return
        }

        if (entityThreshold > 0 && shouldCancelEntitySpawn(
            event = event,
            world = world,
            chunkX = chunkX,
            chunkZ = chunkZ,
            kind = LimitKind.ENTITY,
            targetId = "",
            threshold = entityThreshold
        )) {
            return
        }

        if (Settings.chunkEntityLimitMode == Settings.ChunkEntityLimitMode.STRICT && event.entity is Projectile) {
            rememberStrictSpawn(event.entity, world.name, chunkX, chunkZ)
        }
    }

    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        if (!ActivationService.isActive()) return
        if (!Settings.entityModuleEnabled) return
        if (Settings.chunkEntityLimitMode != Settings.ChunkEntityLimitMode.STRICT) return
        val entityThreshold = Settings.chunkEntityThreshold
        val specificThresholds = Settings.entitySpecificThresholds
        if (entityThreshold <= 0 && specificThresholds.isEmpty()) return

        val projectile = event.entity
        if (EntityUtils.shouldIgnoreForChunkLimit(projectile)) return
        val loc = projectile.location
        val world = loc.world ?: return
        if (!Settings.isWorldEnabled(world.name)) return

        val chunkX = loc.blockX shr 4
        val chunkZ = loc.blockZ shr 4

        if (consumeStrictSpawnAdmission(projectile, world.name, chunkX, chunkZ)) return

        val specificLimit = if (specificThresholds.isNotEmpty()) findSpecificEntityLimit(projectile) else null
        if (specificLimit != null && shouldCancelProjectileLaunch(
                event = event,
                world = world,
                chunkX = chunkX,
                chunkZ = chunkZ,
                kind = LimitKind.ENTITY_TYPE,
                targetId = specificLimit.first,
                threshold = specificLimit.second
            )
        ) {
            return
        }

        if (entityThreshold > 0) {
            shouldCancelProjectileLaunch(
                event = event,
                world = world,
                chunkX = chunkX,
                chunkZ = chunkZ,
                kind = LimitKind.ENTITY,
                targetId = "",
                threshold = entityThreshold
            )
        }
    }

    private fun shouldCancelEntitySpawn(
        event: EntitySpawnEvent,
        world: World,
        chunkX: Int,
        chunkZ: Int,
        kind: LimitKind,
        targetId: String,
        threshold: Int
    ): Boolean {
        if (threshold <= 0) return false

        val key = ChunkLimitKey(world.name, chunkX, chunkZ, kind, targetId)

        if (shouldCancelFromCache(key)) {
            event.isCancelled = true
            notifyOverload(world.name, chunkX, chunkZ, event.location.blockX, event.location.blockY, event.location.blockZ, key, -1, threshold, HotspotTracker.State.BREAKER)
            return true
        }

        return when (Settings.chunkEntityLimitMode) {
            Settings.ChunkEntityLimitMode.OFF -> false
            Settings.ChunkEntityLimitMode.SAFE -> shouldCancelEntitySpawnByPressure(event, world.name, chunkX, chunkZ, key, threshold)
            Settings.ChunkEntityLimitMode.STRICT -> shouldCancelEntitySpawnByResidentCount(event, world, chunkX, chunkZ, key, threshold)
        }
    }

    private fun shouldCancelEntitySpawnByResidentCount(
        event: EntitySpawnEvent,
        world: World,
        chunkX: Int,
        chunkZ: Int,
        key: ChunkLimitKey,
        threshold: Int
    ): Boolean {
        val resident = readResidentSnapshot(
            entity = event.entity,
            world = world,
            chunkX = chunkX,
            chunkZ = chunkZ,
            kind = key.kind,
            targetId = key.targetId,
            threshold = threshold,
            useCache = true
        )
        val incoming = if (resident.candidateIncluded) 0 else StackerBridge.quantity(event.entity).coerceAtLeast(1)
        val projected = addCounts(resident.count, incoming)
        val hardLimitReached = projected > threshold
        val softLimitReached = Settings.chunkEntitySoftThreshold > 0 && projected >= Settings.chunkEntitySoftThreshold

        val softDecision = if (softLimitReached) CleanupFilter.explainEntity(event.entity) else null
        if (!hardLimitReached && (softDecision == null || !softDecision.remove)) {
            if (softDecision != null) {
                HotspotTracker.recordPressure(
                    world.name,
                    chunkX,
                    chunkZ,
                    HotspotTracker.SubjectKind.ENTITY,
                    softDecision.id,
                    projected,
                    HotspotTracker.State.WARNING
                )
            }
            rememberResidentCount(key, projected)
            return false
        }

        countCacheMap.remove(key)
        event.isCancelled = true
        if (hardLimitReached) {
            markOverloaded(key)
            notifyOverload(world.name, chunkX, chunkZ, event.location.blockX, event.location.blockY, event.location.blockZ, key, projected, threshold, HotspotTracker.State.BREAKER)
        } else {
            notifyOverload(world.name, chunkX, chunkZ, event.location.blockX, event.location.blockY, event.location.blockZ, key, projected, Settings.chunkEntitySoftThreshold, HotspotTracker.State.THROTTLED)
        }
        return true
    }

    private fun shouldCancelProjectileLaunch(
        event: ProjectileLaunchEvent,
        world: World,
        chunkX: Int,
        chunkZ: Int,
        kind: LimitKind,
        targetId: String,
        threshold: Int
    ): Boolean {
        if (threshold <= 0) return false

        val key = ChunkLimitKey(world.name, chunkX, chunkZ, kind, targetId)
        if (shouldCancelFromCache(key)) {
            event.isCancelled = true
            event.entity.remove()
            notifyOverload(world.name, chunkX, chunkZ, event.entity.location.blockX, event.entity.location.blockY, event.entity.location.blockZ, key, -1, threshold, HotspotTracker.State.BREAKER)
            return true
        }

        val resident = readResidentSnapshot(
            entity = event.entity,
            world = world,
            chunkX = chunkX,
            chunkZ = chunkZ,
            kind = kind,
            targetId = targetId,
            threshold = threshold,
            useCache = false
        )
        val incoming = if (resident.candidateIncluded) 0 else StackerBridge.quantity(event.entity).coerceAtLeast(1)
        val projected = addCounts(resident.count, incoming)
        val hardLimitReached = projected > threshold
        val softLimitReached = Settings.chunkEntitySoftThreshold > 0 && projected >= Settings.chunkEntitySoftThreshold

        val softDecision = if (softLimitReached) CleanupFilter.explainEntity(event.entity) else null
        if (!hardLimitReached && (softDecision == null || !softDecision.remove)) {
            if (softDecision != null) {
                HotspotTracker.recordPressure(
                    world.name,
                    chunkX,
                    chunkZ,
                    HotspotTracker.SubjectKind.ENTITY,
                    softDecision.id,
                    projected,
                    HotspotTracker.State.WARNING
                )
            }
            rememberResidentCount(key, projected)
            return false
        }

        countCacheMap.remove(key)
        event.isCancelled = true
        event.entity.remove()
        if (hardLimitReached) {
            markOverloaded(key)
            notifyOverload(world.name, chunkX, chunkZ, event.entity.location.blockX, event.entity.location.blockY, event.entity.location.blockZ, key, projected, threshold, HotspotTracker.State.BREAKER)
        } else {
            notifyOverload(world.name, chunkX, chunkZ, event.entity.location.blockX, event.entity.location.blockY, event.entity.location.blockZ, key, projected, Settings.chunkEntitySoftThreshold, HotspotTracker.State.THROTTLED)
        }
        return true
    }

    private fun shouldCancelEntitySpawnByPressure(
        event: EntitySpawnEvent,
        worldName: String,
        chunkX: Int,
        chunkZ: Int,
        key: ChunkLimitKey,
        threshold: Int
    ): Boolean {
        val spawned = recordSpawnPressure(key)
        if (spawned < threshold) {
            if (Settings.chunkEntitySoftThreshold <= 0 || spawned < Settings.chunkEntitySoftThreshold) return false
            val decision = CleanupFilter.explainEntity(event.entity)
            if (!decision.remove) {
                HotspotTracker.recordPressure(
                    worldName,
                    chunkX,
                    chunkZ,
                    HotspotTracker.SubjectKind.ENTITY,
                    decision.id,
                    spawned,
                    HotspotTracker.State.WARNING
                )
                return false
            }
            event.isCancelled = true
            notifyOverload(worldName, chunkX, chunkZ, event.location.blockX, event.location.blockY, event.location.blockZ, key, spawned, Settings.chunkEntitySoftThreshold, HotspotTracker.State.THROTTLED)
            return true
        }

        event.isCancelled = true
        markOverloaded(key)
        notifyOverload(worldName, chunkX, chunkZ, event.location.blockX, event.location.blockY, event.location.blockZ, key, spawned, threshold, HotspotTracker.State.BREAKER)
        return true
    }

    private fun recordSpawnPressure(key: ChunkLimitKey): Int {
        val now = System.currentTimeMillis()
        val cached = spawnPressureMap[key]
        if (cached != null && cached.expiresAt > now) {
            val next = cached.count + 1
            spawnPressureMap[key] = SpawnSnapshot(next, cached.expiresAt)
            return next
        }

        spawnPressureMap[key] = SpawnSnapshot(1, now + Settings.chunkEntitySpawnWindowMillis)
        return 1
    }

    private fun readResidentSnapshot(
        entity: Entity,
        world: World,
        chunkX: Int,
        chunkZ: Int,
        kind: LimitKind,
        targetId: String,
        threshold: Int,
        useCache: Boolean
    ): ResidentSnapshot {
        val key = ChunkLimitKey(world.name, chunkX, chunkZ, kind, targetId)
        val now = System.currentTimeMillis()
        if (useCache) {
            val cached = countCacheMap[key]
            if (cached != null && cached.expiresAt > now) {
                return ResidentSnapshot(cached.count, false)
            }
        }

        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return ResidentSnapshot(0, false)
        }

        val chunk = world.getChunkAt(chunkX, chunkZ)
        val stopAt = if (threshold == Int.MAX_VALUE) Int.MAX_VALUE else threshold + 1
        val entities = chunk.entities
        var count = 0
        var candidateIncluded = false
        for (resident in entities) {
            if (resident.uniqueId == entity.uniqueId) {
                candidateIncluded = true
            }
            if (!matchesChunkLimitKind(resident, kind, targetId)) continue
            count = addCounts(count, StackerBridge.quantity(resident))
            if (count >= stopAt) {
                return ResidentSnapshot(count, candidateIncluded)
            }
        }
        return ResidentSnapshot(count, candidateIncluded)
    }

    private fun rememberResidentCount(key: ChunkLimitKey, count: Int) {
        if (Settings.limitCountCacheMillis <= 0L) {
            countCacheMap.remove(key)
            return
        }
        countCacheMap[key] = CountSnapshot(count, System.currentTimeMillis() + Settings.limitCountCacheMillis)
    }

    private fun rememberStrictSpawn(entity: Entity, worldName: String, chunkX: Int, chunkZ: Int) {
        val ttl = Settings.chunkEntitySpawnWindowMillis.coerceIn(100L, 1000L)
        strictSpawnAdmissionMap[entity.uniqueId] = SpawnAdmission(
            worldName = worldName,
            chunkX = chunkX,
            chunkZ = chunkZ,
            expiresAt = System.currentTimeMillis() + ttl
        )
    }

    private fun consumeStrictSpawnAdmission(entity: Entity, worldName: String, chunkX: Int, chunkZ: Int): Boolean {
        val admission = strictSpawnAdmissionMap.remove(entity.uniqueId) ?: return false
        if (admission.expiresAt <= System.currentTimeMillis()) return false
        return admission.worldName == worldName && admission.chunkX == chunkX && admission.chunkZ == chunkZ
    }

    private fun addCounts(first: Int, second: Int): Int {
        if (second <= 0) return first
        return if (first > Int.MAX_VALUE - second) Int.MAX_VALUE else first + second
    }

    private fun shouldCancelFromCache(key: ChunkLimitKey): Boolean {
        val now = System.currentTimeMillis()
        pruneExpired(now)

        val overloadUntil = overloadUntilMap[key] ?: return false
        if (overloadUntil > now) {
            return true
        }

        overloadUntilMap.remove(key, overloadUntil)
        return false
    }

    private fun estimateOrCount(
        key: ChunkLimitKey,
        threshold: Int,
        world: World,
        chunkX: Int,
        chunkZ: Int,
        kind: LimitKind,
        targetId: String = ""
    ): Int {
        val now = System.currentTimeMillis()
        val cached = countCacheMap[key]
        if (cached != null && cached.expiresAt > now) {
            val estimated = cached.count + 1
            countCacheMap[key] = CountSnapshot(estimated, cached.expiresAt)
            return estimated
        }

        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return 0
        }

        val chunk = world.getChunkAt(chunkX, chunkZ)
        val counted = countChunkEntities(chunk, kind, threshold, targetId)
        if (Settings.limitCountCacheMillis > 0L && counted < threshold) {
            countCacheMap[key] = CountSnapshot(counted, now + Settings.limitCountCacheMillis)
        } else {
            countCacheMap.remove(key)
        }

        return counted
    }

    private fun countChunkEntities(chunk: Chunk, kind: LimitKind, threshold: Int, targetId: String = ""): Int {
        val entities = chunk.entities
        var count = 0

        for (i in entities.indices) {
            val entity = entities[i]
            val matchesKind = matchesChunkLimitKind(entity, kind, targetId)

            if (matchesKind) {
                count += StackerBridge.quantity(entity)
                if (count >= threshold) {
                    return count
                }
            }
        }

        return count
    }

    private fun matchesChunkLimitKind(entity: Entity, kind: LimitKind, targetId: String): Boolean {
        return when (kind) {
            LimitKind.ITEM -> entity is Item
            LimitKind.ITEM_TYPE -> matchesSpecificItemLimit(entity, targetId)
            LimitKind.ENTITY -> entity !is Item && !EntityUtils.shouldIgnoreForChunkLimit(entity)
            LimitKind.ENTITY_TYPE -> matchesSpecificEntityLimit(entity, targetId)
        }
    }

    private fun findSpecificItemLimit(item: Item): Pair<String, Int>? {
        val thresholds = Settings.itemSpecificThresholds
        if (thresholds.isEmpty()) return null

        for (rawId in ItemIdentity.matchIds(item.itemStack)) {
            val namespaceId = rawId.lowercase()
            thresholds[namespaceId]?.let { return namespaceId to it }
        }
        return null
    }

    private fun matchesSpecificItemLimit(entity: Entity, targetId: String): Boolean {
        if (entity !is Item) return false
        return ItemIdentity.matchIds(entity.itemStack).any { it.equals(targetId, ignoreCase = true) }
    }

    private fun findSpecificEntityLimit(entity: Entity): Pair<String, Int>? {
        val thresholds = Settings.entitySpecificThresholds
        if (thresholds.isEmpty()) return null

        for (id in EntityUtils.getFilterIds(entity)) {
            val key = id.lowercase()
            thresholds[key]?.let { return key to it }
        }
        return null
    }

    private fun matchesSpecificEntityLimit(entity: Entity, targetId: String): Boolean {
        if (entity is Item) return false
        if (EntityUtils.shouldIgnoreForChunkLimit(entity)) return false
        return EntityUtils.getFilterIds(entity).any { it.equals(targetId, ignoreCase = true) }
    }

    private fun markOverloaded(key: ChunkLimitKey) {
        countCacheMap.remove(key)
        spawnPressureMap.remove(key)
        if (Settings.limitOverloadCacheMillis > 0L) {
            overloadUntilMap[key] = System.currentTimeMillis() + Settings.limitOverloadCacheMillis
        }
    }

    private fun notifyOverload(
        worldName: String,
        chunkX: Int,
        chunkZ: Int,
        x: Int,
        y: Int,
        z: Int,
        key: ChunkLimitKey,
        count: Int,
        threshold: Int,
        state: HotspotTracker.State
    ) {
        HotspotTracker.recordPressure(
            world = worldName,
            chunkX = chunkX,
            chunkZ = chunkZ,
            kind = when (key.kind) {
                LimitKind.ITEM, LimitKind.ITEM_TYPE -> HotspotTracker.SubjectKind.ITEM
                LimitKind.ENTITY, LimitKind.ENTITY_TYPE -> HotspotTracker.SubjectKind.ENTITY
            },
            subject = overloadId(key),
            count = count,
            state = state,
            breakerUntil = if (state == HotspotTracker.State.BREAKER) overloadUntilMap[key] ?: 0L else 0L
        )
        if (Settings.overloadNoticeTarget == Settings.OverloadNoticeTarget.NONE) return

        val now = System.currentTimeMillis()
        val lastWarning = warningCooldownMap[key] ?: 0L

        if (now - lastWarning > Settings.overloadNoticeCooldownMillis) {
            warningCooldownMap[key] = now

            val msg = Language.get(
                "chunk-overload-warn",
                "world" to worldName,
                "chunk_x" to chunkX.toString(),
                "chunk_z" to chunkZ.toString(),
                "x" to x.toString(),
                "y" to y.toString(),
                "z" to z.toString(),
                "type" to overloadType(key.kind),
                "id" to overloadId(key),
                "count" to overloadCount(count),
                "threshold" to threshold.toString(),
                "duration" to Settings.limitOverloadCacheMillis.toString()
            )
            val adminComponents = createAdminOverloadComponent(msg, worldName, x, y, z)
            when (Settings.overloadNoticeTarget) {
                Settings.OverloadNoticeTarget.NONE -> Unit
                Settings.OverloadNoticeTarget.ADMINS -> PlayerMessageDispatcher.broadcast(adminComponents, "cyuclear.admin")
                Settings.OverloadNoticeTarget.ALL -> {
                    PlayerMessageDispatcher.broadcastInteractive(msg, adminComponents, "cyuclear.admin")
                    SoundNoticeManager.broadcast(SoundNoticeManager.Event.CHUNK_OVERLOAD)
                }
            }
        }
    }

    private fun createAdminOverloadComponent(baseMsg: String, worldName: String, x: Int, y: Int, z: Int): Array<BaseComponent> {
        val baseComponents = TextComponent.fromLegacyText(baseMsg).toMutableList()
        val btnText = if (Language.has("chunk-overload-teleport-button")) Language.getRaw("chunk-overload-teleport-button") else " &#5DADE2[前往查看]"
        val hoverText = if (Language.has("chunk-overload-teleport-hover")) {
            Language.get("chunk-overload-teleport-hover", "world" to worldName, "x" to x.toString(), "y" to y.toString(), "z" to z.toString())
        } else {
            ColorUtils.color("&7点击传送至 &f$worldName &7($x, $y, $z)")
        }

        val tpComponent = TextComponent(ColorUtils.color(btnText)).apply {
            hoverEvent = HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                ComponentBuilder(hoverText).create()
            )
            clickEvent = ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/cyuclear tp $worldName $x $y $z"
            )
        }
        baseComponents.add(tpComponent)
        return baseComponents.toTypedArray()
    }

    private fun overloadType(kind: LimitKind): String {
        val key = when (kind) {
            LimitKind.ITEM -> "overload-type-item"
            LimitKind.ITEM_TYPE -> "overload-type-item-type"
            LimitKind.ENTITY -> "overload-type-entity"
            LimitKind.ENTITY_TYPE -> {
                "overload-type-spawn-pressure"
            }
        }
        val fallback = when (kind) {
            LimitKind.ITEM -> "掉落物"
            LimitKind.ITEM_TYPE -> "指定掉落物"
            LimitKind.ENTITY -> "实体"
            LimitKind.ENTITY_TYPE -> "生成压力"
        }
        return if (Language.has(key)) Language.getRaw(key) else fallback
    }

    private fun overloadId(key: ChunkLimitKey): String {
        return key.targetId.takeIf { it.isNotBlank() } ?: "全部"
    }

    private fun overloadCount(count: Int): String {
        return if (count >= 0) count.toString() else "缓存"
    }

    fun releaseHotspot(world: String, chunkX: Int, chunkZ: Int): Boolean {
        val matchesChunk = { key: ChunkLimitKey ->
            key.worldName.equals(world, ignoreCase = true) && key.chunkX == chunkX && key.chunkZ == chunkZ
        }
        overloadUntilMap.entries.removeIf { matchesChunk(it.key) }
        countCacheMap.entries.removeIf { matchesChunk(it.key) }
        spawnPressureMap.entries.removeIf { matchesChunk(it.key) }
        warningCooldownMap.entries.removeIf { matchesChunk(it.key) }
        return HotspotTracker.release(world, chunkX, chunkZ)
    }

    fun reset() {
        warningCooldownMap.clear()
        overloadUntilMap.clear()
        countCacheMap.clear()
        spawnPressureMap.clear()
        strictSpawnAdmissionMap.clear()
        lastPruneAt.set(0L)
    }

    private fun pruneExpired(now: Long) {
        val previous = lastPruneAt.get()
        if (now - previous < 30000L || !lastPruneAt.compareAndSet(previous, now)) return

        countCacheMap.entries.removeIf { it.value.expiresAt <= now }
        spawnPressureMap.entries.removeIf { it.value.expiresAt <= now }
        overloadUntilMap.entries.removeIf { it.value <= now }
        strictSpawnAdmissionMap.entries.removeIf { it.value.expiresAt <= now }
        warningCooldownMap.entries.removeIf { now - it.value > Settings.overloadNoticeCooldownMillis * 2L }
    }
}

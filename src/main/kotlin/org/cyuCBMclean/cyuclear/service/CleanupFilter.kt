package org.cyuCBMclean.cyuclear.service

import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.config.AreaRules
import org.cyuCBMclean.cyuclear.bridge.pokemon.PokemonEntityHook
import org.cyuCBMclean.cyuclear.platform.EntityStateBridge
import org.cyuCBMclean.cyuclear.util.EntityUtils
import org.cyuCBMclean.cyuclear.util.IdMatcher
import org.cyuCBMclean.cyuclear.util.ItemIdentity
import org.cyuCBMclean.cyuclear.util.ItemText
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object CleanupFilter {

    private data class CachedItemDecision(
        val revision: Long,
        val decision: FilterDecision
    )

    private val itemDecisionCache = ConcurrentHashMap<String, CachedItemDecision>()
    private val itemCacheRevision = AtomicLong(Long.MIN_VALUE)
    private const val ITEM_CACHE_MAX = 8_192
    private val emptyTextRules = ItemText.emptyRules()

    enum class ReasonKey(val title: String) {
        DEFAULT_CLEAN("默认清理"),
        DEFAULT_KEEP("默认保留"),
        KEEP_LIST("保留名单"),
        CLEAN_LIST("清理名单"),
        NAME_KEEP("展示名保留"),
        NAME_CLEAN("展示名清理"),
        LORE_KEEP("Lore 保留"),
        LORE_CLEAN("Lore 清理"),
        NAMED("命名保护"),
        TAMED("驯服保护"),
        PERSISTENT("持久保护"),
        NO_DESPAWN("不远离消失保护"),
        RAID_EVENT("袭击保护"),
        PLAYER("玩家保护"),
        NPC("NPC 保护"),
        PLAYER_OWNED_POKEMON("玩家宝可梦保护"),
        ENTITY_NAME_KEEP("实体名称保留"),
        ENTITY_NAME_CLEAN("实体名称清理"),
        NAMED_RULE_KEEP("命名规则保留"),
        NAMED_RULE_CLEAN("命名规则清理"),
        DETAIL_KEEP("深度保留"),
        DETAIL_CLEAN("深度清理"),
        CRAFT_ENGINE_FURNITURE("CraftEngine 家具保护"),
        AGE_GRACE("掉落宽限"),
        AREA_RULE("区域规则")
    }

    data class FilterDecision(
        val remove: Boolean,
        val id: String,
        val reason: String,
        val reasonKey: ReasonKey,
        val detailRule: String? = null,
        val bypassedProtections: Set<EntityDetailRules.Protection> = emptySet(),
        val itemIds: List<String> = emptyList(),
        val namedRule: String? = null,
        val rulePriority: Int? = null
    )

    fun shouldRemoveEntity(entity: Entity): Boolean {
        return explainEntity(entity).remove
    }

    fun explainEntity(entity: Entity): FilterDecision {
        return explainEntity(
            entity = entity,
            defaultAction = Settings.entityDefaultAction,
            protectMatcher = Settings.entityProtectMatcher,
            cleanMatcher = Settings.entityCleanMatcher,
            mythicRulesPresent = Settings.entityMythicRulesPresent,
            pokemonRulesPresent = Settings.entityPokemonRulesPresent,
            pokemonFullRulesRequired = Settings.entityPokemonFullRulesRequired,
            pokemonLightRules = Settings.entityPokemonLightRules,
            useDetailRules = true,
            trace = null
        )
    }

    fun traceEntity(entity: Entity): DecisionTrace {
        val trace = DecisionTraceBuilder()
        val decision = explainEntity(
            entity = entity,
            defaultAction = Settings.entityDefaultAction,
            protectMatcher = Settings.entityProtectMatcher,
            cleanMatcher = Settings.entityCleanMatcher,
            mythicRulesPresent = Settings.entityMythicRulesPresent,
            pokemonRulesPresent = Settings.entityPokemonRulesPresent,
            pokemonFullRulesRequired = Settings.entityPokemonFullRulesRequired,
            pokemonLightRules = Settings.entityPokemonLightRules,
            useDetailRules = true,
            trace = trace
        )
        return trace.build(decision)
    }

    fun shouldRemoveRealtimeEntity(entity: Entity): Boolean {
        return explainRealtimeEntity(entity).remove
    }

    fun explainRealtimeEntity(entity: Entity): FilterDecision {
        return explainEntity(
            entity = entity,
            defaultAction = Settings.entityRealtimeDefaultAction,
            protectMatcher = Settings.entityRealtimeProtectMatcher,
            cleanMatcher = Settings.entityRealtimeCleanMatcher,
            mythicRulesPresent = Settings.entityRealtimeMythicRulesPresent,
            pokemonRulesPresent = Settings.entityRealtimePokemonRulesPresent,
            pokemonFullRulesRequired = Settings.entityRealtimePokemonFullRulesRequired,
            pokemonLightRules = Settings.entityRealtimePokemonLightRules,
            useDetailRules = false,
            trace = null
        )
    }

    fun traceRealtimeEntity(entity: Entity): DecisionTrace {
        val trace = DecisionTraceBuilder()
        val decision = explainEntity(
            entity = entity,
            defaultAction = Settings.entityRealtimeDefaultAction,
            protectMatcher = Settings.entityRealtimeProtectMatcher,
            cleanMatcher = Settings.entityRealtimeCleanMatcher,
            mythicRulesPresent = Settings.entityRealtimeMythicRulesPresent,
            pokemonRulesPresent = Settings.entityRealtimePokemonRulesPresent,
            pokemonFullRulesRequired = Settings.entityRealtimePokemonFullRulesRequired,
            pokemonLightRules = Settings.entityRealtimePokemonLightRules,
            useDetailRules = false,
            trace = trace
        )
        return trace.build(decision)
    }

    private fun explainEntity(
        entity: Entity,
        defaultAction: Settings.DefaultAction,
        protectMatcher: IdMatcher,
        cleanMatcher: IdMatcher,
        mythicRulesPresent: Boolean,
        pokemonRulesPresent: Boolean,
        pokemonFullRulesRequired: Boolean,
        pokemonLightRules: Set<String>,
        useDetailRules: Boolean,
        trace: DecisionTraceBuilder?
    ): FilterDecision {
        val namespaceId = EntityUtils.getNamespaceId(entity)
        trace?.add("基础识别", namespaceId)
        if (entity is Player) return FilterDecision(false, namespaceId, "玩家保护", ReasonKey.PLAYER)
        if (entity.hasMetadata("NPC")) return FilterDecision(false, namespaceId, "NPC 元数据保护", ReasonKey.NPC)
        val rawName = entity.customName

        val craftEngine = EntityUtils.craftEngineLookup(entity)
        val craftEngineId = craftEngine.id
        if (Settings.entityCraftEngineProtectFurniture && craftEngine.related) {
            val id = craftEngineId?.let { "ce:$it" } ?: namespaceId
            return FilterDecision(false, id, "CraftEngine 家具保护", ReasonKey.CRAFT_ENGINE_FURNITURE)
        }

        val mythicBypassProtection = Settings.entityMythicBypassProtectionFlags
        val mythicInternalName = if (
            mythicRulesPresent ||
            mythicBypassProtection ||
            (useDetailRules && Settings.entityDetailUsesMythic) ||
            AreaRules.hasMythicEntityRules()
        ) {
            EntityUtils.getMythicInternalName(entity)
        } else {
            null
        }
        val bypassAllOrdinaryProtections = mythicInternalName != null && mythicBypassProtection
        val detailUsesPokemon = useDetailRules && Settings.entityDetailUsesPokemon
        val detailRequiresFullPokemon = useDetailRules && Settings.entityDetailRequiresFullPokemon
        val pokemonIds = PokemonEntityHook.getFilterIds(
            entity, namespaceId, pokemonRulesPresent || detailUsesPokemon,
            pokemonFullRulesRequired || detailRequiresFullPokemon,
            pokemonLightRules,
            detailUsesPokemon && !detailRequiresFullPokemon
        )
        val pokemonOwned = pokemonIds.contains("pokemon:owned=true")
        val displayId = when {
            mythicInternalName != null -> "mythic:$mythicInternalName"
            craftEngineId != null -> "ce:$craftEngineId"
            else -> namespaceId
        }
        val namedRules = Settings.namedRules
        val hasDetailRules = useDetailRules && Settings.entityDetailRules.isNotEmpty()
        val areaFilterActive = AreaRules.hasEntityFilterRules()
        val ids = if (trace != null || namedRules.hasEntityRules || hasDetailRules || areaFilterActive) {
            collectEntityIds(namespaceId, mythicInternalName, craftEngineId, pokemonIds)
        } else {
            emptySet()
        }
        trace?.add("实体身份", ids.joinToString("、"))

        val namedLocation = if (namedRules.entityUsesLocation) entity.location else null
        val namedMatch = if (namedRules.hasEntityRules) {
            namedRules.matchEntity(
                RuleEngine.EntityFacts(
                    entity = entity,
                    ids = ids,
                    rawName = rawName,
                    pokemonOwned = pokemonOwned,
                    world = namedLocation?.world?.name,
                    y = namedLocation?.blockY,
                    ageTicks = entity.ticksLived
                )
            )
        } else {
            null
        }
        if (namedMatch != null) {
            trace?.add("命名规则", "命中 ${namedMatch.name}（优先级 ${namedMatch.priority}）")
            if (namedMatch.action == RuleEngine.Action.KEEP) {
                return FilterDecision(
                    remove = false,
                    id = displayId,
                    reason = "命名规则保留：${namedMatch.name}",
                    reasonKey = ReasonKey.NAMED_RULE_KEEP,
                    namedRule = namedMatch.name,
                    rulePriority = namedMatch.priority
                )
            }
            if (!bypassAllOrdinaryProtections) {
                ordinaryProtectionReason(entity, namespaceId, rawName, pokemonOwned, namedMatch.bypasses)?.let { protected ->
                    trace?.add("普通保护", "命中 ${protected.reason}")
                    return protected
                }
            }
            return FilterDecision(
                remove = true,
                id = displayId,
                reason = "命名规则清理：${namedMatch.name}",
                reasonKey = ReasonKey.NAMED_RULE_CLEAN,
                bypassedProtections = namedMatch.bypasses,
                namedRule = namedMatch.name,
                rulePriority = namedMatch.priority
            )
        }
        trace?.add("命名规则", "未命中")
        val nameRules = if (useDetailRules) Settings.entityNameRules else emptyTextRules
        val normalizedName = if (nameRules.hasRules) ItemText.normalize(rawName, nameRules.colorMode) else ""
        val nameTexts = if (normalizedName.isNotEmpty()) listOf(normalizedName) else emptyList()
        val hasCustomName = rawName != null
        val detailFacts = if (hasDetailRules) {
            EntityDetailRules.Facts(entity, ids, rawName, pokemonOwned)
        } else {
            null
        }

        if (nameRules.canProtect(nameTexts, hasCustomName)) {
            return FilterDecision(false, displayId, "实体名称保留名单", ReasonKey.ENTITY_NAME_KEEP)
        }
        if (useDetailRules) trace?.add("名称与深度规则", "继续检查")
        if (useDetailRules) {
            if (detailFacts != null) {
                val keepMatch = EntityDetailRules.firstMatch(Settings.entityDetailRules, EntityDetailRules.Action.KEEP, detailFacts)
                if (keepMatch != null) {
                    return FilterDecision(false, displayId, "实体深度保留规则：${keepMatch.name}", ReasonKey.DETAIL_KEEP, keepMatch.name)
                }
                val cleanMatch = EntityDetailRules.firstMatch(Settings.entityDetailRules, EntityDetailRules.Action.CLEAN, detailFacts)
                if (cleanMatch != null) {
                    val blocked = if (bypassAllOrdinaryProtections) null else
                        ordinaryProtectionReason(entity, namespaceId, rawName, pokemonOwned, cleanMatch.bypasses)
                    if (blocked == null) {
                        return FilterDecision(true, displayId, "实体深度清理规则：${cleanMatch.name}", ReasonKey.DETAIL_CLEAN, cleanMatch.name, cleanMatch.bypasses)
                    }
                }
            }
            if (nameRules.canClean(nameTexts, hasCustomName)) {
                val bypasses = Settings.entityNameCleanBypasses
                val blocked = if (bypassAllOrdinaryProtections) null else
                    ordinaryProtectionReason(entity, namespaceId, rawName, pokemonOwned, bypasses)
                if (blocked == null) {
                    return FilterDecision(true, displayId, "实体名称清理名单", ReasonKey.ENTITY_NAME_CLEAN, bypassedProtections = bypasses)
                }
            }
        }

        if (!bypassAllOrdinaryProtections) {
            ordinaryProtectionReason(entity, namespaceId, rawName, pokemonOwned, emptySet())?.let { return it }
            trace?.add("普通保护", "未命中")
        } else {
            trace?.add("普通保护", "MythicMobs 已配置绕过")
        }
        val areaLocation = namedLocation ?: if (areaFilterActive || trace != null) entity.location else null
        val areaRule = areaLocation?.let { location ->
            location.world?.let { world ->
                AreaRules.find(world.name, location.blockX, location.blockY, location.blockZ)
            }
        }
        areaRule?.entities?.decide(ids, areaRule.name)?.let {
            return FilterDecision(it.remove, displayId, it.reason, ReasonKey.AREA_RULE)
        }
        trace?.add("区域规则", areaRule?.let { "命中 ${it.name}，继续使用全局规则" } ?: "未命中")
        trace?.add("基础名单", "继续检查")
        if (isEntityMatched(namespaceId, mythicInternalName, craftEngineId, pokemonIds, protectMatcher)) {
            return FilterDecision(false, displayId, "实体保留名单", ReasonKey.KEEP_LIST)
        }
        if (isEntityMatched(namespaceId, mythicInternalName, craftEngineId, pokemonIds, cleanMatcher)) {
            return FilterDecision(true, displayId, "实体清理名单", ReasonKey.CLEAN_LIST)
        }
        return when (defaultAction) {
            Settings.DefaultAction.CLEAN -> FilterDecision(true, displayId, "默认清理", ReasonKey.DEFAULT_CLEAN)
            Settings.DefaultAction.KEEP -> FilterDecision(false, displayId, "默认保留", ReasonKey.DEFAULT_KEEP)
        }
    }

    private fun ordinaryProtectionReason(
        entity: Entity, namespaceId: String, rawName: String?, pokemonOwned: Boolean,
        bypasses: Set<EntityDetailRules.Protection>
    ): FilterDecision? {
        if (Settings.entityIgnoreNamed && EntityDetailRules.Protection.NAMED !in bypasses &&
            rawName != null && !bypassesNamedProtection(entity, namespaceId)) {
            return FilterDecision(false, namespaceId, "命名实体保护", ReasonKey.NAMED)
        }
        if (Settings.entityIgnorePersistent && EntityDetailRules.Protection.PERSISTENT !in bypasses &&
            EntityStateBridge.isPersistent(entity)) {
            return FilterDecision(false, namespaceId, "持久实体保护", ReasonKey.PERSISTENT)
        }
        if (Settings.entityIgnoreNoDespawn && EntityDetailRules.Protection.NO_DESPAWN !in bypasses &&
            entity is LivingEntity && !entity.removeWhenFarAway) {
            return FilterDecision(false, namespaceId, "不自然消失实体保护", ReasonKey.NO_DESPAWN)
        }
        if (Settings.entityIgnoreTamed && EntityDetailRules.Protection.TAMED !in bypasses &&
            entity is Tameable && entity.isTamed) {
            return FilterDecision(false, namespaceId, "驯服实体保护", ReasonKey.TAMED)
        }
        if (Settings.entityProtectRaidEvent && EntityDetailRules.Protection.RAID !in bypasses && EntityStateBridge.isInRaid(entity)) {
            return FilterDecision(false, namespaceId, "袭击事件保护", ReasonKey.RAID_EVENT)
        }
        if (Settings.entityPokemonIgnorePlayerOwned && EntityDetailRules.Protection.PLAYER_OWNED_POKEMON !in bypasses && pokemonOwned) {
            return FilterDecision(false, namespaceId, "玩家拥有的宝可梦保护", ReasonKey.PLAYER_OWNED_POKEMON)
        }
        return null
    }

    private fun bypassesNamedProtection(entity: Entity, namespaceId: String): Boolean {
        if (Settings.entityNamedBypassMatcher.matchesNormalized(namespaceId)) return true
        if (!Settings.hasNamedProtectionBypassRegions()) return false
        val location = entity.location
        return Settings.isNamedProtectionBypassed(
            entity.world.name,
            location.blockX,
            location.blockY,
            location.blockZ
        )
    }

    private fun collectEntityIds(
        namespaceId: String,
        mythicInternalName: String?,
        craftEngineId: String?,
        pokemonIds: Set<String>
    ): Set<String> {
        val ids = LinkedHashSet<String>(pokemonIds.size + 5)
        ids += namespaceId
        if (mythicInternalName != null) ids += "mythic:$mythicInternalName"
        if (craftEngineId != null) {
            ids += "ce:$craftEngineId"
            ids += "craftengine:$craftEngineId"
        }
        ids += pokemonIds
        return ids
    }

    private fun isEntityMatched(
        namespaceId: String,
        mythicInternalName: String?,
        craftEngineId: String?,
        pokemonIds: Set<String>,
        matcher: IdMatcher
    ): Boolean {
        if (mythicInternalName != null) {
            if (matcher.matchesNormalized("mythic:$mythicInternalName")) return true
            if (!Settings.entityMythicIdOnly && matcher.matchesNormalized(namespaceId)) return true
            return matcher.matchesAnyNormalized(pokemonIds)
        }

        if (craftEngineId != null) {
            if (matcher.matchesNormalized("ce:$craftEngineId") || matcher.matchesNormalized("craftengine:$craftEngineId")) return true
            if (!Settings.entityCraftEngineIdOnly && matcher.matchesNormalized(namespaceId)) return true
            return matcher.matchesAnyNormalized(pokemonIds)
        }

        if (matcher.matchesNormalized(namespaceId)) return true
        return matcher.matchesAnyNormalized(pokemonIds)
    }

    fun shouldRemoveItem(item: Item): Boolean {
        return explainItem(item).remove
    }

    fun explainItem(item: Item, honorGrace: Boolean = true): FilterDecision {
        return explainItem(item, honorGrace, null)
    }

    fun traceItem(item: Item, honorGrace: Boolean = true): DecisionTrace {
        val trace = DecisionTraceBuilder()
        val decision = explainItem(item, honorGrace, trace)
        return trace.build(decision)
    }

    private fun explainItem(item: Item, honorGrace: Boolean, trace: DecisionTraceBuilder?): FilterDecision {
        val stack = item.itemStack
        val ids = ItemIdentity.matchIds(stack)
        val id = ids.first()
        trace?.add("基础识别", ids.joinToString("、"))
        if (honorGrace) {
            val highValue = Settings.itemHighValueMatcher.matchesAnyNormalized(ids)
            val minimumAge = if (highValue) Settings.itemHighValueMinimumAgeTicks else Settings.itemMinimumAgeTicks
            if (minimumAge > 0 && item.ticksLived < minimumAge) {
                return FilterDecision(false, id, "新掉落物宽限", ReasonKey.AGE_GRACE, itemIds = ids)
            }
        }

        val nameRules = Settings.itemNameRules
        val loreRules = Settings.itemLoreRules
        val namedRules = Settings.namedRules
        val itemLocation = if (namedRules.itemUsesLocation || AreaRules.hasItemFilterRules() || trace != null) item.location else null
        val areaRule = itemLocation?.let { location ->
            location.world?.let { world ->
                AreaRules.find(world.name, location.blockX, location.blockY, location.blockZ)
            }
        }
        val areaNameEnabled = areaRule?.itemNames?.hasRules == true
        val areaLoreEnabled = areaRule?.itemLores?.hasRules == true
        val globalTextActive = nameRules.hasRules || loreRules.hasRules
        val textRulesActive = globalTextActive || areaNameEnabled || areaLoreEnabled || namedRules.itemUsesName || namedRules.itemUsesLore

        val rawName = if (textRulesActive) ItemText.displayName(stack) else null
        val rawLore = if (loreRules.hasRules || areaLoreEnabled || namedRules.itemUsesLore) ItemText.loreLines(stack) else emptyList()
        val hasCustomName = rawName != null

        val areaNameTexts = if (areaNameEnabled && rawName != null) {
            ItemText.normalize(rawName, areaRule!!.itemNames.colorMode)
                .takeIf { it.isNotEmpty() }
                ?.let { listOf(it) }
                .orEmpty()
        } else {
            emptyList()
        }
        val areaLoreTexts = if (areaLoreEnabled && rawLore.isNotEmpty()) {
            rawLore.map { ItemText.normalize(it, areaRule!!.itemLores.colorMode) }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        val normalizedName = if (nameRules.hasRules && rawName != null) {
            ItemText.normalize(rawName, nameRules.colorMode)
        } else {
            null
        }
        val nameTexts = if (!normalizedName.isNullOrEmpty()) listOf(normalizedName) else emptyList()
        val loreTexts = if (loreRules.hasRules && rawLore.isNotEmpty()) {
            rawLore.map { ItemText.normalize(it, loreRules.colorMode) }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        val revision = Settings.filterRevision
        val cacheName = normalizedName ?: rawName.takeIf { namedRules.itemUsesName }
        val cacheLore = if (loreTexts.isNotEmpty()) loreTexts else if (namedRules.itemUsesLore) rawLore else emptyList()
        val namedRulesNeedContext = namedRules.itemUsesName || namedRules.itemUsesLore || namedRules.itemUsesContext
        val cacheKey = if (textRulesActive || areaRule != null || namedRulesNeedContext) {
            id + '\u0000' + (areaRule?.name.orEmpty()) + '\u0000' + ItemText.cacheKey(cacheName, cacheLore) +
                '\u0000' + ItemText.cacheKey(areaNameTexts.firstOrNull(), areaLoreTexts)
        } else {
            id
        }
        val cacheAllowed = !namedRules.itemUsesContext
        if (trace == null && cacheAllowed) {
            prepareItemCache(revision)
            val cached = itemDecisionCache[cacheKey]
            if (cached != null && cached.revision == revision) {
                return cached.decision
            }
        }

        val namedMatch = if (namedRules.hasItemRules) {
            namedRules.matchItem(
                RuleEngine.ItemFacts(
                    ids = ids,
                    rawName = rawName,
                    rawLore = rawLore,
                    world = itemLocation?.world?.name,
                    y = itemLocation?.blockY,
                    ageTicks = item.ticksLived
                )
            )
        } else {
            null
        }
        if (namedMatch != null) {
            trace?.add("命名规则", "命中 ${namedMatch.name}（优先级 ${namedMatch.priority}）")
            val decision = FilterDecision(
                remove = namedMatch.action == RuleEngine.Action.CLEAN,
                id = id,
                reason = if (namedMatch.action == RuleEngine.Action.CLEAN) {
                    "命名规则清理：${namedMatch.name}"
                } else {
                    "命名规则保留：${namedMatch.name}"
                },
                reasonKey = if (namedMatch.action == RuleEngine.Action.CLEAN) {
                    ReasonKey.NAMED_RULE_CLEAN
                } else {
                    ReasonKey.NAMED_RULE_KEEP
                },
                itemIds = ids,
                namedRule = namedMatch.name,
                rulePriority = namedMatch.priority
            )
            if (trace == null && cacheAllowed) storeItemDecision(cacheKey, revision, decision)
            return decision
        }
        trace?.add("命名规则", "未命中")

        areaRule?.decideItem(ids, areaNameTexts, hasCustomName, areaLoreTexts)?.let {
            return FilterDecision(it.remove, id, it.reason, ReasonKey.AREA_RULE, itemIds = ids)
        }
        trace?.add("区域规则", areaRule?.let { "命中 ${it.name}，继续使用全局规则" } ?: "未命中")

        trace?.add("物品规则", "继续检查")
        val decision = explainItemLayers(ids, nameRules, nameTexts, hasCustomName, loreRules, loreTexts)
        if (trace == null && cacheAllowed) storeItemDecision(cacheKey, revision, decision)
        return decision
    }

    private fun explainItemLayers(
        ids: List<String>,
        nameRules: ItemText.RuleSet,
        nameTexts: List<String>,
        hasCustomName: Boolean,
        loreRules: ItemText.RuleSet,
        loreTexts: List<String>
    ): FilterDecision {
        val id = ids.first()

        if (nameRules.canProtect(nameTexts, hasCustomName)) {
            return FilterDecision(false, id, "展示名保留名单", ReasonKey.NAME_KEEP, itemIds = ids)
        }
        if (loreRules.canProtect(loreTexts, hasCustomName = false)) {
            return FilterDecision(false, id, "Lore 保留名单", ReasonKey.LORE_KEEP, itemIds = ids)
        }

        if (nameRules.forceClean && nameRules.canClean(nameTexts, hasCustomName)) {
            return FilterDecision(true, id, "展示名强制清理", ReasonKey.NAME_CLEAN, itemIds = ids)
        }
        if (loreRules.forceClean && loreRules.canClean(loreTexts, hasCustomName = false)) {
            return FilterDecision(true, id, "Lore 强制清理", ReasonKey.LORE_CLEAN, itemIds = ids)
        }

        if (Settings.itemProtectMatcher.matchesAnyNormalized(ids)) {
            return FilterDecision(false, id, "掉落物保留名单", ReasonKey.KEEP_LIST, itemIds = ids)
        }

        if (!nameRules.forceClean && nameRules.canClean(nameTexts, hasCustomName)) {
            return FilterDecision(true, id, "展示名清理名单", ReasonKey.NAME_CLEAN, itemIds = ids)
        }
        if (!loreRules.forceClean && loreRules.canClean(loreTexts, hasCustomName = false)) {
            return FilterDecision(true, id, "Lore 清理名单", ReasonKey.LORE_CLEAN, itemIds = ids)
        }

        if (Settings.itemCleanMatcher.matchesAnyNormalized(ids)) {
            return FilterDecision(true, id, "掉落物清理名单", ReasonKey.CLEAN_LIST, itemIds = ids)
        }

        return when (Settings.itemDefaultAction) {
            Settings.DefaultAction.CLEAN -> FilterDecision(true, id, "默认清理", ReasonKey.DEFAULT_CLEAN, itemIds = ids)
            Settings.DefaultAction.KEEP -> FilterDecision(false, id, "默认保留", ReasonKey.DEFAULT_KEEP, itemIds = ids)
        }
    }

    private fun prepareItemCache(revision: Long) {
        if (itemCacheRevision.get() == revision) return
        synchronized(itemDecisionCache) {
            if (itemCacheRevision.get() == revision) return
            itemDecisionCache.clear()
            itemCacheRevision.set(revision)
        }
    }

    private fun storeItemDecision(cacheKey: String, revision: Long, decision: FilterDecision) {
        if (itemDecisionCache.size >= ITEM_CACHE_MAX) {
            itemDecisionCache.clear()
        }
        itemDecisionCache[cacheKey] = CachedItemDecision(revision, decision)
    }
}

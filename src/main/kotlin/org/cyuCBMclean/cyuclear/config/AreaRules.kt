package org.cyuCBMclean.cyuclear.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.util.IdMatcher
import org.cyuCBMclean.cyuclear.util.ItemText
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object AreaRules {
    enum class Mode {
        INHERIT,
        KEEP_ALL,
        CLEAN_ALL,
        BLACKLIST,
        WHITELIST,
        PARALLEL
    }

    data class Decision(val remove: Boolean, val reason: String)

    class TargetRule(
        val mode: Mode,
        private val keep: IdMatcher,
        private val clean: IdMatcher,
        val usesMythic: Boolean = false
    ) {
        fun matchesKeep(ids: Collection<String>): Boolean = keep.matchesAnyNormalized(ids)

        fun matchesClean(ids: Collection<String>): Boolean = clean.matchesAnyNormalized(ids)

        fun decide(ids: Collection<String>, areaName: String): Decision? {
            return when (mode) {
                Mode.INHERIT -> null
                Mode.KEEP_ALL -> Decision(false, "区域 $areaName 全部保留")
                Mode.CLEAN_ALL -> Decision(true, "区域 $areaName 全部清理")
                Mode.BLACKLIST -> if (matchesKeep(ids)) {
                    Decision(false, "区域 $areaName 保留名单")
                } else {
                    Decision(true, "区域 $areaName 默认清理")
                }
                Mode.WHITELIST -> if (matchesClean(ids)) {
                    Decision(true, "区域 $areaName 清理名单")
                } else {
                    Decision(false, "区域 $areaName 默认保留")
                }
                Mode.PARALLEL -> when {
                    matchesKeep(ids) -> Decision(false, "区域 $areaName 保留名单")
                    matchesClean(ids) -> Decision(true, "区域 $areaName 清理名单")
                    else -> Decision(false, "区域 $areaName 默认保留")
                }
            }
        }
    }

    data class Rule(
        val name: String,
        val priority: Int,
        val order: Int,
        val worlds: Set<String>,
        val bounds: Bounds?,
        val items: TargetRule,
        val itemNames: ItemText.RuleSet,
        val itemLores: ItemText.RuleSet,
        val entities: TargetRule,
        val binCleanupRecovery: BinEntryRules.Policy?,
        val binPlayerDeposit: BinEntryRules.Policy?
    ) {
        fun binPolicy(source: BinEntryRules.Source): BinEntryRules.Policy? {
            return when (source) {
                BinEntryRules.Source.CLEANUP_RECOVERY -> binCleanupRecovery
                BinEntryRules.Source.PLAYER_DEPOSIT -> binPlayerDeposit
            }
        }

        fun decideItem(
            ids: Collection<String>,
            nameTexts: List<String>,
            hasCustomName: Boolean,
            loreTexts: List<String>
        ): Decision? {
            when (items.mode) {
                Mode.KEEP_ALL -> return Decision(false, "区域 $name 全部保留")
                Mode.CLEAN_ALL -> return Decision(true, "区域 $name 全部清理")
                else -> Unit
            }

            if (itemNames.canProtect(nameTexts, hasCustomName)) {
                return Decision(false, "区域 $name 展示名保留")
            }
            if (itemLores.canProtect(loreTexts, hasCustomName = false)) {
                return Decision(false, "区域 $name Lore 保留")
            }

            val forceName = itemNames.forceClean
            val forceLore = itemLores.forceClean
            if (forceName && itemNames.canClean(nameTexts, hasCustomName)) {
                return Decision(true, "区域 $name 展示名强制清理")
            }
            if (forceLore && itemLores.canClean(loreTexts, hasCustomName = false)) {
                return Decision(true, "区域 $name Lore 强制清理")
            }

            if (items.mode != Mode.INHERIT && items.matchesKeep(ids)) {
                return Decision(false, "区域 $name 保留名单")
            }

            if (!forceName && itemNames.canClean(nameTexts, hasCustomName)) {
                return Decision(true, "区域 $name 展示名清理")
            }
            if (!forceLore && itemLores.canClean(loreTexts, hasCustomName = false)) {
                return Decision(true, "区域 $name Lore 清理")
            }

            if (items.mode == Mode.INHERIT) {
                return null
            }

            if (items.matchesClean(ids)) {
                return Decision(true, "区域 $name 清理名单")
            }

            return when (items.mode) {
                Mode.BLACKLIST -> Decision(true, "区域 $name 默认清理")
                Mode.WHITELIST, Mode.PARALLEL -> Decision(false, "区域 $name 默认保留")
                else -> null
            }
        }
    }

    data class Bounds(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int
    ) {
        fun contains(x: Int, y: Int, z: Int): Boolean = x in minX..maxX && y in minY..maxY && z in minZ..maxZ
        fun intersectsChunk(chunkX: Int, chunkZ: Int): Boolean {
            val chunkMinX = chunkX shl 4
            val chunkMinZ = chunkZ shl 4
            return maxX >= chunkMinX && minX <= chunkMinX + 15 && maxZ >= chunkMinZ && minZ <= chunkMinZ + 15
        }
    }

    private data class ChunkKey(val world: String, val x: Int, val z: Int)

    private data class LocalCandidates(
        val revision: Long,
        val world: String,
        val x: Int,
        val z: Int,
        val rules: List<Rule>
    )

    @Volatile
    private var rulesByWorld: Map<String, List<Rule>> = emptyMap()
    @Volatile
    private var binEntryRulesPresent: Boolean = false
    @Volatile
    private var itemFilterRulesPresent: Boolean = false
    @Volatile
    private var entityFilterRulesPresent: Boolean = false
    @Volatile
    private var entityMythicFilterRulesPresent: Boolean = false
    private val chunkCandidates = ConcurrentHashMap<ChunkKey, List<Rule>>()
    private val runtimeWorldRules = ConcurrentHashMap<String, List<Rule>>()
    private val clearingChunkCandidates = AtomicBoolean(false)
    private val candidateRevision = AtomicLong(0L)
    private val localCandidates = ThreadLocal<LocalCandidates?>()

    fun load(config: YamlConfiguration) {
        chunkCandidates.clear()
        runtimeWorldRules.clear()
        binEntryRulesPresent = false
        itemFilterRulesPresent = false
        entityFilterRulesPresent = false
        entityMythicFilterRulesPresent = false
        if (!config.getBoolean("areas.enabled", false)) {
            rulesByWorld = emptyMap()
            candidateRevision.incrementAndGet()
            return
        }
        val section = config.getConfigurationSection("areas.rules")
        if (section == null) {
            rulesByWorld = emptyMap()
            candidateRevision.incrementAndGet()
            return
        }
        val loaded = ArrayList<Rule>()
        section.getKeys(false).forEachIndexed { order, name ->
            val rule = section.getConfigurationSection(name) ?: return@forEachIndexed
            if (!rule.getBoolean("enabled", true)) return@forEachIndexed
            val worlds = rule.getStringList("worlds").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            if (worlds.isEmpty()) {
                Cyuclear.instance.logger.warning("Cyuclear 跳过了未填写 worlds 的区域规则 areas.rules.$name")
                return@forEachIndexed
            }
            val bounds = loadBounds(rule, name)
            if (rule.isConfigurationSection("area") && bounds == null) return@forEachIndexed
            val itemsSection = rule.getConfigurationSection("items")
            loaded.add(
                Rule(
                    name = name,
                    priority = rule.getInt("priority", 0),
                    order = order,
                    worlds = worlds,
                    bounds = bounds,
                    items = loadTarget(rule, "items", name),
                    itemNames = ItemText.loadRuleSet(
                        section = itemsSection?.getConfigurationSection("name-rules"),
                        groupPrefix = "areas.rules.$name.items.name-rules",
                        requireCustomNameDefault = true,
                        logger = Cyuclear.instance.logger::warning
                    ),
                    itemLores = ItemText.loadRuleSet(
                        section = itemsSection?.getConfigurationSection("lore-rules"),
                        groupPrefix = "areas.rules.$name.items.lore-rules",
                        requireCustomNameDefault = false,
                        logger = Cyuclear.instance.logger::warning
                    ),
                    entities = loadTarget(rule, "entities", name),
                    binCleanupRecovery = BinEntryRules.loadAreaPolicy(
                        rule.getConfigurationSection("void-bin.cleanup-recovery"),
                        "areas.rules.$name.void-bin.cleanup-recovery"
                    ),
                    binPlayerDeposit = BinEntryRules.loadAreaPolicy(
                        rule.getConfigurationSection("void-bin.player-deposit"),
                        "areas.rules.$name.void-bin.player-deposit"
                    )
                )
            )
        }
        binEntryRulesPresent = loaded.any { it.binCleanupRecovery != null || it.binPlayerDeposit != null }
        itemFilterRulesPresent = loaded.any { rule ->
            rule.items.mode != Mode.INHERIT || rule.itemNames.hasRules || rule.itemLores.hasRules
        }
        entityFilterRulesPresent = loaded.any { it.entities.mode != Mode.INHERIT }
        entityMythicFilterRulesPresent = loaded.any { it.entities.usesMythic }
        val grouped = LinkedHashMap<String, MutableList<Rule>>()
        for (rule in loaded) for (world in rule.worlds) grouped.getOrPut(world, ::ArrayList).add(rule)
        rulesByWorld = grouped.mapValues { (_, rules) -> rules.sortedWith(compareByDescending<Rule> { it.priority }.thenBy { it.order }) }
        candidateRevision.incrementAndGet()
    }

    fun find(world: String, x: Int, y: Int, z: Int): Rule? {
        val chunkX = x shr 4
        val chunkZ = z shr 4
        val revision = candidateRevision.get()
        val local = localCandidates.get()
        val candidates = if (local != null && local.revision == revision && local.world == world && local.x == chunkX && local.z == chunkZ) {
            local.rules
        } else {
            val key = ChunkKey(world, chunkX, chunkZ)
            trimChunkCacheIfNeeded()
            val resolved = chunkCandidates.computeIfAbsent(key) {
                rulesFor(world).filter { rule -> rule.bounds?.intersectsChunk(chunkX, chunkZ) != false }
            }
            localCandidates.set(LocalCandidates(revision, world, chunkX, chunkZ, resolved))
            resolved
        }
        return candidates.firstOrNull { it.bounds?.contains(x, y, z) != false }
    }

    fun hasBinEntryRules(): Boolean = binEntryRulesPresent

    fun hasItemFilterRules(): Boolean = itemFilterRulesPresent

    fun hasEntityFilterRules(): Boolean = entityFilterRulesPresent

    fun hasMythicEntityRules(): Boolean = entityMythicFilterRulesPresent

    private fun rulesFor(world: String): List<Rule> {
        return runtimeWorldRules.computeIfAbsent(world) { name -> rulesByWorld[name.lowercase()].orEmpty() }
    }

    private fun trimChunkCacheIfNeeded() {
        if (chunkCandidates.size < MAX_CACHED_CHUNKS || !clearingChunkCandidates.compareAndSet(false, true)) return
        try {
            chunkCandidates.clear()
        } finally {
            clearingChunkCandidates.set(false)
        }
    }

    private fun loadTarget(rule: ConfigurationSection, path: String, areaName: String): TargetRule {
        val section = rule.getConfigurationSection(path) ?: return TargetRule(Mode.INHERIT, IdMatcher.empty(), IdMatcher.empty())
        val mode = parseMode(section.getString("mode"), "areas.rules.$areaName.$path.mode")
        val keep = loadMatcher(section, "keep-list", "areas.rules.$areaName.$path.keep-list")
        val clean = loadMatcher(section, "clean-list", "areas.rules.$areaName.$path.clean-list")
        val usesMythic = when (mode) {
            Mode.BLACKLIST, Mode.WHITELIST, Mode.PARALLEL ->
                listUsesMythic(section, "keep-list") || listUsesMythic(section, "clean-list")
            else -> false
        }
        return TargetRule(mode, keep, clean, usesMythic)
    }

    private fun listUsesMythic(section: ConfigurationSection, path: String): Boolean {
        val nested = section.getConfigurationSection(path)
        val mode = when (nested?.getString("match-mode")?.trim()?.lowercase()) {
            "通配", "wildcard" -> Settings.MatchMode.WILDCARD
            "正则", "regex" -> Settings.MatchMode.REGEX
            else -> Settings.MatchMode.EXACT
        }
        val entries = if (nested == null) section.getStringList(path) else nested.getStringList("list")
        return TargetRuleCapabilities.hasMythicRules(listOf(entries to mode))
    }

    private fun loadMatcher(section: ConfigurationSection, path: String, label: String): IdMatcher {
        val nested = section.getConfigurationSection(path)
        val mode = when (nested?.getString("match-mode")?.trim()?.lowercase()) {
            "通配", "wildcard" -> Settings.MatchMode.WILDCARD
            "正则", "regex" -> Settings.MatchMode.REGEX
            else -> Settings.MatchMode.EXACT
        }
        val entries = if (nested == null) section.getStringList(path) else nested.getStringList("list")
        return IdMatcher.compile(mode, entries, label, Cyuclear.instance.logger::warning)
    }

    private fun parseMode(raw: String?, path: String): Mode {
        return when (raw?.trim()?.lowercase()) {
            null, "", "继承", "inherit" -> Mode.INHERIT
            "全部保留", "保留", "keep", "keep-all" -> Mode.KEEP_ALL
            "全部清理", "清理", "clean", "clean-all" -> Mode.CLEAN_ALL
            "黑名单", "blacklist" -> Mode.BLACKLIST
            "白名单", "whitelist" -> Mode.WHITELIST
            "并行名单", "parallel" -> Mode.PARALLEL
            else -> {
                Cyuclear.instance.logger.warning("Cyuclear 在 $path 读取到未知值 '$raw'，已回退为继承")
                Mode.INHERIT
            }
        }
    }

    private fun loadBounds(rule: ConfigurationSection, name: String): Bounds? {
        if (!rule.isConfigurationSection("area")) return null
        val area = rule.getConfigurationSection("area") ?: return null
        if (!area.isConfigurationSection("min") || !area.isConfigurationSection("max")) {
            Cyuclear.instance.logger.warning("Cyuclear 跳过了坐标不完整的区域范围 areas.rules.$name.area")
            return null
        }
        return Bounds(
            minOf(area.getInt("min.x"), area.getInt("max.x")),
            minOf(area.getInt("min.y"), area.getInt("max.y")),
            minOf(area.getInt("min.z"), area.getInt("max.z")),
            maxOf(area.getInt("min.x"), area.getInt("max.x")),
            maxOf(area.getInt("min.y"), area.getInt("max.y")),
            maxOf(area.getInt("min.z"), area.getInt("max.z"))
        )
    }

    private const val MAX_CACHED_CHUNKS = 65536
}

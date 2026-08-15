package org.cyuCBMclean.cyuclear.service

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.platform.EntityStateBridge
import org.cyuCBMclean.cyuclear.util.IdMatcher
import org.cyuCBMclean.cyuclear.util.ItemText
import java.util.Locale

object RuleEngine {
    enum class Target {
        ITEM,
        ENTITY
    }

    enum class Action {
        KEEP,
        CLEAN
    }

    data class Match(
        val name: String,
        val target: Target,
        val action: Action,
        val priority: Int,
        val stopProcessing: Boolean,
        val bypasses: Set<EntityDetailRules.Protection>
    )

    data class ItemFacts(
        val ids: List<String>,
        val rawName: String?,
        val rawLore: List<String>,
        val world: String?,
        val y: Int?,
        val ageTicks: Int
    )

    data class EntityFacts(
        val entity: Entity,
        val ids: Set<String>,
        val rawName: String?,
        val pokemonOwned: Boolean,
        val world: String?,
        val y: Int?,
        val ageTicks: Int
    )

    class Rules internal constructor(
        private val itemRules: List<Rule>,
        private val entityRules: List<Rule>
    ) {
        val itemRuleCount: Int = itemRules.size
        val entityRuleCount: Int = entityRules.size
        val ruleCount: Int = itemRuleCount + entityRuleCount
        val hasItemRules: Boolean = itemRules.isNotEmpty()
        val itemUsesName: Boolean = itemRules.any { it.nameMatcher != null }
        val itemUsesLore: Boolean = itemRules.any { it.loreMatcher != null }
        val itemUsesLocation: Boolean = itemRules.any { it.usesLocation }
        val itemUsesContext: Boolean = itemRules.any { it.usesLocation || it.ageRange != null }
        val hasEntityRules: Boolean = entityRules.isNotEmpty()
        val entityUsesLocation: Boolean = entityRules.any { it.usesLocation }
        val entityUsesPokemon: Boolean = entityRules.any { it.usesPokemon }
        val entityRequiresFullPokemon: Boolean = entityRules.any { it.requiresFullPokemon }
        val entityLightPokemonRules: Set<String> = entityRules.flatMapTo(LinkedHashSet()) { it.lightPokemonRules }
        val entityUsesMythic: Boolean = entityRules.any { it.usesMythic }

        fun matchItem(facts: ItemFacts): Match? {
            var matched: Rule? = null
            for (rule in itemRules) {
                if (!rule.matchesItem(facts)) continue
                if (rule.stopProcessing) return match(rule)
                matched = rule
            }
            return if (matched == null) null else match(matched)
        }

        fun matchEntity(facts: EntityFacts): Match? {
            var matched: Rule? = null
            for (rule in entityRules) {
                if (!rule.matchesEntity(facts)) continue
                if (rule.stopProcessing) return match(rule)
                matched = rule
            }
            return if (matched == null) null else match(matched)
        }

        private fun match(rule: Rule): Match = Match(
            rule.name,
            rule.target,
            rule.action,
            rule.priority,
            rule.stopProcessing,
            rule.bypasses
        )
    }

    internal data class MatcherValue(
        val matcher: IdMatcher,
        val entries: List<String>
    )

    internal data class TextMatcher(
        val matcher: IdMatcher,
        val colorMode: ItemText.ColorMode
    ) {
        fun matches(value: String?): Boolean {
            return matcher.matches(ItemText.normalize(value, colorMode))
        }

        fun matchesAny(values: List<String>): Boolean {
            for (value in values) {
                if (matches(value)) return true
            }
            return false
        }
    }

    internal data class NumberRange(
        val minimum: Long?,
        val maximum: Long?
    ) {
        fun contains(value: Long): Boolean {
            return (minimum == null || value >= minimum) && (maximum == null || value <= maximum)
        }
    }

    internal data class Rule(
        val name: String,
        val target: Target,
        val action: Action,
        val priority: Int,
        val order: Int,
        val matchAll: Boolean,
        val stopProcessing: Boolean,
        val idMatcher: IdMatcher?,
        val nameMatcher: TextMatcher?,
        val loreMatcher: TextMatcher?,
        val worldMatcher: IdMatcher?,
        val heightRange: NumberRange?,
        val ageRange: NumberRange?,
        val tagMatcher: IdMatcher?,
        val states: EntityDetailRules.StateConditions?,
        val bypasses: Set<EntityDetailRules.Protection>,
        val usesPokemon: Boolean,
        val requiresFullPokemon: Boolean,
        val lightPokemonRules: Set<String>,
        val usesMythic: Boolean
    ) {
        val usesLocation: Boolean
            get() = worldMatcher != null || heightRange != null

        fun matchesItem(facts: ItemFacts): Boolean {
            if (target != Target.ITEM) return false
            if (matchAll) {
                idMatcher?.let { if (!it.matchesAnyNormalized(facts.ids)) return false }
                nameMatcher?.let { if (!it.matches(facts.rawName)) return false }
                loreMatcher?.let { if (!it.matchesAny(facts.rawLore)) return false }
                worldMatcher?.let {
                    val world = facts.world ?: return false
                    if (!it.matches(world)) return false
                }
                heightRange?.let {
                    val y = facts.y ?: return false
                    if (!it.contains(y.toLong())) return false
                }
                ageRange?.let { if (!it.contains(facts.ageTicks.toLong() / 20L)) return false }
                return hasItemConditions()
            }
            idMatcher?.let { if (it.matchesAnyNormalized(facts.ids)) return true }
            nameMatcher?.let { if (it.matches(facts.rawName)) return true }
            loreMatcher?.let { if (it.matchesAny(facts.rawLore)) return true }
            worldMatcher?.let { matcher ->
                val world = facts.world
                if (world != null && matcher.matches(world)) return true
            }
            heightRange?.let { range ->
                val y = facts.y
                if (y != null && range.contains(y.toLong())) return true
            }
            ageRange?.let { if (it.contains(facts.ageTicks.toLong() / 20L)) return true }
            return false
        }

        fun matchesEntity(facts: EntityFacts): Boolean {
            if (target != Target.ENTITY) return false
            if (matchAll) {
                idMatcher?.let { if (!it.matchesAnyNormalized(facts.ids)) return false }
                nameMatcher?.let { if (!it.matches(facts.rawName)) return false }
                worldMatcher?.let {
                    val world = facts.world ?: return false
                    if (!it.matches(world)) return false
                }
                heightRange?.let {
                    val y = facts.y ?: return false
                    if (!it.contains(y.toLong())) return false
                }
                ageRange?.let { if (!it.contains(facts.ageTicks.toLong() / 20L)) return false }
                tagMatcher?.let { if (!EntityStateBridge.scoreboardTags(facts.entity).any(it::matches)) return false }
                states?.let { if (!it.matches(facts.entity, facts.rawName, facts.pokemonOwned)) return false }
                return hasEntityConditions()
            }
            idMatcher?.let { if (it.matchesAnyNormalized(facts.ids)) return true }
            nameMatcher?.let { if (it.matches(facts.rawName)) return true }
            worldMatcher?.let { matcher ->
                val world = facts.world
                if (world != null && matcher.matches(world)) return true
            }
            heightRange?.let { range ->
                val y = facts.y
                if (y != null && range.contains(y.toLong())) return true
            }
            ageRange?.let { if (it.contains(facts.ageTicks.toLong() / 20L)) return true }
            tagMatcher?.let { if (EntityStateBridge.scoreboardTags(facts.entity).any(it::matches)) return true }
            states?.let { if (it.matches(facts.entity, facts.rawName, facts.pokemonOwned)) return true }
            return false
        }

        private fun hasItemConditions(): Boolean {
            return idMatcher != null || nameMatcher != null || loreMatcher != null || worldMatcher != null || heightRange != null || ageRange != null
        }

        private fun hasEntityConditions(): Boolean {
            return idMatcher != null || nameMatcher != null || worldMatcher != null || heightRange != null || ageRange != null || tagMatcher != null || states != null
        }
    }

    fun empty(): Rules = Rules(emptyList(), emptyList())

    fun load(section: ConfigurationSection?, logger: (String) -> Unit): Rules {
        if (section == null) return empty()
        val loaded = ArrayList<Rule>()
        section.getKeys(false).forEachIndexed { order, name ->
            val ruleSection = section.getConfigurationSection(name) ?: return@forEachIndexed
            if (!ruleSection.getBoolean("enabled", true)) return@forEachIndexed
            val path = "rules.$name"
            val target = parseTarget(ruleSection.getString("target"))
            if (target == null) {
                logger("Cyuclear 跳过了未识别目标的命名规则 $path")
                return@forEachIndexed
            }
            val action = parseAction(ruleSection.getString("action"))
            if (action == null) {
                logger("Cyuclear 跳过了未识别动作的命名规则 $path")
                return@forEachIndexed
            }
            val conditions = ruleSection.getConfigurationSection("conditions") ?: ruleSection
            val ids = loadMatcher(conditions, "ids", "$path.conditions.ids", logger)
            val names = loadTextMatcher(conditions, "names", "$path.conditions.names", logger)
            val lore = loadTextMatcher(conditions, "lore", "$path.conditions.lore", logger)
            val worlds = loadMatcher(conditions, "worlds", "$path.conditions.worlds", logger)
            val height = loadRange(conditions.getConfigurationSection("height"), "min", "max")
            val age = loadRange(
                conditions.getConfigurationSection("age"),
                "minimum-seconds",
                "maximum-seconds",
                "min",
                "max"
            )
            val tags = loadMatcher(conditions, "scoreboard-tags", "$path.conditions.scoreboard-tags", logger)
            val states = EntityDetailRules.loadStates(conditions.getConfigurationSection("states"))
            if (ids == null && names == null && lore == null && worlds == null && height == null && age == null && tags == null && states == null) {
                logger("Cyuclear 跳过了没有匹配条件的命名规则 $path")
                return@forEachIndexed
            }
            if (target == Target.ITEM && (tags != null || states != null)) {
                logger("Cyuclear 跳过了物品命名规则 $path 中仅实体可用的条件")
                return@forEachIndexed
            }
            if (target == Target.ENTITY && lore != null) {
                logger("Cyuclear 跳过了实体命名规则 $path 中仅物品可用的 Lore 条件")
                return@forEachIndexed
            }
            val idEntries = ids?.entries.orEmpty()
            val pokemon = pokemonRequirements(idEntries, states)
            loaded += Rule(
                name = name,
                target = target,
                action = action,
                priority = ruleSection.getInt("priority", 0).coerceIn(-10_000, 10_000),
                order = order,
                matchAll = parseMatchAll(conditions.getString("match")),
                stopProcessing = ruleSection.getBoolean("stop-processing", true),
                idMatcher = ids?.matcher,
                nameMatcher = names,
                loreMatcher = lore,
                worldMatcher = worlds?.matcher,
                heightRange = height,
                ageRange = age,
                tagMatcher = tags?.matcher,
                states = states,
                bypasses = if (target == Target.ENTITY) {
                    EntityDetailRules.parseBypasses(ruleSection.getStringList("bypass-protections"), path, logger)
                } else {
                    emptySet()
                },
                usesPokemon = target == Target.ENTITY && pokemon.usesPokemon,
                requiresFullPokemon = target == Target.ENTITY && pokemon.requiresFullPokemon,
                lightPokemonRules = if (target == Target.ENTITY) pokemon.lightRules else emptySet(),
                usesMythic = target == Target.ENTITY && idEntries.any(::isMythicRule)
            )
        }
        val sorted = loaded.sortedWith(compareByDescending<Rule> { it.priority }.thenBy { it.order })
        return Rules(sorted.filter { it.target == Target.ITEM }, sorted.filter { it.target == Target.ENTITY })
    }

    private fun parseTarget(raw: String?): Target? {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "item", "items", "物品", "掉落物" -> Target.ITEM
            "entity", "entities", "实体" -> Target.ENTITY
            else -> null
        }
    }

    private fun parseAction(raw: String?): Action? {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "keep", "保留" -> Action.KEEP
            "clean", "remove", "清理", "删除" -> Action.CLEAN
            else -> null
        }
    }

    private fun parseMatchAll(raw: String?): Boolean {
        return raw?.trim()?.lowercase(Locale.ROOT) !in setOf("any", "任意", "任一")
    }

    private fun loadMatcher(
        section: ConfigurationSection,
        key: String,
        path: String,
        logger: (String) -> Unit
    ): MatcherValue? {
        val nested = section.getConfigurationSection(key)
        val entries = readEntries(section, key)
        if (entries.isEmpty()) return null
        val mode = parseMatchMode(nested?.getString("match-mode") ?: nested?.getString("match"))
        return MatcherValue(IdMatcher.compile(mode, entries, "$path.list", logger), entries)
    }

    private fun loadTextMatcher(
        section: ConfigurationSection,
        key: String,
        path: String,
        logger: (String) -> Unit
    ): TextMatcher? {
        val nested = section.getConfigurationSection(key)
        val entries = readEntries(section, key)
        if (entries.isEmpty()) return null
        val colorMode = ItemText.parseColorMode(nested?.getString("color-mode"))
        val mode = parseMatchMode(nested?.getString("match-mode") ?: nested?.getString("match"))
        return TextMatcher(
            IdMatcher.compile(mode, ItemText.prepareRuleEntries(entries, colorMode), "$path.list", logger),
            colorMode
        )
    }

    private fun readEntries(section: ConfigurationSection, key: String): List<String> {
        val nested = section.getConfigurationSection(key)
        if (nested != null) return nested.getStringList("list")
        if (section.isList(key)) return section.getStringList(key)
        return section.getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()
    }

    private fun parseMatchMode(raw: String?): Settings.MatchMode {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "通配", "wildcard", "glob" -> Settings.MatchMode.WILDCARD
            "正则", "regex", "regexp", "re" -> Settings.MatchMode.REGEX
            else -> Settings.MatchMode.EXACT
        }
    }

    private fun loadRange(section: ConfigurationSection?, vararg names: String): NumberRange? {
        if (section == null) return null
        val minimum = names
            .filter { it.startsWith("minimum") || it == "min" }
            .firstOrNull(section::contains)
            ?.let(section::getLong)
        val maximum = names
            .filter { it.startsWith("maximum") || it == "max" }
            .firstOrNull(section::contains)
            ?.let(section::getLong)
        return if (minimum == null && maximum == null) null else NumberRange(minimum, maximum)
    }

    private data class PokemonRequirements(
        val usesPokemon: Boolean,
        val requiresFullPokemon: Boolean,
        val lightRules: Set<String>
    )

    private fun pokemonRequirements(entries: List<String>, states: EntityDetailRules.StateConditions?): PokemonRequirements {
        val normalized = entries.map(::ruleValue)
        val pokemonEntries = normalized.filter(::isPokemonRule)
        val lightRules = pokemonEntries.mapNotNull(::lightPokemonRule).toSet()
        val usesPokemon = pokemonEntries.isNotEmpty() || states?.playerOwnedPokemon != null
        return PokemonRequirements(
            usesPokemon = usesPokemon,
            requiresFullPokemon = pokemonEntries.any { lightPokemonRule(it) == null },
            lightRules = lightRules
        )
    }

    private fun ruleValue(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        val separator = value.indexOfFirst { it == ':' || it == '：' }
        if (separator <= 0) return value
        return when (value.substring(0, separator).trim()) {
            "exact", "精准", "精确", "wildcard", "glob", "通配", "regex", "regexp", "re", "正则" -> {
                value.substring(separator + 1).trim()
            }
            else -> value
        }
    }

    private fun isPokemonRule(value: String): Boolean {
        return value.startsWith("pokemon:") || value.startsWith("cobblemon:") || value.startsWith("pixelmon:")
    }

    private fun isMythicRule(raw: String): Boolean = ruleValue(raw).startsWith("mythic:")

    private fun lightPokemonRule(value: String): String? {
        if (value == "pokemon:shiny=true") return "shiny"
        if (value == "pokemon:owned=true" || value == "pokemon:player_owned=true") return "owned"
        if (value == "cobblemon:owned=true" || value == "pixelmon:owned=true") return "owned"
        if (value.startsWith("pokemon:mod=")) return "mod"
        val tag = when {
            value.startsWith("pokemon:tag=") -> value.substringAfter('=')
            value.startsWith("cobblemon:label=") -> value.substringAfter('=')
            value.startsWith("pixelmon:tag=") -> value.substringAfter('=')
            else -> return null
        }
        return normalizePokemonTag(tag).takeIf { it in lightPokemonTags }
    }

    private fun normalizePokemonTag(tag: String): String {
        return when (tag.replace('-', '_')) {
            "ultrabeast" -> "ultra_beast"
            "gmax" -> "gigantamax"
            else -> tag.replace('-', '_')
        }
    }

    private val lightPokemonTags = setOf(
        "legendary",
        "mythical",
        "ultra_beast",
        "paradox",
        "mega",
        "gigantamax",
        "boss"
    )
}

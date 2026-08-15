package org.cyuCBMclean.cyuclear.service

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Tameable
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.config.TargetRuleCapabilities
import org.cyuCBMclean.cyuclear.platform.EntityStateBridge
import org.cyuCBMclean.cyuclear.util.IdMatcher
import org.cyuCBMclean.cyuclear.util.ItemText
import java.util.Locale

object EntityDetailRules {
    enum class Action { KEEP, CLEAN }
    enum class Protection { NAMED, TAMED, PERSISTENT, NO_DESPAWN, RAID, PLAYER_OWNED_POKEMON }

    data class Match(val name: String, val action: Action, val bypasses: Set<Protection>)
    data class Facts(val entity: Entity, val ids: Set<String>, val rawName: String?, val pokemonOwned: Boolean)

    data class Rule(
        val name: String,
        val action: Action,
        val matchAll: Boolean,
        val idMatcher: IdMatcher?,
        val nameMatcher: IdMatcher?,
        val nameColorMode: ItemText.ColorMode,
        val tagMatcher: IdMatcher?,
        val states: StateConditions?,
        val bypasses: Set<Protection>,
        val usesPokemon: Boolean,
        val requiresFullPokemon: Boolean,
        val usesMythic: Boolean
    ) {
        fun matches(facts: Facts): Boolean {
            if (matchAll) {
                idMatcher?.let { matcher ->
                    if (!matcher.matchesAnyNormalized(facts.ids)) return false
                }
                nameMatcher?.let { matcher ->
                    val normalized = ItemText.normalize(facts.rawName, nameColorMode)
                    if (normalized.isEmpty() || !matcher.matches(normalized)) return false
                }
                tagMatcher?.let { matcher ->
                    if (!EntityStateBridge.scoreboardTags(facts.entity).any(matcher::matches)) return false
                }
                states?.let {
                    if (!it.matches(facts)) return false
                }
                return hasConditions()
            }

            idMatcher?.let { matcher ->
                if (matcher.matchesAnyNormalized(facts.ids)) return true
            }
            nameMatcher?.let { matcher ->
                val normalized = ItemText.normalize(facts.rawName, nameColorMode)
                if (normalized.isNotEmpty() && matcher.matches(normalized)) return true
            }
            tagMatcher?.let { matcher ->
                if (EntityStateBridge.scoreboardTags(facts.entity).any(matcher::matches)) return true
            }
            states?.let {
                if (it.matches(facts)) return true
            }
            return false
        }

        private fun hasConditions(): Boolean {
            return idMatcher != null || nameMatcher != null || tagMatcher != null || states != null
        }
    }

    data class StateConditions(
        val named: Boolean?,
        val tamed: Boolean?,
        val persistent: Boolean?,
        val noDespawn: Boolean?,
        val inRaid: Boolean?,
        val playerOwnedPokemon: Boolean?,
        val hasPassengers: Boolean?,
        val hasVehicle: Boolean?
    ) {
        fun matches(facts: Facts): Boolean {
            return matches(facts.entity, facts.rawName, facts.pokemonOwned)
        }

        fun matches(entity: Entity, rawName: String?, pokemonOwned: Boolean): Boolean {
            if (named != null && (rawName != null) != named) return false
            if (tamed != null && (entity is Tameable && entity.isTamed) != tamed) return false
            if (persistent != null && EntityStateBridge.isPersistent(entity) != persistent) return false
            if (noDespawn != null && (entity is LivingEntity && !entity.removeWhenFarAway) != noDespawn) return false
            if (inRaid != null && EntityStateBridge.isInRaid(entity) != inRaid) return false
            if (playerOwnedPokemon != null && pokemonOwned != playerOwnedPokemon) return false
            if (hasPassengers != null && EntityStateBridge.hasPassengers(entity) != hasPassengers) return false
            if (hasVehicle != null && EntityStateBridge.hasVehicle(entity) != hasVehicle) return false
            return true
        }
    }

    fun firstMatch(rules: List<Rule>, action: Action, facts: Facts): Match? {
        for (rule in rules) {
            if (rule.action == action && rule.matches(facts)) {
                return Match(rule.name, rule.action, rule.bypasses)
            }
        }
        return null
    }

    fun load(section: ConfigurationSection?, logger: (String) -> Unit): List<Rule> {
        if (section == null) return emptyList()
        val rules = ArrayList<Rule>()
        for (key in section.getKeys(false)) {
            val path = "targets.entities.detail-rules.$key"
            val ruleSection = section.getConfigurationSection(key) ?: continue
            val action = when (ruleSection.getString("action")?.trim()?.lowercase(Locale.ROOT)) {
                "clean", "remove", "清理", "删除" -> Action.CLEAN
                else -> Action.KEEP
            }
            val matchAll = when (ruleSection.getString("match")?.trim()?.lowercase(Locale.ROOT)) {
                "any", "任意", "任一" -> false
                else -> true
            }
            val idEntries = entries(ruleSection, "ids")
            val idMatcher = loadMatcher(ruleSection, "ids", "$path.ids", logger)
            val nameColorMode = ItemText.parseColorMode(ruleSection.getString("names.color-mode"))
            val nameMatcher = loadTextMatcher(ruleSection, "names", "$path.names", nameColorMode, logger)
            val tagMatcher = loadMatcher(ruleSection, "scoreboard-tags", "$path.scoreboard-tags", logger)
            val states = loadStates(ruleSection.getConfigurationSection("states"))
            if (idMatcher == null && nameMatcher == null && tagMatcher == null && states == null) {
                logger("Cyuclear 跳过了没有匹配条件的实体深度规则 $path")
                continue
            }
            val pokemonIdRule = idEntries.any { it.trim().removePrefix("通配:").removePrefix("正则:").startsWith("pokemon:", true) }
            val pokemonStateRule = states?.playerOwnedPokemon != null
            val idMatchMode = parseMatchMode(ruleSection.getConfigurationSection("ids")?.getString("match-mode"))
            val usesMythic = TargetRuleCapabilities.hasMythicRules(listOf(idEntries to idMatchMode))
            rules += Rule(key, action, matchAll, idMatcher, nameMatcher, nameColorMode, tagMatcher, states,
                parseBypasses(ruleSection.getStringList("bypass-protections"), path, logger),
                pokemonIdRule || pokemonStateRule, pokemonIdRule, usesMythic)
        }
        return rules
    }

    private fun entries(section: ConfigurationSection, key: String): List<String> {
        val nested = section.getConfigurationSection(key)
        return if (nested != null) nested.getStringList("list") else section.getStringList(key)
    }

    private fun loadMatcher(section: ConfigurationSection, key: String, path: String, logger: (String) -> Unit): IdMatcher? {
        val nested = section.getConfigurationSection(key)
        val entries = if (nested != null) nested.getStringList("list") else section.getStringList(key)
        if (entries.isEmpty()) return null
        return IdMatcher.compile(parseMatchMode(nested?.getString("match-mode")), entries, path, logger)
    }

    private fun loadTextMatcher(
        section: ConfigurationSection,
        key: String,
        path: String,
        colorMode: ItemText.ColorMode,
        logger: (String) -> Unit
    ): IdMatcher? {
        val nested = section.getConfigurationSection(key)
        val entries = if (nested != null) nested.getStringList("list") else section.getStringList(key)
        val prepared = ItemText.prepareRuleEntries(entries, colorMode)
        if (prepared.isEmpty()) return null
        return IdMatcher.compile(parseMatchMode(nested?.getString("match-mode")), prepared, path, logger)
    }

    fun loadStates(section: ConfigurationSection?): StateConditions? {
        if (section == null) return null
        val states = StateConditions(
            optionalBoolean(section, "named"),
            optionalBoolean(section, "tamed"),
            optionalBoolean(section, "persistent"),
            optionalBoolean(section, "no-despawn"),
            optionalBoolean(section, "in-raid"),
            optionalBoolean(section, "player-owned-pokemon"),
            optionalBoolean(section, "has-passengers"),
            optionalBoolean(section, "has-vehicle")
        )
        return states.takeIf {
            it.named != null || it.tamed != null || it.persistent != null || it.noDespawn != null ||
                it.inRaid != null || it.playerOwnedPokemon != null || it.hasPassengers != null || it.hasVehicle != null
        }
    }

    private fun optionalBoolean(section: ConfigurationSection, path: String): Boolean? =
        if (section.contains(path)) section.getBoolean(path) else null

    fun parseBypasses(values: List<String>, path: String, logger: (String) -> Unit): Set<Protection> {
        val result = LinkedHashSet<Protection>()
        for (raw in values) {
            val protection = when (raw.trim().lowercase(Locale.ROOT)) {
                "named", "命名" -> Protection.NAMED
                "tamed", "驯服" -> Protection.TAMED
                "persistent", "持久" -> Protection.PERSISTENT
                "no-despawn", "不自然消失" -> Protection.NO_DESPAWN
                "raid", "raid-event", "袭击" -> Protection.RAID
                "player-owned-pokemon", "玩家宝可梦" -> Protection.PLAYER_OWNED_POKEMON
                else -> null
            }
            if (protection == null) logger("Cyuclear 跳过了未知实体保护名 $path.bypass-protections -> ''$raw''")
            else result += protection
        }
        return result
    }

    private fun parseMatchMode(raw: String?): Settings.MatchMode = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "通配", "wildcard", "glob" -> Settings.MatchMode.WILDCARD
        "正则", "regex", "regexp", "re" -> Settings.MatchMode.REGEX
        else -> Settings.MatchMode.EXACT
    }
}

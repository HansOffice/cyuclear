package org.cyuCBMclean.cyuclear.config

internal object TargetRuleCapabilities {

    fun hasPokemonRules(ruleGroups: List<Pair<List<String>, Settings.MatchMode>>): Boolean {
        return ruleGroups.any { (entries, _) -> entries.any(::isPokemonRule) }
    }

    fun hasMythicRules(ruleGroups: List<Pair<List<String>, Settings.MatchMode>>): Boolean {
        return ruleGroups.any { (entries, matchMode) ->
            entries.isNotEmpty() && (matchMode != Settings.MatchMode.EXACT || entries.any(::isMythicRule))
        }
    }

    fun requiresFullPokemonRules(ruleGroups: List<Pair<List<String>, Settings.MatchMode>>): Boolean {
        return ruleGroups.any { (entries, matchMode) ->
            if (matchMode != Settings.MatchMode.EXACT) {
                entries.any(::isPokemonRule)
            } else {
                entries.any { entry ->
                    val value = entry.trim().lowercase()
                    isDirectPokemonRule(value) && lightRuleKey(value) == null
                }
            }
        }
    }

    fun collectLightPokemonRules(ruleGroups: List<Pair<List<String>, Settings.MatchMode>>): Set<String> {
        val rules = LinkedHashSet<String>()
        for ((entries, matchMode) in ruleGroups) {
            if (matchMode != Settings.MatchMode.EXACT) continue
            for (entry in entries) {
                val key = lightRuleKey(entry.trim().lowercase()) ?: continue
                rules += key
            }
        }
        return rules
    }

    private fun isPokemonRule(raw: String): Boolean {
        val value = raw.trim().lowercase()
        return value.startsWith("pokemon:") ||
            value.startsWith("cobblemon:") ||
            value.startsWith("pixelmon:") ||
            value.startsWith("^pokemon:") ||
            value.contains("pokemon:") ||
            value.contains("cobblemon:") ||
            value.contains("pixelmon:")
    }

    private fun isMythicRule(raw: String): Boolean = raw.trim().lowercase().contains("mythic:")

    private fun isDirectPokemonRule(value: String): Boolean {
        return value.startsWith("pokemon:") || value.startsWith("cobblemon:") || value.startsWith("pixelmon:")
    }

    private fun lightRuleKey(value: String): String? {
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

        return normalizeTag(tag).takeIf { it in lightPokemonTags }
    }

    private fun normalizeTag(tag: String): String {
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
        "ultrabeast",
        "paradox",
        "mega",
        "gigantamax",
        "gmax",
        "boss"
    )
}

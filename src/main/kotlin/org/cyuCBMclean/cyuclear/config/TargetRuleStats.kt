package org.cyuCBMclean.cyuclear.config

import org.bukkit.configuration.ConfigurationSection

object TargetRuleStats {

    data class ListCounts(
        val itemKeep: Int,
        val itemClean: Int,
        val nameKeep: Int,
        val nameClean: Int,
        val loreKeep: Int,
        val loreClean: Int,
        val entityKeep: Int,
        val entityClean: Int
    )

    fun listCounts(): ListCounts {
        val rules = ConfigFiles.rules()
        return ListCounts(
            itemKeep = count(rules, "targets.items.keep-list.list"),
            itemClean = count(rules, "targets.items.clean-list.list"),
            nameKeep = count(rules, "targets.items.name-rules.keep-list.list"),
            nameClean = count(rules, "targets.items.name-rules.clean-list.list"),
            loreKeep = count(rules, "targets.items.lore-rules.keep-list.list"),
            loreClean = count(rules, "targets.items.lore-rules.clean-list.list"),
            entityKeep = count(rules, "targets.entities.keep-list.list"),
            entityClean = count(rules, "targets.entities.clean-list.list")
        )
    }

    private fun count(rules: ConfigurationSection, path: String): Int {
        return rules.getStringList(path).size
    }
}

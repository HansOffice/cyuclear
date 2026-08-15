package org.cyuCBMclean.cyuclear.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cyuCBMclean.cyuclear.util.IdMatcher
import org.cyuCBMclean.cyuclear.util.ItemText

internal class TargetRuleLoader(
    private val config: YamlConfiguration,
    private val warning: (String) -> Unit
) {

    data class TargetRules(
        val defaultAction: Settings.DefaultAction,
        val protectMatcher: IdMatcher,
        val cleanMatcher: IdMatcher,
        val protectMatchMode: Settings.MatchMode,
        val cleanMatchMode: Settings.MatchMode,
        val rawEntries: List<String>,
        val ruleGroups: List<Pair<List<String>, Settings.MatchMode>>
    )

    data class LegacyTargetRules(
        val defaultAction: Settings.DefaultAction,
        val protectMatcher: IdMatcher,
        val cleanMatcher: IdMatcher,
        val filterMode: Settings.FilterMode,
        val matchMode: Settings.MatchMode,
        val legacyMatcher: IdMatcher,
        val rawEntries: List<String>,
        val ruleGroups: List<Pair<List<String>, Settings.MatchMode>>
    )

    fun loadItemTextRules(basePath: String, requireCustomNameDefault: Boolean): ItemText.RuleSet {
        val section = config.getConfigurationSection(basePath)
        if (section != null) {
            return ItemText.loadRuleSet(
                section = section,
                groupPrefix = basePath,
                requireCustomNameDefault = requireCustomNameDefault,
                logger = warning
            )
        }

        val enabled = config.getBoolean("$basePath.enabled", true)
        val colorMode = ItemText.parseColorMode(config.getString("$basePath.color-mode"))
        val requireCustomName = config.getBoolean("$basePath.require-custom-name", requireCustomNameDefault)
        val lineMode = ItemText.parseLineMode(config.getString("$basePath.line-mode"))
        val forceClean = config.getBoolean("$basePath.force-clean", false)
        val protectModePath = firstExistingPath("$basePath.keep-list.match-mode", "$basePath.keep-list.match")
        val cleanModePath = firstExistingPath("$basePath.clean-list.match-mode", "$basePath.clean-list.match")
        val protectListPath = firstExistingPath("$basePath.keep-list.list", "$basePath.keep-list")
        val cleanListPath = firstExistingPath("$basePath.clean-list.list", "$basePath.clean-list")
        val protectMode = parseMatchMode(config.getString(protectModePath), protectModePath)
        val cleanMode = parseMatchMode(config.getString(cleanModePath), cleanModePath)
        val protectEntries = if (config.isList(protectListPath)) config.getStringList(protectListPath) else emptyList()
        val cleanEntries = if (config.isList(cleanListPath)) config.getStringList(cleanListPath) else emptyList()
        return ItemText.compileRuleSet(
            enabled = enabled,
            colorMode = colorMode,
            requireCustomName = requireCustomName,
            lineMode = lineMode,
            forceClean = forceClean,
            protectMode = protectMode,
            protectEntries = protectEntries,
            cleanMode = cleanMode,
            cleanEntries = cleanEntries,
            groupPrefix = basePath,
            logger = warning
        )
    }

    fun loadTargetRules(basePath: String, fallbackAction: Settings.DefaultAction): TargetRules {
        val actionPath = firstExistingPath("$basePath.mode", "$basePath.default-action")
        val defaultAction = parseDefaultAction(
            raw = config.getString(actionPath),
            path = actionPath,
            fallback = fallbackAction
        )
        val protectMatchModePath = firstExistingPath(
            "$basePath.keep-list.match-mode",
            "$basePath.keep-list.match",
            "$basePath.protect.match-mode"
        )
        val cleanMatchModePath = firstExistingPath(
            "$basePath.clean-list.match-mode",
            "$basePath.clean-list.match",
            "$basePath.clean.match-mode"
        )
        val protectListPath = firstExistingPath("$basePath.keep-list.list", "$basePath.protect.list")
        val cleanListPath = firstExistingPath("$basePath.clean-list.list", "$basePath.clean.list")
        val protectMatchMode = parseMatchMode(config.getString(protectMatchModePath), protectMatchModePath)
        val cleanMatchMode = parseMatchMode(config.getString(cleanMatchModePath), cleanMatchModePath)
        val protectEntries = config.getStringList(protectListPath)
        val cleanEntries = config.getStringList(cleanListPath)

        return TargetRules(
            defaultAction = defaultAction,
            protectMatcher = IdMatcher.compile(
                mode = protectMatchMode,
                rawEntries = protectEntries,
                groupName = protectListPath,
                logger = warning
            ),
            cleanMatcher = IdMatcher.compile(
                mode = cleanMatchMode,
                rawEntries = cleanEntries,
                groupName = cleanListPath,
                logger = warning
            ),
            protectMatchMode = protectMatchMode,
            cleanMatchMode = cleanMatchMode,
            rawEntries = protectEntries + cleanEntries,
            ruleGroups = IdMatcher.effectiveRuleGroups(protectMatchMode, protectEntries) +
                IdMatcher.effectiveRuleGroups(cleanMatchMode, cleanEntries)
        )
    }

    fun loadLegacyTargetRules(
        filterModePath: String,
        matchModePath: String,
        listPath: String,
        fallbackFilterMode: Settings.FilterMode = Settings.FilterMode.BLACKLIST
    ): LegacyTargetRules {
        val filterMode = parseFilterMode(
            raw = config.getString(filterModePath),
            path = filterModePath,
            fallback = fallbackFilterMode
        )
        val matchMode = parseMatchMode(config.getString(matchModePath), matchModePath)
        val entries = config.getStringList(listPath)
        val matcher = IdMatcher.compile(
            mode = matchMode,
            rawEntries = entries,
            groupName = listPath,
            logger = warning
        )

        return if (filterMode == Settings.FilterMode.BLACKLIST) {
            LegacyTargetRules(
                defaultAction = Settings.DefaultAction.CLEAN,
                protectMatcher = matcher,
                cleanMatcher = IdMatcher.empty(),
                filterMode = filterMode,
                matchMode = matchMode,
                legacyMatcher = matcher,
                rawEntries = entries,
                ruleGroups = IdMatcher.effectiveRuleGroups(matchMode, entries)
            )
        } else {
            LegacyTargetRules(
                defaultAction = Settings.DefaultAction.KEEP,
                protectMatcher = IdMatcher.empty(),
                cleanMatcher = matcher,
                filterMode = filterMode,
                matchMode = matchMode,
                legacyMatcher = matcher,
                rawEntries = entries,
                ruleGroups = IdMatcher.effectiveRuleGroups(matchMode, entries)
            )
        }
    }

    fun displayListMode(raw: String?, action: Settings.DefaultAction): String {
        return when (normalize(raw, "")) {
            "黑名单", "单黑名单", "clean", "清理", "默认清理" -> "黑名单"
            "白名单", "单白名单", "keep", "保留", "默认保留" -> "白名单"
            "并行名单", "并行", "parallel" -> "并行名单"
            else -> if (action == Settings.DefaultAction.CLEAN) "黑名单" else "白名单"
        }
    }

    private fun parseDefaultAction(
        raw: String?,
        path: String,
        fallback: Settings.DefaultAction
    ): Settings.DefaultAction {
        val fallbackName = fallback.name.lowercase()
        return when (normalize(raw, fallbackName)) {
            "clean", "清理", "拦截", "黑名单", "单黑名单", "默认清理" -> Settings.DefaultAction.CLEAN
            "keep", "保留", "放行", "白名单", "单白名单", "默认保留", "并行名单", "并行", "parallel" -> Settings.DefaultAction.KEEP
            else -> {
                warning("Cyuclear 在 $path 读取到未知值 '$raw'，已回退为 $fallbackName")
                fallback
            }
        }
    }

    private fun parseFilterMode(
        raw: String?,
        path: String,
        fallback: Settings.FilterMode
    ): Settings.FilterMode {
        val fallbackName = fallback.name.lowercase()
        return when (normalize(raw, fallbackName)) {
            "blacklist" -> Settings.FilterMode.BLACKLIST
            "whitelist" -> Settings.FilterMode.WHITELIST
            else -> {
                warning("Cyuclear 在 $path 读取到未知值 '$raw'，已回退为 $fallbackName")
                fallback
            }
        }
    }

    private fun parseMatchMode(raw: String?, path: String): Settings.MatchMode {
        return when (normalize(raw, "exact")) {
            "exact", "精确" -> Settings.MatchMode.EXACT
            "wildcard", "通配" -> Settings.MatchMode.WILDCARD
            "regex", "正则" -> Settings.MatchMode.REGEX
            else -> {
                warning("Cyuclear 在 $path 读取到未知值 '$raw'，已回退为 exact")
                Settings.MatchMode.EXACT
            }
        }
    }

    private fun firstExistingPath(vararg paths: String): String {
        return paths.firstOrNull(config::contains) ?: paths.first()
    }

    private fun normalize(raw: String?, fallback: String): String {
        val value = raw?.trim()?.lowercase()
        return if (value.isNullOrEmpty()) fallback else value
    }
}

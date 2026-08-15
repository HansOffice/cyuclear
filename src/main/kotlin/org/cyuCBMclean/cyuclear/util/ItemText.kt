package org.cyuCBMclean.cyuclear.util

import org.bukkit.ChatColor
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.config.Settings.MatchMode
import java.util.Locale

object ItemText {

    enum class ColorMode {
        STRIP,
        KEEP
    }

    enum class LineMode {
        ANY,
        ALL
    }

    data class RuleSet(
        val enabled: Boolean,
        val colorMode: ColorMode,
        val requireCustomName: Boolean,
        val lineMode: LineMode,
        val forceClean: Boolean,
        val protectAny: IdMatcher,
        val protectAll: List<IdMatcher>,
        val cleanAny: IdMatcher,
        val cleanAll: List<IdMatcher>
    ) {
        val hasRules: Boolean
            get() = enabled && (canMatch(protectAny, protectAll) || canMatch(cleanAny, cleanAll))

        fun matchesProtect(texts: List<String>): Boolean = matches(texts, protectAny, protectAll)

        fun matchesClean(texts: List<String>): Boolean = matches(texts, cleanAny, cleanAll)

        fun canProtect(texts: List<String>, hasCustomName: Boolean): Boolean {
            if (!enabled) return false
            if (requireCustomName && !hasCustomName) return false
            return matchesProtect(texts)
        }

        fun canClean(texts: List<String>, hasCustomName: Boolean): Boolean {
            if (!enabled) return false
            if (requireCustomName && !hasCustomName) return false
            return matchesClean(texts)
        }

        private fun matches(texts: List<String>, anyMatcher: IdMatcher, allMatchers: List<IdMatcher>): Boolean {
            if (texts.isEmpty()) return false
            if (lineMode == LineMode.ANY) {
                for (text in texts) {
                    if (anyMatcher.matches(text)) return true
                }
                return false
            }
            if (allMatchers.isEmpty()) return false
            for (matcher in allMatchers) {
                var matched = false
                for (text in texts) {
                    if (matcher.matches(text)) {
                        matched = true
                        break
                    }
                }
                if (!matched) return false
            }
            return true
        }

        private fun canMatch(anyMatcher: IdMatcher, allMatchers: List<IdMatcher>): Boolean {
            return when (lineMode) {
                LineMode.ANY -> !anyMatcher.isEmpty()
                LineMode.ALL -> allMatchers.isNotEmpty() && allMatchers.all { !it.isEmpty() }
            }
        }
    }

    fun emptyRules(): RuleSet = RuleSet(
        enabled = false,
        colorMode = ColorMode.STRIP,
        requireCustomName = true,
        lineMode = LineMode.ANY,
        forceClean = false,
        protectAny = IdMatcher.empty(),
        protectAll = emptyList(),
        cleanAny = IdMatcher.empty(),
        cleanAll = emptyList()
    )

    fun parseColorMode(raw: String?): ColorMode {
        val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (value) {
            "keep", "保留", "保留颜色", "colored", "color" -> ColorMode.KEEP
            else -> ColorMode.STRIP
        }
    }

    fun parseLineMode(raw: String?): LineMode {
        val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (value) {
            "all", "全部", "全部命中", "every" -> LineMode.ALL
            else -> LineMode.ANY
        }
    }

    fun displayName(stack: ItemStack): String? {
        if (!stack.hasItemMeta()) return null
        val meta = stack.itemMeta ?: return null
        if (!meta.hasDisplayName()) return null
        val name = meta.displayName
        return name.takeIf { it.isNotEmpty() }
    }

    fun loreLines(stack: ItemStack): List<String> {
        if (!stack.hasItemMeta()) return emptyList()
        val meta = stack.itemMeta ?: return emptyList()
        if (!meta.hasLore()) return emptyList()
        return meta.lore?.filterNotNull().orEmpty()
    }

    fun normalize(raw: String?, mode: ColorMode): String {
        if (raw.isNullOrEmpty()) return ""
        return when (mode) {
            ColorMode.STRIP -> ChatColor.stripColor(raw)?.trim().orEmpty()
            ColorMode.KEEP -> raw.trim()
        }
    }

    fun prepareRuleEntries(rawEntries: List<String>, mode: ColorMode): List<String> {
        return rawEntries.mapNotNull { entry ->
            val prepared = prepareRuleEntry(entry, mode)
            prepared.takeIf { it.isNotEmpty() }
        }
    }

    fun prepareRuleEntry(rawEntry: String, mode: ColorMode): String {
        val entry = rawEntry.trim()
        if (entry.isEmpty()) return ""

        val colonIndex = entry.indexOfFirst { it == ':' || it == '：' }
        if (colonIndex > 0) {
            val prefix = entry.substring(0, colonIndex).trim().lowercase(Locale.ROOT)
            val isModePrefix = when (prefix) {
                "exact", "精准", "精确", "wildcard", "glob", "通配", "regex", "regexp", "re", "正则" -> true
                else -> false
            }
            if (isModePrefix) {
                val value = entry.substring(colonIndex + 1).trim()
                if (value.isEmpty()) return ""
                return "$prefix:${prepareValue(value, mode)}"
            }
        }

        return prepareValue(entry, mode)
    }

    fun compileRuleSet(
        enabled: Boolean,
        colorMode: ColorMode,
        requireCustomName: Boolean,
        lineMode: LineMode,
        forceClean: Boolean,
        protectMode: MatchMode,
        protectEntries: List<String>,
        cleanMode: MatchMode,
        cleanEntries: List<String>,
        groupPrefix: String,
        logger: (String) -> Unit
    ): RuleSet {
        val preparedProtect = prepareRuleEntries(protectEntries, colorMode)
        val preparedClean = prepareRuleEntries(cleanEntries, colorMode)
        return RuleSet(
            enabled = enabled,
            colorMode = colorMode,
            requireCustomName = requireCustomName,
            lineMode = lineMode,
            forceClean = forceClean,
            protectAny = IdMatcher.compile(protectMode, preparedProtect, "$groupPrefix.keep-list.list", logger),
            protectAll = preparedProtect.mapIndexed { index, entry ->
                IdMatcher.compile(protectMode, listOf(entry), "$groupPrefix.keep-list.list[$index]", logger)
            },
            cleanAny = IdMatcher.compile(cleanMode, preparedClean, "$groupPrefix.clean-list.list", logger),
            cleanAll = preparedClean.mapIndexed { index, entry ->
                IdMatcher.compile(cleanMode, listOf(entry), "$groupPrefix.clean-list.list[$index]", logger)
            }
        )
    }

    fun loadRuleSet(
        section: ConfigurationSection?,
        groupPrefix: String,
        requireCustomNameDefault: Boolean,
        logger: (String) -> Unit
    ): RuleSet {
        if (section == null) return emptyRules()
        val enabled = section.getBoolean("enabled", true)
        val colorMode = parseColorMode(section.getString("color-mode"))
        val requireCustomName = section.getBoolean("require-custom-name", requireCustomNameDefault)
        val lineMode = parseLineMode(section.getString("line-mode"))
        val forceClean = section.getBoolean("force-clean", false)
        val protectMode = parseMatchMode(section.getString("keep-list.match-mode") ?: section.getString("keep-list.match"))
        val cleanMode = parseMatchMode(section.getString("clean-list.match-mode") ?: section.getString("clean-list.match"))
        val protectEntries = readList(section, "keep-list")
        val cleanEntries = readList(section, "clean-list")
        return compileRuleSet(
            enabled = enabled,
            colorMode = colorMode,
            requireCustomName = requireCustomName,
            lineMode = lineMode,
            forceClean = forceClean,
            protectMode = protectMode,
            protectEntries = protectEntries,
            cleanMode = cleanMode,
            cleanEntries = cleanEntries,
            groupPrefix = groupPrefix,
            logger = logger
        )
    }

    fun cacheKey(name: String?, lore: List<String>): String {
        if (name.isNullOrEmpty() && lore.isEmpty()) return ""
        val builder = StringBuilder((name?.length ?: 0) + lore.sumOf { it.length + 1 } + 4)
        if (!name.isNullOrEmpty()) {
            builder.append(name)
        }
        builder.append('\u0001')
        lore.forEachIndexed { index, line ->
            if (index > 0) builder.append('\u0002')
            builder.append(line)
        }
        return builder.toString()
    }

    private fun readList(section: ConfigurationSection, path: String): List<String> {
        val nested = section.getConfigurationSection(path)
        return if (nested != null) nested.getStringList("list") else section.getStringList(path)
    }

    private fun parseMatchMode(raw: String?): MatchMode {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "通配", "wildcard", "glob" -> MatchMode.WILDCARD
            "正则", "regex", "regexp", "re" -> MatchMode.REGEX
            else -> MatchMode.EXACT
        }
    }

    private fun prepareValue(value: String, mode: ColorMode): String {
        val colored = ColorUtils.color(value)
        return when (mode) {
            ColorMode.STRIP -> ChatColor.stripColor(colored)?.trim().orEmpty()
            ColorMode.KEEP -> colored.trim()
        }
    }
}

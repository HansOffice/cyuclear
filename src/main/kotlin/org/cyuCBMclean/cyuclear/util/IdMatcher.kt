package org.cyuCBMclean.cyuclear.util

import org.cyuCBMclean.cyuclear.config.Settings.MatchMode
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class IdMatcher private constructor(
    private val exactValues: Set<String>,
    private val patterns: List<Pattern>
) {
    private val hasExact: Boolean = exactValues.isNotEmpty()
    private val hasPatterns: Boolean = patterns.isNotEmpty()
    private val patternArray: Array<Pattern> = patterns.toTypedArray()

    fun matches(input: String): Boolean {
        val normalized = input.trim().lowercase()
        return matchesNormalized(normalized)
    }

    fun matchesNormalized(input: String): Boolean {
        if (input.isEmpty()) return false
        if (hasExact && exactValues.contains(input)) return true
        if (!hasPatterns) return false

        for (i in patternArray.indices) {
            if (patternArray[i].matcher(input).matches()) {
                return true
            }
        }

        return false
    }

    fun matchesAnyNormalized(values: Iterable<String>): Boolean {
        if (isEmpty()) return false

        // Fast-path 1: 常规精准名称直接进行 O(1) 哈希查询，无需触发任何正则或通配计算
        if (hasExact) {
            for (value in values) {
                if (value.isNotEmpty() && exactValues.contains(value)) return true
            }
        }

        // Fast-path 2: 若未配置正则/通配规则，直接快速短路返回
        if (!hasPatterns) return false

        for (value in values) {
            if (value.isEmpty()) continue
            for (i in patternArray.indices) {
                if (patternArray[i].matcher(value).matches()) {
                    return true
                }
            }
        }
        return false
    }

    fun isEmpty(): Boolean = !hasExact && !hasPatterns

    companion object {
        private val EMPTY = IdMatcher(emptySet(), emptyList())

        fun empty(): IdMatcher = EMPTY

        fun effectiveRuleGroups(mode: MatchMode, rawEntries: List<String>): List<Pair<List<String>, MatchMode>> {
            val grouped = linkedMapOf(
                MatchMode.EXACT to mutableListOf<String>(),
                MatchMode.WILDCARD to mutableListOf<String>(),
                MatchMode.REGEX to mutableListOf<String>()
            )

            for (rawEntry in rawEntries) {
                val parsed = parseEntry(mode, rawEntry) ?: continue
                grouped.getValue(parsed.mode).add(parsed.value)
            }

            return grouped
                .mapNotNull { (matchMode, entries) -> entries.takeIf { it.isNotEmpty() }?.let { it to matchMode } }
        }

        fun compile(
            mode: MatchMode,
            rawEntries: List<String>,
            groupName: String,
            logger: (String) -> Unit
        ): IdMatcher {
            val entries = rawEntries
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()

            if (entries.isEmpty()) {
                return EMPTY
            }

            val exacts = LinkedHashSet<String>()
            val patterns = ArrayList<Pattern>()

            for (entry in entries) {
                val parsed = parseEntry(mode, entry)
                if (parsed == null) {
                    if (org.cyuCBMclean.cyuclear.config.Language.isEnglish) {
                        logger("CyuClear skipped empty match entry $groupName -> '$entry'")
                    } else {
                        logger("Cyuclear 跳过了空的混合匹配规则 $groupName -> '$entry'")
                    }
                    continue
                }

                when (parsed.mode) {
                    MatchMode.EXACT -> exacts.add(parsed.value.lowercase())
                    MatchMode.WILDCARD -> compileWildcard(groupName, parsed.value, logger)?.let(patterns::add)
                    MatchMode.REGEX -> compileRegex(groupName, parsed.value, logger)?.let(patterns::add)
                }
            }

            if (exacts.isEmpty() && patterns.isEmpty()) {
                return EMPTY
            }

            return IdMatcher(exactValues = exacts, patterns = patterns)
        }

        private data class ParsedEntry(
            val mode: MatchMode,
            val value: String
        )

        private fun parseEntry(defaultMode: MatchMode, rawEntry: String): ParsedEntry? {
            val entry = rawEntry.trim()
            if (entry.isEmpty()) return null

            val colonIndex = entry.indexOfFirst { it == ':' || it == '：' }
            if (colonIndex <= 0) {
                return ParsedEntry(defaultMode, entry)
            }

            val prefix = entry.substring(0, colonIndex).trim().lowercase()
            val mode = when (prefix) {
                "exact", "精准", "精确" -> MatchMode.EXACT
                "wildcard", "glob", "通配" -> MatchMode.WILDCARD
                "regex", "regexp", "re", "正则" -> MatchMode.REGEX
                else -> return ParsedEntry(defaultMode, entry)
            }

            val value = entry.substring(colonIndex + 1).trim()
            if (value.isEmpty()) return null
            return ParsedEntry(mode, value)
        }

        private fun compileWildcard(
            groupName: String,
            entry: String,
            logger: (String) -> Unit
        ): Pattern? {
            return try {
                Pattern.compile(wildcardToRegex(entry.lowercase()), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (ex: PatternSyntaxException) {
                if (org.cyuCBMclean.cyuclear.config.Language.isEnglish) {
                    logger("CyuClear skipped invalid wildcard rule $groupName -> '$entry': ${ex.description}")
                } else {
                    logger("Cyuclear 跳过了无效通配规则 $groupName -> '$entry'：${ex.description}")
                }
                null
            }
        }

        private fun compileRegex(
            groupName: String,
            entry: String,
            logger: (String) -> Unit
        ): Pattern? {
            return try {
                Pattern.compile(entry, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (ex: PatternSyntaxException) {
                if (org.cyuCBMclean.cyuclear.config.Language.isEnglish) {
                    logger("CyuClear skipped invalid regex rule $groupName -> '$entry': ${ex.description}")
                } else {
                    logger("Cyuclear 跳过了无效正则规则 $groupName -> '$entry'：${ex.description}")
                }
                null
            }
        }

        private fun wildcardToRegex(value: String): String {
            val builder = StringBuilder(value.length * 2 + 2)
            builder.append('^')

            for (char in value) {
                when (char) {
                    '*' -> builder.append(".*")
                    '?' -> builder.append('.')
                    '\\', '.', '(', ')', '[', ']', '{', '}', '^', '$', '|', '+' -> {
                        builder.append('\\').append(char)
                    }
                    else -> builder.append(char)
                }
            }

            builder.append('$')
            return builder.toString()
        }
    }
}

package org.cyuCBMclean.cyuclear.util

import org.cyuCBMclean.cyuclear.config.Settings.MatchMode
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class IdMatcher private constructor(
    private val exactValues: Set<String>,
    private val patterns: List<Pattern>
) {

    fun matches(input: String): Boolean {
        val normalized = input.trim().lowercase()
        return matchesNormalized(normalized)
    }

    fun matchesNormalized(input: String): Boolean {
        if (input.isEmpty()) return false
        if (exactValues.contains(input)) return true

        for (pattern in patterns) {
            if (pattern.matcher(input).matches()) {
                return true
            }
        }

        return false
    }

    fun matchesAnyNormalized(values: Iterable<String>): Boolean {
        for (value in values) {
            if (matchesNormalized(value)) return true
        }
        return false
    }

    fun isEmpty(): Boolean = exactValues.isEmpty() && patterns.isEmpty()

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
                    logger("Cyuclear 跳过了空的混合匹配规则 $groupName -> '$entry'")
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
                logger("Cyuclear 跳过了无效通配规则 $groupName -> '$entry'：${ex.description}")
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
                logger("Cyuclear 跳过了无效正则规则 $groupName -> '$entry'：${ex.description}")
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

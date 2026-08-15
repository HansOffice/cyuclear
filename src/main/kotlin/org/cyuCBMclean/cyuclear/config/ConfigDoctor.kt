package org.cyuCBMclean.cyuclear.config

import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.command.CommandSender
import org.cyuCBMclean.cyuclear.Cyuclear
import java.io.File
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object ConfigDoctor {
    enum class Level(val display: String) {
        ERROR("错误"),
        WARNING("注意"),
        OK("正常")
    }

    data class Finding(
        val level: Level,
        val file: String,
        val path: String,
        val message: String
    )

    data class Report(val findings: List<Finding>) {
        val errors: Int
            get() = findings.count { it.level == Level.ERROR }
        val warnings: Int
            get() = findings.count { it.level == Level.WARNING }
        val healthy: Boolean
            get() = errors == 0
    }

    private data class AreaBox(
        val name: String,
        val priority: Int,
        val world: String,
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int
    ) {
        fun overlaps(other: AreaBox): Boolean {
            return world == other.world && minX <= other.maxX && maxX >= other.minX &&
                minY <= other.maxY && maxY >= other.minY && minZ <= other.maxZ && maxZ >= other.minZ
        }
    }

    private val expectedFiles = listOf(
        "config.yml",
        "rules.yml",
        "areas.yml",
        "storage.yml",
        "void-bin.yml",
        "sounds.yml",
        "messages.yml",
        "menu/main.yml",
        "menu/target.yml",
        "menu/list.yml",
        "menu/bin.yml",
        "menu/deposit-buffer.yml",
        "menu/admin.yml",
        "menu/runs.yml",
        "menu/recovery.yml",
        "menu/hotspots.yml",
        "menu/hotspot-detail.yml"
    )

    private val nonButtonMenuSymbols = mapOf(
        "menu/hotspot-detail.yml" to setOf('I')
    )

    fun inspect(): Report {
        val dataFolder = Cyuclear.instance.dataFolder
        val findings = ArrayList<Finding>()
        for (relative in expectedFiles) {
            val file = File(dataFolder, relative)
            if (!file.exists()) {
                findings += Finding(Level.ERROR, relative, "", "文件不存在")
                continue
            }
            if (!file.isFile) {
                findings += Finding(Level.ERROR, relative, "", "不是有效文件")
            }
        }
        val config = load(dataFolder, "config.yml", findings)
        val rules = load(dataFolder, "rules.yml", findings)
        val areas = load(dataFolder, "areas.yml", findings)
        if (config != null) inspectConfig(config, findings)
        if (rules != null) inspectRules(rules, findings)
        if (areas != null) inspectAreas(areas, findings)
        inspectMenus(dataFolder, findings)
        if (findings.none { it.level == Level.ERROR || it.level == Level.WARNING }) {
            findings += Finding(Level.OK, "全部", "", "配置结构检查通过")
        }
        return Report(findings)
    }

    fun send(sender: CommandSender) {
        val report = inspect()
        sender.sendMessage(Language.getRaw("doctor-header"))
        report.findings.take(12).forEach { finding ->
            sender.sendMessage(
                Language.get(
                    "doctor-entry",
                    "level" to finding.level.display,
                    "file" to finding.file,
                    "path" to finding.path.ifBlank { "-" },
                    "message" to finding.message
                )
            )
        }
        if (report.findings.size > 12) sender.sendMessage(Language.get("doctor-more", "count" to (report.findings.size - 12).toString()))
        sender.sendMessage(Language.get("doctor-summary", "errors" to report.errors.toString(), "warnings" to report.warnings.toString()))
        sender.sendMessage(Language.getRaw("doctor-footer"))
    }

    private fun load(dataFolder: File, relative: String, findings: MutableList<Finding>): YamlConfiguration? {
        val file = File(dataFolder, relative)
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            YamlConfiguration.loadConfiguration(file).also { config ->
                if (config.getKeys(false).isEmpty()) error("文件为空或 YAML 格式无效")
            }
        }.getOrElse {
            findings += Finding(Level.ERROR, relative, "", "无法读取：${it.message}")
            null
        }
    }

    private fun inspectConfig(config: YamlConfiguration, findings: MutableList<Finding>) {
        if (config.getInt("config-version", 0) != ConfigUpgradeManager.CURRENT_CONFIG_VERSION) {
            findings += Finding(Level.WARNING, "config.yml", "config-version", "不是当前配置版本")
        }
        if (config.getInt("config-layout", 0) < ConfigUpgradeManager.CURRENT_CONFIG_LAYOUT) {
            findings += Finding(Level.WARNING, "config.yml", "config-layout", "建议重启一次，让插件完成配置整理")
        }
        if (config.getBoolean("recovery.enabled", false) && config.getInt("recovery.max-entries-per-run", 0) <= 0) {
            findings += Finding(Level.WARNING, "config.yml", "recovery.max-entries-per-run", "恢复中心已开启，但不会保存任何掉落物")
        }
        if (config.getLong("recovery.expire-hours", 72L) !in 1L..720L) {
            findings += Finding(Level.WARNING, "config.yml", "recovery.expire-hours", "建议填写 1 到 720 小时")
        }
        if (config.getInt("recovery.recent-limit", 50) !in 10..200) {
            findings += Finding(Level.WARNING, "config.yml", "recovery.recent-limit", "建议填写 10 到 200")
        }
        if (config.getString("performance.profile").orEmpty().trim() !in setOf("保守", "均衡", "快速", "极限", "safe", "balanced", "fast", "extreme")) {
            findings += Finding(Level.WARNING, "config.yml", "performance.profile", "未识别的性能档位会回退为快速")
        }
        if (config.getInt("performance.scan.max-chunks-per-tick", 240) !in 1..5000) {
            findings += Finding(Level.WARNING, "config.yml", "performance.scan.max-chunks-per-tick", "建议填写 1 到 5000")
        }
        if (config.getLong("performance.scan.max-millis-per-tick", 7L) !in 1L..50L) {
            findings += Finding(Level.WARNING, "config.yml", "performance.scan.max-millis-per-tick", "建议填写 1 到 50")
        }
    }

    private fun inspectRules(rules: YamlConfiguration, findings: MutableList<Finding>) {
        inspectTarget(rules, "targets.items", "rules.yml", findings)
        inspectTarget(rules, "targets.entities", "rules.yml", findings)
        inspectTarget(rules, "limits.realtime", "rules.yml", findings)
        inspectTextRules(rules, "targets.items.name-rules", "rules.yml", findings)
        inspectTextRules(rules, "targets.items.lore-rules", "rules.yml", findings)
        inspectTextRules(rules, "targets.entities.name-rules", "rules.yml", findings)
        inspectAdvancedRules(rules, findings)
        inspectPanic(rules, findings)
        inspectHookAvailability(rules, findings)
        if (rules.getLong("limits.chunk.hotspot.retention-seconds", 300L) !in 30L..3600L) {
            findings += Finding(Level.WARNING, "rules.yml", "limits.chunk.hotspot.retention-seconds", "建议填写 30 到 3600 秒")
        }
        if (rules.getInt("limits.chunk.hotspot.max-records", 80) !in 10..512) {
            findings += Finding(Level.WARNING, "rules.yml", "limits.chunk.hotspot.max-records", "建议填写 10 到 512")
        }
        if (rules.isConfigurationSection("modules")) {
            findings += Finding(Level.WARNING, "rules.yml", "modules", "发现旧版规则区，升级后可删除")
        }
        if (rules.isConfigurationSection("panic-mode")) {
            findings += Finding(Level.WARNING, "rules.yml", "panic-mode", "发现旧版紧急清理区，升级后可删除")
        }
    }

    private fun inspectAdvancedRules(config: YamlConfiguration, findings: MutableList<Finding>) {
        val rules = config.getConfigurationSection("rules") ?: return
        val priorityGroups = LinkedHashMap<String, MutableList<String>>()
        for (name in rules.getKeys(false)) {
            val rule = rules.getConfigurationSection(name)
            if (rule == null) {
                findings += Finding(Level.ERROR, "rules.yml", "rules.$name", "规则必须是一个配置区")
                continue
            }
            if (!rule.getBoolean("enabled", true)) continue
            val path = "rules.$name"
            val target = rule.getString("target").orEmpty().trim().lowercase(Locale.ROOT)
            if (target !in setOf("item", "items", "物品", "掉落物", "entity", "entities", "实体")) {
                findings += Finding(Level.ERROR, "rules.yml", "$path.target", "目标只能是物品或实体")
            }
            val action = rule.getString("action").orEmpty().trim().lowercase(Locale.ROOT)
            if (action !in setOf("keep", "保留", "clean", "remove", "清理", "删除")) {
                findings += Finding(Level.ERROR, "rules.yml", "$path.action", "动作只能是保留或清理")
            }
            if (rule.getInt("priority", 0) !in -10_000..10_000) {
                findings += Finding(Level.WARNING, "rules.yml", "$path.priority", "建议填写 -10000 到 10000")
            }
            val conditions = rule.getConfigurationSection("conditions") ?: rule
            val usedIds = inspectAdvancedMatcher(conditions, "ids", "$path.conditions.ids", findings)
            val usedNames = inspectAdvancedMatcher(conditions, "names", "$path.conditions.names", findings)
            val usedLore = inspectAdvancedMatcher(conditions, "lore", "$path.conditions.lore", findings)
            val usedWorlds = inspectAdvancedMatcher(conditions, "worlds", "$path.conditions.worlds", findings)
            val usedTags = inspectAdvancedMatcher(conditions, "scoreboard-tags", "$path.conditions.scoreboard-tags", findings)
            val usedHeight = inspectAdvancedRange(conditions.getConfigurationSection("height"), "height", "$path.conditions.height", findings)
            val usedAge = inspectAdvancedRange(conditions.getConfigurationSection("age"), "age", "$path.conditions.age", findings)
            val usedStates = conditions.getConfigurationSection("states")?.getKeys(false)?.isNotEmpty() == true
            if (!usedIds && !usedNames && !usedLore && !usedWorlds && !usedTags && !usedHeight && !usedAge && !usedStates) {
                findings += Finding(Level.ERROR, "rules.yml", "$path.conditions", "至少填写一项匹配条件")
            }
            val match = conditions.getString("match").orEmpty().trim().lowercase(Locale.ROOT)
            if (match.isNotEmpty() && match !in setOf("all", "any", "全部", "任意", "任一")) {
                findings += Finding(Level.WARNING, "rules.yml", "$path.conditions.match", "未识别的匹配方式会按全部处理")
            }
            val itemTarget = target in setOf("item", "items", "物品", "掉落物")
            val entityTarget = target in setOf("entity", "entities", "实体")
            if (itemTarget && (usedTags || usedStates)) {
                findings += Finding(Level.WARNING, "rules.yml", "$path.conditions", "物品规则不支持计分板标签和实体状态")
            }
            if (entityTarget && usedLore) {
                findings += Finding(Level.WARNING, "rules.yml", "$path.conditions.lore", "实体规则不支持 Lore")
            }
            if (itemTarget && rule.getStringList("bypass-protections").isNotEmpty()) {
                findings += Finding(Level.WARNING, "rules.yml", "$path.bypass-protections", "物品规则不会使用实体保护绕过")
            }
            val invalidBypasses = rule.getStringList("bypass-protections").filterNot(::isKnownBypass)
            if (invalidBypasses.isNotEmpty()) {
                findings += Finding(Level.WARNING, "rules.yml", "$path.bypass-protections", "有 ${invalidBypasses.size} 个未识别保护名")
            }
            if (itemTarget || entityTarget) {
                priorityGroups.getOrPut("$target:${rule.getInt("priority", 0)}") { ArrayList() }.add(name)
            }
        }
        for ((_, names) in priorityGroups) {
            if (names.size <= 1) continue
            findings += Finding(Level.WARNING, "rules.yml", "rules", "${names.joinToString("、")} 优先级相同，会按配置顺序处理")
        }
    }

    private fun inspectAdvancedMatcher(
        section: ConfigurationSection,
        key: String,
        path: String,
        findings: MutableList<Finding>
    ): Boolean {
        val nested = section.getConfigurationSection(key)
        val values = readAdvancedEntries(section, key)
        if (values.isEmpty()) return false
        val mode = (nested?.getString("match-mode") ?: nested?.getString("match")).orEmpty().trim().lowercase(Locale.ROOT)
        if (mode.isNotEmpty() && mode !in setOf("精确", "通配", "正则", "exact", "wildcard", "regex", "glob", "regexp", "re")) {
            findings += Finding(Level.WARNING, "rules.yml", "$path.match-mode", "未识别的匹配方式会回退为精确")
        }
        val duplicates = values.groupBy(::normalizedEntry).filter { (value, entries) -> value.isNotEmpty() && entries.size > 1 }.size
        if (duplicates > 0) {
            findings += Finding(Level.WARNING, "rules.yml", "$path.list", "有 $duplicates 条重复内容")
        }
        inspectRegexes(values, mode, "rules.yml", "$path.list", findings)
        return true
    }

    private fun inspectAdvancedRange(
        section: ConfigurationSection?,
        kind: String,
        path: String,
        findings: MutableList<Finding>
    ): Boolean {
        if (section == null) return false
        val minimumPath = if (kind == "age") "minimum-seconds" else "min"
        val maximumPath = if (kind == "age") "maximum-seconds" else "max"
        val minimum = when {
            section.contains(minimumPath) -> section.getLong(minimumPath)
            section.contains("min") -> section.getLong("min")
            else -> null
        }
        val maximum = when {
            section.contains(maximumPath) -> section.getLong(maximumPath)
            section.contains("max") -> section.getLong("max")
            else -> null
        }
        if (minimum == null && maximum == null) {
            findings += Finding(Level.ERROR, "rules.yml", path, "至少填写最小值或最大值")
            return true
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            findings += Finding(Level.ERROR, "rules.yml", path, "最小值不能大于最大值")
        }
        if (kind == "age" && (minimum ?: 0L) < 0L) {
            findings += Finding(Level.WARNING, "rules.yml", path, "存在时间不应小于 0")
        }
        return true
    }

    private fun readAdvancedEntries(section: ConfigurationSection, key: String): List<String> {
        val nested = section.getConfigurationSection(key)
        if (nested != null) return nested.getStringList("list")
        if (section.isList(key)) return section.getStringList(key)
        return section.getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()
    }

    private fun isKnownBypass(value: String): Boolean {
        return value.trim().lowercase(Locale.ROOT) in setOf(
            "named", "命名", "tamed", "驯服", "persistent", "持久", "no-despawn", "不自然消失",
            "raid", "raid-event", "袭击", "player-owned-pokemon", "玩家宝可梦"
        )
    }

    private fun inspectTarget(
        config: YamlConfiguration,
        base: String,
        file: String,
        findings: MutableList<Finding>
    ) {
        val section = config.getConfigurationSection(base) ?: run {
            findings += Finding(Level.ERROR, file, base, "缺少规则区")
            return
        }
        val mode = section.getString("mode").orEmpty().trim().lowercase(Locale.ROOT)
        if (mode !in setOf("黑名单", "白名单", "并行名单", "blacklist", "whitelist", "parallel")) {
            findings += Finding(Level.WARNING, file, "$base.mode", "未识别的名单模式会使用默认值")
        }
        inspectList(section, "keep-list", "$base.keep-list", file, findings)
        inspectList(section, "clean-list", "$base.clean-list", file, findings)
        val keep = section.getConfigurationSection("keep-list")?.getStringList("list").orEmpty()
        val clean = section.getConfigurationSection("clean-list")?.getStringList("list").orEmpty()
        val duplicates = keep.map(::normalizedEntry).toSet().intersect(clean.map(::normalizedEntry).toSet()).filter { it.isNotEmpty() }
        if (duplicates.isNotEmpty()) {
            findings += Finding(Level.WARNING, file, base, "保留与清理名单有 ${duplicates.size} 条相同内容")
        }
    }

    private fun inspectList(
        parent: ConfigurationSection,
        key: String,
        path: String,
        file: String,
        findings: MutableList<Finding>
    ) {
        val section = parent.getConfigurationSection(key) ?: return
        val values = section.getStringList("list")
        val duplicates = values.groupBy { it.trim().lowercase(Locale.ROOT) }
            .filter { (value, entries) -> value.isNotEmpty() && entries.size > 1 }
            .keys
        if (duplicates.isNotEmpty()) {
            findings += Finding(Level.WARNING, file, "$path.list", "有 ${duplicates.size} 条重复内容")
        }
        val matchMode = section.getString("match-mode").orEmpty().trim().lowercase(Locale.ROOT)
        if (matchMode !in setOf("精确", "通配", "正则", "exact", "wildcard", "regex", "")) {
            findings += Finding(Level.WARNING, file, "$path.match-mode", "未识别的匹配方式会回退为精确")
        }
        inspectRegexes(values, matchMode, file, "$path.list", findings)
    }

    private fun inspectTextRules(config: YamlConfiguration, base: String, file: String, findings: MutableList<Finding>) {
        val section = config.getConfigurationSection(base) ?: return
        inspectList(section, "keep-list", "$base.keep-list", file, findings)
        inspectList(section, "clean-list", "$base.clean-list", file, findings)
        val keep = section.getConfigurationSection("keep-list")?.getStringList("list").orEmpty()
        val clean = section.getConfigurationSection("clean-list")?.getStringList("list").orEmpty()
        val duplicates = keep.map(::normalizedEntry).toSet().intersect(clean.map(::normalizedEntry).toSet()).filter { it.isNotEmpty() }
        if (duplicates.isNotEmpty()) {
            findings += Finding(Level.WARNING, file, base, "保留与清理名单有 ${duplicates.size} 条相同内容")
        }
    }

    private fun inspectRegexes(
        values: List<String>,
        defaultMode: String,
        file: String,
        path: String,
        findings: MutableList<Finding>
    ) {
        for ((index, raw) in values.withIndex()) {
            val entry = raw.trim()
            if (entry.isEmpty()) continue
            val parsed = parseMode(entry, defaultMode)
            if (parsed.first !in setOf("正则", "regex")) continue
            try {
                Pattern.compile(parsed.second, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (error: PatternSyntaxException) {
                findings += Finding(Level.ERROR, file, "$path[$index]", "正则无效：${error.description}")
            }
        }
    }

    private fun parseMode(entry: String, fallback: String): Pair<String, String> {
        val separator = entry.indexOfFirst { it == ':' || it == '：' }
        if (separator <= 0) return fallback.trim().lowercase(Locale.ROOT) to entry
        val prefix = entry.substring(0, separator).trim().lowercase(Locale.ROOT)
        return when (prefix) {
            "regex", "regexp", "re", "正则" -> "regex" to entry.substring(separator + 1).trim()
            "wildcard", "glob", "通配" -> "wildcard" to entry.substring(separator + 1).trim()
            "exact", "精确", "精准" -> "exact" to entry.substring(separator + 1).trim()
            else -> fallback.trim().lowercase(Locale.ROOT) to entry
        }
    }

    private fun inspectPanic(rules: YamlConfiguration, findings: MutableList<Finding>) {
        val panic = rules.getInt("limits.panic.max-global-entities", 5000)
        val chunk = rules.getInt("limits.chunk.entities.threshold", 500)
        if (panic <= 0) {
            findings += Finding(Level.ERROR, "rules.yml", "limits.panic.max-global-entities", "必须大于 0")
        } else if (chunk > 0 && panic <= chunk) {
            findings += Finding(Level.WARNING, "rules.yml", "limits.panic.max-global-entities", "不应低于单区块实体限制")
        }
        if (rules.getLong("limits.panic.check-interval-seconds", 15L) !in 5L..300L) {
            findings += Finding(Level.WARNING, "rules.yml", "limits.panic.check-interval-seconds", "建议填写 5 到 300 秒")
        }
    }

    private fun inspectHookAvailability(rules: YamlConfiguration, findings: MutableList<Finding>) {
        val entries = collectEntries(rules, "targets.entities") + collectAdvancedEntityEntries(rules)
        if (entries.any { it.startsWith("mythic:", ignoreCase = true) } && Bukkit.getPluginManager().getPlugin("MythicMobs") == null) {
            findings += Finding(Level.WARNING, "rules.yml", "targets.entities", "存在 MythicMobs 规则，但未检测到 MythicMobs")
        }
        if (entries.any { it.startsWith("ce:", ignoreCase = true) || it.startsWith("craftengine:", ignoreCase = true) } &&
            Bukkit.getPluginManager().getPlugin("CraftEngine") == null && Bukkit.getPluginManager().getPlugin("CE") == null
        ) {
            findings += Finding(Level.WARNING, "rules.yml", "targets.entities", "存在 CraftEngine 规则，但未检测到 CraftEngine")
        }
    }

    private fun collectEntries(config: YamlConfiguration, base: String): List<String> {
        val section = config.getConfigurationSection(base) ?: return emptyList()
        return section.getConfigurationSection("keep-list")?.getStringList("list").orEmpty() +
            section.getConfigurationSection("clean-list")?.getStringList("list").orEmpty()
    }

    private fun collectAdvancedEntityEntries(config: YamlConfiguration): List<String> {
        val rules = config.getConfigurationSection("rules") ?: return emptyList()
        val entries = ArrayList<String>()
        for (name in rules.getKeys(false)) {
            val rule = rules.getConfigurationSection(name) ?: continue
            val target = rule.getString("target").orEmpty().trim().lowercase(Locale.ROOT)
            if (target !in setOf("entity", "entities", "实体")) continue
            val conditions = rule.getConfigurationSection("conditions") ?: rule
            entries += readAdvancedEntries(conditions, "ids")
        }
        return entries
    }

    private fun inspectAreas(areas: YamlConfiguration, findings: MutableList<Finding>) {
        val rules = areas.getConfigurationSection("areas.rules") ?: return
        val boxes = ArrayList<AreaBox>()
        for (name in rules.getKeys(false)) {
            val rule = rules.getConfigurationSection(name) ?: continue
            if (!rule.getBoolean("enabled", true)) continue
            val worlds = rule.getStringList("worlds").map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
            if (worlds.isEmpty()) {
                findings += Finding(Level.WARNING, "areas.yml", "areas.rules.$name.worlds", "未填写世界")
                continue
            }
            val area = rule.getConfigurationSection("area") ?: continue
            val min = area.getConfigurationSection("min")
            val max = area.getConfigurationSection("max")
            if (min == null || max == null) {
                findings += Finding(Level.ERROR, "areas.yml", "areas.rules.$name.area", "缺少 min 或 max")
                continue
            }
            val minX = min.getInt("x")
            val minY = min.getInt("y")
            val minZ = min.getInt("z")
            val maxX = max.getInt("x")
            val maxY = max.getInt("y")
            val maxZ = max.getInt("z")
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                findings += Finding(Level.WARNING, "areas.yml", "areas.rules.$name.area", "min 坐标大于 max 坐标")
            }
            for (world in worlds) {
                boxes += AreaBox(name, rule.getInt("priority", 0), world, minOf(minX, maxX), minOf(minY, maxY), minOf(minZ, maxZ), maxOf(minX, maxX), maxOf(minY, maxY), maxOf(minZ, maxZ))
            }
        }
        val limited = boxes.take(128)
        for (index in limited.indices) {
            for (otherIndex in index + 1 until limited.size) {
                val first = limited[index]
                val second = limited[otherIndex]
                if (first.priority == second.priority && first.overlaps(second)) {
                    findings += Finding(Level.WARNING, "areas.yml", "areas.rules.${second.name}", "与 ${first.name} 重叠且优先级相同")
                }
            }
        }
        if (boxes.size > limited.size) {
            findings += Finding(Level.WARNING, "areas.yml", "areas.rules", "区域过多，仅检查前 ${limited.size} 条重叠关系")
        }
    }

    private fun normalizedEntry(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun inspectMenus(dataFolder: File, findings: MutableList<Finding>) {
        for (relative in expectedFiles.filter { it.startsWith("menu/") }) {
            val config = load(dataFolder, relative, findings) ?: continue
            if (config.contains("rows")) {
                findings += Finding(Level.WARNING, relative, "rows", "发现旧字段 rows，请删掉")
            }
            val layout = config.getStringList("layout")
            if (layout.isEmpty()) {
                findings += Finding(Level.ERROR, relative, "layout", "缺少布局")
                continue
            }
            if (layout.size > 6) findings += Finding(Level.WARNING, relative, "layout", "超过 6 行，只会显示前 6 行")
            layout.take(6).forEachIndexed { index, line ->
                if (line.length != 9) findings += Finding(Level.WARNING, relative, "layout[$index]", "建议每行写满 9 个字符")
            }
            val items = config.getConfigurationSection("items")
            val nonButtonSymbols = nonButtonMenuSymbols[relative].orEmpty()
            val unknown = layout.flatMap { row -> row.toList() }
                .filter { symbol ->
                    symbol != ' ' && symbol != '*' && symbol !in nonButtonSymbols &&
                        items?.contains(symbol.toString()) != true
                }
                .distinct()
            if (unknown.isNotEmpty()) {
                findings += Finding(Level.WARNING, relative, "layout", "存在未配置的按钮：${unknown.joinToString("、")}")
            }
        }
    }
}

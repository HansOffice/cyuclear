package org.cyuCBMclean.cyuclear.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cyuCBMclean.cyuclear.Cyuclear
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

object ConfigUpgradeManager {

    const val CURRENT_CONFIG_VERSION = 140
    const val CURRENT_CONFIG_LAYOUT = 6

    private val managedFiles = listOf(
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

    private val rootFiles = mapOf(
        "enabled" to "config.yml",
        "cleanup" to "config.yml",
        "performance" to "config.yml",
        "audit" to "config.yml",
        "recovery" to "config.yml",
        "emergency" to "config.yml",
        "worlds" to "config.yml",
        "rules" to "rules.yml",
        "targets" to "rules.yml",
        "hooks" to "rules.yml",
        "limits" to "rules.yml",
        "modules" to "rules.yml",
        "panic-mode" to "rules.yml",
        "areas" to "areas.yml",
        "cluster" to "storage.yml",
        "void-bin" to "void-bin.yml",
        "sounds" to "sounds.yml"
    )

    private val pathAliases = mapOf(
        "limits.chunk-item-threshold" to "limits.chunk.items.threshold",
        "limits.chunk-item-soft-threshold" to "limits.chunk.items.soft-threshold",
        "limits.item-thresholds" to "limits.chunk.items.specific",
        "limits.chunk-entity-threshold" to "limits.chunk.entities.threshold",
        "limits.chunk-entity-soft-threshold" to "limits.chunk.entities.soft-threshold",
        "limits.chunk-entity-mode" to "limits.chunk.entities.mode",
        "limits.chunk-entity-spawn-window-millis" to "limits.chunk.entities.spawn-window-millis",
        "limits.entity-thresholds" to "limits.chunk.entities.specific",
        "limits.broadcast-location" to "limits.chunk.broadcast-location",
        "limits.count-cache-millis" to "limits.chunk.count-cache-millis",
        "limits.overload-cache-millis" to "limits.chunk.overload-cache-millis",
        "panic-mode.enabled" to "limits.panic.enabled",
        "panic-mode.max-global-entities" to "limits.panic.max-global-entities",
        "modules.items.enabled" to "targets.items.enabled",
        "modules.items.grace.minimum-age-seconds" to "targets.items.grace.minimum-age-seconds",
        "modules.items.grace.high-value-minimum-age-seconds" to "targets.items.grace.high-value-minimum-age-seconds",
        "modules.items.grace.high-value-list" to "targets.items.grace.high-value-list",
        "modules.entities.enabled" to "targets.entities.enabled",
        "modules.entities.ignore-named" to "targets.entities.protections.named",
        "modules.entities.named-bypass.entities" to "targets.entities.protections.named-bypass.entities",
        "modules.entities.named-bypass.regions" to "targets.entities.protections.named-bypass.regions",
        "modules.entities.ignore-tamed" to "targets.entities.protections.tamed",
        "modules.entities.ignore-persistent" to "targets.entities.protections.persistent",
        "modules.entities.ignore-no-despawn" to "targets.entities.protections.no-despawn",
        "modules.entities.protect-raid-event" to "targets.entities.protections.events.raid",
        "modules.entities.pokemon.ignore-player-owned" to "targets.entities.protections.player-owned-pokemon",
        "modules.entities.pokemon.enabled" to "hooks.pokemon.enabled",
        "modules.entities.mythic-mobs.enabled" to "hooks.mythic-mobs.enabled",
        "modules.entities.mythic-mobs.id-only" to "hooks.mythic-mobs.id-only",
        "modules.entities.mythic-mobs.bypass-protection-flags" to "hooks.mythic-mobs.bypass-protection-flags",
        "modules.entities.mythic-mobs.exclude-from-chunk-limit" to "hooks.mythic-mobs.exclude-from-chunk-limit",
        "modules.entities.mythic-mobs.exclude-from-panic-count" to "hooks.mythic-mobs.exclude-from-panic-count",
        "modules.entities.realtime.enabled" to "limits.realtime.enabled"
    )

    private val ignoredPaths = setOf("config-version", "config-layout")

    private val emptyTemplatePaths = setOf(
        "rules.yml:targets.items.clean-list.list",
        "rules.yml:targets.items.name-rules.keep-list.list",
        "rules.yml:targets.items.name-rules.clean-list.list",
        "rules.yml:targets.items.lore-rules.keep-list.list",
        "rules.yml:targets.items.lore-rules.clean-list.list",
        "rules.yml:targets.entities.clean-list.list",
        "rules.yml:targets.entities.name-rules.keep-list.list",
        "rules.yml:targets.entities.name-rules.clean-list.list",
        "rules.yml:targets.entities.detail-rules",
        "rules.yml:limits.chunk.items.specific",
        "rules.yml:limits.chunk.entities.specific",
        "rules.yml:limits.realtime.keep-list.list",
        "rules.yml:limits.realtime.clean-list.list",
        "void-bin.yml:void-bin.entry-rules.cleanup-recovery.allow-list.list",
        "void-bin.yml:void-bin.entry-rules.cleanup-recovery.deny-list.list",
        "void-bin.yml:void-bin.entry-rules.cleanup-recovery.name-rules.allow-list.list",
        "void-bin.yml:void-bin.entry-rules.cleanup-recovery.name-rules.deny-list.list",
        "void-bin.yml:void-bin.entry-rules.cleanup-recovery.lore-rules.allow-list.list",
        "void-bin.yml:void-bin.entry-rules.cleanup-recovery.lore-rules.deny-list.list",
        "void-bin.yml:void-bin.entry-rules.player-deposit.allow-list.list",
        "void-bin.yml:void-bin.entry-rules.player-deposit.deny-list.list",
        "void-bin.yml:void-bin.entry-rules.player-deposit.name-rules.allow-list.list",
        "void-bin.yml:void-bin.entry-rules.player-deposit.name-rules.deny-list.list",
        "void-bin.yml:void-bin.entry-rules.player-deposit.lore-rules.allow-list.list",
        "void-bin.yml:void-bin.entry-rules.player-deposit.lore-rules.deny-list.list",
        "areas.yml:areas.rules"
    )

    data class UpgradeResult(
        val upgraded: Boolean,
        val backupFolder: File? = null,
        val failed: Boolean = false,
        val migratedValues: Int = 0,
        val unmappedValues: Int = 0
    )

    private data class SourceConfig(val fileName: String, val config: YamlConfiguration)

    private data class DestinationValue(val fileName: String, val path: String, val value: Any)

    private data class MigrationPlan(
        val values: LinkedHashMap<String, DestinationValue>,
        val consumed: MutableSet<String>,
        val sourcePaths: LinkedHashMap<String, Any>
    )

    fun prepare(): UpgradeResult {
        val plugin = Cyuclear.instance
        val dataFolder = plugin.dataFolder
        val configFile = File(dataFolder, "config.yml")
        if (!configFile.exists()) return UpgradeResult(upgraded = false)

        val config = loadYaml(configFile)
        val version = config.getInt("config-version", 0)
        if (version >= CURRENT_CONFIG_VERSION && config.getInt("config-layout", 0) >= CURRENT_CONFIG_LAYOUT) {
            return UpgradeResult(upgraded = false)
        }

        val stageFolder = File(dataFolder, ".migration-140-${UUID.randomUUID()}")
        var backupFolder: File? = null
        val originalFiles = managedFiles.filter { File(dataFolder, it).exists() }.toSet()
        return try {
            check(stageFolder.mkdirs()) { "无法创建配置迁移临时目录" }
            val templates = createTemplates(stageFolder)
            val sources = loadSources(dataFolder)
            val plan = buildPlan(sources, templates, version < CURRENT_CONFIG_VERSION)
            applyPlan(stageFolder, plan)
            validateStage(stageFolder)

            backupFolder = createBackupFolder(dataFolder)
            backupFiles(dataFolder, backupFolder)
            val unmapped = plan.sourcePaths.filterKeys { it !in plan.consumed && it.substringAfter(':') !in ignoredPaths }
            writeUpgradeReport(backupFolder, version, plan.values.size, unmapped)
            commitStage(stageFolder, dataFolder)

            plugin.logger.info("配置已迁移到 1.4.0，保留 ${plan.values.size} 项自定义值")
            plugin.logger.info("已整理配置结构，并补齐清理批次、恢复与热点菜单")
            plugin.logger.info("原配置备份：backup/${backupFolder.name}")
            if (unmapped.isNotEmpty()) {
                plugin.logger.warning("有 ${unmapped.size} 项旧配置无法自动识别，详情见 backup/${backupFolder.name}/unmapped.yml")
            }
            UpgradeResult(true, backupFolder, migratedValues = plan.values.size, unmappedValues = unmapped.size)
        } catch (error: Throwable) {
            if (backupFolder != null) restoreBackup(dataFolder, backupFolder, originalFiles)
            plugin.logger.severe("配置自动迁移失败，已保留原配置：${error.message}")
            UpgradeResult(upgraded = false, backupFolder = backupFolder, failed = true)
        } finally {
            stageFolder.deleteRecursively()
        }
    }

    private fun createTemplates(stageFolder: File): Map<String, YamlConfiguration> {
        val plugin = Cyuclear.instance
        return managedFiles.associateWith { fileName ->
            val file = File(stageFolder, fileName)
            file.parentFile?.mkdirs()
            val resource = plugin.getResource(fileName) ?: error("插件 jar 中缺少 $fileName")
            resource.use { input -> file.outputStream().use(input::copyTo) }
            loadYaml(file)
        }
    }

    private fun loadSources(dataFolder: File): List<SourceConfig> {
        val ordered = ArrayList<SourceConfig>()
        val configFile = File(dataFolder, "config.yml")
        if (configFile.exists()) ordered += SourceConfig("config.yml", loadYaml(configFile))
        managedFiles.filter { it != "config.yml" }.forEach { fileName ->
            val file = File(dataFolder, fileName)
            if (file.exists()) ordered += SourceConfig(fileName, loadYaml(file))
        }
        return ordered
    }

    private fun buildPlan(
        sources: List<SourceConfig>,
        templates: Map<String, YamlConfiguration>,
        enableAfterUpgrade: Boolean
    ): MigrationPlan {
        val values = LinkedHashMap<String, DestinationValue>()
        val consumed = HashSet<String>()
        val sourcePaths = LinkedHashMap<String, Any>()

        for (source in sources) {
            for ((path, value) in source.config.getValues(true)) {
                if (value is ConfigurationSection || path in ignoredPaths) continue
                val sourceKey = "${source.fileName}:$path"
                sourcePaths[sourceKey] = value
                val direct = directDestination(source.fileName, path, value, templates) ?: continue
                values["${direct.fileName}:${direct.path}"] = direct
                consumed += sourceKey
            }
        }

        for (source in sources) {
            if (source.fileName != "rules.yml") continue
            val section = source.config.getConfigurationSection("rules") ?: continue
            val copied = LinkedHashMap<String, Any>()
            for ((path, value) in section.getValues(true)) {
                if (value is ConfigurationSection) continue
                copied[path] = value
                consumed += "rules.yml:rules.$path"
            }
            if (copied.isNotEmpty()) {
                values["rules.yml:rules"] = DestinationValue("rules.yml", "rules", copied)
            }
        }

        sources.forEach { source ->
            migrateLegacyTarget(source, "modules.items", "targets.items", "rules.yml", values, consumed)
            migrateLegacyTarget(source, "modules.entities", "targets.entities", "rules.yml", values, consumed)
            migrateLegacyTarget(source, "modules.entities.realtime", "limits.realtime", "rules.yml", values, consumed)
        }

        values["config.yml:config-version"] = DestinationValue("config.yml", "config-version", CURRENT_CONFIG_VERSION)
        values["config.yml:config-layout"] = DestinationValue("config.yml", "config-layout", CURRENT_CONFIG_LAYOUT)
        if (enableAfterUpgrade) {
            values["config.yml:enabled"] = DestinationValue("config.yml", "enabled", true)
        }
        return MigrationPlan(values, consumed, sourcePaths)
    }

    private fun directDestination(
        sourceFile: String,
        sourcePath: String,
        value: Any,
        templates: Map<String, YamlConfiguration>
    ): DestinationValue? {
        if (sourcePath.endsWith(".filter-mode") || sourcePath.endsWith(".match-mode") || sourcePath.endsWith(".list")) {
            if (sourcePath.startsWith("modules.items") || sourcePath.startsWith("modules.entities")) return null
        }
        val aliasedPath = pathAliases[sourcePath] ?: sourcePath
        val destinationFile = when {
            sourceFile != "config.yml" && sourceFile in managedFiles -> sourceFile
            else -> rootFiles[aliasedPath.substringBefore('.')] ?: sourceFile.takeIf { it == "messages.yml" }
        } ?: return null
        val template = templates[destinationFile] ?: return null
        return if (template.contains(aliasedPath) || "$destinationFile:$aliasedPath" in emptyTemplatePaths) {
            DestinationValue(destinationFile, aliasedPath, value)
        } else {
            null
        }
    }

    private fun migrateLegacyTarget(
        source: SourceConfig,
        oldBase: String,
        newBase: String,
        destinationFile: String,
        values: LinkedHashMap<String, DestinationValue>,
        consumed: MutableSet<String>
    ) {
        if (!source.config.isConfigurationSection(oldBase)) return
        val modePath = "$oldBase.filter-mode"
        val matchPath = "$oldBase.match-mode"
        val listPath = "$oldBase.list"
        if (!source.config.contains(modePath) && !source.config.contains(listPath)) return

        val mode = source.config.getString(modePath, "blacklist").orEmpty().trim().lowercase(Locale.ROOT)
        val modeName = when (mode) {
            "whitelist", "白名单" -> "白名单"
            "parallel", "并行名单" -> "并行名单"
            else -> "黑名单"
        }
        val listTarget = if (modeName == "白名单") "$newBase.clean-list" else "$newBase.keep-list"
        values["$destinationFile:$newBase.mode"] = DestinationValue(destinationFile, "$newBase.mode", modeName)
        val containsMatch = usesContainsMatchMode(source.config.getString(matchPath))
        if (source.config.contains(matchPath)) {
            values["$destinationFile:$listTarget.match-mode"] = DestinationValue(
                destinationFile,
                "$listTarget.match-mode",
                if (containsMatch) "通配" else normalizeMatchMode(source.config.getString(matchPath))
            )
        }
        if (source.config.contains(listPath)) {
            values["$destinationFile:$listTarget.list"] = DestinationValue(
                destinationFile,
                "$listTarget.list",
                source.config.getList(listPath).orEmpty().mapNotNull { entry ->
                    entry?.let { if (containsMatch) asWildcardContains(it) else it }
                }
            )
        }
        if (source.config.contains(modePath)) consumed += "${source.fileName}:$modePath"
        if (source.config.contains(matchPath)) consumed += "${source.fileName}:$matchPath"
        if (source.config.contains(listPath)) consumed += "${source.fileName}:$listPath"
    }

    private fun normalizeMatchMode(value: String?): String = when (value.orEmpty().trim().lowercase(Locale.ROOT)) {
        "regex", "正则" -> "正则"
        "wildcard", "通配" -> "通配"
        else -> "精确"
    }

    private fun usesContainsMatchMode(value: String?): Boolean {
        return value.orEmpty().trim().lowercase(Locale.ROOT) in setOf("contains", "包含")
    }

    private fun asWildcardContains(value: Any): String {
        val entry = value.toString().trim()
        if (entry.isEmpty()) return entry
        val prefix = entry.substringBefore(':').lowercase(Locale.ROOT)
        if (prefix in setOf("exact", "精准", "精确", "wildcard", "glob", "通配", "regex", "regexp", "re", "正则")) {
            return entry
        }
        return "通配:*$entry*"
    }

    private fun applyPlan(stageFolder: File, plan: MigrationPlan) {
        val grouped = plan.values.values.groupBy(DestinationValue::fileName)
        val missing = ArrayList<String>()
        for (fileName in managedFiles) {
            val file = File(stageFolder, fileName)
            val editor = TemplateValueWriter(file.readText(Charsets.UTF_8))
            grouped[fileName].orEmpty().forEach { value ->
                if (!editor.set(value.path, value.value)) {
                    missing += "$fileName:${value.path}"
                }
            }
            file.writeText(editor.text(), Charsets.UTF_8)
        }
        if (missing.isNotEmpty()) {
            Cyuclear.instance.logger.warning(
                "配置迁移跳过 ${missing.size} 个模板中不存在的路径，示例: ${missing.take(5).joinToString()}"
            )
        }
    }

    private fun validateStage(stageFolder: File) {
        managedFiles.forEach { loadYaml(File(stageFolder, it)) }
        val config = loadYaml(File(stageFolder, "config.yml"))
        check(config.getInt("config-version") == CURRENT_CONFIG_VERSION) { "迁移后的 config-version 无效" }
        check(config.getInt("config-layout") == CURRENT_CONFIG_LAYOUT) { "迁移后的 config-layout 无效" }
    }

    private fun backupFiles(dataFolder: File, backupFolder: File) {
        managedFiles.forEach { fileName ->
            val source = File(dataFolder, fileName)
            if (!source.exists()) return@forEach
            val target = File(backupFolder, fileName)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = false)
        }
    }

    private fun writeUpgradeReport(
        backupFolder: File,
        oldVersion: Int,
        migratedCount: Int,
        unmapped: Map<String, Any>
    ) {
        File(backupFolder, "upgrade-note.txt").writeText(
            buildString {
                appendLine("CyuClear 配置已迁移到 1.4.0")
                appendLine("旧配置文件保存在当前文件夹")
                appendLine("保留自定义配置: $migratedCount 项")
                appendLine("未识别配置: ${unmapped.size} 项")
                appendLine("旧配置版本: ${if (oldVersion > 0) oldVersion else "未标记"}")
                appendLine("本次整理: 配置结构、清理批次、恢复与热点菜单")
            },
            Charsets.UTF_8
        )
        if (unmapped.isNotEmpty()) {
            val report = YamlConfiguration()
            report.set(
                "unmapped",
                unmapped.map { (path, value) -> linkedMapOf("source" to path, "value" to value) }
            )
            report.save(File(backupFolder, "unmapped.yml"))
        }
    }

    private fun commitStage(stageFolder: File, dataFolder: File) {
        managedFiles.forEach { fileName ->
            val target = File(dataFolder, fileName)
            target.parentFile?.mkdirs()
            atomicMove(File(stageFolder, fileName), target)
        }
    }

    private fun restoreBackup(dataFolder: File, backupFolder: File, originalFiles: Set<String>) {
        managedFiles.forEach { fileName ->
            val live = File(dataFolder, fileName)
            val backup = File(backupFolder, fileName)
            runCatching {
                if (fileName in originalFiles && backup.exists()) {
                    live.parentFile?.mkdirs()
                    Files.copy(backup.toPath(), live.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else if (fileName !in originalFiles) {
                    Files.deleteIfExists(live.toPath())
                }
                Unit
            }
        }
    }

    private fun createBackupFolder(dataFolder: File): File {
        val backupRoot = File(dataFolder, "backup")
        check(backupRoot.exists() || backupRoot.mkdirs()) { "无法创建配置备份目录" }
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date())
        var folder = File(backupRoot, "1.4.0-$timestamp")
        var index = 2
        while (folder.exists()) folder = File(backupRoot, "1.4.0-$timestamp-${index++}")
        check(folder.mkdirs()) { "无法创建备份目录 ${folder.path}" }
        return folder
    }

    private fun loadYaml(file: File): YamlConfiguration = YamlConfiguration().also { it.load(file) }

    private fun atomicMove(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class TemplateValueWriter(source: String) {
        private val lines = source.replace("\r\n", "\n").split('\n').toMutableList()

        fun set(path: String, value: Any): Boolean {
            val index = findPath(path)
            if (index == null) {
                return false
            }
            val indent = indentation(lines[index])
            val encoded = encode(value)
            if (encoded.size == 1) {
                val separator = lines[index].indexOf(':')
                val comment = inlineComment(lines[index].substring(separator + 1))
                lines[index] = lines[index].substring(0, separator + 1) + " " + encoded[0] + comment
                return true
            }

            val separator = lines[index].indexOf(':')
            lines[index] = lines[index].substring(0, separator + 1)
            val nextKey = nextSibling(index, indent)
            var contentEnd = nextKey
            while (contentEnd > index + 1 && lines[contentEnd - 1].trim().let { it.isEmpty() || it.startsWith("#") }) {
                contentEnd--
            }
            repeat(contentEnd - index - 1) { lines.removeAt(index + 1) }
            lines.addAll(index + 1, encoded.drop(1).map { " ".repeat(indent) + it })
            return true
        }

        fun text(): String = lines.joinToString("\n").trimEnd() + "\n"

        private fun findPath(path: String): Int? {
            val expected = path.split('.')
            val stack = ArrayList<Pair<Int, String>>()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) continue
                val separator = trimmed.indexOf(':')
                if (separator <= 0) continue
                val indent = indentation(line)
                while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.lastIndex)
                val key = trimmed.substring(0, separator).trim().trim('\'', '"')
                if (stack.map { it.second } + key == expected) return index
                stack += indent to key
            }
            return null
        }

        private fun nextSibling(index: Int, baseIndent: Int): Int {
            for (cursor in index + 1 until lines.size) {
                val trimmed = lines[cursor].trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) continue
                if (indentation(lines[cursor]) <= baseIndent) return cursor
            }
            return lines.size
        }

        private fun encode(value: Any): List<String> {
            return when (value) {
                is String -> listOf(quoteScalar(value))
                is Boolean, is Number -> listOf(value.toString())
                is List<*> -> {
                    if (value.isEmpty()) {
                        listOf("[]")
                    } else {
                        listOf("") + value.map { item -> "  - ${encodeListItem(item)}" }
                    }
                }
                is Map<*, *> -> {
                    val yaml = YamlConfiguration()
                    value.forEach { (key, item) ->
                        if (key != null && item != null) yaml.set(key.toString(), item)
                    }
                    listOf("") + yaml.saveToString().trimEnd().lines().map { "  $it" }
                }
                else -> {
                    val yaml = YamlConfiguration()
                    yaml.set("value", value)
                    yaml.saveToString().trimEnd().lines().mapIndexed { index, line ->
                        if (index == 0) line.substringAfter(':').trimStart() else line
                    }.let { encoded ->
                        if (encoded.size == 1) encoded else listOf("") + encoded.drop(1)
                    }
                }
            }
        }

        private fun encodeListItem(item: Any?): String {
            return when (item) {
                null -> "null"
                is String -> quoteScalar(item)
                is Boolean, is Number -> item.toString()
                else -> quoteScalar(item.toString())
            }
        }

        private fun quoteScalar(value: String): String {
            val escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            return "\"$escaped\""
        }

        private fun inlineComment(valueText: String): String {
            val commentIndex = valueText.indexOf(" #")
            return if (commentIndex >= 0) valueText.substring(commentIndex) else ""
        }

        private fun indentation(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
    }
}

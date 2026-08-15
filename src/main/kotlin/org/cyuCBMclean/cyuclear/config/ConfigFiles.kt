package org.cyuCBMclean.cyuclear.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cyuCBMclean.cyuclear.Cyuclear
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConfigFiles {
    private val splitFiles = linkedMapOf(
        "rules.yml" to setOf("rules", "targets", "hooks", "limits", "modules", "panic-mode"),
        "areas.yml" to setOf("areas"),
        "storage.yml" to setOf("cluster"),
        "void-bin.yml" to setOf("void-bin"),
        "sounds.yml" to setOf("sounds")
    )

    @Volatile
    private var rules = YamlConfiguration()

    fun prepare() {
        migrateMonolithicConfig()
        for (fileName in splitFiles.keys) {
            val file = File(Cyuclear.instance.dataFolder, fileName)
            if (!file.exists()) Cyuclear.instance.saveResource(fileName, false)
        }
    }

    fun load(): YamlConfiguration {
        Cyuclear.instance.reloadConfig()
        val merged = YamlConfiguration()
        copyInto(Cyuclear.instance.config, merged)
        for (fileName in splitFiles.keys) {
            val loaded = YamlConfiguration.loadConfiguration(File(Cyuclear.instance.dataFolder, fileName))
            if (fileName == "rules.yml") rules = loaded
            copyInto(loaded, merged)
        }
        return merged
    }

    fun rules(): YamlConfiguration = rules

    private fun copyInto(source: ConfigurationSection, target: YamlConfiguration) {
        for ((path, value) in source.getValues(true)) {
            if (value !is ConfigurationSection) target.set(path, value)
        }
    }

    private fun migrateMonolithicConfig() {
        val dataFolder = Cyuclear.instance.dataFolder
        val configFile = File(dataFolder, "config.yml")
        if (!configFile.exists()) return
        val source = configFile.readText(Charsets.UTF_8)
        if (Regex("(?m)^config-layout:\\s*(?:[2-9]|[1-9]\\d+)\\s*$").containsMatchIn(source)) return
        val sections = splitTopLevel(source)
        if (splitFiles.values.flatten().none(sections::containsKey)) return

        val backupFolder = createBackupFolder(dataFolder)
        configFile.copyTo(File(backupFolder, "config.yml"), overwrite = false)
        for ((fileName, roots) in splitFiles) {
            val target = File(dataFolder, fileName)
            if (target.exists()) {
                target.copyTo(File(backupFolder, fileName), overwrite = false)
                continue
            }
            val content = roots.mapNotNull(sections::get).joinToString("\n").trimEnd()
            if (content.isNotEmpty()) atomicWrite(target, "$content\n")
        }

        val movedRoots = splitFiles.values.flatten().toSet()
        val reduced = removeTopLevel(source, movedRoots)
        val versionLine = Regex("(?m)^config-version:\\s*\\d+\\s*$").find(reduced)
        val marked = if (versionLine == null) {
            "config-layout: ${ConfigUpgradeManager.CURRENT_CONFIG_LAYOUT}\n$reduced"
        } else {
            reduced.replaceRange(versionLine.range.last + 1, versionLine.range.last + 1, "\nconfig-layout: ${ConfigUpgradeManager.CURRENT_CONFIG_LAYOUT}")
        }
        atomicWrite(configFile, marked.trimEnd() + "\n")
        Cyuclear.instance.logger.info("已将单文件配置拆分，原 config.yml 保存在 ${backupFolder.name}")
    }

    private fun splitTopLevel(source: String): Map<String, String> {
        val lines = source.replace("\r\n", "\n").split('\n')
        val starts = lines.mapIndexedNotNull { index, line ->
            Regex("^([A-Za-z0-9_-]+):(?:\\s.*)?$").matchEntire(line)?.groupValues?.get(1)?.let { index to it }
        }
        return starts.mapIndexed { index, (start, key) ->
            val end = starts.getOrNull(index + 1)?.first ?: lines.size
            key to lines.subList(start, end).joinToString("\n").trimEnd()
        }.toMap()
    }

    private fun removeTopLevel(source: String, roots: Set<String>): String {
        val lines = source.replace("\r\n", "\n").split('\n')
        val result = ArrayList<String>(lines.size)
        var skipping = false
        for (line in lines) {
            val root = Regex("^([A-Za-z0-9_-]+):(?:\\s.*)?$").matchEntire(line)?.groupValues?.get(1)
            if (root != null) skipping = root in roots
            if (!skipping) result.add(line)
        }
        return result.joinToString("\n")
    }

    private fun createBackupFolder(dataFolder: File): File {
        val root = File(dataFolder, "backup")
        root.mkdirs()
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date())
        var folder = File(root, "config-split-$timestamp")
        var index = 2
        while (folder.exists()) folder = File(root, "config-split-$timestamp-${index++}")
        check(folder.mkdirs()) { "无法创建配置拆分备份目录 ${folder.path}" }
        return folder
    }

    private fun atomicWrite(file: File, text: String) {
        val temp = File(file.parentFile, ".${file.name}.split.tmp")
        temp.writeText(text, Charsets.UTF_8)
        runCatching {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

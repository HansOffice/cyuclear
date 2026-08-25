package org.cyuCBMclean.cyuclear.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.util.ColorUtils
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

object Language {

    data class ClickMessage(
        val text: String,
        val hover: String
    )

    private val messages = HashMap<String, String>()
    private val fallbackMessages = HashMap<String, String>()
    private var prefixString = ""
    private var config: YamlConfiguration? = null
    var currentLanguageCode: String = "zh_CN"
        private set

    val prefix: String
        get() = prefixString

    fun load() {
        messages.clear()
        fallbackMessages.clear()

        val dataFolder = Cyuclear.instance.dataFolder
        val langDir = File(dataFolder, "lang")
        if (!langDir.exists()) {
            langDir.mkdirs()
        }

        saveLangResource("lang/messages_zh.yml", File(langDir, "messages_zh.yml"))
        saveLangResource("lang/messages_en.yml", File(langDir, "messages_en.yml"))

        val configFile = File(dataFolder, "config.yml")
        var configuredLang = "zh_CN"
        if (configFile.exists()) {
            val cfg = YamlConfiguration.loadConfiguration(configFile)
            configuredLang = cfg.getString("language", "zh_CN")?.trim().orEmpty().ifBlank { "zh_CN" }
        }

        currentLanguageCode = normalizeLanguage(configuredLang)

        loadFallback(currentLanguageCode)

        val activeFile = resolveActiveFile(dataFolder, langDir, currentLanguageCode)
        config = YamlConfiguration.loadConfiguration(activeFile)
        val loadedConfig = config ?: return

        val rawPrefix = loadedConfig.getString("prefix") ?: fallbackMessages["prefix"] ?: "&8[&bCyuclear&8] &f"
        prefixString = ColorUtils.color(rawPrefix)

        for (key in loadedConfig.getKeys(true)) {
            if (key == "prefix" || !loadedConfig.isString(key)) continue
            val rawStr = loadedConfig.getString(key) ?: continue
            messages[key] = ColorUtils.color(rawStr)
        }
    }

    fun setLanguage(langInput: String, saveToConfig: Boolean = true): Boolean {
        val normalized = normalizeLanguage(langInput)
        currentLanguageCode = normalized

        if (saveToConfig) {
            val configFile = File(Cyuclear.instance.dataFolder, "config.yml")
            if (configFile.exists()) {
                val cfg = YamlConfiguration.loadConfiguration(configFile)
                cfg.set("language", normalized)
                runCatching { cfg.save(configFile) }
            }
        }

        val dataFolder = Cyuclear.instance.dataFolder
        val langDir = File(dataFolder, "lang")
        val activeFile = File(dataFolder, "messages.yml")
        val sourceBundle = if (normalized == "en_US") File(langDir, "messages_en.yml") else File(langDir, "messages_zh.yml")
        if (sourceBundle.exists()) {
            runCatching { sourceBundle.copyTo(activeFile, overwrite = true) }
        }

        load()
        return true
    }

    private fun resolveActiveFile(dataFolder: File, langDir: File, lang: String): File {
        val messagesFile = File(dataFolder, "messages.yml")
        if (messagesFile.exists()) {
            return messagesFile
        }
        val bundleFile = if (lang == "en_US") File(langDir, "messages_en.yml") else File(langDir, "messages_zh.yml")
        if (bundleFile.exists()) {
            runCatching { bundleFile.copyTo(messagesFile, overwrite = false) }
            return messagesFile
        }
        Cyuclear.instance.saveResource("messages.yml", false)
        return messagesFile
    }

    private fun saveLangResource(resourcePath: String, targetFile: File) {
        if (targetFile.exists()) return
        val stream = Cyuclear.instance.getResource(resourcePath) ?: return
        stream.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun loadFallback(lang: String) {
        val resourcePath = if (lang == "en_US") "lang/messages_en.yml" else "lang/messages_zh.yml"
        val stream = Cyuclear.instance.getResource(resourcePath) ?: Cyuclear.instance.getResource("messages.yml") ?: return
        InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            val fallbackConfig = YamlConfiguration.loadConfiguration(reader)
            for (key in fallbackConfig.getKeys(true)) {
                if (!fallbackConfig.isString(key)) continue
                val rawStr = fallbackConfig.getString(key) ?: continue
                fallbackMessages[key] = ColorUtils.color(rawStr)
            }
        }
    }

    private fun normalizeLanguage(input: String): String {
        val clean = input.trim().lowercase(Locale.ROOT)
        return when {
            clean == "auto" -> {
                val sysLang = Locale.getDefault().language.lowercase(Locale.ROOT)
                if (sysLang == "zh") "zh_CN" else "en_US"
            }
            clean in listOf("en", "en_us", "en-us", "english", "us") -> "en_US"
            else -> "zh_CN"
        }
    }

    fun has(key: String): Boolean {
        return messages.containsKey(key) || fallbackMessages.containsKey(key)
    }

    val isEnglish: Boolean
        get() {
            if (currentLanguageCode.startsWith("en", ignoreCase = true)) return true
            val sample = messages["reload-success"] ?: fallbackMessages["reload-success"] ?: return false
            return !sample.any { it in '\u4e00'..'\u9fa5' }
        }

    fun getInt(key: String, def: Int = 0): Int {
        return config?.getInt(key, def) ?: def
    }

    fun get(key: String, vararg placeholders: Pair<String, String>): String {
        var text = messages[key] ?: fallbackMessages[key] ?: return "§cMissing message: $key"

        for ((placeholder, replacement) in placeholders) {
            text = text.replace("{$placeholder}", replacement)
        }

        return prefixString + text
    }

    fun getRaw(key: String, vararg placeholders: Pair<String, String>): String {
        var text = messages[key] ?: fallbackMessages[key] ?: return "§cMissing message: $key"

        for ((placeholder, replacement) in placeholders) {
            text = text.replace("{$placeholder}", replacement)
        }

        return text
    }

    fun getClickMessage(path: String, vararg placeholders: Pair<String, String>): ClickMessage {
        val textRaw = messages["$path.text"]
            ?: config?.getString("$path.text")
            ?: fallbackMessages["$path.text"]
            ?: ""
        val hoverRaw = messages["$path.hover"]
            ?: config?.getString("$path.hover")
            ?: fallbackMessages["$path.hover"]
            ?: ""
        return ClickMessage(
            text = applyPlaceholders(ColorUtils.color(textRaw), placeholders),
            hover = applyPlaceholders(ColorUtils.color(hoverRaw), placeholders)
        )
    }

    private fun applyPlaceholders(text: String, placeholders: Array<out Pair<String, String>>): String {
        var result = text
        for ((placeholder, replacement) in placeholders) {
            result = result.replace("{$placeholder}", replacement)
        }
        return result
    }
}

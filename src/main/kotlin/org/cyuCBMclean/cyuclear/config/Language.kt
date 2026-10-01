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
    var currentLanguageCode: String = "zh_cn"
        private set

    val prefix: String
        get() = prefixString

    fun load() {
        messages.clear()
        fallbackMessages.clear()

        val plugin = Cyuclear.instance
        val dataFolder = plugin.dataFolder

        ensureResource("lang/zh_cn.yml")
        ensureResource("lang/en_us.yml")

        val configFile = File(dataFolder, "config.yml")
        var configuredLang = "zh_cn"
        if (configFile.exists()) {
            val cfg = YamlConfiguration.loadConfiguration(configFile)
            configuredLang = cfg.getString("language", "zh_cn")?.trim().orEmpty().ifBlank { "zh_cn" }
        }

        currentLanguageCode = normalizeLanguage(configuredLang)
        loadFallback(currentLanguageCode)

        val activeFile = ensureResource("lang/$currentLanguageCode.yml")
        val loadedConfig = YamlConfiguration.loadConfiguration(activeFile)
        config = loadedConfig

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
                try {
                    cfg.save(configFile)
                } catch (ex: Exception) {
                    Cyuclear.instance.logger.warning("无法保存 language 到 config.yml: ${ex.message}")
                    return false
                }
            }
        }

        load()
        return true
    }

    private fun ensureResource(path: String): File {
        val file = File(Cyuclear.instance.dataFolder, path)
        if (!file.exists()) {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            if (Cyuclear.instance.getResource(path) != null) {
                Cyuclear.instance.saveResource(path, false)
            }
        }
        return file
    }

    private fun loadFallback(lang: String) {
        val resourcePath = "lang/$lang.yml"
        val stream = Cyuclear.instance.getResource(resourcePath) ?: Cyuclear.instance.getResource("lang/zh_cn.yml") ?: return
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
        if (clean == "auto") {
            val sysLang = Locale.getDefault().language.lowercase(Locale.ROOT)
            return if (sysLang == "zh") "zh_cn" else "en_us"
        }
        val standardized = when (clean) {
            "en", "en-us", "english", "us" -> "en_us"
            "zh", "zh-cn", "chinese", "cn" -> "zh_cn"
            else -> clean
        }
        val file = File(Cyuclear.instance.dataFolder, "lang/$standardized.yml")
        if (file.exists() || Cyuclear.instance.getResource("lang/$standardized.yml") != null) {
            return standardized
        }
        return "zh_cn"
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

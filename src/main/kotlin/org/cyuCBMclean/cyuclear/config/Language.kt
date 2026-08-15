package org.cyuCBMclean.cyuclear.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.util.ColorUtils
import java.io.File

object Language {

    data class ClickMessage(
        val text: String,
        val hover: String
    )

    private val messages = HashMap<String, String>()
    private var prefix = ""
    private var config: YamlConfiguration? = null

    fun load() {
        messages.clear()

        val file = File(Cyuclear.instance.dataFolder, "messages.yml")
        if (!file.exists()) {
            Cyuclear.instance.saveResource("messages.yml", false)
        }

        config = YamlConfiguration.loadConfiguration(file)
        val loadedConfig = config ?: return

        prefix = ColorUtils.color(loadedConfig.getString("prefix", "&8[&bCyuclear&8] ")!!)

        for (key in loadedConfig.getKeys(false)) {
            if (key == "prefix") continue
            val rawStr = loadedConfig.getString(key) ?: continue
            messages[key] = ColorUtils.color(rawStr)
        }
    }

    fun has(key: String): Boolean {
        return messages.containsKey(key)
    }

    fun getInt(key: String, def: Int = 0): Int {
        return config?.getInt(key, def) ?: def
    }

    fun get(key: String, vararg placeholders: Pair<String, String>): String {
        var text = messages[key] ?: return "§cMissing message: $key"

        for ((placeholder, replacement) in placeholders) {
            text = text.replace("{$placeholder}", replacement)
        }

        return prefix + text
    }

    fun getRaw(key: String, vararg placeholders: Pair<String, String>): String {
        var text = messages[key] ?: return "§cMissing message: $key"

        for ((placeholder, replacement) in placeholders) {
            text = text.replace("{$placeholder}", replacement)
        }

        return text
    }

    fun getClickMessage(path: String, vararg placeholders: Pair<String, String>): ClickMessage {
        val section = config?.getConfigurationSection(path)
            ?: return ClickMessage("", "")

        return ClickMessage(
            text = applyPlaceholders(ColorUtils.color(section.getString("text", "") ?: ""), placeholders),
            hover = applyPlaceholders(ColorUtils.color(section.getString("hover", "") ?: ""), placeholders)
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

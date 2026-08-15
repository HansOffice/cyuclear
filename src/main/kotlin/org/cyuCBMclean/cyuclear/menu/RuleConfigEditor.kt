package org.cyuCBMclean.cyuclear.menu

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.config.ConfigTextEditor
import org.cyuCBMclean.cyuclear.config.ConfigFiles
import org.cyuCBMclean.cyuclear.platform.SpawnEggBridge
import org.cyuCBMclean.cyuclear.util.ItemIdentity
import org.cyuCBMclean.cyuclear.util.ItemText
import java.util.Locale

object RuleConfigEditor {
    enum class Target(
        val path: String,
        val display: String,
        val entityInput: Boolean,
        val supportsParallel: Boolean
    ) {
        ITEMS("targets.items", "掉落物规则", false, true),
        ENTITIES("targets.entities", "实体规则", true, true),
        REALTIME("limits.realtime", "实时拦截", true, false)
    }

    enum class ListKind(val path: String, val display: String) {
        KEEP("keep-list.list", "保留名单"),
        CLEAN("clean-list.list", "清理名单")
    }

    enum class ListDomain(
        val segment: String?,
        val display: String,
        val input: InputType,
        val itemsOnly: Boolean
    ) {
        MATERIAL(null, "ID", InputType.MATERIAL, false),
        NAME("name-rules", "展示名", InputType.DISPLAY_NAME, true),
        LORE("lore-rules", "Lore", InputType.LORE, true)
    }

    enum class InputType {
        MATERIAL,
        DISPLAY_NAME,
        LORE
    }

    private val matchModes = listOf("精确", "通配", "正则")

    fun enabled(target: Target): Boolean = ConfigFiles.rules().getBoolean("${target.path}.enabled", true)

    fun mode(target: Target): String {
        val raw = ConfigFiles.rules().getString("${target.path}.mode").orEmpty().trim().lowercase(Locale.ROOT)
        return when (raw) {
            "黑名单", "blacklist", "clean", "清理" -> "黑名单"
            "并行名单", "parallel", "并行" -> if (target.supportsParallel) "并行名单" else "白名单"
            "白名单", "whitelist", "keep", "保留" -> "白名单"
            else -> defaultMode(target)
        }
    }

    fun listPath(target: Target, domain: ListDomain, kind: ListKind): String {
        return if (domain.segment == null) {
            "${target.path}.${kind.path}"
        } else {
            "${target.path}.${domain.segment}.${kind.path}"
        }
    }

    fun list(target: Target, domain: ListDomain, kind: ListKind): List<String> =
        ConfigFiles.rules().getStringList(listPath(target, domain, kind))

    fun matchMode(target: Target, domain: ListDomain, kind: ListKind): String {
        val path = matchModePath(target, domain, kind)
        val raw = ConfigFiles.rules().getString(path).orEmpty()
        return matchModes.firstOrNull { it == raw || modeAlias(it) == raw.lowercase(Locale.ROOT) } ?: "精确"
    }

    fun domainEnabled(target: Target, domain: ListDomain): Boolean {
        if (domain == ListDomain.MATERIAL || target != Target.ITEMS) return true
        return ConfigFiles.rules().getBoolean("${target.path}.${domain.segment}.enabled", true)
    }

    fun listTitle(domain: ListDomain, kind: ListKind): String {
        return when (domain) {
            ListDomain.MATERIAL -> kind.display
            ListDomain.NAME -> "展示名 · ${kind.display}"
            ListDomain.LORE -> "Lore · ${kind.display}"
        }
    }

    fun toggle(target: Target) {
        ConfigTextEditor.setScalar("${target.path}.enabled", (!enabled(target)).toString())
        Settings.load()
    }

    fun toggleDomain(target: Target, domain: ListDomain) {
        if (domain == ListDomain.MATERIAL || target != Target.ITEMS) return
        val path = "${target.path}.${domain.segment}.enabled"
        ConfigTextEditor.setScalar(path, (!domainEnabled(target, domain)).toString())
        Settings.load()
    }

    fun cycleMode(target: Target) {
        val modes = if (target.supportsParallel) listOf("黑名单", "白名单", "并行名单") else listOf("白名单", "黑名单")
        val current = modes.indexOf(mode(target))
        ConfigTextEditor.setScalar("${target.path}.mode", "\"${modes[(current + 1).mod(modes.size)]}\"")
        Settings.load()
    }

    fun cycleMatchMode(target: Target, domain: ListDomain, kind: ListKind) {
        val current = matchModes.indexOf(matchMode(target, domain, kind)).coerceAtLeast(0)
        ConfigTextEditor.setScalar(matchModePath(target, domain, kind), "\"${matchModes[(current + 1).mod(matchModes.size)]}\"")
        Settings.load()
    }

    fun add(target: Target, domain: ListDomain, kind: ListKind, item: ItemStack): AddResult {
        if (domain.itemsOnly && target != Target.ITEMS) {
            return AddResult.ItemsOnly
        }

        return when (domain.input) {
            InputType.MATERIAL -> addMaterialOrEntity(target, domain, kind, item)
            InputType.DISPLAY_NAME -> addDisplayName(target, domain, kind, item)
            InputType.LORE -> addLoreLines(target, domain, kind, item)
        }
    }

    fun remove(target: Target, domain: ListDomain, kind: ListKind, index: Int, expected: String? = null): Boolean {
        val values = list(target, domain, kind).toMutableList()
        if (index !in values.indices) return false
        if (expected != null && values[index] != expected) return false
        values.removeAt(index)
        ConfigTextEditor.setList(listPath(target, domain, kind), values)
        Settings.load()
        return true
    }

    fun displayItem(target: Target, domain: ListDomain, id: String): ItemStack {
        if (domain.input == InputType.DISPLAY_NAME || domain.input == InputType.LORE) {
            val material = Material.matchMaterial("PAPER") ?: Material.STONE
            val stack = ItemStack(material)
            val meta = stack.itemMeta
            if (meta != null) {
                meta.setDisplayName(id)
                stack.itemMeta = meta
            }
            return stack
        }

        if (target.entityInput) {
            val exactId = id.substringAfter(':', id).uppercase(Locale.ROOT)
            val material = if (id.startsWith("minecraft:") && !id.contains('*') && !id.contains('?')) {
                Material.matchMaterial("${exactId}_SPAWN_EGG") ?: Material.matchMaterial("MONSTER_EGG")
            } else {
                Material.matchMaterial(exactId)
            } ?: Material.matchMaterial("PAPER") ?: Material.STONE
            return ItemStack(material)
        }

        val (base, cmd) = ItemIdentity.parseRuleId(id)
        val exactId = base.substringAfter(':', base).uppercase(Locale.ROOT)
        val material = Material.matchMaterial(exactId)
            ?: Material.matchMaterial("PAPER")
            ?: Material.STONE
        val stack = ItemStack(material)
        if (cmd != null) {
            ItemIdentity.applyCustomModelData(stack, cmd)
        }
        return stack
    }

    private fun addMaterialOrEntity(target: Target, domain: ListDomain, kind: ListKind, item: ItemStack): AddResult {
        val id = if (target.entityInput) entityId(item) else ItemIdentity.ruleId(item)
        if (id == null) return AddResult.InvalidEntityItem
        return addSingle(target, domain, kind, id)
    }

    private fun addDisplayName(target: Target, domain: ListDomain, kind: ListKind, item: ItemStack): AddResult {
        val raw = ItemText.displayName(item) ?: return AddResult.NoDisplayName
        val plain = ItemText.normalize(raw, ItemText.ColorMode.STRIP)
        if (plain.isEmpty()) return AddResult.NoDisplayName
        return addSingle(target, domain, kind, plain)
    }

    private fun addLoreLines(target: Target, domain: ListDomain, kind: ListKind, item: ItemStack): AddResult {
        val lines = ItemText.loreLines(item)
            .map { ItemText.normalize(it, ItemText.ColorMode.STRIP) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (lines.isEmpty()) return AddResult.NoLore

        val path = listPath(target, domain, kind)
        val values = list(target, domain, kind).toMutableList()
        var added = 0
        for (line in lines) {
            if (values.any { it.equals(line, ignoreCase = true) }) continue
            values.add(line)
            added++
        }
        if (added == 0) return AddResult.Duplicate
        ConfigTextEditor.setList(path, values)
        Settings.load()
        return if (added == 1) AddResult.Added else AddResult.AddedMulti(added)
    }

    private fun addSingle(target: Target, domain: ListDomain, kind: ListKind, value: String): AddResult {
        val path = listPath(target, domain, kind)
        val values = list(target, domain, kind).toMutableList()
        if (values.any { it.equals(value, ignoreCase = true) }) return AddResult.Duplicate
        values.add(value)
        ConfigTextEditor.setList(path, values)
        Settings.load()
        return AddResult.Added
    }

    private fun matchModePath(target: Target, domain: ListDomain, kind: ListKind): String {
        return listPath(target, domain, kind).removeSuffix(".list") + ".match-mode"
    }

    private fun modeAlias(mode: String): String {
        return when (mode) {
            "精确" -> "exact"
            "通配" -> "wildcard"
            else -> "regex"
        }
    }

    private fun entityId(item: ItemStack): String? {
        val name = item.type.name
        if (name.endsWith("_SPAWN_EGG")) {
            return "minecraft:${name.removeSuffix("_SPAWN_EGG").lowercase(Locale.ROOT)}"
        }
        if (name != "MONSTER_EGG") return null
        return SpawnEggBridge.spawnedTypeName(item)?.let { "minecraft:$it" }
    }

    private fun defaultMode(target: Target): String = if (target == Target.REALTIME) "白名单" else "黑名单"

    sealed class AddResult {
        object Added : AddResult()
        data class AddedMulti(val count: Int) : AddResult()
        object Duplicate : AddResult()
        object InvalidEntityItem : AddResult()
        object NoDisplayName : AddResult()
        object NoLore : AddResult()
        object ItemsOnly : AddResult()
    }
}

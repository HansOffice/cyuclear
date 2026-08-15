package org.cyuCBMclean.cyuclear.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.platform.ContainerContentsBridge
import org.cyuCBMclean.cyuclear.util.IdMatcher
import org.cyuCBMclean.cyuclear.util.ItemIdentity
import org.cyuCBMclean.cyuclear.util.ItemText
import java.util.Locale

object BinEntryRules {
    enum class Source {
        CLEANUP_RECOVERY,
        PLAYER_DEPOSIT
    }

    enum class DefaultAction {
        ALLOW,
        DENY,
        INHERIT
    }

    data class Decision(
        val allowed: Boolean,
        val reason: String,
        val area: String? = null
    )

    class Policy internal constructor(
        private val defaultAction: DefaultAction,
        private val allowIds: IdMatcher,
        private val allowIdsPresent: Boolean,
        private val denyIds: IdMatcher,
        private val denyIdsPresent: Boolean,
        private val nameRules: TextRules,
        private val loreRules: TextRules
    ) {
        val unrestricted: Boolean
            get() = defaultAction == DefaultAction.ALLOW &&
                !allowIdsPresent && !denyIdsPresent && !nameRules.active && !loreRules.active

        internal fun decide(stack: ItemStack, ids: Collection<String>, prefix: String): Decision? {
            if (allowIdsPresent && ids.any(allowIds::matches)) return Decision(true, "$prefix 允许名单")
            if (nameRules.matchesAllow(stack)) return Decision(true, "$prefix 展示名允许名单")
            if (loreRules.matchesAllow(stack)) return Decision(true, "$prefix Lore 允许名单")
            if (denyIdsPresent && ids.any(denyIds::matches)) return Decision(false, "$prefix 禁止名单")
            if (nameRules.matchesDeny(stack)) return Decision(false, "$prefix 展示名禁止名单")
            if (loreRules.matchesDeny(stack)) return Decision(false, "$prefix Lore 禁止名单")
            return when (defaultAction) {
                DefaultAction.ALLOW -> Decision(true, "$prefix 默认允许")
                DefaultAction.DENY -> Decision(false, "$prefix 默认拒绝")
                DefaultAction.INHERIT -> null
            }
        }
    }

    internal class TextRules(
        val active: Boolean,
        private val colorMode: ItemText.ColorMode,
        private val lineMode: ItemText.LineMode,
        private val lore: Boolean,
        private val allowAny: IdMatcher,
        private val allowAll: List<IdMatcher>,
        private val denyAny: IdMatcher,
        private val denyAll: List<IdMatcher>
    ) {
        fun matchesAllow(stack: ItemStack): Boolean = matches(stack, allowAny, allowAll)

        fun matchesDeny(stack: ItemStack): Boolean = matches(stack, denyAny, denyAll)

        private fun matches(stack: ItemStack, any: IdMatcher, all: List<IdMatcher>): Boolean {
            if (!active) return false
            val raw = if (lore) ItemText.loreLines(stack) else listOfNotNull(ItemText.displayName(stack))
            val texts = raw.map { ItemText.normalize(it, colorMode) }.filter { it.isNotEmpty() }
            if (texts.isEmpty()) return false
            return when (lineMode) {
                ItemText.LineMode.ANY -> texts.any(any::matches)
                ItemText.LineMode.ALL -> all.isNotEmpty() && all.all { matcher -> texts.any(matcher::matches) }
            }
        }
    }

    @Volatile
    private var cleanupRecovery = allowPolicy()

    @Volatile
    private var playerDeposit = allowPolicy()

    @Volatile
    var playerDepositEnabled: Boolean = true
        private set

    @Volatile
    var playerDepositPermission: String = "cyuclear.bin.deposit"
        private set

    @Volatile
    var playerDepositMaxUniqueItems: Int = 2048
        private set

    @Volatile
    private var protectCleanupKeptItems: Boolean = false

    @Volatile
    private var denyNonEmptyContainers: Boolean = false

    fun load(config: YamlConfiguration) {
        cleanupRecovery = loadPolicy(
            config.getConfigurationSection("void-bin.entry-rules.cleanup-recovery"),
            "void-bin.entry-rules.cleanup-recovery",
            DefaultAction.ALLOW
        )
        val playerPath = "void-bin.entry-rules.player-deposit"
        val playerSection = config.getConfigurationSection(playerPath)
        playerDeposit = loadPolicy(playerSection, playerPath, DefaultAction.ALLOW)
        playerDepositEnabled = playerSection?.getBoolean("enabled", true) ?: true
        playerDepositPermission = if (playerSection?.contains("permission") == true) {
            playerSection.getString("permission").orEmpty().trim()
        } else {
            "cyuclear.bin.deposit"
        }
        playerDepositMaxUniqueItems = (playerSection?.getInt("max-unique-items", 2048) ?: 2048).coerceIn(0, 100000)
        protectCleanupKeptItems = playerSection?.getBoolean("protect-cleanup-kept-items", false) ?: false
        denyNonEmptyContainers = playerSection?.getBoolean("deny-non-empty-containers", false) ?: false
    }

    fun loadAreaPolicy(section: ConfigurationSection?, path: String): Policy? {
        if (section == null) return null
        return loadPolicy(section, path, DefaultAction.INHERIT)
    }

    fun evaluate(
        stack: ItemStack,
        source: Source,
        world: String,
        x: Int,
        y: Int,
        z: Int,
        knownIds: Collection<String> = emptyList()
    ): Decision {
        if (source == Source.PLAYER_DEPOSIT) {
            if (!playerDepositEnabled) return Decision(false, "玩家投放已关闭")
            if (protectCleanupKeptItems && isExplicitlyProtectedByCleanup(stack, knownIds)) {
                return Decision(false, "清理保留名单保护")
            }
            if (denyNonEmptyContainers && isNonEmptyContainer(stack)) {
                return Decision(false, "非空容器保护")
            }
        }

        val global = if (source == Source.CLEANUP_RECOVERY) cleanupRecovery else playerDeposit
        if (!AreaRules.hasBinEntryRules() && global.unrestricted) return Decision(true, "默认允许")

        val ids = if (knownIds.isEmpty()) ItemIdentity.matchIds(stack) else knownIds
        if (AreaRules.hasBinEntryRules()) {
            val area = AreaRules.find(world, x, y, z)
            val areaPolicy = area?.binPolicy(source)
            if (area != null && areaPolicy != null) {
                val decision = areaPolicy.decide(stack, ids, "区域 ${area.name}")
                if (decision != null) return decision.copy(area = area.name)
            }
        }
        return global.decide(stack, ids, "全局") ?: Decision(true, "全局默认允许")
    }

    private fun isExplicitlyProtectedByCleanup(stack: ItemStack, knownIds: Collection<String>): Boolean {
        val ids = if (knownIds.isEmpty()) ItemIdentity.matchIds(stack) else knownIds
        if (ids.any(Settings.itemProtectMatcher::matches)) return true

        val nameRules = Settings.itemNameRules
        if (nameRules.hasRules) {
            val rawName = ItemText.displayName(stack)
            val names = rawName?.let { listOf(ItemText.normalize(it, nameRules.colorMode)) }.orEmpty()
                .filter { it.isNotEmpty() }
            if (nameRules.canProtect(names, rawName != null)) return true
        }

        val loreRules = Settings.itemLoreRules
        if (loreRules.hasRules) {
            val lore = ItemText.loreLines(stack)
                .map { ItemText.normalize(it, loreRules.colorMode) }
                .filter { it.isNotEmpty() }
            if (loreRules.canProtect(lore, hasCustomName = false)) return true
        }
        return false
    }

    private fun isNonEmptyContainer(stack: ItemStack): Boolean = ContainerContentsBridge.isNonEmpty(stack)

    private fun loadPolicy(
        section: ConfigurationSection?,
        path: String,
        fallback: DefaultAction
    ): Policy {
        if (section == null) return if (fallback == DefaultAction.ALLOW) allowPolicy() else inheritPolicy()
        val defaultAction = parseDefault(section.getString("default"), "$path.default", fallback)
        val allow = loadMatcher(section, "allow-list", "$path.allow-list")
        val deny = loadMatcher(section, "deny-list", "$path.deny-list")
        return Policy(
            defaultAction = defaultAction,
            allowIds = allow.first,
            allowIdsPresent = allow.second,
            denyIds = deny.first,
            denyIdsPresent = deny.second,
            nameRules = loadTextRules(section.getConfigurationSection("name-rules"), "$path.name-rules", false),
            loreRules = loadTextRules(section.getConfigurationSection("lore-rules"), "$path.lore-rules", true)
        )
    }

    private fun loadTextRules(section: ConfigurationSection?, path: String, lore: Boolean): TextRules {
        if (section == null || !section.getBoolean("enabled", false)) return emptyTextRules(lore)
        val colorMode = ItemText.parseColorMode(section.getString("color-mode"))
        val lineMode = if (lore) ItemText.parseLineMode(section.getString("line-mode")) else ItemText.LineMode.ANY
        val allow = loadTextMatcher(section, "allow-list", "$path.allow-list", colorMode, lineMode)
        val deny = loadTextMatcher(section, "deny-list", "$path.deny-list", colorMode, lineMode)
        return TextRules(
            active = allow.third || deny.third,
            colorMode = colorMode,
            lineMode = lineMode,
            lore = lore,
            allowAny = allow.first,
            allowAll = allow.second,
            denyAny = deny.first,
            denyAll = deny.second
        )
    }

    private fun loadTextMatcher(
        section: ConfigurationSection,
        key: String,
        path: String,
        colorMode: ItemText.ColorMode,
        lineMode: ItemText.LineMode
    ): Triple<IdMatcher, List<IdMatcher>, Boolean> {
        val entries = readList(section, key)
        val prepared = ItemText.prepareRuleEntries(entries, colorMode)
        if (prepared.isEmpty()) return Triple(IdMatcher.empty(), emptyList(), false)
        val mode = readMatchMode(section.getConfigurationSection(key)?.getString("match-mode"))
        val any = IdMatcher.compile(mode, prepared, "$path.list", Cyuclear.instance.logger::warning)
        val all = if (lineMode == ItemText.LineMode.ALL) {
            prepared.mapIndexed { index, entry ->
                IdMatcher.compile(mode, listOf(entry), "$path.list[$index]", Cyuclear.instance.logger::warning)
            }
        } else {
            emptyList()
        }
        return Triple(any, all, true)
    }

    private fun loadMatcher(
        section: ConfigurationSection,
        key: String,
        path: String
    ): Pair<IdMatcher, Boolean> {
        val entries = readList(section, key).map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return IdMatcher.empty() to false
        val mode = readMatchMode(section.getConfigurationSection(key)?.getString("match-mode"))
        return IdMatcher.compile(mode, entries, "$path.list", Cyuclear.instance.logger::warning) to true
    }

    private fun readList(section: ConfigurationSection, key: String): List<String> {
        val nested = section.getConfigurationSection(key)
        return if (nested == null) section.getStringList(key) else nested.getStringList("list")
    }

    private fun readMatchMode(raw: String?): Settings.MatchMode {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "通配", "wildcard", "glob" -> Settings.MatchMode.WILDCARD
            "正则", "regex", "regexp", "re" -> Settings.MatchMode.REGEX
            else -> Settings.MatchMode.EXACT
        }
    }

    private fun parseDefault(raw: String?, path: String, fallback: DefaultAction): DefaultAction {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            null, "" -> fallback
            "允许", "allow", "accept" -> DefaultAction.ALLOW
            "拒绝", "deny", "block" -> DefaultAction.DENY
            "继承", "inherit" -> DefaultAction.INHERIT
            else -> {
                Cyuclear.instance.logger.warning("Cyuclear 在 $path 读取到未知值 '$raw'，已回退为${display(fallback)}")
                fallback
            }
        }
    }

    private fun display(action: DefaultAction): String {
        return when (action) {
            DefaultAction.ALLOW -> "允许"
            DefaultAction.DENY -> "拒绝"
            DefaultAction.INHERIT -> "继承"
        }
    }

    private fun allowPolicy(): Policy = Policy(
        DefaultAction.ALLOW,
        IdMatcher.empty(),
        false,
        IdMatcher.empty(),
        false,
        emptyTextRules(false),
        emptyTextRules(true)
    )

    private fun inheritPolicy(): Policy = Policy(
        DefaultAction.INHERIT,
        IdMatcher.empty(),
        false,
        IdMatcher.empty(),
        false,
        emptyTextRules(false),
        emptyTextRules(true)
    )

    private fun emptyTextRules(lore: Boolean): TextRules = TextRules(
        false,
        ItemText.ColorMode.STRIP,
        ItemText.LineMode.ANY,
        lore,
        IdMatcher.empty(),
        emptyList(),
        IdMatcher.empty(),
        emptyList()
    )
}

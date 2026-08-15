package org.cyuCBMclean.cyuclear.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.platform.NoticeBossBarColor
import org.cyuCBMclean.cyuclear.platform.NoticeBossBarStyle
import org.cyuCBMclean.cyuclear.service.CleanupOrigin
import org.cyuCBMclean.cyuclear.service.EntityDetailRules
import org.cyuCBMclean.cyuclear.service.RuleEngine
import org.cyuCBMclean.cyuclear.util.IdMatcher
import org.cyuCBMclean.cyuclear.util.ItemText

object Settings {

    private lateinit var config: YamlConfiguration

    enum class FilterMode {
        BLACKLIST,
        WHITELIST
    }

    enum class DefaultAction {
        CLEAN,
        KEEP
    }

    enum class MatchMode {
        EXACT,
        WILDCARD,
        REGEX
    }

    enum class ChunkEntityLimitMode {
        OFF,
        SAFE,
        STRICT
    }

    enum class OverloadNoticeTarget {
        NONE,
        ADMINS,
        ALL
    }

    enum class ClusterStorageType {
        REDIS,
        MYSQL
    }

    data class SoundSetting(
        val enabled: Boolean,
        val sound: String,
        val volume: Float,
        val pitch: Float
    )

    data class NamedBypassRegion(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int
    ) {
        fun contains(x: Int, y: Int, z: Int): Boolean {
            return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
        }
    }

    var enabled: Boolean = false
        private set
    var intervalSeconds: Int = 600
        private set
    var warningTimes: List<Int> = emptyList()
        private set
    var cleanupWarningChatEnabled: Boolean = true
        private set
    var cleanupWarningActionBarEnabled: Boolean = false
        private set
    var cleanupWarningBossBarEnabled: Boolean = false
        private set
    var cleanupWarningBossBarColor: NoticeBossBarColor = NoticeBossBarColor.BLUE
        private set
    var cleanupWarningBossBarStyle: NoticeBossBarStyle = NoticeBossBarStyle.SOLID
        private set
    var cleanupChunksPerTick: Int = 10
        private set
    var performanceProfile: String = "快速"
        private set
    var scanMaxChunksPerTick: Int = 240
        private set
    var scanMaxMillisPerTick: Long = 7L
        private set
    var foliaMaxActiveRegionTasks: Int = 512
        private set
    var foliaDispatchChunksPerTick: Int = 512
        private set
    var cleanupDetailStats: Boolean = true
        private set
    var cleanupStageTimings: Boolean = false
        private set
    var candidateIndexEnabled: Boolean = true
        private set
    var candidateFullScanEveryCycles: Long = 6L
        private set
    var configVersion: Int = 0
        private set
    var auditEnabled: Boolean = true
        private set
    var auditMaxLines: Int = 30
        private set
    var recoveryEnabled: Boolean = false
        private set
    var recoveryScheduled: Boolean = true
        private set
    var recoveryManual: Boolean = true
        private set
    var recoveryPanic: Boolean = false
        private set
    var recoveryMaxEntriesPerRun: Int = 256
        private set
    var recoveryExpireHours: Long = 72L
        private set
    var recoveryRecentLimit: Int = 50
        private set

    var clusterEnabled: Boolean = false
        private set
    var clusterId: String = "default"
        private set
    var clusterServerId: String = ""
        private set
    var clusterStorageType: ClusterStorageType = ClusterStorageType.REDIS
        private set
    var clusterStorageValid: Boolean = true
        private set
    var clusterRedisHost: String = "127.0.0.1"
        private set
    var clusterRedisPort: Int = 6379
        private set
    var clusterRedisUsername: String = ""
        private set
    var clusterRedisPassword: String = ""
        private set
    var clusterRedisDatabase: Int = 0
        private set
    var clusterRedisSsl: Boolean = false
        private set
    var clusterConnectTimeoutMillis: Int = 3000
        private set
    var clusterSocketTimeoutMillis: Int = 3000
        private set
    var clusterMysqlHost: String = "127.0.0.1"
        private set
    var clusterMysqlPort: Int = 3306
        private set
    var clusterMysqlDatabase: String = "minecraft"
        private set
    var clusterMysqlUsername: String = "root"
        private set
    var clusterMysqlPassword: String = ""
        private set
    var clusterMysqlSsl: Boolean = false
        private set
    var clusterMysqlConnectTimeoutMillis: Int = 3000
        private set
    var clusterMysqlSocketTimeoutMillis: Int = 3000
        private set
    var clusterMysqlTablePrefix: String = "cyuclear_"
        private set
    var clusterHeartbeatSeconds: Int = 1
        private set
    var clusterMemberTimeoutSeconds: Int = 20
        private set
    var chunkItemThreshold: Int = 1600
        private set
    var chunkItemSoftThreshold: Int = 1000
        private set
    var itemSpecificThresholds: Map<String, Int> = emptyMap()
        private set
    var chunkEntityThreshold: Int = 500
        private set
    var chunkEntitySoftThreshold: Int = 300
        private set
    var chunkEntityLimitMode: ChunkEntityLimitMode = ChunkEntityLimitMode.SAFE
        private set
    var chunkEntitySpawnWindowMillis: Long = 1000L
        private set
    var entitySpecificThresholds: Map<String, Int> = emptyMap()
        private set
    var hasMythicEntitySpecificThresholds: Boolean = false
        private set
    var overloadNoticeTarget: OverloadNoticeTarget = OverloadNoticeTarget.ADMINS
        private set
    var overloadNoticeCooldownMillis: Long = 60000L
        private set
    var limitCountCacheMillis: Long = 250L
        private set
    var limitOverloadCacheMillis: Long = 2000L
        private set
    var hotspotEnabled: Boolean = true
        private set
    var hotspotRetentionMillis: Long = 300000L
        private set
    var hotspotMaxRecords: Int = 80
        private set

    var binEnabled: Boolean = true
        private set
    var binAlwaysOpen: Boolean = false
        private set
    var binStackedMode: Boolean = true
        private set
    var voidBinExpireSeconds: Int = 60
        private set
    var binWarningTimes: List<Int> = emptyList()
        private set
    var binWarningChatEnabled: Boolean = true
        private set
    var binWarningActionBarEnabled: Boolean = false
        private set
    var binWarningBossBarEnabled: Boolean = false
        private set
    var binWarningBossBarColor: NoticeBossBarColor = NoticeBossBarColor.RED
        private set
    var binWarningBossBarStyle: NoticeBossBarStyle = NoticeBossBarStyle.SOLID
        private set
    var binDepositFeedbackEnabled: Boolean = true
        private set
    var binDepositBufferEnabled: Boolean = true
        private set
    var binOpenClickEnabled: Boolean = true
        private set
    var binOpenClickCommand: String = "/cc bin"
        private set
    var binClaimCooldownEnabled: Boolean = true
        private set
    var binClaimCooldownSeconds: Int = 10
        private set
    var binClaimCooldownBypassPermission: String = "cyuclear.admin"
        private set
    var binClaimAuditEnabled: Boolean = true
        private set
    var emergencyChunkLoadSweepEnabled: Boolean = false
        private set
    var emergencyChunkUnloadSweepEnabled: Boolean = false
        private set

    var soundEnabled: Boolean = false
        private set
    private var soundSettings: Map<String, SoundSetting> = emptyMap()

    var itemModuleEnabled: Boolean = true
        private set
    var itemDefaultAction: DefaultAction = DefaultAction.CLEAN
        private set
    var itemListModeName: String = "黑名单"
        private set
    var itemProtectMatcher: IdMatcher = IdMatcher.empty()
        private set
    var itemCleanMatcher: IdMatcher = IdMatcher.empty()
        private set
    var itemFilterMode: FilterMode = FilterMode.BLACKLIST
        private set
    var itemMatchMode: MatchMode = MatchMode.EXACT
        private set
    var itemMatcher: IdMatcher = IdMatcher.empty()
        private set
    var itemMinimumAgeTicks: Int = 200
        private set
    var itemHighValueMinimumAgeTicks: Int = 1200
        private set
    var itemHighValueMatcher: IdMatcher = IdMatcher.empty()
        private set
    var itemNameRules: ItemText.RuleSet = ItemText.emptyRules()
        private set
    var itemLoreRules: ItemText.RuleSet = ItemText.emptyRules()
        private set
    @Volatile
    var filterRevision: Long = 0L
        private set

    var entityModuleEnabled: Boolean = true
        private set
    var entityDefaultAction: DefaultAction = DefaultAction.CLEAN
        private set
    var entityListModeName: String = "黑名单"
        private set
    var entityProtectMatcher: IdMatcher = IdMatcher.empty()
        private set
    var entityCleanMatcher: IdMatcher = IdMatcher.empty()
        private set
    var entityFilterMode: FilterMode = FilterMode.BLACKLIST
        private set
    var entityMatchMode: MatchMode = MatchMode.EXACT
        private set
    var entityRealtimeCleanupEnabled: Boolean = false
        private set
    var entityRealtimeDefaultAction: DefaultAction = DefaultAction.KEEP
        private set
    var entityRealtimeListModeName: String = "白名单"
        private set
    var entityRealtimeProtectMatcher: IdMatcher = IdMatcher.empty()
        private set
    var entityRealtimeCleanMatcher: IdMatcher = IdMatcher.empty()
        private set
    var entityRealtimeFilterMode: FilterMode = FilterMode.WHITELIST
        private set
    var entityRealtimeMatchMode: MatchMode = MatchMode.EXACT
        private set
    var entityRealtimeMatcher: IdMatcher = IdMatcher.empty()
        private set
    var entityMatcher: IdMatcher = IdMatcher.empty()
        private set
    var entityNameRules: ItemText.RuleSet = ItemText.emptyRules()
        private set
    var entityNameCleanBypasses: Set<EntityDetailRules.Protection> = emptySet()
        private set
    var entityDetailRules: List<EntityDetailRules.Rule> = emptyList()
        private set
    var namedRules: RuleEngine.Rules = RuleEngine.empty()
        private set
    var entityDetailUsesPokemon: Boolean = false
        private set
    var entityDetailRequiresFullPokemon: Boolean = false
        private set
    var entityDetailUsesMythic: Boolean = false
        private set
    var entityIgnoreNamed: Boolean = true
        private set
    var entityNamedBypassMatcher: IdMatcher = IdMatcher.empty()
        private set
    private var entityNamedBypassRegions: Map<String, List<NamedBypassRegion>> = emptyMap()
    var entityIgnoreTamed: Boolean = true
        private set
    var entityIgnorePersistent: Boolean = false
        private set
    var entityIgnoreNoDespawn: Boolean = true
        private set
    var entityProtectRaidEvent: Boolean = true
        private set
    var entityMythicEnabled: Boolean = true
        private set
    var entityMythicIdOnly: Boolean = false
        private set
    var entityMythicBypassProtectionFlags: Boolean = false
        private set
    var mythicExcludeFromChunkLimit: Boolean = false
        private set
    var mythicExcludeFromPanicCount: Boolean = false
        private set
    var entityCraftEngineEnabled: Boolean = true
        private set
    var entityCraftEngineProtectFurniture: Boolean = true
        private set
    var entityCraftEngineIdOnly: Boolean = false
        private set
    var craftEngineExcludeFromChunkLimit: Boolean = true
        private set
    var craftEngineExcludeFromPanicCount: Boolean = true
        private set
    var entityPokemonEnabled: Boolean = true
        private set
    var entityPokemonIgnorePlayerOwned: Boolean = true
        private set
    var entityPokemonRulesPresent: Boolean = false
        private set
    var entityPokemonFullRulesRequired: Boolean = false
        private set
    var entityPokemonLightRules: Set<String> = emptySet()
        private set
    var entityRealtimePokemonRulesPresent: Boolean = false
        private set
    var entityRealtimePokemonFullRulesRequired: Boolean = false
        private set
    var entityRealtimePokemonLightRules: Set<String> = emptySet()
        private set
    var entityMythicRulesPresent: Boolean = false
        private set
    var entityRealtimeMythicRulesPresent: Boolean = false
        private set

    var worldMode: String = "blacklist"
        private set
    var worldList: Set<String> = emptySet()
        private set

    var panicEnabled: Boolean = false
        private set
    var panicNoticeTarget: OverloadNoticeTarget = OverloadNoticeTarget.ALL
        private set
    var maxGlobalEntities: Int = 5000
        private set
    var panicCheckIntervalMillis: Long = 15_000L
        private set

    fun load() {
        config = ConfigFiles.load()
        AreaRules.load(config)
        val targetRules = TargetRuleLoader(config, Cyuclear.instance.logger::warning)

        configVersion = config.getInt("config-version", 0)
        enabled = config.getBoolean("enabled", false)

        clusterEnabled = config.getBoolean("cluster.enabled", false)
        clusterId = config.getString("cluster.id", "default")?.trim().orEmpty().ifBlank { "default" }
        clusterServerId = config.getString("cluster.server-id", "")?.trim().orEmpty()
        val clusterStorageName = config.getString("cluster.storage", "redis")?.trim()?.lowercase()
        clusterStorageValid = clusterStorageName == "redis" || clusterStorageName == "mysql"
        clusterStorageType = if (clusterStorageName == "mysql") ClusterStorageType.MYSQL else ClusterStorageType.REDIS
        clusterRedisHost = config.getString("cluster.redis.host", "127.0.0.1")?.trim().orEmpty()
        clusterRedisPort = config.getInt("cluster.redis.port", 6379).coerceIn(1, 65535)
        clusterRedisUsername = config.getString("cluster.redis.username", "")?.trim().orEmpty()
        clusterRedisPassword = config.getString("cluster.redis.password", "").orEmpty()
        clusterRedisDatabase = config.getInt("cluster.redis.database", 0).coerceIn(0, 15)
        clusterRedisSsl = config.getBoolean("cluster.redis.ssl", false)
        clusterConnectTimeoutMillis = config.getInt("cluster.redis.connect-timeout-millis", 3000).coerceIn(500, 30000)
        clusterSocketTimeoutMillis = config.getInt("cluster.redis.socket-timeout-millis", 3000).coerceIn(500, 30000)
        clusterMysqlHost = config.getString("cluster.mysql.host", "127.0.0.1")?.trim().orEmpty()
        clusterMysqlPort = config.getInt("cluster.mysql.port", 3306).coerceIn(1, 65535)
        clusterMysqlDatabase = config.getString("cluster.mysql.database", "minecraft")?.trim().orEmpty()
        clusterMysqlUsername = config.getString("cluster.mysql.username", "root")?.trim().orEmpty()
        clusterMysqlPassword = config.getString("cluster.mysql.password", "").orEmpty()
        clusterMysqlSsl = config.getBoolean("cluster.mysql.ssl", false)
        clusterMysqlConnectTimeoutMillis = config.getInt("cluster.mysql.connect-timeout-millis", 3000).coerceIn(500, 30000)
        clusterMysqlSocketTimeoutMillis = config.getInt("cluster.mysql.socket-timeout-millis", 3000).coerceIn(500, 30000)
        clusterMysqlTablePrefix = config.getString("cluster.mysql.table-prefix", "cyuclear_")?.trim().orEmpty()
        clusterHeartbeatSeconds = config.getInt("cluster.heartbeat-seconds", 1).coerceIn(1, 5)
        clusterMemberTimeoutSeconds = config.getInt("cluster.member-timeout-seconds", 20)
            .coerceIn(clusterHeartbeatSeconds * 3, 120)
        intervalSeconds = config.getInt("cleanup.interval-seconds", 600).coerceAtLeast(10)
        warningTimes = config.getIntegerList("cleanup.warning-times")
        cleanupWarningChatEnabled = config.getBoolean("cleanup.warning-output.chat", true)
        cleanupWarningActionBarEnabled = config.getBoolean("cleanup.warning-output.actionbar", false)
        cleanupWarningBossBarEnabled = config.getBoolean("cleanup.warning-output.bossbar", false)
        cleanupWarningBossBarColor = parseBossBarColor(
            config.getString("cleanup.warning-output.bossbar-color"),
            "cleanup.warning-output.bossbar-color",
            NoticeBossBarColor.BLUE
        )
        cleanupWarningBossBarStyle = parseBossBarStyle(
            config.getString("cleanup.warning-output.bossbar-style"),
            "cleanup.warning-output.bossbar-style",
            NoticeBossBarStyle.SOLID
        )
        cleanupChunksPerTick = config.getInt("cleanup.chunks-per-tick", 10).coerceIn(1, 500)
        loadPerformance()
        recoveryEnabled = config.getBoolean("recovery.enabled", false)
        recoveryScheduled = config.getBoolean("recovery.capture.scheduled", true)
        recoveryManual = config.getBoolean("recovery.capture.manual", true)
        recoveryPanic = config.getBoolean("recovery.capture.panic", false)
        recoveryMaxEntriesPerRun = config.getInt("recovery.max-entries-per-run", 256).coerceIn(0, 4096)
        recoveryExpireHours = config.getLong("recovery.expire-hours", 72L).coerceIn(1L, 720L)
        recoveryRecentLimit = config.getInt("recovery.recent-limit", 50).coerceIn(10, 200)

        chunkItemThreshold = getInt("limits.chunk.items.threshold", "limits.chunk-item-threshold", 1600).coerceAtLeast(0)
        chunkItemSoftThreshold = getInt("limits.chunk.items.soft-threshold", "limits.chunk-item-soft-threshold", 1000)
            .coerceIn(0, chunkItemThreshold.coerceAtLeast(0))
        itemSpecificThresholds = loadSpecificThresholds("limits.chunk.items.specific", "limits.item-thresholds")
        chunkEntityThreshold = getInt("limits.chunk.entities.threshold", "limits.chunk-entity-threshold", 500).coerceAtLeast(0)
        chunkEntitySoftThreshold = getInt("limits.chunk.entities.soft-threshold", "limits.chunk-entity-soft-threshold", 300)
            .coerceIn(0, chunkEntityThreshold.coerceAtLeast(0))
        val chunkEntityLimitModePath = firstExistingPath("limits.chunk.entities.mode", "limits.chunk-entity-mode")
        chunkEntityLimitMode = parseChunkEntityLimitMode(
            config.getString(chunkEntityLimitModePath),
            chunkEntityLimitModePath
        )
        chunkEntitySpawnWindowMillis = getLong(
            "limits.chunk.entities.spawn-window-millis",
            "limits.chunk-entity-spawn-window-millis",
            1000L
        )
            .coerceIn(100L, 10000L)
        entitySpecificThresholds = loadSpecificThresholds("limits.chunk.entities.specific", "limits.entity-thresholds")
        hasMythicEntitySpecificThresholds = entitySpecificThresholds.keys.any { it.startsWith("mythic:") }
        overloadNoticeTarget = parseOverloadNoticeTarget(
            config.getString(firstExistingPath("limits.chunk.notice-target", "limits.notice-target"))
        )
        overloadNoticeCooldownMillis = config.getLong(
            firstExistingPath("limits.chunk.notice-cooldown-seconds", "limits.notice-cooldown-seconds"),
            60L
        )
            .coerceIn(1L, 3600L) * 1000L
        limitCountCacheMillis = getLong("limits.chunk.count-cache-millis", "limits.count-cache-millis", 250L).coerceAtLeast(0L)
        limitOverloadCacheMillis = getLong("limits.chunk.overload-cache-millis", "limits.overload-cache-millis", 2000L).coerceAtLeast(0L)
        hotspotEnabled = config.getBoolean("limits.chunk.hotspot.enabled", true)
        hotspotRetentionMillis = config.getLong("limits.chunk.hotspot.retention-seconds", 300L).coerceIn(30L, 3600L) * 1000L
        hotspotMaxRecords = config.getInt("limits.chunk.hotspot.max-records", 80).coerceIn(10, 512)

        binEnabled = config.getBoolean("void-bin.enabled", true)
        binAlwaysOpen = config.getBoolean("void-bin.always-open", false)
        binStackedMode = config.getBoolean("void-bin.stacked-mode", true)
        voidBinExpireSeconds = config.getInt("void-bin.expire-seconds", 60)
        binWarningTimes = config.getIntegerList("void-bin.warning-times")
        binWarningChatEnabled = config.getBoolean("void-bin.warning-output.chat", true)
        binWarningActionBarEnabled = config.getBoolean("void-bin.warning-output.actionbar", false)
        binWarningBossBarEnabled = config.getBoolean("void-bin.warning-output.bossbar", false)
        binWarningBossBarColor = parseBossBarColor(
            config.getString("void-bin.warning-output.bossbar-color"),
            "void-bin.warning-output.bossbar-color",
            NoticeBossBarColor.RED
        )
        binWarningBossBarStyle = parseBossBarStyle(
            config.getString("void-bin.warning-output.bossbar-style"),
            "void-bin.warning-output.bossbar-style",
            NoticeBossBarStyle.SOLID
        )
        binDepositFeedbackEnabled = config.getBoolean("void-bin.deposit-feedback.enabled", true)
        binDepositBufferEnabled = config.getBoolean("void-bin.deposit-buffer.enabled", true)
        binOpenClickEnabled = config.getBoolean("void-bin.open-click.enabled", true)
        binOpenClickCommand = config.getString("void-bin.open-click.command", "/cc bin")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "/cc bin"
        binClaimCooldownEnabled = config.getBoolean("void-bin.claim-cooldown.enabled", true)
        binClaimCooldownSeconds = config.getInt("void-bin.claim-cooldown.seconds", 10).coerceAtLeast(0)
        binClaimCooldownBypassPermission = config.getString("void-bin.claim-cooldown.bypass-permission", "cyuclear.admin")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "cyuclear.admin"
        binClaimAuditEnabled = config.getBoolean("void-bin.claim-audit.enabled", true)
        emergencyChunkLoadSweepEnabled = config.getBoolean("emergency.chunk-load-sweep", false)
        emergencyChunkUnloadSweepEnabled = config.getBoolean("emergency.chunk-unload-sweep", false)
        namedRules = RuleEngine.load(config.getConfigurationSection("rules"), Cyuclear.instance.logger::warning)

        val hasNewItemRules = config.isConfigurationSection("targets.items")
        val graceBase = if (hasNewItemRules) "targets.items.grace" else "modules.items.grace"
        itemMinimumAgeTicks = config.getInt("$graceBase.minimum-age-seconds", 10).coerceIn(0, 3600) * 20
        itemHighValueMinimumAgeTicks = config.getInt("$graceBase.high-value-minimum-age-seconds", 60).coerceIn(0, 86400) * 20
        itemHighValueMatcher = IdMatcher.compile(
            MatchMode.EXACT,
            config.getStringList("$graceBase.high-value-list"),
            "$graceBase.high-value-list",
            Cyuclear.instance.logger::warning
        )
        itemModuleEnabled = getBoolean("targets.items.enabled", "modules.items.enabled", true)
        if (hasNewItemRules) {
            val actionPath = firstExistingPath("targets.items.mode", "targets.items.default-action")
            val rules = targetRules.loadTargetRules(
                basePath = "targets.items",
                fallbackAction = DefaultAction.CLEAN
            )
            itemDefaultAction = rules.defaultAction
            itemListModeName = targetRules.displayListMode(config.getString(actionPath), rules.defaultAction)
            itemProtectMatcher = rules.protectMatcher
            itemCleanMatcher = rules.cleanMatcher
            itemMatchMode = rules.protectMatchMode
            itemFilterMode = if (rules.defaultAction == DefaultAction.CLEAN) FilterMode.BLACKLIST else FilterMode.WHITELIST
            itemMatcher = if (rules.defaultAction == DefaultAction.CLEAN) rules.protectMatcher else rules.cleanMatcher
            itemNameRules = targetRules.loadItemTextRules("targets.items.name-rules", requireCustomNameDefault = true)
            itemLoreRules = targetRules.loadItemTextRules("targets.items.lore-rules", requireCustomNameDefault = false)
        } else {
            val legacy = targetRules.loadLegacyTargetRules(
                filterModePath = "modules.items.filter-mode",
                matchModePath = "modules.items.match-mode",
                listPath = "modules.items.list"
            )
            itemDefaultAction = legacy.defaultAction
            itemListModeName = if (legacy.filterMode == FilterMode.BLACKLIST) "黑名单" else "白名单"
            itemProtectMatcher = legacy.protectMatcher
            itemCleanMatcher = legacy.cleanMatcher
            itemFilterMode = legacy.filterMode
            itemMatchMode = legacy.matchMode
            itemMatcher = legacy.legacyMatcher
            itemNameRules = ItemText.emptyRules()
            itemLoreRules = ItemText.emptyRules()
        }

        val hasNewEntityRules = config.isConfigurationSection("targets.entities")
        entityModuleEnabled = getBoolean("targets.entities.enabled", "modules.entities.enabled", true)
        val entityRuleGroups: List<Pair<List<String>, MatchMode>>
        if (hasNewEntityRules) {
            val actionPath = firstExistingPath("targets.entities.mode", "targets.entities.default-action")
            val rules = targetRules.loadTargetRules(
                basePath = "targets.entities",
                fallbackAction = DefaultAction.CLEAN
            )
            entityDefaultAction = rules.defaultAction
            entityListModeName = targetRules.displayListMode(config.getString(actionPath), rules.defaultAction)
            entityProtectMatcher = rules.protectMatcher
            entityCleanMatcher = rules.cleanMatcher
            entityFilterMode = if (rules.defaultAction == DefaultAction.CLEAN) FilterMode.BLACKLIST else FilterMode.WHITELIST
            entityMatchMode = rules.protectMatchMode
            entityMatcher = if (rules.defaultAction == DefaultAction.CLEAN) rules.protectMatcher else rules.cleanMatcher
            entityRuleGroups = rules.ruleGroups
        } else {
            val legacy = targetRules.loadLegacyTargetRules(
                filterModePath = "modules.entities.filter-mode",
                matchModePath = "modules.entities.match-mode",
                listPath = "modules.entities.list"
            )
            entityDefaultAction = legacy.defaultAction
            entityListModeName = if (legacy.filterMode == FilterMode.BLACKLIST) "黑名单" else "白名单"
            entityProtectMatcher = legacy.protectMatcher
            entityCleanMatcher = legacy.cleanMatcher
            entityFilterMode = legacy.filterMode
            entityMatchMode = legacy.matchMode
            entityMatcher = legacy.legacyMatcher
            entityRuleGroups = legacy.ruleGroups
            warnIfLikelyEntityBlacklistMistake(legacy.rawEntries)
        }
        entityNameRules = if (hasNewEntityRules) {
            targetRules.loadItemTextRules("targets.entities.name-rules", requireCustomNameDefault = true)
        } else {
            ItemText.emptyRules()
        }
        entityNameCleanBypasses = if (hasNewEntityRules) {
            EntityDetailRules.parseBypasses(
                config.getStringList("targets.entities.name-rules.clean-bypasses"),
                "targets.entities.name-rules",
                Cyuclear.instance.logger::warning
            )
        } else {
            emptySet()
        }
        entityDetailRules = if (hasNewEntityRules) {
            EntityDetailRules.load(
                config.getConfigurationSection("targets.entities.detail-rules"),
                Cyuclear.instance.logger::warning
            )
        } else {
            emptyList()
        }
        entityDetailUsesPokemon = entityDetailRules.any { it.usesPokemon }
        entityDetailRequiresFullPokemon = entityDetailRules.any { it.requiresFullPokemon }
        entityDetailUsesMythic = entityDetailRules.any { it.usesMythic }

        val hasNewRealtimeRules = config.isConfigurationSection("limits.realtime")
        entityRealtimeCleanupEnabled = getBoolean("limits.realtime.enabled", "modules.entities.realtime.enabled", false)
        val realtimeRuleGroups: List<Pair<List<String>, MatchMode>>
        if (hasNewRealtimeRules) {
            val actionPath = firstExistingPath("limits.realtime.mode", "limits.realtime.default-action")
            val rules = targetRules.loadTargetRules(
                basePath = "limits.realtime",
                fallbackAction = DefaultAction.KEEP
            )
            entityRealtimeDefaultAction = rules.defaultAction
            entityRealtimeListModeName = targetRules.displayListMode(config.getString(actionPath), rules.defaultAction)
            entityRealtimeProtectMatcher = rules.protectMatcher
            entityRealtimeCleanMatcher = rules.cleanMatcher
            entityRealtimeFilterMode = if (rules.defaultAction == DefaultAction.CLEAN) FilterMode.BLACKLIST else FilterMode.WHITELIST
            entityRealtimeMatchMode = rules.cleanMatchMode
            entityRealtimeMatcher = if (rules.defaultAction == DefaultAction.CLEAN) rules.protectMatcher else rules.cleanMatcher
            realtimeRuleGroups = rules.ruleGroups
        } else {
            val legacy = targetRules.loadLegacyTargetRules(
                filterModePath = "modules.entities.realtime.filter-mode",
                matchModePath = "modules.entities.realtime.match-mode",
                listPath = "modules.entities.realtime.list",
                fallbackFilterMode = FilterMode.WHITELIST
            )
            entityRealtimeDefaultAction = legacy.defaultAction
            entityRealtimeListModeName = if (legacy.filterMode == FilterMode.BLACKLIST) "黑名单" else "白名单"
            entityRealtimeProtectMatcher = legacy.protectMatcher
            entityRealtimeCleanMatcher = legacy.cleanMatcher
            entityRealtimeFilterMode = legacy.filterMode
            entityRealtimeMatchMode = legacy.matchMode
            entityRealtimeMatcher = legacy.legacyMatcher
            realtimeRuleGroups = legacy.ruleGroups
        }

        entityIgnoreNamed = getBoolean("targets.entities.protections.named", "modules.entities.ignore-named", true)
        val namedBypassBase = if (hasNewEntityRules) {
            "targets.entities.protections.named-bypass"
        } else {
            "modules.entities.named-bypass"
        }
        entityNamedBypassMatcher = IdMatcher.compile(
            MatchMode.EXACT,
            config.getStringList("$namedBypassBase.entities"),
            "$namedBypassBase.entities",
            Cyuclear.instance.logger::warning
        )
        entityNamedBypassRegions = loadNamedBypassRegions("$namedBypassBase.regions")
        entityIgnoreTamed = getBoolean("targets.entities.protections.tamed", "modules.entities.ignore-tamed", true)
        entityIgnorePersistent = getBoolean("targets.entities.protections.persistent", "modules.entities.ignore-persistent", false)
        entityIgnoreNoDespawn = getBoolean("targets.entities.protections.no-despawn", "modules.entities.ignore-no-despawn", true)
        entityProtectRaidEvent = getBoolean("targets.entities.protections.events.raid", "modules.entities.protect-raid-event", true)
        entityPokemonIgnorePlayerOwned = getBoolean(
            "targets.entities.protections.player-owned-pokemon",
            "modules.entities.pokemon.ignore-player-owned",
            true
        )
        entityMythicEnabled = getBoolean("hooks.mythic-mobs.enabled", "modules.entities.mythic-mobs.enabled", true)
        entityMythicIdOnly = getBoolean("hooks.mythic-mobs.id-only", "modules.entities.mythic-mobs.id-only", false)
        entityMythicBypassProtectionFlags = getBoolean(
            "hooks.mythic-mobs.bypass-protection-flags",
            "modules.entities.mythic-mobs.bypass-protection-flags",
            false
        )
        mythicExcludeFromChunkLimit = getBoolean(
            "hooks.mythic-mobs.exclude-from-chunk-limit",
            "modules.entities.mythic-mobs.exclude-from-chunk-limit",
            false
        )
        mythicExcludeFromPanicCount = getBoolean(
            "hooks.mythic-mobs.exclude-from-panic-count",
            "modules.entities.mythic-mobs.exclude-from-panic-count",
            false
        )
        entityCraftEngineEnabled = config.getBoolean("hooks.craft-engine.enabled", true)
        entityCraftEngineProtectFurniture = config.getBoolean("hooks.craft-engine.protect-furniture", true)
        entityCraftEngineIdOnly = config.getBoolean("hooks.craft-engine.id-only", false)
        craftEngineExcludeFromChunkLimit = config.getBoolean("hooks.craft-engine.exclude-from-chunk-limit", true)
        craftEngineExcludeFromPanicCount = config.getBoolean("hooks.craft-engine.exclude-from-panic-count", true)
        entityPokemonEnabled = getBoolean("hooks.pokemon.enabled", "modules.entities.pokemon.enabled", true)
        entityPokemonRulesPresent = TargetRuleCapabilities.hasPokemonRules(entityRuleGroups) || namedRules.entityUsesPokemon
        entityPokemonFullRulesRequired = TargetRuleCapabilities.requiresFullPokemonRules(entityRuleGroups) || namedRules.entityRequiresFullPokemon
        entityPokemonLightRules = TargetRuleCapabilities.collectLightPokemonRules(entityRuleGroups) + namedRules.entityLightPokemonRules
        entityRealtimePokemonRulesPresent = TargetRuleCapabilities.hasPokemonRules(realtimeRuleGroups) || namedRules.entityUsesPokemon
        entityRealtimePokemonFullRulesRequired = TargetRuleCapabilities.requiresFullPokemonRules(realtimeRuleGroups) || namedRules.entityRequiresFullPokemon
        entityRealtimePokemonLightRules = TargetRuleCapabilities.collectLightPokemonRules(realtimeRuleGroups) + namedRules.entityLightPokemonRules
        entityMythicRulesPresent = TargetRuleCapabilities.hasMythicRules(entityRuleGroups) || namedRules.entityUsesMythic
        entityRealtimeMythicRulesPresent = TargetRuleCapabilities.hasMythicRules(realtimeRuleGroups) || namedRules.entityUsesMythic

        worldMode = normalizeMode(config.getString("worlds.mode"), "blacklist")
        worldList = config.getStringList("worlds.list")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        panicEnabled = getBoolean("limits.panic.enabled", "panic-mode.enabled", false)
        panicNoticeTarget = parseNoticeTarget(
            config.getString("limits.panic.notice-target"),
            OverloadNoticeTarget.ALL
        )
        maxGlobalEntities = getInt("limits.panic.max-global-entities", "panic-mode.max-global-entities", 5000)
        panicCheckIntervalMillis = config.getLong("limits.panic.check-interval-seconds", 15L).coerceIn(5L, 300L) * 1000L

        soundEnabled = config.getBoolean("sounds.enabled", false)
        soundSettings = soundDefaults.mapValues { (key, fallback) ->
            SoundSetting(
                enabled = config.getBoolean("sounds.$key.enabled", fallback.enabled),
                sound = config.getString("sounds.$key.sound", fallback.sound)?.trim().orEmpty(),
                volume = config.getDouble("sounds.$key.volume", fallback.volume.toDouble()).toFloat().coerceAtLeast(0.0f),
                pitch = config.getDouble("sounds.$key.pitch", fallback.pitch.toDouble()).toFloat().coerceIn(0.0f, 2.0f)
            )
        }
        BinEntryRules.load(config)
        filterRevision++
    }

    fun getSoundSetting(key: String): SoundSetting? {
        if (!soundEnabled) return null
        return soundSettings[key]?.takeIf { it.enabled && it.sound.isNotBlank() }
    }

    fun isWorldEnabled(worldName: String): Boolean {
        val inList = worldList.contains(worldName) || worldList.contains("*") || worldList.contains("all")
        return if (worldMode.equals("whitelist", ignoreCase = true)) {
            inList
        } else {
            !inList
        }
    }

    private fun getBoolean(primaryPath: String, fallbackPath: String, defaultValue: Boolean): Boolean {
        return when {
            config.contains(primaryPath) -> config.getBoolean(primaryPath, defaultValue)
            config.contains(fallbackPath) -> config.getBoolean(fallbackPath, defaultValue)
            else -> defaultValue
        }
    }

    private fun getInt(primaryPath: String, fallbackPath: String, defaultValue: Int): Int {
        return when {
            config.contains(primaryPath) -> config.getInt(primaryPath, defaultValue)
            config.contains(fallbackPath) -> config.getInt(fallbackPath, defaultValue)
            else -> defaultValue
        }
    }

    private fun getLong(primaryPath: String, fallbackPath: String, defaultValue: Long): Long {
        return when {
            config.contains(primaryPath) -> config.getLong(primaryPath, defaultValue)
            config.contains(fallbackPath) -> config.getLong(fallbackPath, defaultValue)
            else -> defaultValue
        }
    }

    private data class PerformanceDefaults(
        val maxChunksPerTick: Int,
        val maxMillisPerTick: Long,
        val foliaMaxActiveRegionTasks: Int,
        val foliaDispatchChunksPerTick: Int
    )

    private fun loadPerformance() {
        performanceProfile = normalizePerformanceProfile(config.getString("performance.profile"))
        val defaults = performanceDefaults(performanceProfile)

        scanMaxChunksPerTick = config.getInt(
            "performance.scan.max-chunks-per-tick",
            defaults.maxChunksPerTick.coerceAtLeast(cleanupChunksPerTick)
        ).coerceIn(1, 5000)
        scanMaxMillisPerTick = config.getLong(
            "performance.scan.max-millis-per-tick",
            defaults.maxMillisPerTick
        ).coerceIn(1L, 50L)
        foliaMaxActiveRegionTasks = config.getInt(
            "performance.folia.max-active-region-tasks",
            defaults.foliaMaxActiveRegionTasks
        ).coerceIn(1, 8192)
        foliaDispatchChunksPerTick = config.getInt(
            "performance.folia.dispatch-chunks-per-tick",
            defaults.foliaDispatchChunksPerTick
        ).coerceIn(1, 8192)
        cleanupDetailStats = config.getBoolean("performance.stats.detail", true)
        cleanupStageTimings = config.getBoolean("performance.stats.stage-timing", false)
        candidateIndexEnabled = config.getBoolean("performance.candidate-index.enabled", true)
        candidateFullScanEveryCycles = config.getLong("performance.candidate-index.full-scan-every-cycles", 6L).coerceIn(1L, 144L)
        auditEnabled = config.getBoolean("audit.enabled", true)
        auditMaxLines = config.getInt("audit.max-lines", 30).coerceIn(1, 200)
    }

    private fun normalizePerformanceProfile(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "保守", "conservative", "safe" -> "保守"
            "均衡", "balanced", "balance" -> "均衡"
            "极限", "extreme", "max" -> "极限"
            "快速", "fast", null, "" -> "快速"
            else -> "快速"
        }
    }

    private fun performanceDefaults(profile: String): PerformanceDefaults {
        return when (profile) {
            "保守" -> PerformanceDefaults(60, 3L, 128, 128)
            "均衡" -> PerformanceDefaults(140, 5L, 320, 320)
            "极限" -> PerformanceDefaults(1000, 12L, 2048, 2048)
            else -> PerformanceDefaults(240, 7L, 512, 512)
        }
    }

    private fun parseChunkEntityLimitMode(raw: String?, path: String): ChunkEntityLimitMode {
        return when (normalizeMode(raw, "safe")) {
            "off", "false", "disable", "disabled", "关闭" -> ChunkEntityLimitMode.OFF
            "safe", "安全", "安全模式" -> ChunkEntityLimitMode.SAFE
            "strict", "resident", "count", "数量", "总数", "严格", "严格模式", "硬限制" -> ChunkEntityLimitMode.STRICT
            "legacy", "old", "compat", "兼容", "兼容旧版", "旧版" -> {
                Cyuclear.instance.logger.warning("Cyuclear 不再提供实体硬限制兼容模式，$path 已按安全模式处理")
                ChunkEntityLimitMode.SAFE
            }
            else -> {
                Cyuclear.instance.logger.warning("Cyuclear 在 $path 读取到未知值 '$raw'，已回退为 safe")
                ChunkEntityLimitMode.SAFE
            }
        }
    }

    private fun parseBossBarColor(raw: String?, path: String, fallback: NoticeBossBarColor): NoticeBossBarColor {
        val parsed = NoticeBossBarColor.parse(raw)
        if (parsed != null) return parsed
        if (!raw.isNullOrBlank()) {
            Cyuclear.instance.logger.warning("Cyuclear 在 $path 读取到未知 BossBar 颜色 '$raw'，已回退为 ${fallback.name}")
        }
        return fallback
    }

    private fun parseBossBarStyle(raw: String?, path: String, fallback: NoticeBossBarStyle): NoticeBossBarStyle {
        val parsed = NoticeBossBarStyle.parse(raw)
        if (parsed != null) return parsed
        if (!raw.isNullOrBlank()) {
            Cyuclear.instance.logger.warning("Cyuclear 在 $path 读取到未知 BossBar 分段样式 '$raw'，已回退为 ${fallback.name}")
        }
        return fallback
    }

    private fun parseOverloadNoticeTarget(raw: String?): OverloadNoticeTarget {
        val fallback = if (config.getBoolean("limits.chunk.broadcast-location", true)) {
            OverloadNoticeTarget.ADMINS
        } else {
            OverloadNoticeTarget.NONE
        }
        return parseNoticeTarget(raw, fallback)
    }

    fun recoveryCaptureEnabled(origin: CleanupOrigin): Boolean {
        if (!recoveryEnabled || recoveryMaxEntriesPerRun <= 0) return false
        return when (origin) {
            CleanupOrigin.SCHEDULED -> recoveryScheduled
            CleanupOrigin.MANUAL -> recoveryManual
            CleanupOrigin.PANIC -> recoveryPanic
        }
    }

    private fun parseNoticeTarget(raw: String?, fallback: OverloadNoticeTarget): OverloadNoticeTarget {
        return when (normalizeMode(raw, "")) {
            "all", "players", "全服", "玩家" -> OverloadNoticeTarget.ALL
            "none", "off", "关闭", "不发送" -> OverloadNoticeTarget.NONE
            "admin", "admins", "管理员" -> OverloadNoticeTarget.ADMINS
            else -> fallback
        }
    }

    private fun normalizeMode(raw: String?, fallback: String): String {
        val value = raw?.trim()?.lowercase()
        return if (value.isNullOrEmpty()) fallback else value
    }

    private fun firstExistingPath(vararg paths: String): String {
        return paths.firstOrNull(config::contains) ?: paths.first()
    }

    private fun loadSpecificThresholds(vararg paths: String): Map<String, Int> {
        val path = paths.firstOrNull { config.isConfigurationSection(it) } ?: paths.firstOrNull() ?: return emptyMap()
        val section = config.getConfigurationSection(path) ?: return emptyMap()
        val thresholds = LinkedHashMap<String, Int>()

        for (rawKey in section.getKeys(false)) {
            val key = rawKey.trim().lowercase()
            if (key.isEmpty()) continue

            val threshold = section.getInt(rawKey, -1)
            if (threshold <= 0) {
                Cyuclear.instance.logger.warning("Cyuclear: $path.$rawKey must be greater than 0, skipped.")
                continue
            }

            thresholds[key] = threshold
        }

        return thresholds
    }

    fun isNamedProtectionBypassed(world: String, x: Int, y: Int, z: Int): Boolean {
        return entityNamedBypassRegions[world.lowercase()]?.any { it.contains(x, y, z) } == true
    }

    fun hasNamedProtectionBypassRegions(): Boolean = entityNamedBypassRegions.isNotEmpty()

    private fun loadNamedBypassRegions(path: String): Map<String, List<NamedBypassRegion>> {
        val section = config.getConfigurationSection(path) ?: return emptyMap()
        val regions = LinkedHashMap<String, MutableList<NamedBypassRegion>>()
        for (key in section.getKeys(false)) {
            val region = section.getConfigurationSection(key) ?: continue
            val world = region.getString("world")?.trim()?.lowercase().orEmpty()
            if (world.isEmpty()) {
                Cyuclear.instance.logger.warning("Cyuclear 跳过了未填写 world 的命名保护区域 $path.$key")
                continue
            }
            if (!region.isConfigurationSection("min") || !region.isConfigurationSection("max")) {
                Cyuclear.instance.logger.warning("Cyuclear 跳过了未填写 min 或 max 的命名保护区域 $path.$key")
                continue
            }
            val minX = minOf(region.getInt("min.x"), region.getInt("max.x"))
            val minY = minOf(region.getInt("min.y"), region.getInt("max.y"))
            val minZ = minOf(region.getInt("min.z"), region.getInt("max.z"))
            val maxX = maxOf(region.getInt("min.x"), region.getInt("max.x"))
            val maxY = maxOf(region.getInt("min.y"), region.getInt("max.y"))
            val maxZ = maxOf(region.getInt("min.z"), region.getInt("max.z"))
            regions.getOrPut(world, ::ArrayList).add(NamedBypassRegion(minX, minY, minZ, maxX, maxY, maxZ))
        }
        return regions
    }

    private fun warnIfLikelyEntityBlacklistMistake(rawEntries: List<String>) {
        if (entityFilterMode != FilterMode.BLACKLIST) return

        val protectedProjectiles = rawEntries
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it == "minecraft:shulker_bullet" || it == "shulker_bullet" }
            .distinct()
            .toList()

        if (protectedProjectiles.isEmpty()) return

        Cyuclear.instance.logger.warning(
            "Cyuclear: legacy modules.entities blacklist is treated as protect list, so ${protectedProjectiles.joinToString()} " +
                "will not be cleaned. Move it to targets.entities.clean-list.list if it should be cleaned"
        )
    }

    private val soundDefaults = mapOf(
        "cleanup-warning" to SoundSetting(false, "BLOCK_NOTE_BLOCK_PLING", 0.8f, 1.2f),
        "cleanup-start" to SoundSetting(false, "BLOCK_BEACON_ACTIVATE", 0.8f, 1.0f),
        "cleanup-complete" to SoundSetting(false, "ENTITY_PLAYER_LEVELUP", 0.9f, 1.0f),
        "panic" to SoundSetting(false, "ENTITY_WITHER_SPAWN", 0.9f, 0.8f),
        "chunk-overload" to SoundSetting(false, "BLOCK_NOTE_BLOCK_BASS", 0.8f, 0.7f),
        "bin-open" to SoundSetting(false, "BLOCK_CHEST_OPEN", 0.8f, 1.1f),
        "bin-warning" to SoundSetting(false, "BLOCK_NOTE_BLOCK_BELL", 0.8f, 1.0f),
        "bin-expire" to SoundSetting(false, "BLOCK_CHEST_LOCKED", 0.8f, 0.8f),
        "bin-deposit" to SoundSetting(false, "ENTITY_ITEM_PICKUP", 0.6f, 1.4f)
    )
}

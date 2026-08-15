package org.cyuCBMclean.cyuclear.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.service.CleanupFilter
import org.cyuCBMclean.cyuclear.service.CleanupOrigin
import org.cyuCBMclean.cyuclear.service.CleanupRunManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLongArray

internal object CleanupRunStore {

    private const val formatVersion = 1
    private const val directoryName = "cleanup-runs"
    private const val indexFileName = "index.yml"

    fun loadRecent(limit: Int): List<CleanupRunManager.RunRecord> {
        val folder = directory()
        if (!folder.exists()) return emptyList()

        val ids = YamlConfiguration.loadConfiguration(File(folder, indexFileName))
            .getStringList("runs")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(limit.coerceAtLeast(1))
            .toList()

        return ids.mapNotNull { id -> load(File(folder, "$id.yml")) }
    }

    fun save(record: CleanupRunManager.RunRecord): Boolean {
        val folder = directory()
        if (!folder.exists() && !folder.mkdirs()) return false

        val file = File(folder, "${record.id}.yml")
        val temporary = File(folder, ".${record.id}.tmp")
        return runCatching {
            val config = YamlConfiguration()
            config.set("format-version", formatVersion)
            config.set("id", record.id)
            config.set("origin", record.origin.name)
            config.set("status", record.status.name)
            config.set("started-at", record.startedAt)
            config.set("finished-at", record.finishedAt)
            config.set("duration-millis", record.durationMillis)
            config.set("full-scan", record.fullScan)
            config.set("queued-chunks", record.queuedChunks)
            config.set("processed-chunks", record.processedChunks)
            config.set("failed-chunks", record.failedChunks.get())
            config.set("scanned-entities", record.scannedEntities.get())
            config.set("removed-items", record.removedItems.get())
            config.set("removed-entities", record.removedEntities.get())
            config.set("recovery-enabled", record.captureRecovery)
            config.set("recovery-expires-at", record.recoveryExpiresAt)
            config.set("recovery-skipped-entries", record.skippedRecoveryEntries.get())
            synchronized(record.slowestLock) {
                config.set("slowest.world", record.slowestWorld ?: "")
                config.set("slowest.chunk-x", record.slowestChunkX)
                config.set("slowest.chunk-z", record.slowestChunkZ)
                config.set("slowest.millis", record.slowestNanos.get() / 1_000_000L)
            }
            config.set("failure-message", record.failureMessage ?: "")
            config.set("reasons.items", reasonMap(record.itemReasons))
            config.set("reasons.entities", reasonMap(record.entityReasons))
            config.set(
                "recovery.entries",
                record.recoveryEntries.map { entry ->
                    linkedMapOf<String, Any>(
                        "item" to entry.encodedItem,
                        "item-id" to entry.itemId,
                        "amount" to entry.amount,
                        "world" to entry.world,
                        "x" to entry.x,
                        "y" to entry.y,
                        "z" to entry.z,
                        "reason" to entry.reason,
                        "claimed-by" to (entry.claimedBy ?: ""),
                        "claimed-at" to entry.claimedAt
                    )
                }
            )
            config.save(temporary)
            moveAtomically(temporary, file)
            true
        }.getOrElse {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            Cyuclear.instance.logger.warning("保存清理批次 ${record.id} 失败：${it.message}")
            false
        }
    }

    fun saveIndex(ids: List<String>) {
        val folder = directory()
        if (!folder.exists() && !folder.mkdirs()) return

        val temporary = File(folder, ".index.tmp")
        val target = File(folder, indexFileName)
        runCatching {
            YamlConfiguration().also { config ->
                config.set("format-version", formatVersion)
                config.set("runs", ids)
            }.save(temporary)
            moveAtomically(temporary, target)
        }.onFailure {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            Cyuclear.instance.logger.warning("保存清理批次索引失败：${it.message}")
        }
    }

    private fun load(file: File): CleanupRunManager.RunRecord? {
        if (!file.exists()) return null

        return runCatching {
            val config = YamlConfiguration.loadConfiguration(file)
            if (config.getInt("format-version", 0) != formatVersion) return@runCatching null

            val id = config.getString("id")?.trim().orEmpty()
            if (id.isEmpty()) return@runCatching null

            val origin = runCatching {
                CleanupOrigin.valueOf(config.getString("origin", "SCHEDULED").orEmpty())
            }.getOrDefault(CleanupOrigin.SCHEDULED)
            val status = runCatching {
                CleanupRunManager.Status.valueOf(config.getString("status", "COMPLETED").orEmpty())
            }.getOrDefault(CleanupRunManager.Status.COMPLETED)
            val record = CleanupRunManager.RunRecord(
                id = id,
                origin = origin,
                startedAt = config.getLong("started-at", 0L),
                fullScan = config.getBoolean("full-scan", true),
                captureRecovery = config.getBoolean("recovery-enabled", false),
                maxRecoveryEntries = config.getMapList("recovery.entries").size,
                recoveryExpiresAt = config.getLong("recovery-expires-at", 0L)
            )
            record.status = status
            record.finishedAt = config.getLong("finished-at", 0L)
            record.durationMillis = config.getLong("duration-millis", 0L)
            record.queuedChunks = config.getInt("queued-chunks", 0)
            record.processedChunks = config.getInt("processed-chunks", 0)
            record.failedChunks.set(config.getInt("failed-chunks", 0).coerceAtLeast(0))
            record.scannedEntities.set(config.getInt("scanned-entities", 0))
            record.removedItems.set(config.getLong("removed-items", 0L))
            record.removedEntities.set(config.getLong("removed-entities", 0L))
            record.skippedRecoveryEntries.set(config.getLong("recovery-skipped-entries", 0L))
            record.slowestWorld = config.getString("slowest.world")?.trim()?.takeIf { it.isNotEmpty() }
            record.slowestChunkX = config.getInt("slowest.chunk-x", 0)
            record.slowestChunkZ = config.getInt("slowest.chunk-z", 0)
            record.slowestNanos.set(config.getLong("slowest.millis", 0L).coerceAtLeast(0L) * 1_000_000L)
            record.failureMessage = config.getString("failure-message")?.trim()?.takeIf { it.isNotEmpty() }
            loadReasons(config, "reasons.items", record.itemReasons)
            loadReasons(config, "reasons.entities", record.entityReasons)
            for (value in config.getMapList("recovery.entries")) {
                val encoded = value["item"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val amount = value["amount"].toIntValue()?.takeIf { it > 0 } ?: continue
                val world = value["world"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val claimedBy = value["claimed-by"]?.toString()?.takeIf { it.isNotBlank() }
                record.recoveryEntries += CleanupRunManager.RecoveryEntry(
                    encodedItem = encoded,
                    itemId = value["item-id"]?.toString().orEmpty().ifEmpty { "minecraft:air" },
                    amount = amount,
                    world = world,
                    x = value["x"].toIntValue() ?: 0,
                    y = value["y"].toIntValue() ?: 0,
                    z = value["z"].toIntValue() ?: 0,
                    reason = value["reason"]?.toString().orEmpty().ifEmpty { "已保存" },
                    claimedBy = claimedBy,
                    claimedAt = value["claimed-at"].toLongValue() ?: 0L
                )
                record.reservedRecoveryEntries.incrementAndGet()
                record.recoveryEntryCount.incrementAndGet()
                if (claimedBy != null) record.claimedRecoveryEntries.incrementAndGet()
            }
            record
        }.getOrElse {
            Cyuclear.instance.logger.warning("读取清理批次 ${file.name} 失败：${it.message}")
            null
        }
    }

    private fun reasonMap(values: AtomicLongArray): Map<String, Long> {
        val reasons = LinkedHashMap<String, Long>()
        for (reason in CleanupFilter.ReasonKey.values()) {
            val amount = values.get(reason.ordinal)
            if (amount > 0L) reasons[reason.name] = amount
        }
        return reasons
    }

    private fun loadReasons(config: YamlConfiguration, path: String, target: AtomicLongArray) {
        val section = config.getConfigurationSection(path) ?: return
        for (key in section.getKeys(false)) {
            val reason = runCatching { CleanupFilter.ReasonKey.valueOf(key) }.getOrNull() ?: continue
            target.set(reason.ordinal, section.getLong(key, 0L).coerceAtLeast(0L))
        }
    }

    private fun directory(): File = File(Cyuclear.instance.dataFolder, directoryName)

    private fun moveAtomically(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun Any?.toIntValue(): Int? = when (this) {
        is Number -> toInt()
        else -> toString().toIntOrNull()
    }

    private fun Any?.toLongValue(): Long? = when (this) {
        is Number -> toLong()
        else -> toString().toLongOrNull()
    }
}

package org.cyuCBMclean.cyuclear.service

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import org.cyuCBMclean.cyuclear.cluster.ClusterBinReservation
import org.cyuCBMclean.cyuclear.util.ItemIdentity
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object BinClaimAudit {

    private const val PAGE_SIZE = 8
    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    data class Record(
        val timeMillis: Long,
        val playerId: String,
        val playerName: String,
        val serverId: String,
        val itemId: String,
        val amount: Int,
        val cycleId: String,
        val claimId: String,
        val delivery: String
    )

    internal fun record(player: Player, item: ItemStack, amount: Int, reservation: ClusterBinReservation, delivery: String) {
        if (!Settings.binClaimAuditEnabled || amount <= 0) return
        val record = Record(
            timeMillis = System.currentTimeMillis(),
            playerId = player.uniqueId.toString(),
            playerName = player.name,
            serverId = Settings.clusterServerId.ifBlank { "local" },
            itemId = ItemIdentity.ruleId(item),
            amount = amount,
            cycleId = reservation.cycleId,
            claimId = reservation.claimId,
            delivery = delivery
        )
        CyuScheduler.runTaskAsynchronously(Cyuclear.instance, Runnable { append(record) })
    }

    fun read(playerFilter: String?, page: Int, callback: (List<Record>, Int) -> Unit) {
        CyuScheduler.runTaskAsynchronously(Cyuclear.instance, Runnable {
            val filter = playerFilter?.trim()?.takeIf { it.isNotEmpty() }
            val files = auditFiles()
            val matched = countMatching(files, filter)
            val totalPages = maxOf(1, (matched + PAGE_SIZE - 1) / PAGE_SIZE)
            val safePage = page.coerceIn(1, totalPages)
            callback(readPage(files, filter, matched, safePage), totalPages)
        })
    }

    fun formatTime(timeMillis: Long): String = timeFormatter.format(Instant.ofEpochMilli(timeMillis))

    private fun append(record: Record) {
        val directory = Cyuclear.instance.dataFolder.toPath().resolve("audit")
        Files.createDirectories(directory)
        val file = directory.resolve("bin-claims-${dayFormatter.format(Instant.ofEpochMilli(record.timeMillis))}.tsv")
        val line = listOf(
            record.timeMillis,
            record.playerId,
            record.playerName,
            record.serverId,
            record.itemId,
            record.amount,
            record.cycleId,
            record.claimId,
            record.delivery
        ).joinToString("\t") { it.toString().replace('\t', ' ').replace('\r', ' ').replace('\n', ' ') } + System.lineSeparator()
        Files.write(file, line.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun auditFiles(): List<Path> {
        val directory = Cyuclear.instance.dataFolder.toPath().resolve("audit")
        if (!Files.isDirectory(directory)) return emptyList()
        val result = ArrayList<Path>()
        Files.list(directory).use { files ->
            files.filter { file ->
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                    file.fileName.toString().startsWith("bin-claims-") &&
                    file.fileName.toString().endsWith(".tsv")
            }
                .sorted()
                .forEach(result::add)
        }
        return result
    }

    private fun countMatching(files: List<Path>, filter: String?): Int {
        var matched = 0
        for (file in files) {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val record = parse(line) ?: return@forEach
                    if (matches(record, filter)) matched++
                }
            }
        }
        return matched
    }

    private fun readPage(files: List<Path>, filter: String?, matched: Int, page: Int): List<Record> {
        if (matched == 0) return emptyList()
        val latestStart = (page - 1) * PAGE_SIZE
        val latestEnd = minOf(latestStart + PAGE_SIZE, matched)
        val earliestIndex = matched - latestEnd
        val latestIndex = matched - latestStart
        var currentIndex = 0
        val records = ArrayList<Record>(latestEnd - latestStart)

        for (file in files) {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val record = parse(line) ?: return@forEach
                    if (!matches(record, filter)) return@forEach
                    if (currentIndex in earliestIndex until latestIndex) {
                        records += record
                    }
                    currentIndex++
                }
            }
        }
        records.reverse()
        return records
    }

    private fun matches(record: Record, filter: String?): Boolean {
        return filter == null || record.playerName.equals(filter, true) || record.playerId.equals(filter, true)
    }

    private fun parse(line: String): Record? {
        val parts = line.split('\t')
        if (parts.size != 9) return null
        return Record(
            timeMillis = parts[0].toLongOrNull() ?: return null,
            playerId = parts[1],
            playerName = parts[2],
            serverId = parts[3],
            itemId = parts[4],
            amount = parts[5].toIntOrNull() ?: return null,
            cycleId = parts[6],
            claimId = parts[7],
            delivery = parts[8]
        )
    }
}

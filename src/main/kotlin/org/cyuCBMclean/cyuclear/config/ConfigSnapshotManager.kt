package org.cyuCBMclean.cyuclear.config

import org.cyuCBMclean.cyuclear.Cyuclear
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConfigSnapshotManager {
    data class Result(
        val folder: File?,
        val copiedFiles: Int,
        val error: String? = null
    ) {
        val success: Boolean
            get() = folder != null && error == null
    }

    private val files = listOf(
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

    fun create(reason: String): Result {
        val dataFolder = Cyuclear.instance.dataFolder
        val root = File(dataFolder, "backup/snapshots")
        if (!root.exists() && !root.mkdirs()) {
            return Result(null, 0, "无法创建备份目录")
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date())
        val name = "${timestamp}-${safeName(reason)}"
        var folder = File(root, name)
        var index = 2
        while (folder.exists()) folder = File(root, "$name-${index++}")
        if (!folder.mkdirs()) return Result(null, 0, "无法创建备份文件夹")

        var copied = 0
        return runCatching {
            for (relative in files) {
                val source = File(dataFolder, relative)
                if (!source.exists() || !source.isFile) continue
                val target = File(folder, relative)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = false)
                copied++
            }
            Result(folder, copied)
        }.getOrElse { error ->
            Cyuclear.instance.logger.warning("创建配置快照失败：${error.message}")
            Result(null, copied, error.message ?: "写入失败")
        }
    }

    private fun safeName(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]"), "-")
        return normalized.trim('-').takeIf { it.isNotEmpty() } ?: "manual"
    }
}

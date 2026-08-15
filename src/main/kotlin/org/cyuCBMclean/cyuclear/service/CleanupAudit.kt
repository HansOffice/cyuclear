package org.cyuCBMclean.cyuclear.service

import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object CleanupAudit {

    private data class AuditKey(
        val kind: String,
        val id: String,
        val reason: String
    )

    private val removed = ConcurrentHashMap<AuditKey, AtomicInteger>()

    class Batch internal constructor() {
        private var counts: HashMap<AuditKey, Int>? = null

        fun record(kind: String, id: String, reason: String, amount: Int = 1) {
            val key = AuditKey(kind, id, reason)
            val current = counts ?: HashMap<AuditKey, Int>().also { counts = it }
            current[key] = (current[key] ?: 0) + amount.coerceAtLeast(1)
        }

        fun commit() {
            for ((key, count) in counts ?: return) {
                removed.computeIfAbsent(key) { AtomicInteger(0) }.addAndGet(count)
            }
        }
    }

    fun begin() {
        removed.clear()
    }

    fun newBatch(): Batch? = if (Settings.auditEnabled) Batch() else null

    fun record(kind: String, id: String, reason: String) {
        if (!Settings.auditEnabled) return
        val key = AuditKey(kind, id, reason)
        removed.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
    }

    fun flush() {
        if (!Settings.auditEnabled || removed.isEmpty()) return

        val logger = Cyuclear.instance.logger
        val lineLimit = Settings.auditMaxLines
        val displayOrder = compareByDescending<Map.Entry<AuditKey, AtomicInteger>> { it.value.get() }
            .thenBy { it.key.kind }
            .thenBy { it.key.id }
            .thenBy { it.key.reason }
        val retainedOrder = compareBy<Map.Entry<AuditKey, AtomicInteger>> { it.value.get() }
            .thenByDescending { it.key.kind }
            .thenByDescending { it.key.id }
            .thenByDescending { it.key.reason }
        val entries = if (removed.size <= lineLimit) {
            removed.entries.toList()
        } else {
            val retained = PriorityQueue<Map.Entry<AuditKey, AtomicInteger>>(lineLimit, retainedOrder)
            for (entry in removed.entries) {
                if (retained.size < lineLimit) {
                    retained += entry
                } else if (displayOrder.compare(entry, retained.peek()) < 0) {
                    retained.poll()
                    retained += entry
                }
            }
            retained.toList()
        }.sortedWith(displayOrder)

        logger.info("本次清理原因汇总:")
        entries.forEach { (key, count) ->
            logger.info("${key.kind} ${key.id} x${count.get()}，原因: ${key.reason}")
        }

        val hidden = removed.size - entries.size
        if (hidden > 0) {
            logger.info("还有 $hidden 条清理原因已折叠，可调高 audit.max-lines 查看")
        }
    }
}

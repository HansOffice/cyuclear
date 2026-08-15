package org.cyuCBMclean.cyuclear.service

import java.util.concurrent.atomic.LongAdder

object CleanupTimings {
    @Volatile
    private var enabled = false
    private val queue = LongAdder()
    private val snapshot = LongAdder()
    private val filter = LongAdder()
    private val remove = LongAdder()
    private val recovery = LongAdder()
    private val audit = LongAdder()

    fun reset(stageTimingEnabled: Boolean) {
        enabled = stageTimingEnabled
        queue.reset()
        snapshot.reset()
        filter.reset()
        remove.reset()
        recovery.reset()
        audit.reset()
    }

    fun start(): Long = if (enabled) System.nanoTime() else 0L

    fun queueSince(start: Long) = addSince(queue, start)
    fun snapshotSince(start: Long) = addSince(snapshot, start)
    fun filterSince(start: Long) = addSince(filter, start)
    fun removeSince(start: Long) = addSince(remove, start)
    fun recoverySince(start: Long) = addSince(recovery, start)
    fun auditSince(start: Long) = addSince(audit, start)

    private fun addSince(counter: LongAdder, start: Long) {
        if (start != 0L) counter.add(System.nanoTime() - start)
    }

    fun text(totalMillis: Long): String {
        val queueMillis = queue.sum() / 1_000_000L
        val snapshotMillis = snapshot.sum() / 1_000_000L
        val filterMillis = filter.sum() / 1_000_000L
        val removeMillis = remove.sum() / 1_000_000L
        val recoveryMillis = recovery.sum() / 1_000_000L
        val auditMillis = audit.sum() / 1_000_000L
        val measured = queueMillis + snapshotMillis + filterMillis + removeMillis + recoveryMillis + auditMillis
        val scheduling = (totalMillis - measured).coerceAtLeast(0L)
        return "queue=${queueMillis}ms, snapshot=${snapshotMillis}ms, filter=${filterMillis}ms, remove=${removeMillis}ms, bin=${recoveryMillis}ms, audit=${auditMillis}ms, scheduling=${scheduling}ms"
    }
}

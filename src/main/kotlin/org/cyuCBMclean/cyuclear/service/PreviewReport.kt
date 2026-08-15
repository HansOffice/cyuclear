package org.cyuCBMclean.cyuclear.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PreviewReport {

    private val reasonCounts = ConcurrentHashMap<String, AtomicInteger>()

    val chunks = AtomicInteger(0)
    val scanned = AtomicInteger(0)
    val removeItems = AtomicInteger(0)
    val removeEntities = AtomicInteger(0)
    val protectedNamed = AtomicInteger(0)
    val protectedTamed = AtomicInteger(0)
    val protectedPersistent = AtomicInteger(0)
    val protectedNoDespawn = AtomicInteger(0)
    val protectedEvent = AtomicInteger(0)
    val protectedKeepList = AtomicInteger(0)

    fun record(decision: CleanupFilter.FilterDecision) {
        val key = decision.reason
        reasonCounts.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()

        if (decision.remove) {
            return
        }

        when (decision.reasonKey) {
            CleanupFilter.ReasonKey.NAMED -> protectedNamed.incrementAndGet()
            CleanupFilter.ReasonKey.TAMED -> protectedTamed.incrementAndGet()
            CleanupFilter.ReasonKey.PERSISTENT -> protectedPersistent.incrementAndGet()
            CleanupFilter.ReasonKey.NO_DESPAWN -> protectedNoDespawn.incrementAndGet()
            CleanupFilter.ReasonKey.RAID_EVENT -> protectedEvent.incrementAndGet()
            CleanupFilter.ReasonKey.KEEP_LIST,
            CleanupFilter.ReasonKey.NAME_KEEP,
            CleanupFilter.ReasonKey.LORE_KEEP -> protectedKeepList.incrementAndGet()
            else -> Unit
        }
    }

    fun topReasons(limit: Int): List<Pair<String, Int>> {
        return reasonCounts.entries
            .map { it.key to it.value.get() }
            .sortedByDescending { it.second }
            .take(limit)
    }
}

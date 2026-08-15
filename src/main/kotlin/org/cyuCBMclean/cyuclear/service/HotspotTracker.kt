package org.cyuCBMclean.cyuclear.service

import org.cyuCBMclean.cyuclear.config.Settings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object HotspotTracker {
    enum class SubjectKind {
        ITEM,
        ENTITY
    }

    enum class State(val display: String, val weight: Int) {
        WARNING("警告", 1),
        THROTTLED("限流", 2),
        BREAKER("熔断", 3),
        OBSERVING("恢复观察", 0)
    }

    data class HotspotView(
        val world: String,
        val chunkX: Int,
        val chunkZ: Int,
        val state: State,
        val itemCount: Int,
        val entityCount: Int,
        val itemTriggerRate: Int,
        val entityTriggerRate: Int,
        val itemSubject: String,
        val entitySubject: String,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val triggerCount: Long,
        val cleanupRuns: Int,
        val cleanedItems: Long,
        val cleanedEntities: Long,
        val lastProcessMillis: Long,
        val breakerUntil: Long
    )

    data class Summary(
        val total: Int,
        val breakers: Int
    )

    private data class Key(
        val world: String,
        val chunkX: Int,
        val chunkZ: Int
    )

    private class Record(now: Long) {
        var state = State.WARNING
        var firstSeenAt = now
        var lastSeenAt = now
        var itemCount = 0
        var entityCount = 0
        var itemSubject = ""
        var entitySubject = ""
        var breakerUntil = 0L
        var triggerCount = 0L
        var cleanupRuns = 0
        var cleanedItems = 0L
        var cleanedEntities = 0L
        var lastProcessMillis = 0L
        var itemWindowStartedAt = now
        var entityWindowStartedAt = now
        var itemWindowCount = 0
        var entityWindowCount = 0
        var itemTriggerRate = 0
        var entityTriggerRate = 0
    }

    private val records = ConcurrentHashMap<Key, Record>()
    private val lastPruneAt = AtomicLong(0L)

    fun recordPressure(
        world: String,
        chunkX: Int,
        chunkZ: Int,
        kind: SubjectKind,
        subject: String,
        count: Int,
        state: State,
        breakerUntil: Long = 0L
    ) {
        if (!Settings.hotspotEnabled) return
        val now = System.currentTimeMillis()
        pruneIfNeeded(now)
        val key = Key(world, chunkX, chunkZ)
        val record = records[key] ?: createRecord(key, now) ?: return
        synchronized(record) {
            record.lastSeenAt = now
            record.triggerCount++
            if (kind == SubjectKind.ITEM) {
                if (count >= 0) record.itemCount = maxOf(record.itemCount, count)
                if (subject.isNotBlank() && subject != "全部") record.itemSubject = subject
                updateItemRate(record, now)
            } else {
                if (count >= 0) record.entityCount = maxOf(record.entityCount, count)
                if (subject.isNotBlank() && subject != "全部") record.entitySubject = subject
                updateEntityRate(record, now)
            }
            if (state == State.BREAKER) {
                record.breakerUntil = maxOf(record.breakerUntil, breakerUntil)
            }
            if (state.weight >= record.state.weight || record.state == State.BREAKER && record.breakerUntil <= now) {
                record.state = state
            }
        }
    }

    fun recordCleanup(
        world: String,
        chunkX: Int,
        chunkZ: Int,
        removedItems: Int,
        removedEntities: Int,
        processNanos: Long
    ) {
        if (!Settings.hotspotEnabled) return
        val record = records[Key(world, chunkX, chunkZ)] ?: return
        synchronized(record) {
            record.lastSeenAt = System.currentTimeMillis()
            record.cleanupRuns++
            record.cleanedItems += removedItems.coerceAtLeast(0).toLong()
            record.cleanedEntities += removedEntities.coerceAtLeast(0).toLong()
            record.itemCount = (record.itemCount - removedItems).coerceAtLeast(0)
            record.entityCount = (record.entityCount - removedEntities).coerceAtLeast(0)
            record.lastProcessMillis = (processNanos.coerceAtLeast(0L) / 1_000_000L)
        }
    }

    fun release(world: String, chunkX: Int, chunkZ: Int): Boolean {
        val record = records[Key(world, chunkX, chunkZ)] ?: return false
        synchronized(record) {
            record.breakerUntil = 0L
            if (record.state == State.BREAKER) record.state = State.OBSERVING
            record.lastSeenAt = System.currentTimeMillis()
        }
        return true
    }

    fun find(world: String, chunkX: Int, chunkZ: Int): HotspotView? {
        if (!Settings.hotspotEnabled) return null
        val now = System.currentTimeMillis()
        pruneIfNeeded(now)
        return records[Key(world, chunkX, chunkZ)]?.let { snapshot(world, chunkX, chunkZ, it, now) }
    }

    fun list(page: Int, pageSize: Int): Pair<List<HotspotView>, Int> {
        if (!Settings.hotspotEnabled) return emptyList<HotspotView>() to 1
        val now = System.currentTimeMillis()
        pruneIfNeeded(now)
        val size = pageSize.coerceIn(1, 54)
        val views = records.entries.map { (key, record) -> snapshot(key.world, key.chunkX, key.chunkZ, record, now) }
            .sortedWith(compareByDescending<HotspotView> { it.state.weight }.thenByDescending { it.lastSeenAt })
        val totalPages = maxOf(1, (views.size + size - 1) / size)
        val normalizedPage = page.coerceIn(0, totalPages - 1)
        val from = normalizedPage * size
        val to = minOf(views.size, from + size)
        return if (from >= to) emptyList<HotspotView>() to totalPages else views.subList(from, to) to totalPages
    }

    fun summary(): Summary {
        if (!Settings.hotspotEnabled) return Summary(0, 0)
        val now = System.currentTimeMillis()
        pruneIfNeeded(now)
        var breakers = 0
        for ((_, record) in records) {
            synchronized(record) {
                if (record.breakerUntil > now) breakers++
            }
        }
        return Summary(records.size, breakers)
    }

    fun reset() {
        records.clear()
        lastPruneAt.set(0L)
    }

    private fun createRecord(key: Key, now: Long): Record? {
        if (records.size >= Settings.hotspotMaxRecords) trimOne(now)
        if (records.size >= Settings.hotspotMaxRecords) return null
        val created = Record(now)
        return records.putIfAbsent(key, created) ?: created
    }

    private fun trimOne(now: Long) {
        var oldestKey: Key? = null
        var oldestTime = Long.MAX_VALUE
        for ((key, record) in records) {
            val lastSeen = synchronized(record) { record.lastSeenAt }
            if (lastSeen < oldestTime) {
                oldestKey = key
                oldestTime = lastSeen
            }
        }
        if (oldestKey != null && (now - oldestTime >= 1000L || records.size >= Settings.hotspotMaxRecords)) {
            records.remove(oldestKey)
        }
    }

    private fun snapshot(world: String, chunkX: Int, chunkZ: Int, record: Record, now: Long): HotspotView {
        synchronized(record) {
            val state = when {
                record.breakerUntil > now -> State.BREAKER
                record.state == State.BREAKER -> State.OBSERVING
                else -> record.state
            }
            val itemRate = if (now - record.itemWindowStartedAt <= 1500L) record.itemTriggerRate else 0
            val entityRate = if (now - record.entityWindowStartedAt <= 1500L) record.entityTriggerRate else 0
            return HotspotView(
                world = world,
                chunkX = chunkX,
                chunkZ = chunkZ,
                state = state,
                itemCount = record.itemCount,
                entityCount = record.entityCount,
                itemTriggerRate = itemRate,
                entityTriggerRate = entityRate,
                itemSubject = record.itemSubject,
                entitySubject = record.entitySubject,
                firstSeenAt = record.firstSeenAt,
                lastSeenAt = record.lastSeenAt,
                triggerCount = record.triggerCount,
                cleanupRuns = record.cleanupRuns,
                cleanedItems = record.cleanedItems,
                cleanedEntities = record.cleanedEntities,
                lastProcessMillis = record.lastProcessMillis,
                breakerUntil = record.breakerUntil
            )
        }
    }

    private fun updateItemRate(record: Record, now: Long) {
        val elapsed = now - record.itemWindowStartedAt
        if (elapsed >= 1000L) {
            record.itemTriggerRate = (record.itemWindowCount * 1000L / elapsed.coerceAtLeast(1L)).toInt()
            record.itemWindowStartedAt = now
            record.itemWindowCount = 1
        } else {
            record.itemWindowCount++
            record.itemTriggerRate = (record.itemWindowCount * 1000L / elapsed.coerceAtLeast(1000L)).toInt()
        }
    }

    private fun updateEntityRate(record: Record, now: Long) {
        val elapsed = now - record.entityWindowStartedAt
        if (elapsed >= 1000L) {
            record.entityTriggerRate = (record.entityWindowCount * 1000L / elapsed.coerceAtLeast(1L)).toInt()
            record.entityWindowStartedAt = now
            record.entityWindowCount = 1
        } else {
            record.entityWindowCount++
            record.entityTriggerRate = (record.entityWindowCount * 1000L / elapsed.coerceAtLeast(1000L)).toInt()
        }
    }

    private fun pruneIfNeeded(now: Long) {
        val previous = lastPruneAt.get()
        if (now - previous < 30_000L || !lastPruneAt.compareAndSet(previous, now)) return
        val retention = Settings.hotspotRetentionMillis
        records.entries.removeIf { (_, record) ->
            synchronized(record) { now - record.lastSeenAt > retention }
        }
    }
}

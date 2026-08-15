package org.cyuCBMclean.cyuclear.task

import org.bukkit.Bukkit
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.ActivationService
import org.cyuCBMclean.cyuclear.service.CleanupRequests
import org.cyuCBMclean.cyuclear.service.PanicCountHandle
import org.cyuCBMclean.cyuclear.service.PanicEntityCounter
import org.cyuCBMclean.cyuclear.service.PlayerMessageDispatcher
import org.cyuCBMclean.cyuclear.service.SoundNoticeManager
import org.cyuCBMclean.cyuclear.service.WindowScanner
import org.cyuCBMclean.cyuclear.scheduler.CyuTimer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object PanicMonitorTask {

    private class ActiveCounter {
        @Volatile
        var handle: PanicCountHandle? = null
    }

    private var timer: CyuTimer? = null
    private val worldEntityCounts = ConcurrentHashMap<String, Int>()
    private val worldLastScannedAt = ConcurrentHashMap<String, Long>()
    private val pendingWorlds = ConcurrentHashMap.newKeySet<String>()
    private val activeCounters = ConcurrentHashMap<String, ActiveCounter>()
    private val generation = AtomicLong(0L)
    private var worldCursor = 0

    fun start() {
        val expectedGeneration = generation.incrementAndGet()
        timer?.cancel()
        timer = null
        cancelActiveCounters()
        worldEntityCounts.clear()
        worldLastScannedAt.clear()
        pendingWorlds.clear()
        worldCursor = 0

        if (!Settings.panicEnabled) return

        timer = CyuTimer(Cyuclear.instance) {
            if (!isCurrent(expectedGeneration)) return@CyuTimer
            if (WindowScanner.isRunning) return@CyuTimer
            if (!Settings.itemModuleEnabled && !Settings.entityModuleEnabled) return@CyuTimer

            val worlds = Bukkit.getWorlds().filter { Settings.isWorldEnabled(it.name) }
            if (worlds.isEmpty()) {
                cancelActiveCounters()
                worldEntityCounts.clear()
                worldLastScannedAt.clear()
                pendingWorlds.clear()
                worldCursor = 0
                return@CyuTimer
            }

            val enabledWorldNames = worlds.mapTo(HashSet()) { it.name }
            worldEntityCounts.keys.retainAll(enabledWorldNames)
            worldLastScannedAt.keys.retainAll(enabledWorldNames)
            retainEnabledCounters(enabledWorldNames)

            val now = System.currentTimeMillis()
            val worldsToScan = maxOf(1, (worlds.size + 4) / 5)
            var scanned = 0
            var attempts = 0

            while (scanned < worldsToScan && attempts < worlds.size && !WindowScanner.isRunning) {
                if (worldCursor >= worlds.size) {
                    worldCursor = 0
                }

                val world = worlds[worldCursor]
                worldCursor++
                attempts++

                val lastScannedAt = worldLastScannedAt[world.name] ?: 0L
                if (now - lastScannedAt < Settings.panicCheckIntervalMillis) {
                    continue
                }

                if (!pendingWorlds.add(world.name)) {
                    continue
                }

                val counter = ActiveCounter()
                if (activeCounters.putIfAbsent(world.name, counter) != null) {
                    pendingWorlds.remove(world.name)
                    continue
                }

                val handle = try {
                    val others = worldEntityCounts.asSequence()
                        .filter { it.key != world.name }
                        .sumOf { it.value }
                    val budget = (Settings.maxGlobalEntities - others).coerceAtLeast(1)
                    PanicEntityCounter.count(world, budget) { count ->
                        completeCounter(expectedGeneration, world.name, counter, count)
                    }
                } catch (ex: Exception) {
                    activeCounters.remove(world.name, counter)
                    pendingWorlds.remove(world.name)
                    Cyuclear.instance.logger.warning("紧急实体统计启动失败，${world.name} - ${ex.message}")
                    null
                }
                if (handle != null) {
                    counter.handle = handle
                    if (activeCounters[world.name] !== counter) {
                        handle.cancel()
                    }
                }
                scanned++
            }
        }
        timer?.runTimer(20L, 20L)
    }

    fun stop() {
        generation.incrementAndGet()
        timer?.cancel()
        timer = null
        cancelActiveCounters()
        worldEntityCounts.clear()
        worldLastScannedAt.clear()
        pendingWorlds.clear()
        worldCursor = 0
    }

    private fun completeCounter(expectedGeneration: Long, worldName: String, counter: ActiveCounter, count: Int) {
        if (!isCurrent(expectedGeneration)) return
        if (!activeCounters.remove(worldName, counter)) return
        pendingWorlds.remove(worldName)
        worldEntityCounts[worldName] = count
        worldLastScannedAt[worldName] = System.currentTimeMillis()
        evaluatePanic(expectedGeneration)
    }

    private fun evaluatePanic(expectedGeneration: Long) {
        if (!isCurrent(expectedGeneration)) return
        if (WindowScanner.isRunning) return

        val totalEntities = worldEntityCounts.values.sum()
        if (totalEntities < Settings.maxGlobalEntities) return

        val started = WindowScanner.startScan(
            CleanupRequests.panic(
                cleanItems = Settings.itemModuleEnabled,
                cleanEntities = Settings.entityModuleEnabled
            )
        )
        if (!started) return

        val message = Language.get("panic-warn", "count" to totalEntities.toString())
        when (Settings.panicNoticeTarget) {
            Settings.OverloadNoticeTarget.NONE -> Unit
            Settings.OverloadNoticeTarget.ADMINS -> {
                PlayerMessageDispatcher.broadcast(message, "cyuclear.admin")
                SoundNoticeManager.broadcast(SoundNoticeManager.Event.PANIC, "cyuclear.admin")
            }
            Settings.OverloadNoticeTarget.ALL -> {
                PlayerMessageDispatcher.broadcast(message)
                SoundNoticeManager.broadcast(SoundNoticeManager.Event.PANIC)
            }
        }
        worldEntityCounts.clear()
        worldLastScannedAt.clear()
        pendingWorlds.clear()
        cancelActiveCounters()
    }

    private fun retainEnabledCounters(enabledWorldNames: Set<String>) {
        pendingWorlds.retainAll(enabledWorldNames)
        for ((worldName, counter) in activeCounters) {
            if (worldName !in enabledWorldNames && activeCounters.remove(worldName, counter)) {
                counter.handle?.cancel()
            }
        }
    }

    private fun cancelActiveCounters() {
        val counters = activeCounters.values.toList()
        activeCounters.clear()
        for (counter in counters) {
            counter.handle?.cancel()
        }
    }

    private fun isCurrent(expectedGeneration: Long): Boolean {
        return generation.get() == expectedGeneration && ActivationService.isActive()
    }
}

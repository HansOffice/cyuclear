package org.cyuCBMclean.cyuclear.scheduler

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.function.Consumer

class CyuTimer(private val plugin: Plugin, private val task: () -> Unit) {

    private var foliaTask: ScheduledTask? = null

    fun runTimer(delayTicks: Long, periodTicks: Long) {
        cancel()
        val initialDelay = if (delayTicks < 1L) 1L else delayTicks
        foliaTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            Consumer<ScheduledTask> { task() },
            initialDelay,
            periodTicks
        )
    }

    fun cancel() {
        foliaTask?.cancel()
        foliaTask = null
    }
}

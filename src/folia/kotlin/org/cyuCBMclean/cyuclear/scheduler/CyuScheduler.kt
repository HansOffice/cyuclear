package org.cyuCBMclean.cyuclear.scheduler

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

object CyuScheduler {
    fun runTask(plugin: Plugin, runnable: Runnable) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, runnable)
    }

    fun runTaskAsynchronously(plugin: Plugin, runnable: Runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin, Consumer<ScheduledTask> { runnable.run() })
    }

    fun runTaskLaterAsynchronously(plugin: Plugin, runnable: Runnable, delayTicks: Long) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, Consumer<ScheduledTask> { runnable.run() }, delayTicks * 50L, TimeUnit.MILLISECONDS)
    }

    fun runEntityTask(plugin: Plugin, entity: Entity, runnable: Runnable, retired: Runnable = Runnable {}) {
        entity.scheduler.run(plugin, Consumer<ScheduledTask> { runnable.run() }, retired)
    }

    fun cancelAll(plugin: Plugin) {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin)
        Bukkit.getAsyncScheduler().cancelTasks(plugin)
    }
}
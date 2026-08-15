package org.cyuCBMclean.cyuclear.scheduler

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

class CyuTimer(private val plugin: Plugin, private val task: () -> Unit) {

    private var bukkitTask: BukkitTask? = null

    fun runTimer(delayTicks: Long, periodTicks: Long) {
        cancel()
        bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { task() }, delayTicks, periodTicks)
    }

    fun cancel() {
        bukkitTask?.cancel()
        bukkitTask = null
    }
}

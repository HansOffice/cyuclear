package org.cyuCBMclean.cyuclear.scheduler

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

object CyuScheduler {
    fun runTask(plugin: Plugin, runnable: Runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable)
    }

    fun runTaskAsynchronously(plugin: Plugin, runnable: Runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable)
    }

    fun runTaskLaterAsynchronously(plugin: Plugin, runnable: Runnable, delayTicks: Long) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delayTicks)
    }

    fun runEntityTask(plugin: Plugin, entity: Entity, runnable: Runnable, retired: Runnable = Runnable {}) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (entity is Player && !entity.isOnline) retired.run() else runnable.run()
        })
    }

    fun cancelAll(plugin: Plugin) {
        Bukkit.getScheduler().cancelTasks(plugin)
    }
}
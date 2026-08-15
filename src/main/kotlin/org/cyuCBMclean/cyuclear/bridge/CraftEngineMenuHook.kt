package org.cyuCBMclean.cyuclear.bridge

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import java.util.concurrent.atomic.AtomicBoolean

object CraftEngineMenuHook {
    private const val RELOAD_EVENT = "net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent"

    private val refreshQueued = AtomicBoolean(false)
    private var registered = false

    fun register(onReload: () -> Unit): Boolean {
        val craftEngine = findPlugin() ?: return false
        if (registered) return true

        return try {
            val eventType = Class.forName(RELOAD_EVENT, false, craftEngine.javaClass.classLoader)
                .asSubclass(Event::class.java)
            val listener = object : Listener {}
            val executor = EventExecutor { _, _ -> queueReload(onReload) }
            Bukkit.getPluginManager().registerEvent(
                eventType,
                listener,
                EventPriority.MONITOR,
                executor,
                Cyuclear.instance
            )
            registered = true
            true
        } catch (ex: Exception) {
            logRegistrationFailure(ex)
            true
        } catch (ex: LinkageError) {
            logRegistrationFailure(ex)
            true
        }
    }

    private fun queueReload(onReload: () -> Unit) {
        if (!refreshQueued.compareAndSet(false, true)) return
        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            refreshQueued.set(false)
            if (Cyuclear.instance.isEnabled) onReload()
        })
    }

    private fun findPlugin(): Plugin? {
        val manager = Bukkit.getPluginManager()
        return sequenceOf(manager.getPlugin("CraftEngine"), manager.getPlugin("CE"))
            .filterNotNull()
            .firstOrNull { it.isEnabled }
    }

    private fun logRegistrationFailure(ex: Throwable) {
        val cause = ex.cause ?: ex
        Cyuclear.instance.logger.warning(
            "检测到 CraftEngine，但菜单重载监听器不可用：${cause.message ?: cause.javaClass.simpleName}"
        )
    }
}

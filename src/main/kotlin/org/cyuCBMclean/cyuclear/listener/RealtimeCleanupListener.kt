package org.cyuCBMclean.cyuclear.listener

import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.ActivationService
import org.cyuCBMclean.cyuclear.service.CleanupFilter

object RealtimeCleanupListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntitySpawn(event: EntitySpawnEvent) {
        val entity = event.entity
        if (entity is Item) return
        if (!shouldCleanup(entity)) return

        event.isCancelled = true
        entity.remove()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        if (!shouldCleanup(projectile)) return

        event.isCancelled = true
        projectile.remove()
    }

    private fun shouldCleanup(entity: Entity): Boolean {
        if (!ActivationService.isActive()) return false
        if (!Settings.entityModuleEnabled || !Settings.entityRealtimeCleanupEnabled) return false
        if (!Settings.isWorldEnabled(entity.world.name)) return false
        return CleanupFilter.shouldRemoveRealtimeEntity(entity)
    }
}

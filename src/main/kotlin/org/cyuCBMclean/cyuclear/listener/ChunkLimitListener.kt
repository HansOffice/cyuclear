package org.cyuCBMclean.cyuclear.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.cyuCBMclean.cyuclear.service.ChunkLimitService

object ChunkLimitListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onItemSpawn(event: ItemSpawnEvent) {
        ChunkLimitService.onItemSpawn(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntitySpawn(event: EntitySpawnEvent) {
        ChunkLimitService.onEntitySpawn(event)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        ChunkLimitService.onProjectileLaunch(event)
    }
}

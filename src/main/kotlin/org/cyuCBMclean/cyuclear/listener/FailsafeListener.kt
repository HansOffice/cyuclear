package org.cyuCBMclean.cyuclear.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.FailsafeChunkCleanup

object FailsafeListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (!Settings.emergencyChunkUnloadSweepEnabled) return
        FailsafeChunkCleanup.sweep(event.chunk, event.world.name)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!Settings.emergencyChunkLoadSweepEnabled) return
        FailsafeChunkCleanup.sweep(event.chunk, event.world.name)
    }
}

package org.cyuCBMclean.cyuclear.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.cyuCBMclean.cyuclear.service.CandidateChunkIndex

object CandidateChunkListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpawn(event: EntitySpawnEvent) {
        CandidateChunkIndex.mark(event.location.chunk)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        CandidateChunkIndex.consume(CandidateChunkIndex.key(event.chunk))
    }
}

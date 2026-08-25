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
        val loc = event.location
        val world = loc.world ?: return
        CandidateChunkIndex.mark(world, loc.blockX shr 4, loc.blockZ shr 4)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val chunk = event.chunk
        CandidateChunkIndex.consume(chunk.world.uid, chunk.x, chunk.z)
    }
}

package org.cyuCBMclean.cyuclear.service

import org.bukkit.Chunk
import org.bukkit.entity.Item
import org.cyuCBMclean.cyuclear.config.BinEntryRules
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.bridge.StackerBridge
import org.cyuCBMclean.cyuclear.util.ItemIdentity

object FailsafeChunkCleanup {

    fun sweep(chunk: Chunk, worldName: String) {
        if (!ActivationService.isActive()) return
        if (!Settings.itemModuleEnabled) return
        if (!Settings.isWorldEnabled(worldName)) return

        val entities = chunk.entities
        var itemCount = 0
        var specificItemExceeded = false
        val specificCounts = if (Settings.itemSpecificThresholds.isEmpty()) null else HashMap<String, Int>()

        for (i in entities.indices) {
            val entity = entities[i]
            if (entity is Item) {
                val quantity = StackerBridge.quantity(entity)
                itemCount += quantity
                if (specificCounts != null && !specificItemExceeded) {
                    for (rawId in ItemIdentity.matchIds(entity.itemStack)) {
                        val itemId = rawId.lowercase()
                        val threshold = Settings.itemSpecificThresholds[itemId] ?: continue
                        val count = (specificCounts[itemId] ?: 0) + quantity
                        if (count >= threshold) {
                            specificItemExceeded = true
                            break
                        }
                        specificCounts[itemId] = count
                    }
                }
            }
        }

        val totalLimitExceeded = Settings.chunkItemThreshold > 0 && itemCount >= Settings.chunkItemThreshold
        if (totalLimitExceeded || specificItemExceeded) {
            val sweepStarted = System.nanoTime()
            HotspotTracker.recordPressure(
                worldName,
                chunk.x,
                chunk.z,
                HotspotTracker.SubjectKind.ITEM,
                "全部",
                itemCount,
                HotspotTracker.State.THROTTLED
            )
            var recoveredAmount = 0
            var removedAmount = 0
            var recoveryItems: ArrayList<VoidBinManager.RecoveryItem>? = null
            for (i in entities.indices) {
                val entity = entities[i]
                if (entity is Item) {
                    val decision = CleanupFilter.explainItem(entity, honorGrace = false)
                    if (!decision.remove) continue
                    val removal = StackerBridge.prepare(entity)
                    val quantity = removal.quantity()
                    if (Settings.binEnabled && !Settings.clusterEnabled) {
                        val location = entity.location
                        val allowed = BinEntryRules.evaluate(
                            entity.itemStack,
                            BinEntryRules.Source.CLEANUP_RECOVERY,
                            entity.world.name,
                            location.blockX,
                            location.blockY,
                            location.blockZ,
                            decision.itemIds
                        ).allowed
                        if (allowed) {
                            val recovery = VoidBinManager.prepareRecovery(entity.itemStack, quantity) ?: continue
                            val items = recoveryItems
                                ?: ArrayList<VoidBinManager.RecoveryItem>().also { recoveryItems = it }
                            items.add(recovery)
                            recoveredAmount += quantity
                        }
                    }
                    removal.remove()
                    removedAmount += quantity
                }
            }
            HotspotTracker.recordCleanup(worldName, chunk.x, chunk.z, removedAmount, 0, System.nanoTime() - sweepStarted)
            recoveryItems?.let(VoidBinManager::storeBatch)
            if (recoveredAmount > 0) {
                val wasClosed = VoidBinManager.expireTime == 0L
                VoidBinManager.openWindow(Settings.voidBinExpireSeconds)
                if (wasClosed && VoidBinManager.expireTime > 0L) {
                    VoidBinNoticeManager.broadcastOpenHint(Settings.voidBinExpireSeconds)
                }
            }
        }
    }
}

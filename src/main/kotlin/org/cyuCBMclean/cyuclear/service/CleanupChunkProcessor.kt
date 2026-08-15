package org.cyuCBMclean.cyuclear.service

import org.bukkit.Chunk
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.cyuCBMclean.cyuclear.config.BinEntryRules
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.bridge.StackerBridge

object CleanupChunkProcessor {

    data class CleanupPass(
        val cleanItems: Boolean,
        val cleanEntities: Boolean,
        val collectItemsForRecovery: Boolean,
        val honorItemGrace: Boolean
    )

    data class Result(
        val items: Int,
        val entities: Int,
        val scannedEntities: Int,
        val complete: Boolean = true
    )

    private data class RecoveryPreparation(
        val eligible: Boolean,
        val item: VoidBinManager.RecoveryItem?
    )

    fun process(
        chunk: Chunk,
        pass: CleanupPass,
        run: CleanupRunManager.RunHandle,
        shouldContinue: (() -> Boolean)? = null
    ): Result = process(chunk, pass, run, null, shouldContinue)

    fun processWhileCurrent(
        chunk: Chunk,
        pass: CleanupPass,
        run: CleanupRunManager.RunHandle,
        isRunCurrent: () -> Boolean,
        shouldContinue: (() -> Boolean)? = null
    ): Result = process(chunk, pass, run, isRunCurrent, shouldContinue)

    private fun process(
        chunk: Chunk,
        pass: CleanupPass,
        run: CleanupRunManager.RunHandle,
        isRunCurrent: (() -> Boolean)?,
        shouldContinue: (() -> Boolean)?
    ): Result {
        var itemCount = 0
        var entityCount = 0
        var visited = 0
        var complete = true
        val stageTimings = Settings.cleanupStageTimings
        val snapshotStart = if (stageTimings) System.nanoTime() else 0L
        val entities = chunk.entities
        if (stageTimings) CleanupTimings.snapshotSince(snapshotStart)
        val auditBatch = CleanupAudit.newBatch()
        val runBatch = run.batch()
        var recoveryItems: ArrayList<VoidBinManager.RecoveryItem>? = null

        try {
            if (!pass.cleanItems && pass.cleanEntities) {
                for (entity in entities) {
                    if (isRunCurrent != null && !isRunCurrent()) break
                    if (visited > 0 && shouldContinue != null && !shouldContinue()) {
                        complete = false
                        break
                    }
                    visited++
                    val decision = explainEntity(entity, stageTimings)
                    if (!decision.remove) continue

                    val removal = StackerBridge.prepare(entity)
                    val quantity = removal.quantity()
                    runBatch.entity(decision, quantity)
                    auditBatch?.record("实体", decision.id, decision.reason, quantity)
                    removeEntity(removal, stageTimings)
                    entityCount += quantity
                }
            } else {
                for (entity in entities) {
                    if (isRunCurrent != null && !isRunCurrent()) break
                    if (visited > 0 && shouldContinue != null && !shouldContinue()) {
                        complete = false
                        break
                    }
                    visited++
                    if (entity is Item) {
                        val decision = explainItem(entity, pass.honorItemGrace, stageTimings)
                        if (!decision.remove) continue

                        val removal = StackerBridge.prepare(entity)
                        val quantity = removal.quantity()
                        val recovery = prepareRecovery(entity, decision.itemIds, quantity, pass.collectItemsForRecovery)
                        if (recovery.eligible && recovery.item == null) continue
                        if (recovery.item != null) {
                            val items = recoveryItems
                                ?: ArrayList<VoidBinManager.RecoveryItem>().also { recoveryItems = it }
                            items.add(recovery.item)
                        }
                        runBatch.item(decision, quantity)
                        auditBatch?.record("掉落物", decision.id, decision.reason, quantity)
                        if (run.capturesRecovery) runBatch.capture(entity, decision, quantity)
                        removeEntity(removal, stageTimings)
                        itemCount += quantity
                    } else if (pass.cleanEntities) {
                        val decision = explainEntity(entity, stageTimings)
                        if (!decision.remove) continue

                        val removal = StackerBridge.prepare(entity)
                        val quantity = removal.quantity()
                        runBatch.entity(decision, quantity)
                        auditBatch?.record("实体", decision.id, decision.reason, quantity)
                        removeEntity(removal, stageTimings)
                        entityCount += quantity
                    }
                }
            }
        } finally {
            runBatch.scanned(visited)
            try {
                if (isRunCurrent == null || isRunCurrent()) {
                    try {
                        storeRecovery(recoveryItems, stageTimings)
                    } finally {
                        commitAudit(auditBatch, stageTimings)
                    }
                }
            } finally {
                runBatch.commit()
            }
        }

        return Result(itemCount, entityCount, visited, complete)
    }

    private fun prepareRecovery(
        item: Item,
        itemIds: List<String>,
        quantity: Int,
        collectItems: Boolean
    ): RecoveryPreparation {
        if (!collectItems) return RecoveryPreparation(false, null)
        val location = item.location
        val eligible = BinEntryRules.evaluate(
            item.itemStack,
            BinEntryRules.Source.CLEANUP_RECOVERY,
            item.world.name,
            location.blockX,
            location.blockY,
            location.blockZ,
            itemIds
        ).allowed
        return RecoveryPreparation(eligible, if (eligible) VoidBinManager.prepareRecovery(item.itemStack, quantity) else null)
    }

    private fun storeRecovery(items: List<VoidBinManager.RecoveryItem>?, stageTimings: Boolean) {
        if (!stageTimings) {
            items?.let(VoidBinManager::storeBatch)
            return
        }

        val started = System.nanoTime()
        items?.let(VoidBinManager::storeBatch)
        CleanupTimings.recoverySince(started)
    }

    private fun commitAudit(auditBatch: CleanupAudit.Batch?, stageTimings: Boolean) {
        if (!stageTimings) {
            auditBatch?.commit()
            return
        }

        val started = System.nanoTime()
        auditBatch?.commit()
        CleanupTimings.auditSince(started)
    }

    private fun explainEntity(entity: Entity, stageTimings: Boolean): CleanupFilter.FilterDecision {
        if (!stageTimings) return CleanupFilter.explainEntity(entity)
        val started = System.nanoTime()
        return CleanupFilter.explainEntity(entity).also { CleanupTimings.filterSince(started) }
    }

    private fun explainItem(item: Item, honorGrace: Boolean, stageTimings: Boolean): CleanupFilter.FilterDecision {
        if (!stageTimings) return CleanupFilter.explainItem(item, honorGrace)
        val started = System.nanoTime()
        return CleanupFilter.explainItem(item, honorGrace).also { CleanupTimings.filterSince(started) }
    }

    private fun removeEntity(removal: StackerBridge.Prepared, stageTimings: Boolean) {
        if (!stageTimings) {
            removal.remove()
            return
        }

        val started = System.nanoTime()
        removal.remove()
        CleanupTimings.removeSince(started)
    }
}

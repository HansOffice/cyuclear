package org.cyuCBMclean.cyuclear.service

import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.util.Vector
import org.cyuCBMclean.cyuclear.config.BinEntryRules
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.platform.EntityStateBridge
import org.cyuCBMclean.cyuclear.util.EntityUtils
import org.cyuCBMclean.cyuclear.util.ItemText

object InspectService {

    fun inspect(player: Player) {
        val target = findLookTarget(player, 8.0)
        if (target == null) {
            player.sendMessage(Language.get("check-no-target"))
            return
        }

        if (target is Item) {
            val stack = target.itemStack
            val trace = CleanupFilter.traceItem(target)
            val decision = trace.decision
            val rawName = ItemText.displayName(stack)
            val nameView = if (rawName != null) {
                ItemText.normalize(rawName, Settings.itemNameRules.colorMode).ifEmpty { rawName }
            } else {
                null
            }
            val loreView = ItemText.loreLines(stack)
                .map { ItemText.normalize(it, Settings.itemLoreRules.colorMode).ifEmpty { it } }
                .filter { it.isNotEmpty() }

            player.sendMessage(Language.getRaw("check-header"))
            player.sendMessage(Language.get("check-item-id", "id" to decision.id))
            player.sendMessage(Language.get("check-item-ids", "ids" to decision.itemIds.joinToString("、")))
            if (nameView != null) {
                player.sendMessage(Language.get("check-item-name", "name" to nameView))
            } else {
                player.sendMessage(Language.get("check-item-name-none"))
            }
            if (loreView.isNotEmpty()) {
                player.sendMessage(Language.get("check-item-lore", "lore" to loreView.joinToString(" | ")))
            } else {
                player.sendMessage(Language.get("check-item-lore-none"))
            }
            player.sendMessage(Language.get("check-world", "world" to target.world.name))
            sendLocation(player, target)
            player.sendMessage(Language.get("check-age", "seconds" to (target.ticksLived.coerceAtLeast(0) / 20).toString()))
            sendHotspot(player, target)
            player.sendMessage(Language.get("check-reason", "reason" to decision.reason))
            sendNamedRule(player, decision)
            player.sendMessage(Language.get("check-result", "result" to resultText(decision.remove)))
            val targetLocation = target.location
            val recoveryDecision = if (Settings.binEnabled) {
                BinEntryRules.evaluate(
                    stack,
                    BinEntryRules.Source.CLEANUP_RECOVERY,
                    target.world.name,
                    targetLocation.blockX,
                    targetLocation.blockY,
                    targetLocation.blockZ,
                    decision.itemIds
                )
            } else {
                BinEntryRules.Decision(false, "虚空垃圾桶未启用")
            }
            val playerLocation = player.location
            val depositDecision = if (Settings.binEnabled) {
                BinEntryRules.evaluate(
                    stack,
                    BinEntryRules.Source.PLAYER_DEPOSIT,
                    player.world.name,
                    playerLocation.blockX,
                    playerLocation.blockY,
                    playerLocation.blockZ,
                    decision.itemIds
                )
            } else {
                BinEntryRules.Decision(false, "虚空垃圾桶未启用")
            }
            player.sendMessage(
                Language.get(
                    "check-bin-recovery",
                    "result" to allowText(recoveryDecision.allowed),
                    "reason" to recoveryDecision.reason
                )
            )
            player.sendMessage(
                Language.get(
                    "check-bin-deposit",
                    "result" to allowText(depositDecision.allowed),
                    "reason" to depositDecision.reason
                )
            )
            sendTrace(player, trace)
            player.sendMessage(Language.getRaw("check-footer"))
            return
        }

        val trace = CleanupFilter.traceEntity(target)
        val decision = trace.decision
        val ceId = EntityUtils.getCraftEngineFurnitureId(target)
        player.sendMessage(Language.getRaw("check-header"))
        player.sendMessage(Language.get("check-entity-id", "id" to decision.id))
        if (ceId != null) {
            player.sendMessage(Language.get("check-ce-id", "id" to "ce:$ceId"))
        }
        player.sendMessage(Language.get("check-world", "world" to target.world.name))
        sendLocation(player, target)
        player.sendMessage(Language.get("check-age", "seconds" to (target.ticksLived.coerceAtLeast(0) / 20).toString()))
        sendHotspot(player, target)
        val rawEntityName = target.customName
        if (rawEntityName != null) {
            val name = ItemText.normalize(rawEntityName, Settings.entityNameRules.colorMode).ifEmpty { rawEntityName }
            player.sendMessage(Language.get("check-entity-name", "name" to name))
        } else {
            player.sendMessage(Language.get("check-entity-name-none"))
        }
        val tags = EntityStateBridge.scoreboardTags(target)
        if (tags.isEmpty()) player.sendMessage(Language.get("check-scoreboard-tags-none"))
        else player.sendMessage(Language.get("check-scoreboard-tags", "tags" to tags.sorted().joinToString(", ")))
        player.sendMessage(Language.get("check-named", "value" to yesNo(rawEntityName != null)))
        player.sendMessage(Language.get("check-persistent", "value" to yesNo(EntityStateBridge.isPersistent(target))))
        if (target is LivingEntity) {
            player.sendMessage(Language.get("check-no-despawn", "value" to yesNo(!target.removeWhenFarAway)))
        }
        if (target is Tameable) {
            player.sendMessage(Language.get("check-tamed", "value" to yesNo(target.isTamed)))
        }
        player.sendMessage(Language.get("check-raid-event", "value" to yesNo(EntityStateBridge.isInRaid(target))))
        player.sendMessage(Language.get("check-passengers", "value" to yesNo(EntityStateBridge.hasPassengers(target))))
        player.sendMessage(Language.get("check-vehicle", "value" to yesNo(EntityStateBridge.hasVehicle(target))))
        decision.detailRule?.let { player.sendMessage(Language.get("check-detail-rule", "rule" to it)) }
        sendNamedRule(player, decision)
        if (decision.bypassedProtections.isNotEmpty()) {
            val bypasses = decision.bypassedProtections.joinToString(", ") { it.name.lowercase().replace('_', '-') }
            player.sendMessage(Language.get("check-bypasses", "protections" to bypasses))
        }
        player.sendMessage(Language.get("check-reason", "reason" to decision.reason))
        player.sendMessage(Language.get("check-result", "result" to resultText(decision.remove)))
        sendTrace(player, trace)
        player.sendMessage(Language.getRaw("check-footer"))
    }

    private fun findLookTarget(player: Player, range: Double): org.bukkit.entity.Entity? {
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val rangeSquared = range * range

        return player.getNearbyEntities(range, range, range)
            .asSequence()
            .filter { it.uniqueId != player.uniqueId }
            .mapNotNull { entity ->
                val toEntity = entity.location.clone().add(0.0, EntityStateBridge.height(entity) * 0.5, 0.0).toVector().subtract(eye.toVector())
                val distanceSquared = toEntity.lengthSquared()
                if (distanceSquared > rangeSquared || distanceSquared <= 0.0001) return@mapNotNull null

                val distance = Math.sqrt(distanceSquared)
                val normalized = toEntity.clone().normalize()
                val dot = normalized.dot(direction)
                if (dot < 0.965) return@mapNotNull null

                val lineDistance = distanceToLine(toEntity, direction)
                if (lineDistance > 1.15) return@mapNotNull null

                entity to distance
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun distanceToLine(vector: Vector, direction: Vector): Double {
        val projectionLength = vector.dot(direction)
        val projection = direction.clone().multiply(projectionLength)
        return vector.clone().subtract(projection).length()
    }

    private fun yesNo(value: Boolean): String {
        return if (value) "是" else "否"
    }

    private fun resultText(remove: Boolean): String {
        return if (remove) "会清理" else "不清理"
    }

    private fun allowText(allowed: Boolean): String {
        return if (allowed) "允许" else "拒绝"
    }

    private fun sendNamedRule(player: Player, decision: CleanupFilter.FilterDecision) {
        decision.namedRule?.let { player.sendMessage(Language.get("check-named-rule", "rule" to it)) }
        decision.rulePriority?.let { player.sendMessage(Language.get("check-rule-priority", "priority" to it.toString())) }
    }

    private fun sendLocation(player: Player, target: org.bukkit.entity.Entity) {
        val location = target.location
        player.sendMessage(
            Language.get(
                "check-location",
                "x" to location.blockX.toString(),
                "y" to location.blockY.toString(),
                "z" to location.blockZ.toString(),
                "chunk_x" to (location.blockX shr 4).toString(),
                "chunk_z" to (location.blockZ shr 4).toString()
            )
        )
    }

    private fun sendHotspot(player: Player, target: org.bukkit.entity.Entity) {
        val location = target.location
        val hotspot = HotspotTracker.find(target.world.name, location.blockX shr 4, location.blockZ shr 4)
        if (hotspot == null) {
            player.sendMessage(Language.get("check-hotspot-none"))
            return
        }
        player.sendMessage(
            Language.get(
                "check-hotspot",
                "state" to hotspot.state.display,
                "items" to hotspot.itemCount.toString(),
                "entities" to hotspot.entityCount.toString(),
                "triggers" to hotspot.triggerCount.toString()
            )
        )
    }

    private fun sendTrace(player: Player, trace: DecisionTrace) {
        trace.steps.forEach { step ->
            player.sendMessage(Language.get("check-trace", "stage" to step.stage, "detail" to step.detail))
        }
    }
}

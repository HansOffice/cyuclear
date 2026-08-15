package org.cyuCBMclean.cyuclear.platform

import org.bukkit.entity.Entity
import org.bukkit.entity.Raider

object EntityStateBridge {
    fun isPersistent(entity: Entity): Boolean = entity.isPersistent

    fun height(entity: Entity): Double = entity.height

    fun scoreboardTags(entity: Entity): Set<String> = entity.scoreboardTags

    fun hasPassengers(entity: Entity): Boolean = entity.passengers.isNotEmpty()

    fun hasVehicle(entity: Entity): Boolean = entity.vehicle != null

    fun isInRaid(entity: Entity): Boolean = (entity as? Raider)?.raid != null
}

package org.cyuCBMclean.cyuclear.platform

import org.bukkit.entity.Entity

@Suppress("UNUSED_PARAMETER")
object EntityStateBridge {
    fun isPersistent(entity: Entity): Boolean = false

    fun height(entity: Entity): Double = 1.0

    fun scoreboardTags(entity: Entity): Set<String> = emptySet()

    fun hasPassengers(entity: Entity): Boolean = false

    fun hasVehicle(entity: Entity): Boolean = false

    fun isInRaid(entity: Entity): Boolean = false
}

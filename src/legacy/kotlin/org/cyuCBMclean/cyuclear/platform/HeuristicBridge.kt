package org.cyuCBMclean.cyuclear.platform

import org.bukkit.block.Block
import org.bukkit.entity.Minecart
import org.bukkit.entity.Villager

@Suppress("UNUSED_PARAMETER", "DEPRECATION")
object HeuristicBridge {

    private val TARGET_RAILS = setOf("POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL")

    fun isTradedVillager(villager: Villager): Boolean = false

    fun isWorkstationVillager(villager: Villager): Boolean = false

    fun isIronFarmVillager(villager: Villager): Boolean = false

    fun isPoweredVehicle(minecart: Minecart): Boolean {
        val blockAt = minecart.location.block
        val blockBelow = blockAt.getRelative(0, -1, 0)
        val rail = when {
            isTargetRail(blockAt) -> blockAt
            isTargetRail(blockBelow) -> blockBelow
            else -> null
        } ?: return false

        return rail.isBlockPowered || rail.isBlockIndirectlyPowered || ((rail.data.toInt() and 0x8) != 0)
    }

    private fun isTargetRail(block: Block): Boolean {
        return block.type.name in TARGET_RAILS
    }
}

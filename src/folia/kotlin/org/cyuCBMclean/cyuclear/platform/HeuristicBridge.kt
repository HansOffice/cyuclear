package org.cyuCBMclean.cyuclear.platform

import org.bukkit.block.Block
import org.bukkit.block.data.Powerable
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Villager
import org.bukkit.entity.memory.MemoryKey
import org.bukkit.event.entity.EntityDamageByEntityEvent

object HeuristicBridge {

    private val TARGET_RAILS = setOf("POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL")

    private val WORKSTATION_BLOCKS = setOf(
        "LECTERN",
        "BLAST_FURNACE",
        "SMOKER",
        "CARTOGRAPHY_TABLE",
        "BREWING_STAND",
        "COMPOSTER",
        "BARREL",
        "FLETCHING_TABLE",
        "CAULDRON",
        "WATER_CAULDRON",
        "LAVA_CAULDRON",
        "POWDER_SNOW_CAULDRON",
        "SMITHING_TABLE",
        "LOOM",
        "STONECUTTER",
        "GRINDSTONE"
    )

    private val THREATENING_MOB_NAMES = setOf(
        "ZOMBIE", "DROWNED", "HUSK", "ZOMBIE_VILLAGER",
        "PILLAGER", "VINDICATOR", "RAVAGER", "EVOKER", "VEX", "ILLUSIONER"
    )

    fun isTradedVillager(villager: Villager): Boolean {
        if (villager.villagerExperience > 0) return true
        val recipes = villager.recipes
        if (recipes.any { it.uses > 0 }) return true
        return recipes.isNotEmpty()
    }

    fun isWorkstationVillager(villager: Villager): Boolean {
        val profession = villager.profession
        if (profession == Villager.Profession.NONE || profession == Villager.Profession.NITWIT) {
            return false
        }
        val loc = villager.location
        val world = loc.world ?: return false
        val cx = loc.blockX
        val cy = loc.blockY
        val cz = loc.blockZ

        var hasWorkstation = false
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    val blockType = world.getBlockAt(cx + dx, cy + dy, cz + dz).type.name
                    if (blockType in WORKSTATION_BLOCKS) {
                        hasWorkstation = true
                        break
                    }
                }
                if (hasWorkstation) break
            }
            if (hasWorkstation) break
        }
        if (!hasWorkstation) return false

        var obstructedCount = 0
        val offsets = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        for ((ox, oz) in offsets) {
            val footBlock = world.getBlockAt(cx + ox, cy, cz + oz)
            val eyeBlock = world.getBlockAt(cx + ox, cy + 1, cz + oz)
            if (isObstructed(footBlock) || isObstructed(eyeBlock)) {
                obstructedCount++
            }
        }
        return obstructedCount >= 2
    }

    fun isIronFarmVillager(villager: Villager): Boolean {
        val loc = villager.location
        val world = loc.world ?: return false
        val cx = loc.blockX
        val cy = loc.blockY
        val cz = loc.blockZ

        val nearby = villager.getNearbyEntities(10.0, 5.0, 10.0)
        var villagerCount = 1
        var hasThreateningMob = false

        for (e in nearby) {
            if (e is Villager) {
                villagerCount++
            } else if (e is LivingEntity) {
                if (e.type.name in THREATENING_MOB_NAMES || e.javaClass.simpleName in THREATENING_MOB_NAMES) {
                    hasThreateningMob = true
                }
            }
        }

        if (villagerCount < 2) return false
        if (isVillagerPanicking(villager)) return true

        var hasBeds = false
        for (dx in -4..4) {
            for (dy in -2..2) {
                for (dz in -4..4) {
                    val mat = world.getBlockAt(cx + dx, cy + dy, cz + dz).type.name
                    if (mat.endsWith("_BED") || mat == "BED" || mat == "BED_BLOCK") {
                        hasBeds = true
                        break
                    }
                }
                if (hasBeds) break
            }
            if (hasBeds) break
        }

        return (hasThreateningMob && hasBeds) || (hasThreateningMob && villagerCount >= 3)
    }

    fun isPoweredVehicle(minecart: Minecart): Boolean {
        val blockAt = minecart.location.block
        val blockBelow = blockAt.getRelative(0, -1, 0)
        val rail = when {
            isTargetRail(blockAt) -> blockAt
            isTargetRail(blockBelow) -> blockBelow
            else -> null
        } ?: return false

        return (rail.blockData as? Powerable)?.isPowered == true || rail.isBlockPowered
    }

    private fun isVillagerPanicking(villager: Villager): Boolean {
        if (villager.getMemory(MemoryKey.IS_PANICKING) == true) return true
        if (villager.getMemory(MemoryKey.DANGER_DETECTED_RECENTLY) == true) return true
        if (villager.getMemory(MemoryKey.GOLEM_DETECTED_RECENTLY) == true) return true
        if (villager.noDamageTicks > 0) {
            val cause = villager.lastDamageCause
            if (cause is EntityDamageByEntityEvent) {
                if (cause.damager.type.name in THREATENING_MOB_NAMES) return true
            }
        }
        return false
    }

    private fun isObstructed(block: Block): Boolean {
        val type = block.type.name
        if (type in WORKSTATION_BLOCKS) return true
        if (type == "FENCE" || type.endsWith("_FENCE") ||
            type == "FENCE_GATE" || type.endsWith("_FENCE_GATE") ||
            type == "TRAPDOOR" || type == "TRAP_DOOR" || type.endsWith("_TRAPDOOR") ||
            type.endsWith("_WALL") || type.endsWith("_PANE") || type.contains("GLASS") ||
            type.endsWith("_SLAB") || type.endsWith("_STAIRS") || type.endsWith("_DOOR") ||
            type == "IRON_BARS" || type == "BARRIER"
        ) return true
        if (!block.isPassable) return true
        val mat = block.type
        return mat.isSolid || mat.isOccluding
    }

    private fun isTargetRail(block: Block): Boolean {
        return block.type.name in TARGET_RAILS
    }
}

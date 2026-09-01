package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object TeleportService : Listener {

    private data class OriginRecord(
        val location: Location,
        val timestamp: Long
    )

    private val lastOriginMap = ConcurrentHashMap<UUID, OriginRecord>()
    private val protectedAdmins = ConcurrentHashMap<UUID, Long>()

    fun recordOrigin(player: Player) {
        if (!Settings.teleportBackEnabled) return
        lastOriginMap[player.uniqueId] = OriginRecord(player.location.clone(), System.currentTimeMillis())
    }

    fun applyProtection(player: Player) {
        if (!Settings.teleportLandingProtectionEnabled) return
        val durationMillis = Settings.teleportLandingProtectionDurationSeconds.coerceIn(1, 60) * 1000L
        protectedAdmins[player.uniqueId] = System.currentTimeMillis() + durationMillis
    }

    fun isProtected(player: Player): Boolean {
        if (!Settings.teleportLandingProtectionEnabled) return false
        val expire = protectedAdmins[player.uniqueId] ?: return false
        if (System.currentTimeMillis() < expire) {
            return true
        }
        protectedAdmins.remove(player.uniqueId)
        return false
    }

    fun clearPlayer(uuid: UUID) {
        lastOriginMap.remove(uuid)
        protectedAdmins.remove(uuid)
    }

    fun reset() {
        lastOriginMap.clear()
        protectedAdmins.clear()
    }

    fun teleport(
        player: Player,
        worldName: String,
        x: Double,
        y: Double?,
        z: Double?,
        saveOrigin: Boolean = true,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val world = Bukkit.getWorld(worldName)
        if (world == null) {
            player.sendMessage(Language.get("teleport-world-not-found", "world" to worldName))
            onComplete?.invoke(false)
            return
        }

        if (saveOrigin) {
            recordOrigin(player)
        }

        val targetZ = z ?: 0.0
        val asyncMethod = runCatching {
            player.javaClass.getMethod("teleportAsync", Location::class.java)
        }.getOrNull()

        if (asyncMethod != null) {
            val initialY = y ?: when (world.environment) {
                World.Environment.NETHER -> 70.0
                else -> minOf(120.0, (world.maxHeight - 2).toDouble())
            }
            val initialLoc = Location(world, x, initialY, targetZ, player.location.yaw, player.location.pitch)
            val future = asyncMethod.invoke(player, initialLoc) as? CompletableFuture<*>
            if (future != null) {
                future.thenAccept { success ->
                    CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
                        if (!player.isOnline || !player.isValid) {
                            onComplete?.invoke(false)
                            return@Runnable
                        }
                        if (success == true) {
                            val safeLoc = findSafeLocation(player.world, x, y, targetZ, player.location.yaw, player.location.pitch)
                            if (safeLoc.blockY != player.location.blockY || safeLoc.blockX != player.location.blockX || safeLoc.blockZ != player.location.blockZ) {
                                player.teleport(safeLoc)
                            }
                            applyProtection(player)
                            player.sendMessage(
                                Language.get(
                                    "teleport-success",
                                    "world" to player.world.name,
                                    "x" to safeLoc.blockX.toString(),
                                    "y" to safeLoc.blockY.toString(),
                                    "z" to safeLoc.blockZ.toString()
                                )
                            )
                            onComplete?.invoke(true)
                        } else {
                            player.sendMessage(Language.get("teleport-failed", "reason" to "目标区域拒绝传送"))
                            onComplete?.invoke(false)
                        }
                    })
                }
                return
            }
        }

        CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
            if (!player.isOnline || !player.isValid) {
                onComplete?.invoke(false)
                return@Runnable
            }
            val safeLoc = findSafeLocation(world, x, y, targetZ, player.location.yaw, player.location.pitch)
            val ok = player.teleport(safeLoc)
            if (ok) {
                applyProtection(player)
                player.sendMessage(
                    Language.get(
                        "teleport-success",
                        "world" to world.name,
                        "x" to safeLoc.blockX.toString(),
                        "y" to safeLoc.blockY.toString(),
                        "z" to safeLoc.blockZ.toString()
                    )
                )
                onComplete?.invoke(true)
            } else {
                player.sendMessage(Language.get("teleport-failed", "reason" to "传送失败"))
                onComplete?.invoke(false)
            }
        })
    }

    fun teleportBack(player: Player): Boolean {
        if (!Settings.teleportBackEnabled) {
            player.sendMessage(Language.get("teleport-back-disabled"))
            return false
        }
        val record = lastOriginMap[player.uniqueId]
        if (record == null) {
            player.sendMessage(Language.get("teleport-no-back-location"))
            return false
        }
        if (Settings.teleportBackTimeoutSeconds > 0) {
            val elapsed = System.currentTimeMillis() - record.timestamp
            if (elapsed > Settings.teleportBackTimeoutSeconds * 1000L) {
                lastOriginMap.remove(player.uniqueId)
                player.sendMessage(Language.get("teleport-back-expired"))
                return false
            }
        }
        val origin = record.location
        val targetWorld = origin.world
        if (targetWorld == null) {
            player.sendMessage(Language.get("teleport-world-not-found", "world" to "未知"))
            lastOriginMap.remove(player.uniqueId)
            return false
        }

        teleport(
            player = player,
            worldName = targetWorld.name,
            x = origin.x,
            y = origin.y,
            z = origin.z,
            saveOrigin = false
        ) { success ->
            if (success) {
                lastOriginMap.remove(player.uniqueId)
            }
        }
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (isProtected(player)) {
            event.isCancelled = true
            if (Settings.teleportLandingProtectionNotify) {
                player.sendMessage(Language.get("teleport-damage-protected"))
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player.uniqueId)
    }

    fun findSafeLocation(
        world: World,
        targetX: Double,
        targetY: Double?,
        targetZ: Double?,
        yaw: Float = 0f,
        pitch: Float = 0f
    ): Location {
        val blockX = targetX.toInt()
        val blockZ = (targetZ ?: 0.0).toInt()

        val minHeight: Int = runCatching {
            val method = world.javaClass.getMethod("getMinHeight")
            (method.invoke(world) as? Number)?.toInt() ?: 0
        }.getOrDefault(0)
        val maxHeight: Int = world.maxHeight

        var chosenY: Double

        if (targetY == null) {
            if (world.environment == World.Environment.NETHER) {
                chosenY = findNetherSafeY(world, blockX, blockZ, minHeight, maxHeight)
            } else {
                val highest = world.getHighestBlockYAt(blockX, blockZ)
                chosenY = (highest + 1).toDouble().coerceIn(minHeight.toDouble() + 1, maxHeight.toDouble() - 2)
            }
        } else {
            val initY = targetY.toInt().coerceIn(minHeight + 1, maxHeight - 3)
            val adjusted = findAdjustedSafeY(world, blockX, initY, blockZ, minHeight, maxHeight)
            chosenY = adjusted.toDouble()
        }

        return Location(world, blockX + 0.5, chosenY, blockZ + 0.5, yaw, pitch)
    }

    private fun findNetherSafeY(world: World, x: Int, z: Int, minHeight: Int, maxHeight: Int): Double {
        val startY = minOf(120, maxHeight - 10)
        for (y in startY downTo (minHeight + 5)) {
            val feet = world.getBlockAt(x, y, z)
            val head = world.getBlockAt(x, y + 1, z)
            val ground = world.getBlockAt(x, y - 1, z)
            if (isPassable(feet) && isPassable(head) && isSolidGround(ground)) {
                return y.toDouble()
            }
        }
        val fallback = world.getHighestBlockYAt(x, z)
        return (fallback + 1).toDouble()
    }

    private fun findAdjustedSafeY(world: World, x: Int, startY: Int, z: Int, minHeight: Int, maxHeight: Int): Int {
        val directFeet = world.getBlockAt(x, startY, z)
        val directHead = world.getBlockAt(x, startY + 1, z)
        val directGround = world.getBlockAt(x, startY - 1, z)
        if (isPassable(directFeet) && isPassable(directHead) && isSolidGround(directGround)) {
            return startY
        }

        for (offset in 1..25) {
            val upY = startY + offset
            if (upY < maxHeight - 2) {
                val feet = world.getBlockAt(x, upY, z)
                val head = world.getBlockAt(x, upY + 1, z)
                val ground = world.getBlockAt(x, upY - 1, z)
                if (isPassable(feet) && isPassable(head) && isSolidGround(ground)) {
                    return upY
                }
            }
        }

        for (offset in 1..25) {
            val downY = startY - offset
            if (downY > minHeight + 1) {
                val feet = world.getBlockAt(x, downY, z)
                val head = world.getBlockAt(x, downY + 1, z)
                val ground = world.getBlockAt(x, downY - 1, z)
                if (isPassable(feet) && isPassable(head) && isSolidGround(ground)) {
                    return downY
                }
            }
        }

        val highest = world.getHighestBlockYAt(x, z)
        return highest + 1
    }

    private fun isPassable(block: Block): Boolean {
        if (block.type.isSolid) return false
        val name = block.type.name
        if (name.contains("LAVA") || name.contains("FIRE") || name.contains("CACTUS")) return false
        return true
    }

    private fun isSolidGround(block: Block): Boolean {
        if (!block.type.isSolid) return false
        val name = block.type.name
        if (name.contains("LAVA") || name.contains("FIRE") || name.contains("MAGMA")) return false
        return true
    }
}

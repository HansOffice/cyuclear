package org.cyuCBMclean.cyuclear.platform

import org.bukkit.entity.Entity
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

object EntityStateBridge {
    private val getRaidMethods = ConcurrentHashMap<Class<*>, Optional<Method>>()

    fun isPersistent(entity: Entity): Boolean = entity.isPersistent

    fun height(entity: Entity): Double = entity.height

    fun scoreboardTags(entity: Entity): Set<String> = entity.scoreboardTags

    fun hasPassengers(entity: Entity): Boolean = entity.passengers.isNotEmpty()

    fun hasVehicle(entity: Entity): Boolean = entity.vehicle != null

    fun isInRaid(entity: Entity): Boolean {
        val type = entity.javaClass
        val method = getRaidMethods[type] ?: resolveRaidMethod(type)
        if (!method.isPresent) return false
        return try {
            method.get().invoke(entity) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveRaidMethod(type: Class<*>): Optional<Method> {
        val resolved = Optional.ofNullable(type.methods.firstOrNull { it.name == "getRaid" && it.parameterCount == 0 })
        return getRaidMethods.putIfAbsent(type, resolved) ?: resolved
    }
}

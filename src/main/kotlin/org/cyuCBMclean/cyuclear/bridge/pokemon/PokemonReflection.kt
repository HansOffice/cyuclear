package org.cyuCBMclean.cyuclear.bridge.pokemon

import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Stream

object PokemonReflection {

    private val allowedMethods = setOf(
        "getHandle",
        "getPokemon",
        "getPokemonData",
        "getStoragePokemonData",
        "getSpecies",
        "getForm",
        "getFormEnum",
        "getExposedSpecies",
        "getExposedForm",
        "getExposedAspects",
        "getAspects",
        "getLabels",
        "getNationalPokedexNumber",
        "getName",
        "getPokemonName",
        "getResourceIdentifier",
        "getDex",
        "getGeneration",
        "getPalette",
        "isShiny",
        "getShiny",
        "getGmaxFactor",
        "getTags",
        "isLegendary",
        "isMythical",
        "isUltraBeast",
        "isParadox",
        "isMega",
        "isGigantamax",
        "hasGigantamaxFactor",
        "isBoss",
        "isBossPokemon",
        "isRegional",
        "isGalarian",
        "isAlolan",
        "isHisuian",
        "isPaldean",
        "isMegaForm",
        "hasMegaForm",
        "isGigantamaxForm",
        "hasGigantamaxForm",
        "getRegionalTag",
        "isPlayerOwned",
        "isNPCOwned",
        "isWild",
        "getOwnerUUID",
        "getOwnerTrainerUUID",
        "getOwnerPlayerUUID",
        "getOwnerPlayer",
        "getOwnerTrainer",
        "getOwner",
        "getOwnerEntity"
    )

    private val methodCache = ConcurrentHashMap<String, Optional<Method>>()

    fun call(target: Any?, methodName: String): Any? {
        if (target == null || methodName !in allowedMethods) return null

        val method = findZeroArgMethod(target.javaClass, methodName) ?: return null
        return try {
            method.invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    fun handle(target: Any): Any? {
        return call(target, "getHandle")
    }

    fun text(value: Any?): String? {
        if (value == null) return null

        val raw = when (value) {
            is String -> value
            is Enum<*> -> value.name
            else -> value.toString()
        }

        return normalize(raw)
    }

    fun intValue(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> value?.toString()?.toIntOrNull()
        }
    }

    fun booleanValue(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }
    }

    fun texts(value: Any?): Set<String> {
        if (value == null) return emptySet()

        val result = LinkedHashSet<String>()

        when (value) {
            is Iterable<*> -> value.forEach { text(it)?.let(result::add) }
            is Array<*> -> value.forEach { text(it)?.let(result::add) }
            is Stream<*> -> value.forEach { text(it)?.let(result::add) }
            else -> {
                val nestedTags = call(value, "getTags")
                if (nestedTags != null && nestedTags !== value) {
                    result.addAll(texts(nestedTags))
                } else {
                    text(value)?.let(result::add)
                }
            }
        }

        return result
    }

    fun normalize(raw: String): String? {
        var value = raw.trim().lowercase()
        if (value.isEmpty()) return null

        val colonIndex = value.lastIndexOf(':')
        if (colonIndex >= 0 && colonIndex + 1 < value.length) {
            value = value.substring(colonIndex + 1)
        }

        value = value
            .replace(' ', '_')
            .replace("/", "_")
            .replace("\\", "_")
            .trim('_')

        return value.takeIf { it.isNotEmpty() }
    }

    fun addNormalizedAliases(target: MutableSet<String>, value: String?) {
        if (value.isNullOrBlank()) return
        target.add(value)

        val underline = value.replace('-', '_')
        if (underline != value) {
            target.add(underline)
        }

        when (value) {
            "ultrabeast" -> target.add("ultra_beast")
            "gigantamax" -> target.add("gmax")
            "g-max" -> target.add("gmax")
            "gmax" -> target.add("gigantamax")
        }
    }

    private fun findZeroArgMethod(clazz: Class<*>, methodName: String): Method? {
        val key = clazz.name + "#" + methodName
        return methodCache.computeIfAbsent(key) {
            Optional.ofNullable(clazz.methods.firstOrNull { method ->
                method.name == methodName && method.parameterCount == 0
            })
        }.orElse(null)
    }
}

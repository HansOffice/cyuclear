package org.cyuCBMclean.cyuclear.bridge.pokemon

import org.bukkit.entity.Entity
import org.cyuCBMclean.cyuclear.config.Settings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object PokemonEntityHook {

    private const val CACHE_MILLIS = 500L
    private const val MAX_CACHE_SIZE = 4096
    private const val MAX_PROVIDER_HINTS = 512
    private val cache = ConcurrentHashMap<CacheKey, CachedIds>()
    private val providerHints = ConcurrentHashMap<ProviderKey, ProviderHint>()
    private val pruningCache = AtomicBoolean(false)
    private val scanHold = AtomicInteger(0)

    fun getFilterIds(entity: Entity, namespaceId: String): Set<String> {
        val rulesPresent = Settings.entityPokemonRulesPresent || Settings.entityRealtimePokemonRulesPresent
        val fullRulesRequired = Settings.entityPokemonFullRulesRequired || Settings.entityRealtimePokemonFullRulesRequired
        val lightRules = Settings.entityPokemonLightRules + Settings.entityRealtimePokemonLightRules
        return getFilterIds(entity, namespaceId, rulesPresent, fullRulesRequired, lightRules)
    }

    fun getFilterIds(
        entity: Entity,
        namespaceId: String,
        rulesPresent: Boolean,
        fullRulesRequired: Boolean,
        lightRules: Set<String>,
        requireOwnedId: Boolean = false
    ): Set<String> {
        if (!Settings.entityPokemonEnabled) return emptySet()
        val includeOwned = Settings.entityPokemonIgnorePlayerOwned || requireOwnedId
        if (!rulesPresent && !includeOwned) return emptySet()

        val profile = cacheProfile(rulesPresent, fullRulesRequired)
        val cacheKey = CacheKey(
            uuid = entity.uniqueId,
            profile = profile,
            lightRules = if (fullRulesRequired) emptySet() else lightRules,
            ownedProtection = !fullRulesRequired && includeOwned
        )
        val now = System.currentTimeMillis()
        val cached = cache[cacheKey]
        if (cached != null && (cached.expiresAt > now || cached.pinned && holdingScan())) {
            return cached.ids
        }

        val ids = when {
            rulesPresent && fullRulesRequired -> {
                resolve(entity, namespaceId)?.toFilterIds().orEmpty()
            }
            rulesPresent -> {
                resolveLight(entity, namespaceId, lightRules, includeOwned)
            }
            else -> {
                resolveOwnedOnly(entity, namespaceId)
            }
        }

        if (cache.size >= MAX_CACHE_SIZE) pruneCache(now)
        if (cache.size >= MAX_CACHE_SIZE && !holdingScan()) cache.clear()
        cache[cacheKey] = CachedIds(now + CACHE_MILLIS, ids, holdingScan())
        return ids
    }

    fun beginScanHold() {
        scanHold.incrementAndGet()
    }

    fun endScanHold() {
        while (true) {
            val current = scanHold.get()
            if (current <= 0) return
            if (!scanHold.compareAndSet(current, current - 1)) continue
            if (current > 1) return
            pruneCache(System.currentTimeMillis())
            if (cache.size >= MAX_CACHE_SIZE) cache.clear()
            return
        }
    }

    private fun holdingScan(): Boolean = scanHold.get() > 0

    private fun cacheProfile(rulesPresent: Boolean, fullRulesRequired: Boolean): Int {
        return when {
            rulesPresent && fullRulesRequired -> 2
            rulesPresent -> 1
            else -> 0
        }
    }

    private fun pruneCache(now: Long) {
        if (!pruningCache.compareAndSet(false, true)) return
        try {
            cache.entries.removeIf { entry ->
                entry.value.expiresAt <= now && !(entry.value.pinned && holdingScan())
            }
            if (cache.size >= MAX_CACHE_SIZE && !holdingScan()) cache.clear()
        } finally {
            pruningCache.set(false)
        }
    }

    private fun resolve(entity: Entity, namespaceId: String): PokemonTraits? {
        val resolved = resolveSource(entity, namespaceId) ?: return null

        return when (resolved.provider) {
            "cobblemon" -> resolveCobblemon(resolved.source)
            "pixelmon" -> resolvePixelmon(resolved.source)
            else -> null
        }
    }

    private fun resolveSource(entity: Entity, namespaceId: String): ProviderSource? {
        val direct = entity
        val key = ProviderKey(direct.javaClass, namespaceId)
        val hint = providerHints[key]
        if (hint == ProviderHint.NONE) return null

        val directProvider = hint?.provider ?: detectProvider(namespaceId, direct, null)
        if (directProvider != null) {
            val source = selectSource(directProvider, direct, null)
            if (source != null) {
                cacheProvider(key, directProvider)
                return ProviderSource(directProvider, source)
            }
        }

        val handle = PokemonReflection.handle(entity)
        val provider = directProvider ?: detectProvider(namespaceId, direct, handle)
        if (provider == null) {
            cacheAbsentProvider(key, namespaceId)
            return null
        }
        val source = selectSource(provider, direct, handle) ?: return null
        cacheProvider(key, provider)
        return ProviderSource(provider, source)
    }

    private fun selectSource(provider: String, direct: Any, handle: Any?): Any? {
        if (classNameContains(direct, provider) || findPokemonData(direct) != null) {
            return direct
        }

        if (handle != null && (classNameContains(handle, provider) || findPokemonData(handle) != null)) {
            return handle
        }

        return null
    }

    private fun resolveCobblemon(source: Any): PokemonTraits? {
        val pokemon = findPokemonData(source) ?: return null
        val species = PokemonReflection.call(pokemon, "getSpecies")
        val form = PokemonReflection.call(pokemon, "getForm")

        val aspects = LinkedHashSet<String>()
        PokemonReflection.texts(PokemonReflection.call(pokemon, "getAspects")).forEach {
            PokemonReflection.addNormalizedAliases(aspects, it)
        }
        PokemonReflection.texts(PokemonReflection.call(form, "getAspects")).forEach {
            PokemonReflection.addNormalizedAliases(aspects, it)
        }

        val tags = LinkedHashSet<String>()
        PokemonReflection.texts(PokemonReflection.call(species, "getLabels")).forEach {
            PokemonReflection.addNormalizedAliases(tags, it)
        }
        PokemonReflection.texts(PokemonReflection.call(form, "getLabels")).forEach {
            PokemonReflection.addNormalizedAliases(tags, it)
        }
        addBooleanTag(tags, pokemon, "isLegendary", "legendary")
        addBooleanTag(tags, pokemon, "isMythical", "mythical")
        addBooleanTag(tags, pokemon, "isUltraBeast", "ultra_beast")
        addBooleanTag(tags, species, "isLegendary", "legendary")
        addBooleanTag(tags, species, "isMythical", "mythical")
        addBooleanTag(tags, species, "isUltraBeast", "ultra_beast")
        addBooleanTag(tags, source, "isLegendary", "legendary")
        addBooleanTag(tags, source, "isMythical", "mythical")
        addBooleanTag(tags, source, "isUltraBeast", "ultra_beast")

        val generation = findGeneration(tags)
        val shiny = aspects.contains("shiny") ||
            PokemonReflection.booleanValue(PokemonReflection.call(pokemon, "getShiny")) ||
            PokemonReflection.booleanValue(PokemonReflection.call(pokemon, "isShiny"))
        val playerOwned = isCobblemonOwned(pokemon, source)

        return PokemonTraits(
            provider = "cobblemon",
            species = findFirstText(species, "getResourceIdentifier", "getName"),
            dex = PokemonReflection.intValue(PokemonReflection.call(species, "getNationalPokedexNumber")),
            generation = generation,
            form = findFirstText(form, "getName"),
            aspects = aspects,
            tags = tags,
            shiny = shiny,
            playerOwned = playerOwned
        )
    }

    private fun resolvePixelmon(source: Any): PokemonTraits? {
        val pokemon = findPokemonData(source) ?: return null
        val species = PokemonReflection.call(pokemon, "getSpecies")
        val form = PokemonReflection.call(pokemon, "getFormEnum") ?: PokemonReflection.call(pokemon, "getForm")
        val palette = PokemonReflection.call(pokemon, "getPalette")

        val tags = LinkedHashSet<String>()
        PokemonReflection.texts(PokemonReflection.call(form, "getTags")).forEach {
            PokemonReflection.addNormalizedAliases(tags, it)
        }
        PokemonReflection.texts(PokemonReflection.call(palette, "getTags")).forEach {
            PokemonReflection.addNormalizedAliases(tags, it)
        }
        addBooleanTag(tags, pokemon, "isLegendary", "legendary")
        addBooleanTag(tags, pokemon, "isMythical", "mythical")
        addBooleanTag(tags, pokemon, "isUltraBeast", "ultra_beast")
        addBooleanTag(tags, pokemon, "isParadox", "paradox")
        addBooleanTag(tags, pokemon, "isMega", "mega")
        addBooleanTag(tags, pokemon, "isGigantamax", "gigantamax")
        addBooleanTag(tags, pokemon, "hasGigantamaxFactor", "gmax")
        addBooleanTag(tags, pokemon, "isBoss", "boss")
        addBooleanTag(tags, pokemon, "isBossPokemon", "boss")
        addBooleanTag(tags, species, "isLegendary", "legendary")
        addBooleanTag(tags, species, "isMythical", "mythical")
        addBooleanTag(tags, species, "isUltraBeast", "ultra_beast")
        addBooleanTag(tags, source, "isLegendary", "legendary")
        addBooleanTag(tags, source, "isMythical", "mythical")
        addBooleanTag(tags, source, "isUltraBeast", "ultra_beast")
        addBooleanTag(tags, source, "isParadox", "paradox")
        addBooleanTag(tags, source, "isMega", "mega")
        addBooleanTag(tags, source, "isGigantamax", "gigantamax")
        addBooleanTag(tags, source, "hasGigantamaxFactor", "gmax")
        addBooleanTag(tags, source, "isBoss", "boss")
        addBooleanTag(tags, source, "isBossPokemon", "boss")
        addBooleanTag(tags, form, "isRegional", "regional")
        addBooleanTag(tags, form, "isGalarian", "galarian")
        addBooleanTag(tags, form, "isAlolan", "alolan")
        addBooleanTag(tags, form, "isHisuian", "hisuian")
        addBooleanTag(tags, form, "isPaldean", "paldean")
        addBooleanTag(tags, form, "isMegaForm", "mega")
        addBooleanTag(tags, form, "hasMegaForm", "mega")
        addBooleanTag(tags, form, "isGigantamaxForm", "gigantamax")
        addBooleanTag(tags, form, "hasGigantamaxForm", "gmax")
        PokemonReflection.text(PokemonReflection.call(form, "getRegionalTag"))?.let {
            PokemonReflection.addNormalizedAliases(tags, it)
        }

        val paletteName = findFirstText(palette, "getName")
        val shiny = PokemonReflection.booleanValue(PokemonReflection.call(pokemon, "isShiny")) ||
            PokemonReflection.booleanValue(PokemonReflection.call(pokemon, "getShiny")) ||
            paletteName == "shiny" ||
            tags.contains("shiny")
        val playerOwned = isPixelmonOwned(pokemon, source)

        return PokemonTraits(
            provider = "pixelmon",
            species = findFirstText(species, "getName", "getPokemonName"),
            dex = PokemonReflection.intValue(PokemonReflection.call(species, "getDex")),
            generation = PokemonReflection.intValue(PokemonReflection.call(species, "getGeneration")),
            form = findFirstText(form, "getName", "getPokemonName"),
            palette = paletteName,
            tags = tags,
            shiny = shiny,
            playerOwned = playerOwned
        )
    }

    private fun resolveOwnedOnly(entity: Entity, namespaceId: String): Set<String> {
        val resolved = resolveSource(entity, namespaceId) ?: return emptySet()
        val pokemon = findPokemonData(resolved.source) ?: return emptySet()

        val owned = when (resolved.provider) {
            "cobblemon" -> isCobblemonOwned(pokemon, resolved.source)
            "pixelmon" -> isPixelmonOwned(pokemon, resolved.source)
            else -> false
        }

        if (!owned) return emptySet()
        return linkedSetOf(
            "pokemon:owned=true",
            "pokemon:player_owned=true",
            "${resolved.provider}:owned=true"
        )
    }

    private fun resolveLight(
        entity: Entity,
        namespaceId: String,
        lightRules: Set<String>,
        includeOwnedProtection: Boolean
    ): Set<String> {
        val resolved = resolveSource(entity, namespaceId) ?: return emptySet()
        val provider = resolved.provider
        val source = resolved.source
        val ids = LinkedHashSet<String>()
        if ("mod" in lightRules) {
            ids.add("pokemon:mod=$provider")
        }

        val needsOwned = includeOwnedProtection || "owned" in lightRules
        val needsPokemonData = needsOwned || lightRules.any { it != "mod" }
        if (!needsPokemonData) return ids

        val pokemon = findPokemonData(source) ?: return ids
        val species = PokemonReflection.call(pokemon, "getSpecies")

        when (provider) {
            "cobblemon" -> {
                if ("legendary" in lightRules) {
                    addLightBooleanIds(ids, source, "isLegendary", "legendary", provider)
                    addLightBooleanIds(ids, pokemon, "isLegendary", "legendary", provider)
                    addLightBooleanIds(ids, species, "isLegendary", "legendary", provider)
                }
                if ("mythical" in lightRules) {
                    addLightBooleanIds(ids, source, "isMythical", "mythical", provider)
                    addLightBooleanIds(ids, pokemon, "isMythical", "mythical", provider)
                    addLightBooleanIds(ids, species, "isMythical", "mythical", provider)
                }
                if ("ultra_beast" in lightRules) {
                    addLightBooleanIds(ids, source, "isUltraBeast", "ultra_beast", provider)
                    addLightBooleanIds(ids, pokemon, "isUltraBeast", "ultra_beast", provider)
                    addLightBooleanIds(ids, species, "isUltraBeast", "ultra_beast", provider)
                }
                if ("shiny" in lightRules && (PokemonReflection.booleanValue(PokemonReflection.call(pokemon, "getShiny")) ||
                    PokemonReflection.booleanValue(PokemonReflection.call(pokemon, "isShiny"))
                )) {
                    ids.add("pokemon:shiny=true")
                }
                if (needsOwned && isCobblemonOwned(pokemon, source)) {
                    addOwnedIds(ids, provider)
                }
            }
            "pixelmon" -> {
                if ("legendary" in lightRules) {
                    addLightBooleanIds(ids, source, "isLegendary", "legendary", provider)
                    addLightBooleanIds(ids, pokemon, "isLegendary", "legendary", provider)
                    addLightBooleanIds(ids, species, "isLegendary", "legendary", provider)
                }
                if ("mythical" in lightRules) {
                    addLightBooleanIds(ids, source, "isMythical", "mythical", provider)
                    addLightBooleanIds(ids, pokemon, "isMythical", "mythical", provider)
                    addLightBooleanIds(ids, species, "isMythical", "mythical", provider)
                }
                if ("ultra_beast" in lightRules) {
                    addLightBooleanIds(ids, source, "isUltraBeast", "ultra_beast", provider)
                    addLightBooleanIds(ids, pokemon, "isUltraBeast", "ultra_beast", provider)
                    addLightBooleanIds(ids, species, "isUltraBeast", "ultra_beast", provider)
                }
                if ("paradox" in lightRules) {
                    addLightBooleanIds(ids, source, "isParadox", "paradox", provider)
                    addLightBooleanIds(ids, pokemon, "isParadox", "paradox", provider)
                }
                if ("mega" in lightRules) {
                    addLightBooleanIds(ids, source, "isMega", "mega", provider)
                    addLightBooleanIds(ids, pokemon, "isMega", "mega", provider)
                }
                if ("gigantamax" in lightRules) {
                    addLightBooleanIds(ids, source, "isGigantamax", "gigantamax", provider)
                    addLightBooleanIds(ids, source, "hasGigantamaxFactor", "gmax", provider)
                    addLightBooleanIds(ids, pokemon, "isGigantamax", "gigantamax", provider)
                    addLightBooleanIds(ids, pokemon, "hasGigantamaxFactor", "gmax", provider)
                }
                if ("boss" in lightRules) {
                    addLightBooleanIds(ids, source, "isBoss", "boss", provider)
                    addLightBooleanIds(ids, source, "isBossPokemon", "boss", provider)
                    addLightBooleanIds(ids, pokemon, "isBoss", "boss", provider)
                    addLightBooleanIds(ids, pokemon, "isBossPokemon", "boss", provider)
                }
                if ("shiny" in lightRules && (PokemonReflection.booleanValue(PokemonReflection.call(pokemon, "isShiny")) ||
                    PokemonReflection.booleanValue(PokemonReflection.call(pokemon, "getShiny"))
                )) {
                    ids.add("pokemon:shiny=true")
                }
                if (needsOwned && isPixelmonOwned(pokemon, source)) {
                    addOwnedIds(ids, provider)
                }
            }
        }

        return ids
    }

    private fun detectProvider(namespaceId: String, direct: Any, handle: Any?): String? {
        if (namespaceId.startsWith("cobblemon:", ignoreCase = true)) return "cobblemon"
        if (namespaceId.startsWith("pixelmon:", ignoreCase = true)) return "pixelmon"

        if (classNameContains(direct, "cobblemon") || classNameContains(handle, "cobblemon")) return "cobblemon"
        if (classNameContains(direct, "pixelmon") || classNameContains(handle, "pixelmon")) return "pixelmon"

        return null
    }

    private fun cacheAbsentProvider(key: ProviderKey, namespaceId: String) {
        if (!namespaceId.startsWith("minecraft:", ignoreCase = true)) return
        if (namespaceId.endsWith(":unknown", ignoreCase = true)) return
        cacheProvider(key, ProviderHint.NONE)
    }

    private fun cacheProvider(key: ProviderKey, provider: String) {
        val hint = when (provider) {
            "cobblemon" -> ProviderHint.COBBLEMON
            "pixelmon" -> ProviderHint.PIXELMON
            else -> return
        }
        cacheProvider(key, hint)
    }

    private fun cacheProvider(key: ProviderKey, hint: ProviderHint) {
        if (providerHints.size >= MAX_PROVIDER_HINTS) providerHints.clear()
        providerHints[key] = hint
    }

    private fun classNameContains(target: Any?, value: String): Boolean {
        return target?.javaClass?.name?.contains(value, ignoreCase = true) == true
    }

    private fun findPokemonData(source: Any?): Any? {
        return firstCall(source, "getPokemon", "getPokemonData", "getStoragePokemonData")
    }

    private fun firstCall(target: Any?, vararg methods: String): Any? {
        for (method in methods) {
            val value = PokemonReflection.call(target, method)
            if (value != null) return value
        }
        return null
    }

    private fun findFirstText(target: Any?, vararg methods: String): String? {
        for (method in methods) {
            val value = PokemonReflection.text(PokemonReflection.call(target, method))
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun addBooleanTag(target: MutableSet<String>, source: Any?, method: String, tag: String) {
        if (!PokemonReflection.booleanValue(PokemonReflection.call(source, method))) return
        PokemonReflection.addNormalizedAliases(target, tag)
    }

    private fun addLightBooleanIds(target: MutableSet<String>, source: Any?, method: String, tag: String, provider: String) {
        if (!PokemonReflection.booleanValue(PokemonReflection.call(source, method))) return
        for (normalized in lightTagAliases(tag)) {
            target.add("pokemon:tag=$normalized")
            if (provider == "cobblemon") {
                target.add("cobblemon:label=$normalized")
            } else {
                target.add("pixelmon:tag=$normalized")
            }
        }
    }

    private fun lightTagAliases(tag: String): Set<String> {
        val aliases = LinkedHashSet<String>()
        aliases.add(tag)
        aliases.add(tag.replace('-', '_'))
        when (tag) {
            "ultra_beast" -> aliases.add("ultrabeast")
            "ultrabeast" -> aliases.add("ultra_beast")
            "gigantamax" -> aliases.add("gmax")
            "gmax" -> aliases.add("gigantamax")
        }
        return aliases
    }

    private fun addOwnedIds(target: MutableSet<String>, provider: String) {
        target.add("pokemon:owned=true")
        target.add("pokemon:player_owned=true")
        target.add("$provider:owned=true")
    }

    private fun isCobblemonOwned(pokemon: Any, source: Any): Boolean {
        return PokemonReflection.booleanValue(PokemonReflection.call(pokemon, "isPlayerOwned")) ||
            hasValue(pokemon, "getOwnerUUID", "getOwnerPlayer", "getOwnerEntity") ||
            hasValue(source, "getOwner")
    }

    private fun isPixelmonOwned(pokemon: Any, source: Any): Boolean {
        return hasValue(pokemon, "getOwnerPlayerUUID", "getOwnerPlayer") ||
            hasValue(pokemon, "getOwnerTrainerUUID", "getOwnerTrainer") ||
            hasValue(source, "getOwnerUUID", "getOwner")
    }

    private fun hasValue(target: Any?, vararg methods: String): Boolean {
        for (method in methods) {
            val value = PokemonReflection.call(target, method) ?: continue
            if (value is Boolean) {
                if (value) return true
                continue
            }
            return true
        }
        return false
    }

    private fun findGeneration(tags: Set<String>): Int? {
        for (tag in tags) {
            if (!tag.startsWith("gen")) continue
            val digits = tag.drop(3).takeWhile { it.isDigit() }
            val generation = digits.toIntOrNull()
            if (generation != null && generation > 0) {
                return generation
            }
        }
        return null
    }

    private data class CachedIds(
        val expiresAt: Long,
        val ids: Set<String>,
        val pinned: Boolean = false
    )

    private data class ProviderSource(
        val provider: String,
        val source: Any
    )

    private data class ProviderKey(
        val entityClass: Class<*>,
        val namespaceId: String
    )

    private enum class ProviderHint(val provider: String?) {
        COBBLEMON("cobblemon"),
        PIXELMON("pixelmon"),
        NONE(null)
    }

    private data class CacheKey(
        val uuid: UUID,
        val profile: Int,
        val lightRules: Set<String>,
        val ownedProtection: Boolean
    )
}

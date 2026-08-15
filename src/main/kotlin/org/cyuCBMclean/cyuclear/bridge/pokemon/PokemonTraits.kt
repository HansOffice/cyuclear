package org.cyuCBMclean.cyuclear.bridge.pokemon

data class PokemonTraits(
    val provider: String,
    val species: String? = null,
    val dex: Int? = null,
    val generation: Int? = null,
    val form: String? = null,
    val palette: String? = null,
    val aspects: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val shiny: Boolean = false,
    val playerOwned: Boolean = false
) {

    fun toFilterIds(): Set<String> {
        val ids = LinkedHashSet<String>()
        ids.add("pokemon:mod=$provider")

        addValue(ids, "pokemon:species=", species)
        dex?.takeIf { it > 0 }?.let { ids.add("pokemon:dex=$it") }
        generation?.takeIf { it > 0 }?.let { ids.add("pokemon:generation=$it") }
        addValue(ids, "pokemon:form=", form)
        addValue(ids, "pokemon:palette=", palette)

        for (aspect in aspects) {
            addValue(ids, "pokemon:aspect=", aspect)
            addValue(ids, "$provider:aspect=", aspect)
        }

        for (tag in tags) {
            addValue(ids, "pokemon:tag=", tag)
            val providerKey = if (provider == "cobblemon") "$provider:label=" else "$provider:tag="
            addValue(ids, providerKey, tag)
        }

        if (shiny) {
            ids.add("pokemon:shiny=true")
        }

        if (playerOwned) {
            ids.add("pokemon:owned=true")
            ids.add("pokemon:player_owned=true")
            ids.add("$provider:owned=true")
        }

        return ids
    }

    private fun addValue(ids: MutableSet<String>, prefix: String, value: String?) {
        if (value.isNullOrBlank()) return
        ids.add(prefix + value)
    }
}

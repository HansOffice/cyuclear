package org.cyuCBMclean.cyuclear.util

import org.bukkit.Sound
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object SoundCompat {

    private val cache = ConcurrentHashMap<String, Sound?>()

    private val aliases = mapOf(
        "BLOCK_NOTE_BLOCK_PLING" to listOf("NOTE_PLING", "BLOCK_NOTE_PLING"),
        "BLOCK_NOTE_BLOCK_BASS" to listOf("NOTE_BASS", "BLOCK_NOTE_BASS"),
        "BLOCK_NOTE_BLOCK_BELL" to listOf("NOTE_PLING", "NOTE_BASS"),
        "BLOCK_NOTE_PLING" to listOf("NOTE_PLING", "BLOCK_NOTE_BLOCK_PLING"),
        "BLOCK_NOTE_BASS" to listOf("NOTE_BASS", "BLOCK_NOTE_BLOCK_BASS"),
        "BLOCK_NOTE_BELL" to listOf("NOTE_PLING", "BLOCK_NOTE_BLOCK_BELL"),
        "NOTE_PLING" to listOf("BLOCK_NOTE_BLOCK_PLING", "BLOCK_NOTE_PLING"),
        "NOTE_BASS" to listOf("BLOCK_NOTE_BLOCK_BASS", "BLOCK_NOTE_BASS"),
        "NOTE_BELL" to listOf("BLOCK_NOTE_BLOCK_BELL", "BLOCK_NOTE_BELL"),
        "BLOCK_BEACON_ACTIVATE" to listOf("BEACON_ACTIVATE"),
        "ENTITY_PLAYER_LEVELUP" to listOf("LEVEL_UP"),
        "ENTITY_WITHER_SPAWN" to listOf("WITHER_SPAWN"),
        "BLOCK_CHEST_OPEN" to listOf("CHEST_OPEN"),
        "BLOCK_CHEST_CLOSE" to listOf("CHEST_CLOSE"),
        "BLOCK_CHEST_LOCKED" to listOf("CHEST_CLOSE"),
        "ENTITY_ITEM_PICKUP" to listOf("ITEM_PICKUP"),
        "UI_BUTTON_CLICK" to listOf("CLICK", "NOTE_PLING")
    )

    fun reload() {
        cache.clear()
    }

    fun resolve(rawSound: String): Sound? {
        val normalized = rawSound.trim().uppercase(Locale.ROOT)
        if (normalized.isEmpty()) return null

        return cache.computeIfAbsent(normalized) {
            findSound(sequenceOf(normalized) + aliases[normalized].orEmpty().asSequence())
        }
    }

    private fun findSound(candidates: Sequence<String>): Sound? {
        for (candidate in candidates) {
            try {
                return Sound.valueOf(candidate)
            } catch (_: IllegalArgumentException) {
            }
        }
        return null
    }
}

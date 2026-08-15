package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import org.cyuCBMclean.cyuclear.util.SoundCompat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object SoundNoticeManager {

    enum class Event(val configKey: String) {
        CLEANUP_WARNING("cleanup-warning"),
        CLEANUP_START("cleanup-start"),
        CLEANUP_COMPLETE("cleanup-complete"),
        PANIC("panic"),
        CHUNK_OVERLOAD("chunk-overload"),
        BIN_OPEN("bin-open"),
        BIN_WARNING("bin-warning"),
        BIN_EXPIRE("bin-expire"),
        BIN_DEPOSIT("bin-deposit")
    }

    private val invalidSoundNames = ConcurrentHashMap.newKeySet<String>()

    fun reload() {
        SoundCompat.reload()
        invalidSoundNames.clear()
    }

    fun broadcast(event: Event) {
        val setting = Settings.getSoundSetting(event.configKey) ?: return
        val sound = resolveSound(event, setting.sound) ?: return

        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                playResolved(player, sound, setting.volume, setting.pitch)
            }
        })
    }

    fun broadcast(event: Event, permission: String) {
        val setting = Settings.getSoundSetting(event.configKey) ?: return
        val sound = resolveSound(event, setting.sound) ?: return

        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                playResolved(player, sound, setting.volume, setting.pitch, permission)
            }
        })
    }

    fun play(player: Player, event: Event) {
        val setting = Settings.getSoundSetting(event.configKey) ?: return
        val sound = resolveSound(event, setting.sound) ?: return
        playResolved(player, sound, setting.volume, setting.pitch)
    }

    private fun playResolved(player: Player, sound: Sound, volume: Float, pitch: Float, permission: String? = null) {
        CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
            if (player.isOnline && (permission == null || player.hasPermission(permission))) {
                player.playSound(player.location, sound, volume, pitch)
            }
        })
    }

    private fun resolveSound(event: Event, rawSound: String): Sound? {
        val normalized = rawSound.trim().uppercase(Locale.ROOT)
        if (normalized.isEmpty()) return null

        if (invalidSoundNames.contains(normalized)) return null

        return SoundCompat.resolve(normalized) ?: run {
            invalidSoundNames.add(normalized)
            Cyuclear.instance.logger.warning("Cyuclear 在 sounds.${event.configKey}.sound 读取到未知音效 '$rawSound'，已跳过播放")
            null
        }
    }
}

package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.platform.NoticeBossBar
import org.cyuCBMclean.cyuclear.platform.NoticeBridgeProvider
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler

object BinNoticeManager {

    private var bossBar: NoticeBossBar? = null

    fun sendWarning(remainingSeconds: Int) {
        if (Settings.binWarningChatEnabled) {
            PlayerMessageDispatcher.broadcast(resolveChatWarning(remainingSeconds))
        }

        sendVisualWarning(remainingSeconds)
        SoundNoticeManager.broadcast(SoundNoticeManager.Event.BIN_WARNING)
    }

    fun sendVisualWarning(remainingSeconds: Int) {
        CyuScheduler.runTask(Cyuclear.instance, Runnable {
            sendVisualWarningNow(remainingSeconds)
        })
    }

    fun clearBossBar() {
        CyuScheduler.runTask(Cyuclear.instance, Runnable { clearBossBarNow() })
    }

    fun shutdown() {
        clearBossBarNow()
    }

    private fun sendVisualWarningNow(remainingSeconds: Int) {
        if (VoidBinManager.expireTime == 0L) {
            clearBossBarNow()
            return
        }

        val durationSeconds = VoidBinManager.activeDurationSeconds.coerceAtLeast(1)
        val progress = (remainingSeconds.toDouble() / durationSeconds.toDouble()).coerceIn(0.0, 1.0)
        val title = resolveBossBarWarning(remainingSeconds)

        if (Settings.binWarningActionBarEnabled &&
            (NoticeBridgeProvider.bridge.supportsActionBar || !Settings.binWarningChatEnabled)
        ) {
            sendActionBar(remainingSeconds)
        }

        if (!Settings.binWarningBossBarEnabled) {
            clearBossBarNow()
            return
        }

        val bar = bossBar ?: NoticeBridgeProvider.bridge.createBossBar(
            title,
            Settings.binWarningBossBarColor,
            Settings.binWarningBossBarStyle
        )?.also {
            bossBar = it
        } ?: run {
            if (!Settings.binWarningActionBarEnabled && !Settings.binWarningChatEnabled) sendActionBar(remainingSeconds)
            return
        }

        bar.update(title, progress)
        bar.addOnlinePlayers(Cyuclear.instance, Bukkit.getOnlinePlayers())
    }

    private fun clearBossBarNow() {
        bossBar?.clear(Cyuclear.instance)
        bossBar = null
    }

    private fun sendActionBar(remainingSeconds: Int) {
        val message = resolveActionBarWarning(remainingSeconds)
        NoticeBridgeProvider.bridge.sendActionBar(Cyuclear.instance, Bukkit.getOnlinePlayers(), message)
    }

    private fun resolveChatWarning(remainingSeconds: Int): String {
        val time = remainingSeconds.toString()
        return if (Language.has("bin-closing-warn-chat")) {
            Language.get("bin-closing-warn-chat", "time" to time)
        } else {
            Language.get("bin-closing-warn", "time" to time)
        }
    }

    private fun resolveActionBarWarning(remainingSeconds: Int): String {
        val time = remainingSeconds.toString()
        return if (Language.has("bin-closing-warn-actionbar")) {
            Language.getRaw("bin-closing-warn-actionbar", "time" to time)
        } else {
            Language.getRaw("bin-closing-warn", "time" to time)
        }
    }

    private fun resolveBossBarWarning(remainingSeconds: Int): String {
        val time = remainingSeconds.toString()
        return if (Language.has("bin-closing-warn-bossbar")) {
            Language.getRaw("bin-closing-warn-bossbar", "time" to time)
        } else {
            Language.getRaw("bin-closing-warn", "time" to time)
        }
    }
}

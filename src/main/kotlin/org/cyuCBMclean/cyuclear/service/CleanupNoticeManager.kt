package org.cyuCBMclean.cyuclear.service

import org.bukkit.Bukkit
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.platform.NoticeBossBar
import org.cyuCBMclean.cyuclear.platform.NoticeBridgeProvider
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler

object CleanupNoticeManager {

    private var bossBar: NoticeBossBar? = null

    fun sendWarning(remainingSeconds: Int) {
        if (Settings.cleanupWarningChatEnabled) {
            PlayerMessageDispatcher.broadcast(resolveChatWarning(remainingSeconds))
        }

        sendVisualWarning(remainingSeconds)
        SoundNoticeManager.broadcast(SoundNoticeManager.Event.CLEANUP_WARNING)
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
        if (WindowScanner.isRunning) {
            clearBossBarNow()
            return
        }

        if (Settings.cleanupWarningActionBarEnabled &&
            (NoticeBridgeProvider.bridge.supportsActionBar || !Settings.cleanupWarningChatEnabled)
        ) {
            sendActionBar(remainingSeconds)
        }

        if (!Settings.cleanupWarningBossBarEnabled) {
            clearBossBarNow()
            return
        }

        val progress = (remainingSeconds.toDouble() / Settings.intervalSeconds.coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)
        val title = resolveBossBarWarning(remainingSeconds)

        val bar = bossBar ?: NoticeBridgeProvider.bridge.createBossBar(
            title,
            Settings.cleanupWarningBossBarColor,
            Settings.cleanupWarningBossBarStyle
        )?.also {
            bossBar = it
        } ?: run {
            if (!Settings.cleanupWarningActionBarEnabled && !Settings.cleanupWarningChatEnabled) sendActionBar(remainingSeconds)
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
        return if (Language.has("countdown-warn-chat")) {
            Language.get("countdown-warn-chat", "time" to time)
        } else {
            Language.get("countdown-warn", "time" to time)
        }
    }

    private fun resolveActionBarWarning(remainingSeconds: Int): String {
        val time = remainingSeconds.toString()
        return if (Language.has("countdown-warn-actionbar")) {
            Language.getRaw("countdown-warn-actionbar", "time" to time)
        } else {
            Language.getRaw("countdown-warn", "time" to time)
        }
    }

    private fun resolveBossBarWarning(remainingSeconds: Int): String {
        val time = remainingSeconds.toString()
        return if (Language.has("countdown-warn-bossbar")) {
            Language.getRaw("countdown-warn-bossbar", "time" to time)
        } else {
            Language.getRaw("countdown-warn", "time" to time)
        }
    }
}

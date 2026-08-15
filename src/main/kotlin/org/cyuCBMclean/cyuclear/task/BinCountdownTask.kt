package org.cyuCBMclean.cyuclear.task

import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.BinNoticeManager
import org.cyuCBMclean.cyuclear.service.SoundNoticeManager
import org.cyuCBMclean.cyuclear.service.VoidBinManager
import org.cyuCBMclean.cyuclear.scheduler.CyuTimer

object BinCountdownTask {

    private var timer: CyuTimer? = null
    private var lastRemaining = -1
    private var visualWarningActive = false

    fun start() {
        timer?.cancel()
        lastRemaining = -1
        visualWarningActive = false
        BinNoticeManager.clearBossBar()
        timer = CyuTimer(Cyuclear.instance) {
            if (!Settings.binEnabled || VoidBinManager.expireTime == 0L) {
                lastRemaining = -1
                visualWarningActive = false
                BinNoticeManager.clearBossBar()
                return@CyuTimer
            }

            val remaining = VoidBinManager.getRemainingSeconds()

            if (remaining <= 0) {
                SoundNoticeManager.broadcast(SoundNoticeManager.Event.BIN_EXPIRE)
                VoidBinManager.clear()
                lastRemaining = -1
                visualWarningActive = false
                return@CyuTimer
            }

            if (remaining != lastRemaining) {
                lastRemaining = remaining
                if (Settings.binWarningTimes.contains(remaining)) {
                    visualWarningActive = Settings.binWarningActionBarEnabled || Settings.binWarningBossBarEnabled
                    BinNoticeManager.sendWarning(remaining)
                } else if (visualWarningActive) {
                    BinNoticeManager.sendVisualWarning(remaining)
                }
            }
        }
        timer?.runTimer(20L, 20L)
    }

    fun stop() {
        timer?.cancel()
        visualWarningActive = false
        BinNoticeManager.clearBossBar()
    }
}

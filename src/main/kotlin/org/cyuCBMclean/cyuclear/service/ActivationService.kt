package org.cyuCBMclean.cyuclear.service

import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.cluster.ClusterManager
import org.cyuCBMclean.cyuclear.task.BinCountdownTask
import org.cyuCBMclean.cyuclear.task.CountdownTask
import org.cyuCBMclean.cyuclear.task.PanicMonitorTask

object ActivationService {
    @Volatile
    private var active = false

    fun isActive(): Boolean = active

    fun reload() {
        if (!Settings.enabled) {
            stop()
            return
        }
        active = true
        ActivationReminder.reset()
        ClusterManager.start()
        CountdownTask.start()
        PanicMonitorTask.start()
        BinCountdownTask.start()
    }

    fun stop() {
        active = false
        WindowScanner.stop()
        PreviewScanner.stop()
        ClusterManager.stop()
        CountdownTask.stop()
        PanicMonitorTask.stop()
        BinCountdownTask.stop()
        BinNoticeManager.clearBossBar()
        CleanupNoticeManager.clearBossBar()
    }
}

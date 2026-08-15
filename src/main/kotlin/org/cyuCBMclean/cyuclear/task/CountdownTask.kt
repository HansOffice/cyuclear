package org.cyuCBMclean.cyuclear.task

import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.CleanupNoticeManager
import org.cyuCBMclean.cyuclear.service.CleanupRequests
import org.cyuCBMclean.cyuclear.service.WindowScanner
import org.cyuCBMclean.cyuclear.scheduler.CyuTimer
import org.cyuCBMclean.cyuclear.cluster.ClusterManager

object CountdownTask {
    var remainingSeconds = 0
        private set

    private var timer: CyuTimer? = null
    private var visualWarningActive = false

    fun start() {
        remainingSeconds = if (Settings.clusterEnabled) 0 else Settings.intervalSeconds
        timer?.cancel()
        visualWarningActive = false
        CleanupNoticeManager.clearBossBar()

        timer = CyuTimer(Cyuclear.instance) {
            if (WindowScanner.isRunning) {
                CleanupNoticeManager.clearBossBar()
                return@CyuTimer
            }

            if (!Settings.itemModuleEnabled && !Settings.entityModuleEnabled) {
                remainingSeconds = if (Settings.clusterEnabled) 0 else Settings.intervalSeconds
                visualWarningActive = false
                CleanupNoticeManager.clearBossBar()
                return@CyuTimer
            }

            if (Settings.clusterEnabled) {
                tickClusterCountdown()
            } else {
                tickLocalCountdown()
            }
        }
        timer?.runTimer(20L, 20L)
    }

    fun stop() {
        timer?.cancel()
        visualWarningActive = false
        CleanupNoticeManager.clearBossBar()
    }

    private fun tickClusterCountdown() {
        val synchronizedRemaining = ClusterManager.cleanupRemainingSeconds()
        if (synchronizedRemaining == null) {
            remainingSeconds = 0
            visualWarningActive = false
            CleanupNoticeManager.clearBossBar()
            return
        }

        remainingSeconds = synchronizedRemaining
        if (remainingSeconds <= 0) {
            visualWarningActive = false
            CleanupNoticeManager.clearBossBar()
            ClusterManager.tryStartDueCleanup()
        } else {
            showWarningIfNeeded()
        }
    }

    private fun tickLocalCountdown() {
        remainingSeconds--
        if (remainingSeconds <= 0) {
            visualWarningActive = false
            CleanupNoticeManager.clearBossBar()
            WindowScanner.startScan(
                CleanupRequests.scheduled(
                    cleanItems = Settings.itemModuleEnabled,
                    cleanEntities = Settings.entityModuleEnabled
                )
            )
            remainingSeconds = Settings.intervalSeconds
        } else {
            showWarningIfNeeded()
        }
    }

    private fun showWarningIfNeeded() {
        if (Settings.warningTimes.contains(remainingSeconds)) {
            visualWarningActive = Settings.cleanupWarningActionBarEnabled || Settings.cleanupWarningBossBarEnabled
            CleanupNoticeManager.sendWarning(remainingSeconds)
        } else if (visualWarningActive) {
            CleanupNoticeManager.sendVisualWarning(remainingSeconds)
        }
    }
}

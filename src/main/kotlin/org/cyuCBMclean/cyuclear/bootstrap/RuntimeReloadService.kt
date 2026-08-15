package org.cyuCBMclean.cyuclear.bootstrap

import org.cyuCBMclean.cyuclear.bridge.CraftEngineFurnitureHook
import org.cyuCBMclean.cyuclear.bridge.MythicMobsHook
import org.cyuCBMclean.cyuclear.bridge.StackerBridge
import org.cyuCBMclean.cyuclear.config.ConfigSnapshotManager
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.menu.MenuReloadService
import org.cyuCBMclean.cyuclear.service.ActivationService
import org.cyuCBMclean.cyuclear.service.ChunkLimitService
import org.cyuCBMclean.cyuclear.service.DepositBufferManager
import org.cyuCBMclean.cyuclear.service.HotspotTracker
import org.cyuCBMclean.cyuclear.service.SoundNoticeManager
import org.cyuCBMclean.cyuclear.util.ItemIdentity

internal object RuntimeReloadService {

    data class Result(
        val snapshot: ConfigSnapshotManager.Result,
        val active: Boolean
    )

    fun reload(): Result {
        val snapshot = ConfigSnapshotManager.create("reload")
        ActivationService.stop()
        Settings.load()
        ChunkLimitService.reset()
        HotspotTracker.reset()
        CraftEngineFurnitureHook.clearCache()
        MythicMobsHook.reset()
        ItemIdentity.reloadExternalResolvers()
        SoundNoticeManager.reload()
        Language.load()
        MenuReloadService.reload()
        StackerBridge.reload()
        DepositBufferManager.onSettingsReload()
        ActivationService.reload()
        return Result(snapshot, ActivationService.isActive())
    }
}

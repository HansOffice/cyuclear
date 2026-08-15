package org.cyuCBMclean.cyuclear.menu

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cyuCBMclean.cyuclear.Cyuclear
import org.cyuCBMclean.cyuclear.config.Language
import org.cyuCBMclean.cyuclear.config.Settings
import org.cyuCBMclean.cyuclear.service.DepositBufferManager
import org.cyuCBMclean.cyuclear.service.VoidBinManager
import org.cyuCBMclean.cyuclear.platform.NoticeBridgeProvider
import org.cyuCBMclean.cyuclear.scheduler.CyuScheduler
import org.cyuCBMclean.cyuclear.util.ColorUtils
import org.cyuCBMclean.cyuclear.util.SoundCompat
import java.util.Locale

data class MenuActionBindings(
    val refresh: ((Player) -> Unit)? = null,
    val openPreviousPage: ((Player) -> Unit)? = null,
    val openNextPage: ((Player) -> Unit)? = null,
    val defaultClick: ((Player) -> Unit)? = null
)

object MenuActionExecutor {

    private val menuTargets = setOf("bin", "buffer", "deposit-buffer", "admin", "rules", "runs", "hotspots")

    fun execute(player: Player, actions: List<String>, bindings: MenuActionBindings) {
        if (actions.isEmpty()) return
        CyuScheduler.runEntityTask(Cyuclear.instance, player, Runnable {
            if (!player.isOnline) return@Runnable
            for (rawAction in actions) {
                val action = MenuTextResolver.resolve(player, rawAction).trim()
                if (action.isNotEmpty()) executeAction(player, action, bindings)
            }
        })
    }

    fun validate(actions: List<String>, itemKey: String) {
        for (rawAction in actions) {
            val action = rawAction.trim()
            if (action.isEmpty()) continue
            when {
                action.equals("[default]", ignoreCase = true) ||
                    action.equals("[close]", ignoreCase = true) ||
                    action.equals("[refresh]", ignoreCase = true) ||
                    action.equals("[prev_page]", ignoreCase = true) ||
                    action.equals("[next_page]", ignoreCase = true) -> Unit

                action.startsWith("[open]", ignoreCase = true) -> {
                    val target = payload(action).lowercase(Locale.ROOT)
                    if (target !in menuTargets) {
                        Cyuclear.instance.logger.warning("菜单 $itemKey 的 [open] 入口 '$target' 无法使用")
                    }
                }

                action.startsWith("[player]", ignoreCase = true) ||
                    action.startsWith("[console]", ignoreCase = true) ||
                    action.startsWith("[message]", ignoreCase = true) ||
                    action.startsWith("[actionbar]", ignoreCase = true) ||
                    action.startsWith("[title]", ignoreCase = true) ||
                    action.startsWith("[sound]", ignoreCase = true) -> Unit

                else -> Cyuclear.instance.logger.warning("菜单 $itemKey 存在未知动作 '$action'")
            }
        }
    }

    private fun executeAction(player: Player, action: String, bindings: MenuActionBindings) {
        when {
            action.equals("[default]", ignoreCase = true) -> bindings.defaultClick?.invoke(player)
            action.equals("[close]", ignoreCase = true) -> player.closeInventory()
            action.equals("[refresh]", ignoreCase = true) -> bindings.refresh?.invoke(player)
            action.equals("[prev_page]", ignoreCase = true) -> bindings.openPreviousPage?.invoke(player)
            action.equals("[next_page]", ignoreCase = true) -> bindings.openNextPage?.invoke(player)
            action.startsWith("[open]", ignoreCase = true) -> openMenu(player, payload(action))
            action.startsWith("[player]", ignoreCase = true) -> {
                val command = command(payload(action))
                if (command.isNotEmpty()) player.performCommand(command)
            }
            action.startsWith("[console]", ignoreCase = true) -> {
                val command = command(payload(action))
                if (command.isNotEmpty()) {
                    CyuScheduler.runTask(Cyuclear.instance, Runnable {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                    })
                }
            }
            action.startsWith("[message]", ignoreCase = true) -> {
                payload(action).takeIf { it.isNotEmpty() }?.let { player.sendMessage(ColorUtils.color(it)) }
            }
            action.startsWith("[actionbar]", ignoreCase = true) -> {
                payload(action).takeIf { it.isNotEmpty() }?.let {
                    NoticeBridgeProvider.bridge.sendActionBar(Cyuclear.instance, listOf(player), ColorUtils.color(it))
                }
            }
            action.startsWith("[title]", ignoreCase = true) -> {
                val parts = payload(action).split(";", limit = 2)
                if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                    MenuTitleBridge.send(
                        player,
                        ColorUtils.color(parts[0]),
                        ColorUtils.color(parts.getOrElse(1) { "" })
                    )
                }
            }
            action.startsWith("[sound]", ignoreCase = true) -> playSound(player, payload(action))
        }
    }

    private fun openMenu(player: Player, rawTarget: String) {
        when (rawTarget.trim().lowercase(Locale.ROOT)) {
            "bin" -> openBin(player)
            "buffer", "deposit-buffer" -> DepositBufferMenu.open(player)
            "admin", "rules", "runs", "hotspots" -> openAdminMenu(player, rawTarget.trim().lowercase(Locale.ROOT))
        }
    }

    fun openBin(player: Player, page: Int = 0): Boolean {
        if (!Settings.binEnabled || !Settings.itemModuleEnabled) {
            player.sendMessage(Language.get("bin-not-enabled"))
            return false
        }
        if (Settings.binDepositBufferEnabled && DepositBufferManager.hasPending(player)) {
            DepositBufferMenu.open(player)
            return true
        }
        if (!Settings.binAlwaysOpen) {
            if (VoidBinManager.expireTime == 0L) {
                player.sendMessage(Language.get("bin-empty"))
                return false
            }
            if (System.currentTimeMillis() > VoidBinManager.expireTime) {
                player.sendMessage(Language.get("bin-expired"))
                return false
            }
        }
        BinMenu(page).open(player)
        return true
    }

    private fun openAdminMenu(player: Player, target: String) {
        if (!player.hasPermission("cyuclear.admin")) {
            player.sendMessage(Language.get("no-permission"))
            return
        }
        when (target) {
            "admin" -> AdminMenu.open(player)
            "rules" -> RuleMenu.open(player)
            "runs" -> CleanupRunMenu.openRuns(player, 0)
            "hotspots" -> HotspotMenu.openList(player, 0)
        }
    }

    private fun playSound(player: Player, raw: String) {
        val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val sound = parts.firstOrNull()?.let { SoundCompat.resolve(it.uppercase(Locale.ROOT)) } ?: return
        val volume = parts.getOrNull(1)?.toFloatOrNull() ?: 1.0f
        val pitch = parts.getOrNull(2)?.toFloatOrNull() ?: 1.0f
        player.playSound(player.location, sound, volume, pitch)
    }

    private fun payload(action: String): String = action.substringAfter(']', "").trim()

    private fun command(value: String): String = value.trim().removePrefix("/")
}

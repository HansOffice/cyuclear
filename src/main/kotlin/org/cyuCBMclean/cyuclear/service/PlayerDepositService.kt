package org.cyuCBMclean.cyuclear.service

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cyuCBMclean.cyuclear.config.BinEntryRules

object PlayerDepositService {

    data class Decision(
        val allowed: Boolean,
        val messageKey: String = "",
        val reason: String = ""
    )

    fun check(player: Player, item: ItemStack): Decision {
        if (!BinEntryRules.playerDepositEnabled) {
            return Decision(false, "bin-deposit-disabled")
        }
        val permission = BinEntryRules.playerDepositPermission
        if (!player.hasPermission("cyuclear.admin") && permission.isNotEmpty() && !player.hasPermission(permission)) {
            return Decision(false, "bin-deposit-no-permission")
        }
        val location = player.location
        val decision = BinEntryRules.evaluate(
            item,
            BinEntryRules.Source.PLAYER_DEPOSIT,
            player.world.name,
            location.blockX,
            location.blockY,
            location.blockZ
        )
        return if (decision.allowed) {
            Decision(true)
        } else {
            Decision(false, "bin-deposit-denied", decision.reason)
        }
    }
}

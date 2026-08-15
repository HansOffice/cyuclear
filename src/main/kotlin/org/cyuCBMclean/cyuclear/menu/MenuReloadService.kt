package org.cyuCBMclean.cyuclear.menu

object MenuReloadService {
    fun reload() {
        BinMenuDefinition.load()
        DepositBufferMenu.load()
        RuleMenu.load()
        AdminMenu.load()
        CleanupRunMenu.load()
        HotspotMenu.load()
    }
}

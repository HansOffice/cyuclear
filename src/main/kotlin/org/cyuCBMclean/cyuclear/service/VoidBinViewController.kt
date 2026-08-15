package org.cyuCBMclean.cyuclear.service

interface VoidBinViewController {
    fun closeOpenMenus()

    fun refreshOpenMenus(snapshot: VoidBinManager.MenuSnapshot)
}

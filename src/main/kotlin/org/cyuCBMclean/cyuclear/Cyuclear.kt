package org.cyuCBMclean.cyuclear

import org.bukkit.plugin.java.JavaPlugin
import org.cyuCBMclean.cyuclear.bootstrap.CyuclearLifecycle

class Cyuclear : JavaPlugin() {

    companion object {
        lateinit var instance: Cyuclear
            private set
    }

    override fun onEnable() {
        instance = this
        CyuclearLifecycle.enable(this)
    }

    override fun onDisable() {
        CyuclearLifecycle.disable(this)
    }
}

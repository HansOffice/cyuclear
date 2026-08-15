package org.cyuCBMclean.cyuclear.util

import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.cyuCBMclean.cyuclear.platform.ItemIdBridge

object IdCompat {
    fun materialId(material: Material): String = ItemIdBridge.materialId(material)

    fun entityTypeId(type: EntityType): String = ItemIdBridge.entityTypeId(type)

    fun namespace(id: String): String {
        return id.substringBefore(':', "minecraft")
    }

}

package org.cyuCBMclean.cyuclear.platform

import org.bukkit.Material
import org.bukkit.entity.EntityType
import java.util.Locale

object ItemIdBridge {
    fun materialId(material: Material): String = "minecraft:${material.name.lowercase(Locale.ROOT)}"

    fun entityTypeId(type: EntityType): String = "minecraft:${type.toString().lowercase(Locale.ROOT)}"
}

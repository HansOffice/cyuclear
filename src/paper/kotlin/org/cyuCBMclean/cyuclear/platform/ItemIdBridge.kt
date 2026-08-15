package org.cyuCBMclean.cyuclear.platform

import org.bukkit.Material
import org.bukkit.entity.EntityType
import java.util.Locale

object ItemIdBridge {
    fun materialId(material: Material): String = material.getKey().toString().lowercase(Locale.ROOT)

    fun entityTypeId(type: EntityType): String {
        val name = type.getName().orEmpty().trim()
        return if (name.isEmpty()) {
            "minecraft:${type.toString().lowercase(Locale.ROOT)}"
        } else {
            "minecraft:${name.lowercase(Locale.ROOT)}"
        }
    }
}

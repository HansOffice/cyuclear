package org.cyuCBMclean.cyuclear.cluster

import com.mysql.cj.jdbc.Driver
import redis.clients.jedis.Jedis
import org.cyuCBMclean.cyuclear.config.Settings.ClusterStorageType

internal object ClusterDependencies {

    fun verify(storageType: ClusterStorageType): String? {
        return runCatching {
            when (storageType) {
                ClusterStorageType.REDIS -> Jedis::class.java.name
                ClusterStorageType.MYSQL -> Driver().javaClass.name
            }
        }.fold(
            onSuccess = { null },
            onFailure = { error ->
                "${storageName(storageType)}运行库不可用：${error.message ?: error.javaClass.simpleName}"
            }
        )
    }

    private fun storageName(storageType: ClusterStorageType): String {
        return if (storageType == ClusterStorageType.REDIS) "Redis" else "MySQL"
    }
}

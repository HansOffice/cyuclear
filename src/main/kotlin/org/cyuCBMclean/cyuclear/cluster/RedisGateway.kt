package org.cyuCBMclean.cyuclear.cluster

import org.cyuCBMclean.cyuclear.config.Settings
import redis.clients.jedis.Jedis

internal class RedisGateway {
    private var connection: Jedis? = null

    @Synchronized
    fun eval(script: String, keys: List<String>, arguments: List<String>): Any? {
        return withConnection { jedis -> jedis.eval(script, keys, arguments) }
    }

    @Synchronized
    fun hash(key: String): Map<String, String> = withConnection { jedis -> jedis.hgetAll(key) }

    @Synchronized
    fun close() {
        closeConnection()
    }

    @Synchronized
    fun invalidate() {
        closeConnection()
    }

    private fun <T> withConnection(operation: (Jedis) -> T): T {
        val jedis = connection ?: connect().also { connection = it }
        return try {
            operation(jedis)
        } catch (error: Throwable) {
            closeConnection()
            throw error
        }
    }

    private fun connect(): Jedis {
        val jedis = Jedis(
            Settings.clusterRedisHost,
            Settings.clusterRedisPort,
            Settings.clusterConnectTimeoutMillis,
            Settings.clusterSocketTimeoutMillis,
            Settings.clusterRedisSsl
        )
        try {
            val password = Settings.clusterRedisPassword
            if (password.isNotEmpty()) {
                val username = Settings.clusterRedisUsername
                if (username.isNotEmpty()) {
                    jedis.auth(username, password)
                } else {
                    jedis.auth(password)
                }
            }
            if (Settings.clusterRedisDatabase != 0) {
                jedis.select(Settings.clusterRedisDatabase)
            }
            jedis.clientSetname("cyuclear:${Settings.clusterId}:${Settings.clusterServerId}")
            jedis.ping()
            return jedis
        } catch (error: Throwable) {
            runCatching { jedis.close() }
            throw error
        }
    }

    private fun closeConnection() {
        val current = connection
        connection = null
        if (current != null) {
            runCatching { current.close() }
        }
    }
}

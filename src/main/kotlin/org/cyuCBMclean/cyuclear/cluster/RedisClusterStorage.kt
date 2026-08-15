package org.cyuCBMclean.cyuclear.cluster

internal class RedisClusterStorage(
    private val gateway: RedisGateway
) : ClusterStorage {

    override fun pulse(request: ClusterPulseRequest): ClusterPulseResult {
        val prefix = prefix(request.clusterId)
        val raw = gateway.eval(
            PULSE_SCRIPT,
            listOf(
                "$prefix:identity",
                "$prefix:members",
                "$prefix:member:${request.serverId}",
                "$prefix:leader",
                "$prefix:cleanup:current"
            ),
            listOf(
                request.identity,
                request.identityTimeoutMillis.toString(),
                request.serverId,
                request.memberTimeoutMillis.toString(),
                request.instanceId,
                request.cleanupIntervalMillis.toString(),
                request.executionGraceMillis.toString()
            )
        ) as? List<*> ?: error("Redis 返回了无法识别的跨服心跳结果")

        return when (raw.getOrNull(0)?.toString()) {
            "0" -> ClusterPulseResult.IdentityMismatch(raw.getOrNull(1)?.toString().orEmpty())
            "-1" -> ClusterPulseResult.ServerIdOccupied(raw.getOrNull(1)?.toString().orEmpty())
            "1" -> {
                val seconds = raw.getOrNull(3)?.toString()?.toLongOrNull() ?: error("Redis TIME 秒值无效")
                val micros = raw.getOrNull(4)?.toString()?.toLongOrNull() ?: error("Redis TIME 微秒值无效")
                ClusterPulseResult.Accepted(
                    memberCount = raw.getOrNull(2)?.toString()?.toIntOrNull() ?: 0,
                    storageNowMillis = seconds * 1000L + micros / 1000L,
                    leaderServerId = raw.getOrNull(5)?.toString().orEmpty(),
                    runId = raw.getOrNull(6)?.toString().orEmpty(),
                    executeAtMillis = raw.getOrNull(7)?.toString()?.toLongOrNull() ?: 0L
                )
            }
            else -> error("Redis 返回了未知的跨服心跳状态")
        }
    }

    override fun claimCleanup(request: ClusterClaimRequest): Boolean {
        return gateway.eval(
            CLAIM_CLEANUP_SCRIPT,
            listOf("${prefix(request.clusterId)}:cleanup:claim:${request.runId}:${request.serverId}"),
            listOf(request.instanceId, request.ttlMillis.toString())
        )?.toString() == "1"
    }

    override fun releaseCleanupClaim(request: ClusterClaimRequest) {
        gateway.eval(
            RELEASE_CLEANUP_CLAIM_SCRIPT,
            listOf("${prefix(request.clusterId)}:cleanup:claim:${request.runId}:${request.serverId}"),
            listOf(request.instanceId)
        )
    }

    override fun saveCleanupResult(clusterId: String, runId: String, serverId: String, result: String, ttlMillis: Long) {
        gateway.eval(
            SAVE_CLEANUP_RESULT_SCRIPT,
            listOf("${prefix(clusterId)}:cleanup:result:$runId"),
            listOf(serverId, result, ttlMillis.toString())
        )
    }

    override fun readCleanupResults(clusterId: String, runId: String): Map<String, String> =
        gateway.hash("${prefix(clusterId)}:cleanup:result:$runId")

    override fun beginBinCycle(clusterId: String, cycleId: String, ttlMillis: Long) {
        gateway.eval(
            BEGIN_BIN_CYCLE_SCRIPT,
            listOf(stateKey(clusterId), itemsKey(clusterId, cycleId)),
            listOf(cycleId, ttlMillis.toString())
        )
    }

    override fun addBinItems(clusterId: String, cycleId: String, items: Map<String, Int>, ttlMillis: Long): Long? {
        if (items.isEmpty()) return null
        val arguments = ArrayList<String>(2 + items.size * 2)
        arguments += cycleId
        arguments += ttlMillis.toString()
        items.forEach { (encodedItem, amount) ->
            arguments += encodedItem
            arguments += amount.toString()
        }
        val result = gateway.eval(
            ADD_BIN_ITEMS_SCRIPT,
            listOf(stateKey(clusterId), itemsKey(clusterId, cycleId)),
            arguments
        )?.toString()?.toLongOrNull() ?: return null
        return result.takeIf { it > 0L }
    }

    override fun openBinWindow(clusterId: String, cycleId: String, durationMillis: Long, ttlMillis: Long): Long {
        return gateway.eval(
            OPEN_BIN_WINDOW_SCRIPT,
            listOf(stateKey(clusterId), itemsKey(clusterId, cycleId)),
            listOf(cycleId, durationMillis.toString(), ttlMillis.toString())
        )?.toString()?.toLongOrNull() ?: 0L
    }

    override fun readBinState(clusterId: String, withItems: Boolean): ClusterBinState {
        val state = gateway.eval(READ_BIN_STATE_SCRIPT, listOf(stateKey(clusterId)), emptyList()) as? List<*>
            ?: error("Redis 返回了无法识别的垃圾桶状态")
        val cycleId = state.getOrNull(0)?.toString().orEmpty()
        val items = LinkedHashMap<String, Int>()
        if (withItems && cycleId.isNotBlank()) {
            val fields = gateway.eval(
                FETCH_BIN_ITEMS_SCRIPT,
                listOf(stateKey(clusterId), itemsKey(clusterId, cycleId)),
                listOf(cycleId)
            ) as? List<*> ?: emptyList<Any>()
            var index = 0
            while (index + 1 < fields.size) {
                val amount = fields[index + 1]?.toString()?.toIntOrNull() ?: 0
                if (amount > 0) items[fields[index]?.toString().orEmpty()] = amount
                index += 2
            }
        }
        return ClusterBinState(
            cycleId = cycleId,
            expireAtMillis = state.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L,
            revision = state.getOrNull(2)?.toString()?.toLongOrNull() ?: 0L,
            storageNowMillis = state.getOrNull(3)?.toString()?.toLongOrNull() ?: 0L,
            items = items
        )
    }

    override fun reserveBinItem(
        clusterId: String,
        cycleId: String,
        encodedItem: String,
        amount: Int,
        claimId: String,
        playerId: String
    ): ClusterBinReservation {
        val raw = gateway.eval(
            RESERVE_BIN_ITEM_SCRIPT,
            listOf(stateKey(clusterId), itemsKey(clusterId, cycleId), claimsKey(clusterId)),
            listOf(cycleId, encodedItem, amount.toString(), claimId, playerId, BIN_CLAIM_TTL_MILLIS.toString())
        ) as? List<*> ?: error("Redis 返回了无法识别的垃圾桶领取结果")
        val reservedAmount = raw.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
        return ClusterBinReservation(
            claimId = if (reservedAmount > 0) claimId else "",
            cycleId = cycleId,
            playerId = playerId,
            encodedItem = encodedItem,
            amount = reservedAmount,
            revision = raw.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L
        )
    }

    override fun completeBinReservation(
        clusterId: String,
        cycleId: String,
        claimId: String,
        playerId: String
    ): Boolean {
        return gateway.eval(
            COMPLETE_BIN_RESERVATION_SCRIPT,
            listOf(claimsKey(clusterId)),
            listOf(claimId, cycleId, playerId)
        )?.toString() == "1"
    }

    override fun releaseBinReservation(
        clusterId: String,
        cycleId: String,
        claimId: String,
        playerId: String
    ): Long? {
        val result = gateway.eval(
            RELEASE_BIN_RESERVATION_SCRIPT,
            listOf(stateKey(clusterId), itemsKey(clusterId, cycleId), claimsKey(clusterId)),
            listOf(cycleId, claimId, playerId)
        )?.toString()?.toLongOrNull() ?: return null
        return result.takeIf { it > 0L }
    }

    override fun findBinReservations(clusterId: String, playerId: String): List<ClusterBinReservation> {
        val raw = gateway.eval(
            FIND_BIN_RESERVATIONS_SCRIPT,
            listOf(claimsKey(clusterId)),
            listOf(playerId, BIN_CLAIM_TTL_MILLIS.toString())
        ) as? List<*> ?: return emptyList()
        val reservations = ArrayList<ClusterBinReservation>()
        var index = 0
        while (index + 3 < raw.size) {
            val claimId = raw[index]?.toString().orEmpty()
            val cycleId = raw[index + 1]?.toString().orEmpty()
            val amount = raw[index + 2]?.toString()?.toIntOrNull() ?: 0
            val encodedItem = raw[index + 3]?.toString().orEmpty()
            if (claimId.isNotBlank() && amount > 0 && encodedItem.isNotBlank()) {
                reservations += ClusterBinReservation(claimId, cycleId, playerId, encodedItem, amount, -1L)
            }
            index += 4
        }
        return reservations
    }

    override fun invalidate() = gateway.invalidate()

    override fun close() = gateway.close()

    private fun prefix(clusterId: String) = "cyuclear:{$clusterId}"

    private fun stateKey(clusterId: String) = "${prefix(clusterId)}:bin:current"

    private fun itemsKey(clusterId: String, cycleId: String) = "${prefix(clusterId)}:bin:$cycleId:items"

    private fun claimsKey(clusterId: String) = "${prefix(clusterId)}:bin:claims"

    private companion object {
        const val BIN_CLAIM_TTL_MILLIS = 86400000L
        val PULSE_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            if not current then
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2], 'NX')
                current = redis.call('GET', KEYS[1])
            end
            if current ~= ARGV[1] then return {0, current or ''} end
            local memberOwner = redis.call('GET', KEYS[3])
            if memberOwner and memberOwner ~= ARGV[5] then return {-1, memberOwner} end
            local redisTime = redis.call('TIME')
            local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
            redis.call('SET', KEYS[3], ARGV[5], 'PX', ARGV[4])
            redis.call('ZADD', KEYS[2], now, ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - tonumber(ARGV[4]))
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            local leader = redis.call('GET', KEYS[4])
            if not leader then redis.call('SET', KEYS[4], ARGV[3], 'PX', ARGV[4], 'NX'); leader = redis.call('GET', KEYS[4]) end
            if leader == ARGV[3] then redis.call('PEXPIRE', KEYS[4], ARGV[4]) end
            local runId = redis.call('HGET', KEYS[5], 'run-id')
            local executeAt = tonumber(redis.call('HGET', KEYS[5], 'execute-at') or '0')
            if leader == ARGV[3] and (not runId or now >= executeAt + tonumber(ARGV[7])) then
                executeAt = now + tonumber(ARGV[6]); runId = tostring(now) .. '-' .. ARGV[3]
                redis.call('HSET', KEYS[5], 'run-id', runId, 'execute-at', executeAt)
            end
            if leader == ARGV[3] then redis.call('PEXPIRE', KEYS[5], math.max(tonumber(ARGV[2]), tonumber(ARGV[6]) * 3)) end
            return {1, redis.call('ZCARD', KEYS[2]), redisTime[1], redisTime[2], leader or '', redis.call('HGET', KEYS[5], 'run-id') or '', redis.call('HGET', KEYS[5], 'execute-at') or '0'}
        """.trimIndent()
        val CLAIM_CLEANUP_SCRIPT = """
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end
            return 0
        """.trimIndent()
        val RELEASE_CLEANUP_CLAIM_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
        """.trimIndent()
        val SAVE_CLEANUP_RESULT_SCRIPT = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]); redis.call('PEXPIRE', KEYS[1], ARGV[3]); return 1
        """.trimIndent()
        val BEGIN_BIN_CYCLE_SCRIPT = """
            local current = redis.call('HGET', KEYS[1], 'cycle-id')
            if current ~= ARGV[1] then redis.call('HSET', KEYS[1], 'cycle-id', ARGV[1], 'expire-at', 0, 'revision', 0) end
            redis.call('PEXPIRE', KEYS[1], ARGV[2]); redis.call('PEXPIRE', KEYS[2], ARGV[2]); return 1
        """.trimIndent()
        val ADD_BIN_ITEMS_SCRIPT = """
            if redis.call('HGET', KEYS[1], 'cycle-id') ~= ARGV[1] then return 0 end
            for index = 3, #ARGV, 2 do redis.call('HINCRBY', KEYS[2], ARGV[index], ARGV[index + 1]) end
            local revision = redis.call('HINCRBY', KEYS[1], 'revision', 1)
            redis.call('PEXPIRE', KEYS[1], ARGV[2]); redis.call('PEXPIRE', KEYS[2], ARGV[2]); return revision
        """.trimIndent()
        val OPEN_BIN_WINDOW_SCRIPT = """
            if redis.call('HGET', KEYS[1], 'cycle-id') ~= ARGV[1] then return 0 end
            local time = redis.call('TIME'); local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local requested = now + tonumber(ARGV[2]); local current = tonumber(redis.call('HGET', KEYS[1], 'expire-at') or '0')
            if requested > current then current = requested; redis.call('HSET', KEYS[1], 'expire-at', current) end
            redis.call('PEXPIRE', KEYS[1], ARGV[3]); redis.call('PEXPIRE', KEYS[2], ARGV[3]); return current
        """.trimIndent()
        val READ_BIN_STATE_SCRIPT = """
            local time = redis.call('TIME'); local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            return {redis.call('HGET', KEYS[1], 'cycle-id') or '', redis.call('HGET', KEYS[1], 'expire-at') or '0', redis.call('HGET', KEYS[1], 'revision') or '0', tostring(now)}
        """.trimIndent()
        val FETCH_BIN_ITEMS_SCRIPT = """
            if redis.call('HGET', KEYS[1], 'cycle-id') ~= ARGV[1] then return {} end
            return redis.call('HGETALL', KEYS[2])
        """.trimIndent()
        val RESERVE_BIN_ITEM_SCRIPT = """
            if redis.call('HGET', KEYS[1], 'cycle-id') ~= ARGV[1] then return {-1, 0} end
            local time = redis.call('TIME'); local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local expireAt = tonumber(redis.call('HGET', KEYS[1], 'expire-at') or '0')
            if expireAt <= now then return {-1, 0} end
            local current = tonumber(redis.call('HGET', KEYS[2], ARGV[2]) or '0')
            if current <= 0 then return {0, 0} end
            local taken = math.min(current, tonumber(ARGV[3])); local remaining = current - taken
            if remaining <= 0 then redis.call('HDEL', KEYS[2], ARGV[2]) else redis.call('HSET', KEYS[2], ARGV[2], remaining) end
            redis.call('HSET', KEYS[3], ARGV[4], ARGV[5] .. '|' .. ARGV[1] .. '|' .. tostring(now) .. '|' .. tostring(taken) .. '|' .. ARGV[2])
            redis.call('PEXPIRE', KEYS[3], ARGV[6])
            return {taken, redis.call('HINCRBY', KEYS[1], 'revision', 1)}
        """.trimIndent()
        val COMPLETE_BIN_RESERVATION_SCRIPT = """
            local reservation = redis.call('HGET', KEYS[1], ARGV[1])
            if not reservation then return 0 end
            local first = string.find(reservation, '|', 1, true)
            local second = first and string.find(reservation, '|', first + 1, true) or nil
            if not second or string.sub(reservation, 1, first - 1) ~= ARGV[3] or string.sub(reservation, first + 1, second - 1) ~= ARGV[2] then return 0 end
            return redis.call('HDEL', KEYS[1], ARGV[1])
        """.trimIndent()
        val RELEASE_BIN_RESERVATION_SCRIPT = """
            local reservation = redis.call('HGET', KEYS[3], ARGV[2])
            if not reservation then return 0 end
            local first = string.find(reservation, '|', 1, true)
            local second = first and string.find(reservation, '|', first + 1, true) or nil
            local third = second and string.find(reservation, '|', second + 1, true) or nil
            local fourth = third and string.find(reservation, '|', third + 1, true) or nil
            if not fourth or string.sub(reservation, 1, first - 1) ~= ARGV[3] or string.sub(reservation, first + 1, second - 1) ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'cycle-id') ~= ARGV[1] then return 0 end
            local time = redis.call('TIME'); local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local expireAt = tonumber(redis.call('HGET', KEYS[1], 'expire-at') or '0')
            if expireAt <= now then return 0 end
            local amount = tonumber(string.sub(reservation, third + 1, fourth - 1)) or 0
            local encoded = string.sub(reservation, fourth + 1)
            if amount <= 0 or encoded == '' then return 0 end
            redis.call('HDEL', KEYS[3], ARGV[2])
            redis.call('HINCRBY', KEYS[2], encoded, amount)
            return redis.call('HINCRBY', KEYS[1], 'revision', 1)
        """.trimIndent()
        val FIND_BIN_RESERVATIONS_SCRIPT = """
            local time = redis.call('TIME'); local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local fields = redis.call('HGETALL', KEYS[1]); local result = {}
            for index = 1, #fields, 2 do
                local reservation = fields[index + 1]
                local first = string.find(reservation, '|', 1, true)
                local second = first and string.find(reservation, '|', first + 1, true) or nil
                local third = second and string.find(reservation, '|', second + 1, true) or nil
                local fourth = third and string.find(reservation, '|', third + 1, true) or nil
                local createdAt = third and tonumber(string.sub(reservation, second + 1, third - 1)) or 0
                if fourth and createdAt > now - tonumber(ARGV[2]) and string.sub(reservation, 1, first - 1) == ARGV[1] then
                    table.insert(result, fields[index])
                    table.insert(result, string.sub(reservation, first + 1, second - 1))
                    table.insert(result, string.sub(reservation, third + 1, fourth - 1))
                    table.insert(result, string.sub(reservation, fourth + 1))
                elseif createdAt <= now - tonumber(ARGV[2]) then
                    redis.call('HDEL', KEYS[1], fields[index])
                end
            end
            return result
        """.trimIndent()
    }
}

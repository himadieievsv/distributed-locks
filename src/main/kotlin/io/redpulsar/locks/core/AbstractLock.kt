package io.redpulsar.locks.core

import io.redpulsar.locks.api.Lock
import io.redpulsar.utils.failsafe
import redis.clients.jedis.UnifiedJedis
import redis.clients.jedis.params.SetParams
import java.util.UUID
import kotlin.time.Duration

/**
 * Common functions for broad range of different locks.
 */
abstract class AbstractLock : Lock {
    private val clientId: String = UUID.randomUUID().toString()

    /**
     * Locks the resource on the given Redis instance.
     */
    protected fun lockInstance(
        instance: UnifiedJedis,
        resourceName: String,
        ttl: Duration,
    ): Boolean {
        val lockParams = SetParams().nx().px(ttl.inWholeMilliseconds)
        val result = failsafe(null) { instance.set(resourceName, clientId, lockParams) }
        return result != null
    }

    /**
     * Unlocks the resource on the given Redis instance.
     */
    protected fun unlockInstance(
        instance: UnifiedJedis,
        resourceName: String,
    ) {
        val luaScript =
            """
            if redis.call("get", KEYS[1]) == ARGV[1] then
                return redis.call("del", KEYS[1])
            end
            return 0
            """.trimIndent()
        failsafe { instance.eval(luaScript, listOf(resourceName), listOf(clientId)) }
    }
}
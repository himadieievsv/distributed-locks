package io.redpulsar.jedis.locks

import io.redpulsar.core.locks.abstracts.backends.CountDownLatchBackend
import io.redpulsar.core.utils.failsafe
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import mu.KotlinLogging
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.UnifiedJedis
import kotlin.time.Duration

class JedisCountDownLatchBackend(private val jedis: UnifiedJedis) : CountDownLatchBackend() {
    override fun count(
        latchKeyName: String,
        channelName: String,
        clientId: String,
        count: Int,
        initialCount: Int,
        ttl: Duration,
    ): String? {
        val luaScript =
            """
            redis.call("sadd", KEYS[1], ARGV[1])
            if redis.call("pttl", KEYS[1]) == -1 then
                redis.call("pexpire", KEYS[1], ARGV[2])
            else
                redis.call("pexpire", KEYS[1], ARGV[2], "GT")
            end
            local elementsCount = redis.call("scard", KEYS[1])
            if elementsCount >= tonumber(ARGV[3]) then
                redis.call("publish", KEYS[2], "open")
            end
            return "OK"
            """.trimIndent()
        return failsafe(null) {
            convertToString(
                jedis.eval(
                    luaScript,
                    listOf(latchKeyName, channelName),
                    listOf("$clientId$count", ttl.inWholeMilliseconds.toString(), initialCount.toString()),
                ),
            )
        }
    }

    override fun undoCount(
        latchKeyName: String,
        clientId: String,
        count: Int,
    ): Long? {
        return failsafe(null) {
            jedis.srem(latchKeyName, "$clientId$count")
        }
    }

    override fun checkCount(latchKeyName: String): Long? {
        return failsafe(null) {
            jedis.scard(latchKeyName)
        }
    }

    override fun listen(channelName: String): Flow<String> {
        val flow =
            callbackFlow {
                val pubSub =
                    object : JedisPubSub() {
                        override fun onMessage(
                            channel: String?,
                            message: String?,
                        ) {
                            message?.let {
                                if (message == "open") trySend(it)
                            }
                        }
                    }
                val job = launch { jedis.subscribe(pubSub, "test") }
                awaitClose {
                    try {
                        pubSub.unsubscribe("test")
                    } catch (e: Exception) {
                        // supress unsubscribe errors as it might be uew to lost connection
                        val logger = KotlinLogging.logger {}
                        logger.info { "Unsubscribe failed: ${e.message}" }
                    }
                    job.cancel()
                }
            }
        return flow
    }
}

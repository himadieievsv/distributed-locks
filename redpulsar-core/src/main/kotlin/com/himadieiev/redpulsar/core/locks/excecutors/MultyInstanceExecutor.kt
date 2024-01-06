package com.himadieiev.redpulsar.core.locks.excecutors

import com.himadieiev.redpulsar.core.locks.abstracts.Backend
import com.himadieiev.redpulsar.core.utils.withRetry
import com.himadieiev.redpulsar.core.utils.withTimeoutInThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.Collections
import kotlin.system.measureTimeMillis

/**
 * An algorithm for running closure on multiple remote instances proxied by [backends].
 * Each call will be executed in separate [Job] and wait for the result using one of two self-explanatory strategies:
 * [waitAllJobs] and [waitAnyJobs].
 * Also, it checks whether the result is successful on majority (depends on waiting strategy) of instances and time
 * spend for getting results is not exceeding some reasonable time difference using [timeout] and
 * clok drift.
 *
 * Coroutine used by callee must be cooperative coroutine (not blocking).
 * In order to cancel jobs forcefully, use [withTimeoutInThread] instead.
 */
inline fun <T : Backend, R> multyInstanceExecute(
    backends: List<T>,
    scope: CoroutineScope,
    timeout: Duration,
    defaultDrift: Duration = Duration.ofMillis(3),
    crossinline waiter: suspend (jobs: List<Job>, results: MutableList<R>) -> Unit = ::waitAllJobs,
    crossinline callee: suspend (backend: T) -> R,
): List<R> {
    val jobs = mutableListOf<Job>()
    val quorum: Int = backends.size / 2 + 1
    val results = Collections.synchronizedList(mutableListOf<R>())
    val clockDrift = (timeout.toMillis() * 0.01).toLong() + defaultDrift.toMillis()
    val timeDiff =
        measureTimeMillis {
            backends.forEach { backend ->
                jobs.add(
                    scope.launch {
                        val result = callee(backend)
                        if (result != null) {
                            results.add(result)
                        }
                    },
                )
            }
            runBlocking(scope.coroutineContext) { waiter(jobs, results) }
        }
    val validity = timeout.toMillis() - timeDiff - clockDrift
    if (results.size < quorum || validity < 0) {
        return emptyList()
    }
    return results
}

inline fun <T : Backend, R> multyInstanceExecuteWithRetry(
    backends: List<T>,
    scope: CoroutineScope,
    timeout: Duration,
    defaultDrift: Duration = Duration.ofMillis(3),
    retryCount: Int = 3,
    retryDelay: Duration = Duration.ofMillis(100),
    crossinline waiter: suspend (jobs: List<Job>, results: MutableList<R>) -> Unit = ::waitAllJobs,
    crossinline callee: suspend (backend: T) -> R,
): List<R> {
    return withRetry(retryCount = retryCount, retryDelay = retryDelay) {
        return@withRetry multyInstanceExecute(
            backends = backends,
            scope = scope,
            timeout = timeout,
            defaultDrift = defaultDrift,
            waiter = waiter,
            callee = callee,
        )
    }
}

fun <T : Backend, R> List<T>.executeWithRetry(
    scope: CoroutineScope,
    timeout: Duration,
    defaultDrift: Duration = Duration.ofMillis(3),
    retryCount: Int = 3,
    retryDelay: Duration = Duration.ofMillis(100),
    waiter: suspend (jobs: List<Job>, results: MutableList<R>) -> Unit = ::waitAllJobs,
    callee: suspend (backend: T) -> R,
): List<R> {
    return multyInstanceExecuteWithRetry(
        backends = this,
        scope = scope,
        timeout = timeout,
        defaultDrift = defaultDrift,
        retryCount = retryCount,
        retryDelay = retryDelay,
        waiter = waiter,
        callee = callee,
    )
}
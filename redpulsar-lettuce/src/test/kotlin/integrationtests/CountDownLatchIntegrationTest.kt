package integrationtests

import TestTags
import getInstances
import io.redpulsar.core.locks.ListeningCountDownLatch
import io.redpulsar.core.locks.api.CallResult
import io.redpulsar.core.locks.api.CountDownLatch
import io.redpulsar.core.utils.withTimeoutInThread
import io.redpulsar.lettuce.LettucePubSubPooled
import io.redpulsar.lettuce.locks.LettuceCountDownLatchBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds

@Tag(TestTags.INTEGRATIONS)
class CountDownLatchIntegrationTest {
    private lateinit var instances: List<LettucePubSubPooled<String, String>>
    private lateinit var backends: List<LettuceCountDownLatchBackend>

    @BeforeEach
    fun setUp() {
        instances = getInstances()
        instances.forEach {
            it.sync { sync -> sync.flushall() }
        }
        backends = instances.map { LettuceCountDownLatchBackend(it) }
    }

    @ParameterizedTest(name = "latch opened after {0} counts")
    @ValueSource(ints = [1, 2, 3, 5, 7, 10])
    fun `latch opened`(count: Int) {
        val countingLatches = createLatches(count, count)
        val waitingLatch = createLatch(count)
        CoroutineScope(Dispatchers.Default).launch {
            delay(50)
            countingLatches.forEach {
                it.countDown()
            }
        }
        var wasUnlocked = false
        withTimeoutInThread(1000) {
            val await = waitingLatch.await()
            assertEquals(CallResult.SUCCESS, await)
            wasUnlocked = true
        }
        assertTrue(wasUnlocked)
    }

    @Test
    fun `await will unlock immediately`() {
        val countingLatches = createLatches(3, 3)
        val waitingLatch = createLatch(3)

        countingLatches.forEach {
            it.countDown()
        }

        var wasUnlocked = false
        val time =
            measureTimeMillis {
                withTimeoutInThread(100) {
                    val await = waitingLatch.await()
                    assertEquals(CallResult.SUCCESS, await)
                    wasUnlocked = true
                }
            }
        assertTrue(time < 100)
        assertTrue(wasUnlocked)
    }

    @Test
    fun `different latch names will not affect each others`() {
        val countingLatches = createLatches(3, 3, "latch1")
        val waitingLatch = createLatch(3, "latch2")

        countingLatches.forEach {
            it.countDown()
        }

        val time =
            measureTimeMillis {
                assertEquals(CallResult.FAILED, waitingLatch.await(1.seconds))
            }
        assertFalse(time < 1000)
    }

    @Test
    fun `not enough count downs`() {
        val countingLatches = createLatches(3, 3, "latch1")
        val waitingLatch = createLatch(4, "latch1")

        countingLatches.forEach {
            it.countDown()
        }

        val time =
            measureTimeMillis {
                assertEquals(CallResult.FAILED, waitingLatch.await(1.seconds))
            }
        assertFalse(time < 1000)
    }

    @Test
    fun `return correct number of counts`() {
        val countingLatches = createLatch(3, "latch1")

        repeat(3) { i ->
            assertEquals(3 - i, countingLatches.getCount())
            countingLatches.countDown()
        }
        repeat(3) {
            countingLatches.countDown()
            assertEquals(0, countingLatches.getCount())
        }
    }

    private fun createLatch(
        count: Int,
        name: String = "my-latch",
    ) = createLatches(1, count, name).first()

    private fun createLatches(
        cardinality: Int,
        count: Int,
        name: String = "my-latch",
    ): List<CountDownLatch> {
        val countingLatches = mutableListOf<CountDownLatch>()
        repeat(cardinality) {
            countingLatches.add(
                ListeningCountDownLatch(
                    name = name,
                    count = count,
                    backends = backends,
                    maxDuration = 3.seconds,
                ),
            )
        }
        return countingLatches
    }
}

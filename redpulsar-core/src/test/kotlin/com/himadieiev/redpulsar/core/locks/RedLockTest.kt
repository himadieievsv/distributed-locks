package com.himadieiev.redpulsar.core.locks

import TestTags
import com.himadieiev.redpulsar.core.locks.abstracts.backends.LocksBackend
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

@Tag(TestTags.UNIT)
class RedLockTest {
    @Nested
    inner class SingleRedisInstance {
        private lateinit var backend: LocksBackend

        @BeforeEach
        fun setUp() {
            backend = mockk<LocksBackend>()
        }

        @ParameterizedTest(name = "lock acquired with {0} seconds ttl")
        @ValueSource(ints = [1, 2, 5, 7, 10])
        fun `lock acquired`(ttl: Long) {
            every { backend.setLock(eq("test"), any(), eq(Duration.ofSeconds(ttl))) } returns "OK"

            val redLock = RedLock(listOf(backend))
            val permit = redLock.lock("test", Duration.ofSeconds(ttl))

            assertTrue(permit)
            verify(exactly = 1) { backend.setLock(any(), any(), any()) }
            verify(exactly = 0) { backend.removeLock(any(), any()) }
        }

        @Test
        fun `lock already taken or instance is down`() {
            every { backend.setLock(eq("test"), any(), eq(Duration.ofSeconds(10))) } returns null
            every { backend.removeLock(eq("test"), any()) } returns "OK"

            val redLock = RedLock(listOf(backend), retryCount = 3, retryDelay = Duration.ofMillis(20))
            val permit = redLock.lock("test")

            assertFalse(permit)

            verify(exactly = 3) {
                backend.setLock(any(), any(), any())
                backend.removeLock(any(), any())
            }
        }

        @Test
        fun `unlock resource`() {
            every { backend.removeLock(eq("test"), any()) } returns "OK"

            val redLock = RedLock(listOf(backend))
            // It cant be guarantied that the lock was actually acquired
            redLock.unlock("test")

            verify(exactly = 1) {
                backend.removeLock(eq("test"), any())
            }
            verify(exactly = 0) {
                backend.setLock(any(), any(), any())
            }
        }

        @ParameterizedTest(name = "Validated with retry count - {0}")
        @ValueSource(ints = [-123, -1, 0, 1, 2, 5, 7, 10])
        fun `validate retry count`(retryCount: Int) {
            if (retryCount > 0) {
                Assertions.assertDoesNotThrow { RedLock(listOf(backend), retryCount = retryCount) }
            } else {
                assertThrows<IllegalArgumentException> { RedLock(listOf(backend), retryCount = retryCount) }
            }
        }

        @ParameterizedTest(name = "Validated with retry delay - {0}")
        @ValueSource(ints = [-123, -1, 0, 1, 2, 5, 7, 10])
        fun `validate retry delay`(retryDelay: Long) {
            if (retryDelay > 0) {
                Assertions.assertDoesNotThrow { RedLock(listOf(backend), retryDelay = Duration.ofMillis(retryDelay)) }
            } else {
                assertThrows<IllegalArgumentException> {
                    RedLock(listOf(backend), retryDelay = Duration.ofMillis(retryDelay))
                }
            }
        }

        @Test
        fun `validate instance count`() {
            Assertions.assertDoesNotThrow { RedLock(listOf(backend)) }
            assertThrows<IllegalArgumentException> { RedLock(listOf()) }
        }

        @ParameterizedTest(name = "lock acquired with ttl - {0}")
        @ValueSource(ints = [-123, -1, 0, 1, 2, 5, 7, 10])
        fun `validate ttl`(ttl: Long) {
            every { backend.setLock(eq("test"), any(), eq(Duration.ofMillis(ttl))) } returns "OK"
            // validity can be rejected with tiny ttl
            every { backend.removeLock(eq("test"), any()) } returns "OK"

            val redLock = RedLock(listOf(backend))
            if (ttl > 2) {
                Assertions.assertDoesNotThrow { redLock.lock("test", Duration.ofMillis(ttl)) }
            } else {
                assertThrows<IllegalArgumentException> { redLock.lock("test", Duration.ofMillis(ttl)) }
            }
        }
    }

    @Nested
    inner class MultipleRedisInstance {
        private lateinit var backend1: LocksBackend
        private lateinit var backend2: LocksBackend
        private lateinit var backend3: LocksBackend
        private lateinit var instances: List<LocksBackend>

        @BeforeEach
        fun setUp() {
            backend1 = mockk<LocksBackend>()
            backend2 = mockk<LocksBackend>()
            backend3 = mockk<LocksBackend>()
            instances = listOf(backend1, backend2, backend3)
        }

        @Test
        fun `all instances are in quorum`() {
            instances.forEach { backend ->
                every {
                    backend.setLock(eq("test"), any(), any())
                } returns "OK"
            }

            val redLock = RedLock(instances)
            val permit = redLock.lock("test")

            assertTrue(permit)
            verify(exactly = 1) {
                instances.forEach { backend -> backend.setLock(eq("test"), any(), any()) }
            }
            verify(exactly = 0) {
                instances.forEach { backend -> backend.removeLock(any(), any()) }
            }
        }

        @Test
        fun `two instances are in quorum`() {
            every { backend1.setLock(eq("test"), any(), any()) } returns "OK"
            every { backend2.setLock(eq("test"), any(), any()) } returns null
            every { backend3.setLock(eq("test"), any(), any()) } returns "OK"

            val redLock = RedLock(instances)
            val permit = redLock.lock("test")

            assertTrue(permit)
            verify(exactly = 1) {
                instances.forEach { backend -> backend.setLock(eq("test"), any(), any()) }
            }
            verify(exactly = 0) {
                instances.forEach { backend -> backend.removeLock(any(), any()) }
            }
        }

        @Test
        fun `quorum wasn't reach`() {
            every { backend1.setLock(eq("test"), any(), any()) } returns null
            every { backend2.setLock(eq("test"), any(), any()) } returns "OK"
            every { backend3.setLock(eq("test"), any(), any()) } returns null
            instances.forEach { backend ->
                every { backend.removeLock(eq("test"), any()) } returns "OK"
            }

            val redLock = RedLock(instances, retryCount = 3, retryDelay = Duration.ofMillis(20))
            val permit = redLock.lock("test")

            assertFalse(permit)
            verify(exactly = 3) {
                instances.forEach { backend -> backend.setLock(eq("test"), any(), any()) }
            }
            verify(exactly = 3) {
                instances.forEach { backend -> backend.removeLock(eq("test"), any()) }
            }
        }

        @Test
        fun `lock declined due to clock drift`() {
            every { backend1.setLock(eq("test"), any(), any()) } returns "OK"
            every { backend2.setLock(eq("test"), any(), any()) } answers {
                runBlocking { delay(20) }
                "OK"
            }
            every { backend3.setLock(eq("test"), any(), any()) } returns "OK"
            instances.forEach { backend ->
                every { backend.removeLock(eq("test"), any()) } returns "OK"
            }

            val redLock = RedLock(instances, retryCount = 3, retryDelay = Duration.ofMillis(20))
            val permit = redLock.lock("test", Duration.ofMillis(20))

            assertFalse(permit)
            verify(exactly = 3) {
                instances.forEach { backend -> backend.setLock(eq("test"), any(), any()) }
            }
            verify(exactly = 3) {
                instances.forEach { backend -> backend.removeLock(eq("test"), any()) }
            }
        }
    }
}
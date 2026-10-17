package io.github.ccbitz.mcsrcmcp.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IdleReleaseTest {
    @Test
    fun `idle timeout defaults to thirty minutes and 0 disables it`() {
        assertEquals(Duration.ofMinutes(30), resolveIdleTimeout(emptyMap()))
        assertEquals(Duration.ofMinutes(5), resolveIdleTimeout(mapOf("MCSRC_MCP_IDLE_MINUTES" to "5")))
        assertNull(resolveIdleTimeout(mapOf("MCSRC_MCP_IDLE_MINUTES" to "0")))
        assertEquals(Duration.ofMinutes(30), resolveIdleTimeout(mapOf("MCSRC_MCP_IDLE_MINUTES" to "soon")))
        assertEquals(Duration.ofMinutes(30), resolveIdleTimeout(mapOf("MCSRC_MCP_IDLE_MINUTES" to "-1")))
    }

    @Test
    fun `a running call is never idle, and idle time counts from when it finished`() = runTest {
        var now = 0L
        val tracker = IdleTracker { now }
        val gate = CompletableDeferred<Unit>()

        val call = launch { tracker.track { gate.await() } }
        runCurrent()
        now = Duration.ofHours(1).toNanos()
        assertNull(tracker.idleFor())

        gate.complete(Unit)
        call.join()
        now += Duration.ofMinutes(2).toNanos()
        assertEquals(Duration.ofMinutes(2), tracker.idleFor())
    }

    @Test
    fun `releases once per idle stretch, and again after new activity`() = runTest {
        val tracker = IdleTracker { testScheduler.currentTime * 1_000_000 }
        var releases = 0
        val loop = launch { releaseWhenIdle(tracker, Duration.ofMinutes(10)) { releases++; true } }

        advanceTimeBy(Duration.ofMinutes(9).toMillis())
        assertEquals(0, releases)
        advanceTimeBy(Duration.ofMinutes(30).toMillis())
        assertEquals(1, releases)

        tracker.track { }
        advanceTimeBy(Duration.ofMinutes(11).toMillis())
        assertEquals(2, releases)
        loop.cancel()
    }

    @Test
    fun `a refused release is retried on the next check`() = runTest {
        val tracker = IdleTracker { testScheduler.currentTime * 1_000_000 }
        var attempts = 0
        val loop = launch { releaseWhenIdle(tracker, Duration.ofMinutes(1)) { ++attempts >= 3 } }

        advanceTimeBy(Duration.ofMinutes(10).toMillis() + 1)
        assertEquals(3, attempts)
        loop.cancel()
    }
}

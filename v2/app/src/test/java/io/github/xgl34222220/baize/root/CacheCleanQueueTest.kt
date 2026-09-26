package io.github.xgl34222220.baize.root

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CacheCleanQueueTest {
    @Test fun parallelAppsKeepTheirOwnRootsSequentialAndCallbacksOnCaller() {
        val caller = Thread.currentThread()
        val started = CountDownLatch(2)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val order = Array(8) { mutableListOf<Int>() }
        val results = mutableListOf<Int>()
        CacheCleanQueue.run((0..7).map { app -> (0..3).map { app to it } }, 2, AtomicBoolean(), {
            val running = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, running) }
            started.countDown()
            check(started.await(5, TimeUnit.SECONDS))
            order[it.first] += it.second
            active.decrementAndGet()
            it.first * 10 + it.second
        }, { _, result ->
            assertSame(caller, Thread.currentThread())
            results += result
        })
        assertEquals(2, maxActive.get())
        assertEquals(32, results.size)
        order.forEach { assertEquals(listOf(0, 1, 2, 3), it) }
        assertEquals(0, active.get())
    }

    @Test fun cancellationDrainsInFlightResultsAndLeavesUnstartedWorkAlone() {
        val cancelled = AtomicBoolean()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val results = mutableListOf<Int>()
        val worker = thread {
            try {
                CacheCleanQueue.run((0..9).map { listOf(it * 2, it * 2 + 1) }, 2, cancelled, {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    it
                }, { _, value -> results += value })
            } finally { returned.countDown() }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cancelled.set(true)
            assertFalse(returned.await(50, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        assertEquals(setOf(0, 2), results.toSet())
    }

    @Test fun alreadyCancelledDoesNoWork() {
        CacheCleanQueue.run(listOf(listOf(1)), 4, AtomicBoolean(true),
            { fail("must not start deletion"); it }, { _, _ -> fail("must not report a mutation") })
    }
}

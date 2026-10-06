package com.bitchat.repo.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedIdSetTest {
    @Test fun addReturnsFalseForAnIdAlreadyPresent() {
        val ids = BoundedIdSet(2)

        assertTrue(ids.add("first"))
        assertFalse(ids.add("first"))
        assertEquals(1, ids.size)
    }

    @Test fun addForgetsTheOldestIdAtCapacity() {
        val ids = BoundedIdSet(2)

        ids.add("first")
        ids.add("second")
        assertTrue(ids.add("third"))
        assertTrue(ids.add("first"))
        assertEquals(2, ids.size)
    }

    @Test fun clearRemovesEveryId() {
        val ids = BoundedIdSet(2)
        ids.add("first")
        ids.clear()

        assertEquals(0, ids.size)
        assertTrue(ids.add("first"))
    }

    @Test fun concurrentAddsNeverExceedCapacity() {
        val capacity = 32
        val ids = BoundedIdSet(capacity)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(8)
        val workers = List(8) { worker ->
            thread {
                start.await()
                repeat(1_000) { ids.add("$worker-$it") }
                finished.countDown()
            }
        }

        start.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS), "workers did not finish within five seconds")
        workers.forEach { it.join() }
        assertTrue(ids.size <= capacity)
    }
}

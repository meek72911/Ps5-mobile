package com.example.mobileplay.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPcmTest {
    @Test
    fun partialWriteKeepsTheUnwrittenTail() {
        val queue = PendingPcm(maxFrames = 4, channels = 2)
        val first = shortArrayOf(1, 2, 3, 4, 5, 6)
        assertEquals(0, queue.offer(first))
        assertEquals(1, queue.consume(2))
        assertEquals(2, queue.offset)
        assertEquals(2, queue.queuedFrames)
        assertArrayEquals(first, queue.first())
        assertEquals(2, queue.consume(4))
        assertNull(queue.first())
    }

    @Test
    fun overflowDiscardsTheOldestRemainingFrames() {
        val queue = PendingPcm(maxFrames = 3, channels = 2)
        queue.offer(shortArrayOf(1, 2, 3, 4, 5, 6))
        queue.consume(2)
        val newest = shortArrayOf(7, 8, 9, 10)
        assertEquals(2, queue.offer(newest))
        assertEquals(0, queue.offset)
        assertEquals(2, queue.queuedFrames)
        assertArrayEquals(newest, queue.first())
    }
}

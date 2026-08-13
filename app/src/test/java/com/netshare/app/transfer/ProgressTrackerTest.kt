package com.netshare.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTrackerTest {
    @Test
    fun emaSpeedIncreasesWithBytes() {
        val tracker = ProgressTracker(emaAlpha = 1.0)
        assertEquals(0L, tracker.onBytes(0, nowMs = 1_000))
        val speed = tracker.onBytes(2_000_000, nowMs = 2_000)
        assertTrue("speed=$speed", speed >= 1_500_000)
    }
}

class FrameCodecTest {
    @Test
    fun encodesTypePrefix() {
        val frame = FrameCodec.encode(FrameType.Hello, byteArrayOf(1, 2, 3))
        assertEquals(FrameType.Hello, FrameCodec.peekType(frame))
        assertEquals(4, frame.size)
    }
}

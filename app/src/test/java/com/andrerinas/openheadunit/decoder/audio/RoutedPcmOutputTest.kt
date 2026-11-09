package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class RoutedPcmOutputTest {
    private class Fake(override val name: String) : PcmOutput {
        var started = false; var closed = false; var frames = 0; var writes = 0
        override val capacityFrames = 9600
        override val bufferFrames get() = frames
        override val burstFrames = 480
        override val underruns = 0
        override fun setBufferFrames(frames: Int): Int { this.frames = frames; return frames }
        override fun start() { started = true }
        override fun pause() { started = false }
        override fun write(data: ShortArray, offset: Int, count: Int): Int { writes++; return count }
        override fun close() { closed = true }
    }

    private var generation = 0
    private var carAttached = false
    private val made = ArrayList<Fake>()
    private fun routed() = RoutedPcmOutput({ generation },
        { if (carAttached) Fake("AionLink").also { made.add(it) } else null },
        { Fake("Phone").also { made.add(it) } })

    @Test fun `the same mixer output follows the car across disconnect and reconnect`() {
        carAttached = true; generation = 1
        val output = routed()
        output.setBufferFrames(1920); output.start()
        output.write(ShortArray(4), 0, 4)
        assertEquals("AionLink", output.name)

        carAttached = false; generation = 2 // car session ended
        output.write(ShortArray(4), 0, 4)
        assertEquals("Phone", output.name)
        assertTrue(made[0].closed)

        carAttached = true; generation = 3 // next car session, mixer reused
        output.write(ShortArray(4), 0, 4)
        assertEquals("AionLink", output.name)
        assertTrue(made[1].closed)
        assertTrue("new route keeps playback state", made[2].started)
        assertEquals(1920, made[2].frames)
        assertEquals(1, made[2].writes)
    }

    @Test fun `a route change advances the output epoch so the mixer resets frame accounting`() {
        val output = routed()
        val before = output.outputEpoch
        carAttached = true; generation = 1
        output.write(ShortArray(4), 0, 4)
        assertNotEquals(before, output.outputEpoch)
    }

    @Test fun `no switch and no new output while the route is unchanged`() {
        val output = routed()
        repeat(5) { output.write(ShortArray(4), 0, 4) }
        assertEquals(1, made.size)
        assertEquals(5, made[0].writes)
    }

    @Test fun `a paused output switches without starting the new route`() {
        val output = routed()
        output.start(); output.pause()
        carAttached = true; generation = 1
        output.write(ShortArray(4), 0, 4)
        assertFalse(made[1].started)
    }
}

package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AionLinkPcmOutputTest {
    @Test fun `write admits only up to the effective buffer like a nonblocking device`() {
        val output = AionLinkPcmOutput()
        assertEquals(960, output.setBufferFrames(10))
        val pcm = ShortArray(2000 * 2) { 1 }
        assertEquals(960 * 2, output.write(pcm, 0, pcm.size))
        assertEquals(0, output.write(pcm, 0, pcm.size))
        assertEquals(960L, output.writtenFrames)
    }

    @Test fun `drain mixes queued frames only while started and frees space`() {
        val output = AionLinkPcmOutput()
        output.setBufferFrames(960)
        output.write(shortArrayOf(100, -100, 200, -200), 0, 4)
        val mix = IntArray(8) { 5 }
        output.drainInto(mix, 4)
        assertArrayEquals(IntArray(8) { 5 }, mix) // paused: nothing consumed
        output.start()
        output.drainInto(mix, 4)
        assertArrayEquals(intArrayOf(105, -95, 205, -195, 5, 5, 5, 5), mix)
        assertEquals(2L, output.playedFrames())
        assertEquals(1, output.underruns)
    }

    @Test fun `ring wraps without corrupting sample order`() {
        val output = AionLinkPcmOutput()
        output.setBufferFrames(9600)
        output.start()
        val block = ShortArray(9000 * 2)
        assertEquals(block.size, output.write(block, 0, block.size))
        output.drainInto(IntArray(9000 * 2), 9000)
        val marked = ShortArray(1200 * 2) { it.toShort() }
        assertEquals(marked.size, output.write(marked, 0, marked.size))
        val mix = IntArray(1200 * 2)
        output.drainInto(mix, 1200)
        assertArrayEquals(IntArray(1200 * 2) { it }, mix)
    }

    @Test fun `a detached pipe reports a write error so the mixer reopens the phone output`() {
        val output = AionLinkPcmOutput()
        output.fail()
        assertEquals(-6, output.write(ShortArray(4), 0, 4))
    }

    @Test fun `factory keeps the normal output when no AionLink client is attached`() {
        assertFalse(AionLinkAudioSink.attached)
        assertNull(AionLinkAudioSink.createOutput())
    }

    @Test fun `between client connections an AionLink Self Mode session discards instead of looping into Android Auto`() {
        try {
            val before = AionLinkAudioSink.generation
            AionLinkAudioSink.noteSessionAnnouncement(true)
            assertNotEquals("detached outputs must reroute", before, AionLinkAudioSink.generation)
            assertEquals("Discard", AionLinkAudioSink.createOutput()?.name)
            AionLinkAudioSink.noteSessionAnnouncement(false) // a new ordinary session
            assertNull(AionLinkAudioSink.createOutput())
        } finally { AionLinkAudioSink.noteSessionAnnouncement(false) }
    }

    @Test fun `discard output admits PCM only at the real 48 kHz rate`() {
        var now = 0L
        val output = DiscardPcmOutput { now }
        output.setBufferFrames(960); output.start()
        val pcm = ShortArray(4800 * 2)
        assertEquals(960 * 2, output.write(pcm, 0, pcm.size))
        assertEquals(0, output.write(pcm, 0, pcm.size))
        now += 10_000_000 // 10 ms = 480 frames
        assertEquals(480 * 2, output.write(pcm, 0, pcm.size))
    }
}

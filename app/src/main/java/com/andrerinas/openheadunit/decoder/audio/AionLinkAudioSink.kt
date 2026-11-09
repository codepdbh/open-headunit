package com.andrerinas.openheadunit.decoder.audio

import android.os.ParcelFileDescriptor
import com.andrerinas.openheadunit.utils.AppLog
import java.io.FileOutputStream
import java.io.IOException

/**
 * Optional PCM route to the AionLink client bound through AionLinkSurfaceService.
 *
 * While a pipe is attached, every mixer output created by [AudioOutputFactory] writes here instead of the
 * phone speaker. One pump thread consumes all outputs at the real 48 kHz rate (like a device would), sums
 * them and writes interleaved little-endian PCM16 stereo to the pipe, inserting silence when idle so the
 * client receives a continuous stream. When the pipe fails or is detached, outputs report a write error
 * and RecoveringPcmOutput reopens them through the factory, which then selects the normal phone output.
 */
internal object AionLinkAudioSink {
    const val SAMPLE_RATE = 48000
    private const val CHANNELS = 2
    private const val TICK_MS = 10L
    private val lock = Object()
    private var stream: FileOutputStream? = null
    /** Changes on every attach and detach; [RoutedPcmOutput] compares it to switch routes. */
    @Volatile var generation = 0
        private set
    private val outputs = ArrayList<AionLinkPcmOutput>()
    @Volatile var framesSent = 0L
        private set

    val attached: Boolean get() = synchronized(lock) { stream != null }

    /**
     * True while the current Self Mode session announced media and speech only because a client was attached. Between
     * client connections Android Auto keeps capturing the phone's media for that session, so playing it locally would
     * loop it back; such outputs then discard PCM at the real rate until the client returns or a new session starts.
     */
    @Volatile var selfModeMediaForClient = false
        private set

    /** Called by service discovery for every new session. */
    fun noteSessionAnnouncement(mediaForClient: Boolean) {
        synchronized(lock) {
            if (selfModeMediaForClient == mediaForClient) return
            selfModeMediaForClient = mediaForClient
            if (stream == null) generation++ // reroute detached outputs
        }
    }

    fun attach(pipe: ParcelFileDescriptor) {
        val next = ParcelFileDescriptor.AutoCloseOutputStream(pipe)
        val token: Int
        synchronized(lock) {
            closeLocked()
            stream = next
            token = ++generation
            framesSent = 0
        }
        AppLog.i("AionLinkAudioSink: pipe attached; new audio outputs go to AionLink")
        Thread({ pump(next, token) }, "AionLink-audio-pump").apply { isDaemon = true; start() }
    }

    fun detach() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        val old = stream ?: return
        stream = null
        generation++
        outputs.forEach { it.fail() }
        outputs.clear()
        try { old.close() } catch (_: IOException) { }
        AppLog.i("AionLinkAudioSink: pipe detached; audio returns to the phone output")
    }

    /** Null when the caller should use the normal device output. */
    fun createOutput(): PcmOutput? = synchronized(lock) {
        when {
            stream != null -> AionLinkPcmOutput().also { outputs.add(it) }
            selfModeMediaForClient -> DiscardPcmOutput()
            else -> null
        }
    }

    fun release(output: AionLinkPcmOutput) { synchronized(lock) { outputs.remove(output) } }

    private fun pump(out: FileOutputStream, token: Int) {
        val startNs = System.nanoTime()
        var sent = 0L
        var mix = IntArray(0)
        var bytes = ByteArray(0)
        try {
            while (true) {
                val due = (System.nanoTime() - startNs) / 1000 * SAMPLE_RATE / 1_000_000 - sent
                if (due <= 0) { Thread.sleep(TICK_MS); continue }
                val frames = minOf(due, SAMPLE_RATE / 10L).toInt() // never burst more than 100 ms after a stall
                if (mix.size < frames * CHANNELS) { mix = IntArray(frames * CHANNELS); bytes = ByteArray(frames * CHANNELS * 2) }
                java.util.Arrays.fill(mix, 0, frames * CHANNELS, 0)
                synchronized(lock) {
                    if (generation != token) return
                    outputs.forEach { it.drainInto(mix, frames) }
                }
                for (i in 0 until frames * CHANNELS) {
                    val s = mix[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s shr 8).toByte()
                }
                out.write(bytes, 0, frames * CHANNELS * 2) // blocks if the client reads slowly
                sent += frames
                framesSent = sent
                // Re-anchor after a long block so we do not try to catch up with stale time.
                if ((System.nanoTime() - startNs) / 1000 * SAMPLE_RATE / 1_000_000 - sent > SAMPLE_RATE / 2) {
                    sent = (System.nanoTime() - startNs) / 1000 * SAMPLE_RATE / 1_000_000
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            AppLog.w("AionLinkAudioSink: pipe closed by client: ${e.message}")
        } finally {
            synchronized(lock) { if (generation == token) closeLocked() }
        }
    }
}

/** A software device buffer drained in real time by [AionLinkAudioSink]. Mixer thread writes, pump reads. */
internal class AionLinkPcmOutput : PcmOutput {
    private companion object { const val CAPACITY = 9600; const val CHANNELS = 2 }
    private val ring = ShortArray(CAPACITY * CHANNELS)
    private var readPos = 0
    private var queued = 0 // frames
    private var target = 1920
    private var playing = false
    @Volatile private var failed = false
    private var written = 0L
    private var played = 0L
    private var underrunCount = 0

    override val name = "AionLink"
    override val capacityFrames = CAPACITY
    override val bufferFrames: Int get() = synchronized(this) { target }
    override val burstFrames = 480
    override val underruns: Int get() = synchronized(this) { underrunCount }
    override val minimumBufferFrames = 960
    override val writtenFrames: Long get() = synchronized(this) { written }
    override fun playedFrames(): Long = synchronized(this) { played }

    override fun setBufferFrames(frames: Int): Int = synchronized(this) {
        target = frames.coerceIn(minimumBufferFrames, CAPACITY); target
    }
    override fun start() { synchronized(this) { playing = true } }
    override fun pause() { synchronized(this) { playing = false; queued = 0; written = 0; played = 0 } }

    override fun write(data: ShortArray, offset: Int, count: Int): Int {
        if (count < 0 || count % CHANNELS != 0) throw IllegalArgumentException("count=$count")
        if (failed) return -6
        synchronized(this) {
            val frames = minOf(count / CHANNELS, target - queued)
            if (frames <= 0) return 0
            var w = (readPos + queued) % CAPACITY
            for (f in 0 until frames) {
                ring[w * 2] = data[offset + f * 2]; ring[w * 2 + 1] = data[offset + f * 2 + 1]
                w = (w + 1) % CAPACITY
            }
            queued += frames
            written += frames
            return frames * CHANNELS
        }
    }

    fun drainInto(mix: IntArray, frames: Int) {
      synchronized(this) {
        if (!playing) return
        val n = minOf(frames, queued)
        if (n < frames && written > 0) underrunCount++
        for (f in 0 until n) {
            mix[f * 2] += ring[readPos * 2]; mix[f * 2 + 1] += ring[readPos * 2 + 1]
            readPos = (readPos + 1) % CAPACITY
        }
        queued -= n
        played += n
      }
    }

    fun fail() { failed = true }

    override fun close() { AionLinkAudioSink.release(this); synchronized(this) { queued = 0 } }
}

/** Accepts PCM at the real 48 kHz rate and drops it (no device, no busy loop). */
internal class DiscardPcmOutput(private val nanoTime: () -> Long = System::nanoTime) : PcmOutput {
    private companion object { const val CAPACITY = 9600 }
    private var target = 1920
    private var queued = 0.0 // frames notionally waiting to be "played"
    private var lastNs = -1L
    private var written = 0L
    private var playing = false
    override val name = "Discard"
    override val capacityFrames = CAPACITY
    override val bufferFrames get() = target
    override val burstFrames = 480
    override val underruns = 0
    override val underrunsSupported = false
    override val minimumBufferFrames = 960
    override val writtenFrames get() = written
    override fun playedFrames(): Long { advance(); return written - queued.toLong() }
    override fun setBufferFrames(frames: Int): Int { target = frames.coerceIn(minimumBufferFrames, CAPACITY); return target }
    override fun start() { playing = true; lastNs = nanoTime() }
    override fun pause() { playing = false; queued = 0.0; written = 0 }
    private fun advance() {
        val now = nanoTime()
        if (playing && lastNs >= 0) queued = maxOf(0.0, queued - (now - lastNs) * 48_000.0 / 1e9)
        lastNs = now
    }
    override fun write(data: ShortArray, offset: Int, count: Int): Int {
        if (count < 0 || count % 2 != 0) throw IllegalArgumentException("count=$count")
        advance()
        val frames = minOf(count / 2, (target - queued).toInt())
        if (frames <= 0) return 0
        queued += frames; written += frames
        return frames * 2
    }
    override fun close() {}
}

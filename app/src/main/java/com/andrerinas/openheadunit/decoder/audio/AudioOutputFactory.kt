package com.andrerinas.openheadunit.decoder.audio

import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog

internal object AudioOutputFactory {
    /**
     * Mixer outputs outlive a single AionLink connection (the decoder and shared mixer are reused), so the route is
     * re-evaluated on every attach and detach instead of once at creation: the car while a client holds a pipe,
     * otherwise the phone output.
     */
    fun create(stream: Int, attachHwDsp: Boolean, preferAAudio: Boolean): PcmOutput =
        RoutedPcmOutput({ AionLinkAudioSink.generation }, { AionLinkAudioSink.createOutput() },
            { createDevice(stream, attachHwDsp, preferAAudio) })

    private fun createDevice(stream: Int, attachHwDsp: Boolean, preferAAudio: Boolean): PcmOutput {
        val fallback = { AudioTrackPcmOutput(stream, attachHwDsp) }
        if (AudioOutputPolicy.useAAudio(Build.VERSION.SDK_INT, stream, attachHwDsp, preferAAudio)) {
            try {
                return FallbackPcmOutput(AAudioPcmOutput(), fallback, { AppLog.w(it) })
            } catch (e: LinkageError) {
                AppLog.w("AAudio unavailable; using AudioTrack: ${e.message}")
            } catch (e: Exception) {
                AppLog.w("AAudio could not open; using AudioTrack: ${e.message}")
            }
        }
        return fallback()
    }
}

/**
 * Switches between the AionLink pipe and the phone output when the client attaches or detaches. The switch happens on
 * the mixer thread before a write, drops only PCM still queued in the old route, and advances [outputEpoch] so the
 * mixer restarts its frame accounting. It is not an error, so it never consumes the output recovery budget.
 */
internal class RoutedPcmOutput(
    private val routeGeneration: () -> Int,
    private val external: () -> PcmOutput?,
    private val device: () -> PcmOutput,
) : PcmOutput {
    private var generation = routeGeneration()
    private var delegate: PcmOutput = external() ?: device()
    private var requestedFrames = -1
    private var started = false
    private var swaps = 0L

    private fun route(): PcmOutput {
        val now = routeGeneration()
        if (now == generation) return delegate
        generation = now
        val next = external() ?: device()
        val old = delegate
        delegate = next
        swaps++
        try { old.close() } catch (e: Exception) { AppLog.w("Audio route: closing ${old.name} failed: ${e.message}") }
        if (requestedFrames > 0) next.setBufferFrames(maxOf(requestedFrames, next.minimumBufferFrames))
        if (started) next.start()
        AppLog.i("Audio route: ${old.name} -> ${next.name}")
        return next
    }

    override val name get() = delegate.name
    override val capacityFrames get() = delegate.capacityFrames
    override val bufferFrames get() = delegate.bufferFrames
    override val burstFrames get() = delegate.burstFrames
    override val underruns get() = delegate.underruns
    override val underrunsSupported get() = delegate.underrunsSupported
    override val stagingBufferFrames get() = delegate.stagingBufferFrames
    override val producerUnderruns get() = delegate.producerUnderruns
    override val writtenFrames get() = delegate.writtenFrames
    override fun playedFrames() = delegate.playedFrames()
    override val minimumBufferFrames get() = delegate.minimumBufferFrames
    override val recoveryProgressSamples get() = delegate.recoveryProgressSamples
    override val outputEpoch get() = delegate.outputEpoch + swaps * 1_000_000L
    override val hasPendingRecovery get() = delegate.hasPendingRecovery
    override val writeRetryDelayMs get() = delegate.writeRetryDelayMs

    override fun setBufferFrames(frames: Int): Int { requestedFrames = frames; return route().setBufferFrames(frames) }
    override fun start() { started = true; route().start() }
    override fun pause() { started = false; route().pause() }
    override fun write(data: ShortArray, offset: Int, count: Int): Int = route().write(data, offset, count)
    override fun close() = delegate.close()
    override fun closeForRecovery(): ShortArray = delegate.closeForRecovery()
}

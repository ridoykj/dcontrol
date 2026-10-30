package com.dcontrol.client.feature.mic.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.dcontrol.client.feature.mic.data.MicUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Encodes clips from the live microphone samples (AAC-LC, mono/source rate, 64 kbps, MPEG-4 `.m4a`). The audio
 * module feeds every PCM chunk to [onSamples]; while a clip records, a background thread pulls those chunks, caps
 * each to a full [durationMs] via [ClipTiming], and muxes them. Live streaming never stops: the samples are the
 * ones WebRTC is already capturing. Only one clip records at a time (the session serializes captures).
 */
class AacClipRecorder(private val cacheDir: File) : ClipRecorder {

    @Volatile
    private var active: Recording? = null

    override fun onSamples(pcm: ByteArray, sampleRate: Int, channels: Int) {
        active?.offer(pcm, sampleRate, channels)
    }

    override suspend fun record(durationMs: Int): CapturedAudio {
        val file = withContext(Dispatchers.IO) { File.createTempFile("mic-", ".m4a", cacheDir) }
        val recording = Recording(durationMs, file)
        active = recording
        try {
            // The clip can't finish before durationMs of samples arrive; allow for encode drain and finalize.
            val encoded = withTimeoutOrNull(durationMs.toLong() + FINALIZE_TIMEOUT_MS) { recording.await() }
            if (encoded == null) {
                recording.cancel()
                file.delete()
                error("The clip did not finish within ${durationMs + FINALIZE_TIMEOUT_MS} ms")
            }
            return CapturedAudio(file, MicUploader.MIME_AUDIO, encoded)
        } catch (e: Throwable) {
            recording.cancel()
            file.delete()
            throw e
        } finally {
            active = null
        }
    }

    /** One clip in progress: a PCM queue drained by an encoder thread that starts once the format is known. */
    private class Recording(private val durationMs: Int, private val file: File) {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val done = CompletableDeferred<Int>()

        @Volatile
        private var thread: Thread? = null

        @Volatile
        private var cancelled = false

        @Volatile
        private var started = false

        fun offer(pcm: ByteArray, sampleRate: Int, channels: Int) {
            if (cancelled) return
            synchronized(this) {
                if (!started) {
                    started = true
                    thread = Thread({ encode(sampleRate, channels) }, "mic-encoder").apply {
                        isDaemon = true
                        start()
                    }
                }
            }
            queue.offer(pcm.copyOf())
        }

        suspend fun await(): Int = done.await()

        fun cancel() {
            cancelled = true
            thread?.interrupt()
        }

        private fun encode(sampleRate: Int, channels: Int) {
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            try {
                val timing = ClipTiming(sampleRate, channels, durationMs)
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                    }
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val info = MediaCodec.BufferInfo()
                var trackIndex = -1
                var muxing = false
                var inputDone = false

                while (!cancelled) {
                    if (!inputDone) inputDone = feedInput(codec, timing)
                    // Drain whatever the encoder has ready; the muxer track appears on the first format change.
                    var drained = true
                    while (drained && !cancelled) {
                        val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                        when {
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                check(!muxing) { "The encoder format changed twice" }
                                trackIndex = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                muxing = true
                            }
                            outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> drained = false
                            outIndex >= 0 -> {
                                val buffer = codec.getOutputBuffer(outIndex)
                                if (buffer != null && info.size > 0 && muxing &&
                                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                ) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    muxer.writeSampleData(trackIndex, buffer, info)
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    done.complete(durationMs)
                                    return
                                }
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Clip recording interrupted")
            } catch (e: Exception) {
                Log.w(TAG, "Clip recording failed", e)
                if (!done.isCompleted) done.completeExceptionally(e)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
            }
        }

        /** Feeds one input buffer's worth of PCM; returns true once the end-of-stream buffer has been queued. */
        private fun feedInput(codec: MediaCodec, timing: ClipTiming): Boolean {
            val chunk = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: return timing.isComplete
            var offset = 0
            while (offset < chunk.size) {
                val available = minOf(chunk.size - offset, MAX_INPUT_SIZE)
                val slice = timing.take(available) ?: return true
                val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex < 0) continue
                val buffer = codec.getInputBuffer(inIndex) ?: continue
                buffer.clear()
                buffer.put(chunk, offset, slice.byteCount)
                val flags = if (slice.endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                codec.queueInputBuffer(inIndex, 0, slice.byteCount, slice.presentationTimeUs, flags)
                offset += slice.byteCount
                if (slice.endOfStream) return true
            }
            return false
        }
    }

    private companion object {
        const val TAG = "MicRecorder"
        const val BIT_RATE = 64_000
        const val MAX_INPUT_SIZE = 16_384
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val POLL_MS = 100L
        const val FINALIZE_TIMEOUT_MS = 5_000L
    }
}

/** The real [MicCapture]: one [AacClipRecorder] fed by a [MicSource]'s samples callback. */
internal class AudioMicCapture(cacheDir: File) : MicCapture {
    private val recorder = AacClipRecorder(cacheDir)
    override val source = MicSource(recorder)

    override suspend fun recordClip(durationMs: Int): CapturedAudio = recorder.record(durationMs)

    override fun close() = Unit
}

/** Builds an [AudioMicCapture] per session, writing clips to the app cache. */
class AudioMicCaptureFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : MicCaptureFactory {
    override fun create(): MicCapture = AudioMicCapture(context.cacheDir)
}

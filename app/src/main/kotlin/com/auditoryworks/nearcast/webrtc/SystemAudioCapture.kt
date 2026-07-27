package com.auditoryworks.nearcast.webrtc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.auditoryworks.nearcast.diagnostics.SessionTraceRecorder
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue

private const val AUDIO_CAPTURE_TAG = "SystemAudioCapture"

/**
 * Captures Android playback audio (AudioPlaybackCapture) and exposes PCM chunks for WebRTC.
 */
class SystemAudioCapture(private val context: Context) {
    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    // Pool of pre-allocated buffers to avoid per-frame clone() allocations.
    private val bufferPool = ArrayBlockingQueue<ByteArray>(16)

    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null
    @Volatile
    private var running = false
    var lastError: String? = null
        private set

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun start(
        mediaProjection: MediaProjection,
        sampleRate: Int,
        channelCount: Int
    ): Boolean {
        SessionTraceRecorder.record(
            AUDIO_CAPTURE_TAG,
            "start requested sampleRate=$sampleRate channelCount=$channelCount"
        )
        lastError = null
        if (!isSupported()) return false
        val hasRecordAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordAudioPermission) {
            Log.w(AUDIO_CAPTURE_TAG, "RECORD_AUDIO permission not granted")
            lastError = "RECORD_AUDIO permission not granted"
            return false
        }
        stop()
        return try {
            val channelMask = if (channelCount == 2) {
                AudioFormat.CHANNEL_IN_STEREO
            } else {
                AudioFormat.CHANNEL_IN_MONO
            }
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val targetBuffer = (sampleRate * channelCount * 2 / 100).coerceAtLeast(minBuffer * 2)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(targetBuffer)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(AUDIO_CAPTURE_TAG, "AudioRecord not initialized")
                lastError = "AudioRecord init failed"
                SessionTraceRecorder.record(AUDIO_CAPTURE_TAG, "AudioRecord init failed")
                stop()
                return false
            }

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(AUDIO_CAPTURE_TAG, "AudioRecord startRecording failed")
                lastError = "AudioRecord start failed"
                SessionTraceRecorder.record(AUDIO_CAPTURE_TAG, "AudioRecord start failed")
                stop()
                return false
            }

            running = true
            val frameBytes = sampleRate * channelCount * 2 / 100 // 10ms
            // Pre-fill the pool with reusable buffers.
            repeat(14) { bufferPool.offer(ByteArray(frameBytes)) }
            readerThread = Thread({
                val localBuffer = ByteArray(frameBytes)
                while (running) {
                    val bytesRead = audioRecord?.read(localBuffer, 0, localBuffer.size) ?: 0
                    if (bytesRead > 0) {
                        // Acquire a buffer from the pool; fall back to allocation only if pool is empty.
                        val chunk = bufferPool.poll() ?: ByteArray(frameBytes)
                        System.arraycopy(localBuffer, 0, chunk, 0, bytesRead)
                        if (bytesRead < frameBytes) {
                            chunk.fill(0, bytesRead, frameBytes)
                        }
                        synchronized(lock) {
                            if (queue.size >= 12) {
                                // Return the evicted buffer to the pool instead of discarding it.
                                bufferPool.offer(queue.removeFirst())
                            }
                            queue.addLast(chunk)
                        }
                    }
                }
            }, "SystemAudioCaptureReader").apply { start() }

            Log.d(
                AUDIO_CAPTURE_TAG,
                "System audio capture started, sampleRate=$sampleRate, channelCount=$channelCount"
            )
            SessionTraceRecorder.record(
                AUDIO_CAPTURE_TAG,
                "capture started sampleRate=$sampleRate channelCount=$channelCount"
            )
            true
        } catch (e: Exception) {
            Log.e(AUDIO_CAPTURE_TAG, "Failed to start system audio capture", e)
            lastError = e.message ?: e.javaClass.simpleName
            SessionTraceRecorder.record(
                AUDIO_CAPTURE_TAG,
                "capture failed: ${e.message ?: e.javaClass.simpleName}"
            )
            stop()
            false
        }
    }

    fun fillAudioBuffer(target: ByteArray): Boolean {
        val chunk = synchronized(lock) {
            if (queue.isEmpty()) return false
            queue.removeFirst()
        }
        if (chunk.size >= target.size) {
            System.arraycopy(chunk, 0, target, 0, target.size)
        } else {
            System.arraycopy(chunk, 0, target, 0, chunk.size)
            target.fill(0, chunk.size, target.size)
        }
        // Return the buffer to the pool for reuse.
        bufferPool.offer(chunk)
        return true
    }

    fun stop() {
        SessionTraceRecorder.record(AUDIO_CAPTURE_TAG, "stop requested")
        running = false
        try {
            readerThread?.join(300)
        } catch (_: Exception) {}
        readerThread = null

        synchronized(lock) {
            queue.clear()
        }
        bufferPool.clear()

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null

        // MediaProjection is shared with ScreenCapturerAndroid and owned by that capturer.
        // Stopping it here would also terminate screen capture.
    }
}

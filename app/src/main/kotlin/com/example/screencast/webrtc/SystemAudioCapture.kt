package com.example.screencast.webrtc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.ArrayDeque

private const val AUDIO_CAPTURE_TAG = "SystemAudioCapture"

/**
 * Captures Android playback audio (AudioPlaybackCapture) and exposes PCM chunks for WebRTC.
 */
class SystemAudioCapture(private val context: Context) {
    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null
    @Volatile
    private var running = false
    var lastError: String? = null
        private set

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun start(
        resultCode: Int,
        data: Intent,
        sampleRate: Int,
        channelCount: Int
    ): Boolean {
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
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

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

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
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
                stop()
                return false
            }

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(AUDIO_CAPTURE_TAG, "AudioRecord startRecording failed")
                lastError = "AudioRecord start failed"
                stop()
                return false
            }

            running = true
            val frameBytes = sampleRate * channelCount * 2 / 100 // 10ms
            readerThread = Thread({
                val localBuffer = ByteArray(frameBytes)
                while (running) {
                    val bytesRead = audioRecord?.read(localBuffer, 0, localBuffer.size) ?: 0
                    if (bytesRead > 0) {
                        val chunk = if (bytesRead == localBuffer.size) {
                            localBuffer.clone()
                        } else {
                            localBuffer.copyOf(bytesRead)
                        }
                        synchronized(lock) {
                            if (queue.size >= 12) {
                                queue.removeFirst()
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
            true
        } catch (e: Exception) {
            Log.e(AUDIO_CAPTURE_TAG, "Failed to start system audio capture", e)
            lastError = e.message ?: e.javaClass.simpleName
            stop()
            false
        }
    }

    fun fillAudioBuffer(target: ByteArray): Boolean {
        synchronized(lock) {
            if (queue.isEmpty()) return false
            val chunk = queue.removeFirst()
            if (chunk.size >= target.size) {
                System.arraycopy(chunk, 0, target, 0, target.size)
                return true
            }
            System.arraycopy(chunk, 0, target, 0, chunk.size)
            for (i in chunk.size until target.size) {
                target[i] = 0
            }
            return true
        }
    }

    fun stop() {
        running = false
        try {
            readerThread?.join(300)
        } catch (_: Exception) {}
        readerThread = null

        synchronized(lock) {
            queue.clear()
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null
    }
}

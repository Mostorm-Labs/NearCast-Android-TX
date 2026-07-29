package com.auditoryworks.nearcast.webrtc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import kotlin.math.roundToInt

private const val STATUS_FRAME_INTERVAL_MS = 1_000L
private const val STATUS_FRAME_MAX_EDGE = 1_280

/**
 * Produces a low-frame-rate privacy frame on the existing WebRTC video source. The rendered I420
 * planes are immutable and cached; only a lightweight VideoFrame wrapper and timestamp are created
 * for each emission.
 */
internal class StatusVideoFrameProducer(
    private val scope: CoroutineScope,
    private val observerProvider: () -> CapturerObserver?
) {
    private var job: Job? = null
    private var activeConfiguration: Configuration? = null
    @Volatile
    private var generation = 0L

    fun start(width: Int, height: Int, message: String) {
        val (outputWidth, outputHeight) = fitWithinMaxEdge(width, height)
        val configuration = Configuration(outputWidth, outputHeight, message)
        if (configuration == activeConfiguration && job?.isActive == true) return

        stop()
        activeConfiguration = configuration
        val producerGeneration = generation
        job = scope.launch {
            val frameData = renderFrame(configuration)
            while (isActive && producerGeneration == generation) {
                observerProvider()?.let(frameData::emitTo)
                delay(STATUS_FRAME_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        activeConfiguration = null
    }

    private data class Configuration(
        val width: Int,
        val height: Int,
        val message: String
    )

    private class I420FrameData(
        private val width: Int,
        private val height: Int,
        private val yPlane: ByteBuffer,
        private val uPlane: ByteBuffer,
        private val vPlane: ByteBuffer
    ) {
        fun emitTo(observer: CapturerObserver) {
            val buffer = JavaI420Buffer.wrap(
                width,
                height,
                yPlane.duplicate().apply { rewind() },
                width,
                uPlane.duplicate().apply { rewind() },
                width / 2,
                vPlane.duplicate().apply { rewind() },
                width / 2,
                Runnable { }
            )
            val frame = VideoFrame(buffer, 0, System.nanoTime())
            try {
                observer.onFrameCaptured(frame)
            } finally {
                frame.release()
            }
        }
    }

    private fun renderFrame(configuration: Configuration): I420FrameData {
        val width = configuration.width
        val height = configuration.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (minOf(width, height) * 0.055f).coerceIn(28f, 68f)
            textAlign = android.graphics.Paint.Align.LEFT
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }
        val textWidth = (width * 0.8f).roundToInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(
            configuration.message,
            0,
            configuration.message.length,
            textPaint,
            textWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(textPaint.textSize * 0.22f, 1f)
            .build()
        canvas.save()
        canvas.translate(
            (width - textWidth) / 2f,
            ((height - layout.height) / 2f).coerceAtLeast(0f)
        )
        layout.draw(canvas)
        canvas.restore()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val yPlane = ByteBuffer.allocateDirect(width * height)
        for (pixel in pixels) {
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            // BT.601 limited-range luma. The frame is monochrome, so both chroma planes are 128.
            val y = (((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16)
                .coerceIn(16, 235)
            yPlane.put(y.toByte())
        }
        yPlane.rewind()

        val chromaSize = width * height / 4
        val uPlane = ByteBuffer.allocateDirect(chromaSize)
        val vPlane = ByteBuffer.allocateDirect(chromaSize)
        repeat(chromaSize) {
            uPlane.put(128.toByte())
            vPlane.put(128.toByte())
        }
        uPlane.rewind()
        vPlane.rewind()

        return I420FrameData(width, height, yPlane, uPlane, vPlane)
    }

    companion object {
        internal fun fitWithinMaxEdge(width: Int, height: Int): Pair<Int, Int> {
            val safeWidth = width.coerceAtLeast(2)
            val safeHeight = height.coerceAtLeast(2)
            val largest = maxOf(safeWidth, safeHeight)
            val scale = if (largest > STATUS_FRAME_MAX_EDGE) {
                STATUS_FRAME_MAX_EDGE.toFloat() / largest
            } else {
                1f
            }
            val outputWidth = makeEven((safeWidth * scale).roundToInt())
            val outputHeight = makeEven((safeHeight * scale).roundToInt())
            return outputWidth to outputHeight
        }

        private fun makeEven(value: Int): Int = value.coerceAtLeast(2) and -2
    }
}

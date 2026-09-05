package com.whiteboard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

class RenderService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var notifManager: NotificationManager? = null

    companion object {
        private const val CHANNEL_ID = "whiteboard_render"
        private const val NOTIF_ID = 1001
        private const val FRAME_RATE = 24
        private const val TIMEOUT_US = 10000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notifManager = getSystemService(NotificationManager::class.java)
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Preparing...", 0))
        acquireWakeLock()

        val script = intent?.getStringExtra("script").orEmpty()
        val height = intent?.getIntExtra("height", 720) ?: 720
        val aspect = intent?.getStringExtra("aspect") ?: "16:9"
        val sceneSeconds = intent?.getIntExtra("sceneSeconds", 5) ?: 5
        val lowHeat = intent?.getBooleanExtra("lowHeat", false) ?: false
        val audioUriStr = intent?.getStringExtra("audioUri")

        scope.launch {
            RenderState.running = true
            RenderState.cancelRequested = false
            RenderState.progress = 0
            try {
                render(script, height, aspect, sceneSeconds, lowHeat, audioUriStr)
            } catch (e: Exception) {
                RenderState.status = "Error: " + (e.message ?: "unknown")
            } finally {
                RenderState.running = false
                releaseWakeLock()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun render(
        script: String,
        height: Int,
        aspect: String,
        sceneSeconds: Int,
        lowHeat: Boolean,
        audioUriStr: String?
    ) {
        val scenes = script.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (scenes.isEmpty()) {
            RenderState.status = "Script is empty"
            return
        }

        val size = sizeFor(height, aspect)
        val width = size.first
        val vHeight = size.second

        val audioUri = audioUriStr?.let { Uri.parse(it) }

        RenderState.status = "Planning scenes..."
        val durations: List<Double> = if (audioUri != null) {
            AudioAnalyzer.sceneDurations(this, audioUri, scenes)
        } else {
            scenes.map { sceneSeconds.toDouble() }
        }

        val frameCounts = durations.map { (it * FRAME_RATE).toInt().coerceAtLeast(1) }
        val totalFrames = frameCounts.sum()

        val outDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        outDir?.mkdirs()
        val tempFile = File(outDir, "whiteboard_" + System.currentTimeMillis() + ".mp4")

        val bitrate = when {
            vHeight >= 1080 -> 6_000_000
            vHeight >= 720 -> 3_000_000
            else -> 1_500_000
        }

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, vHeight
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false

        var audioExtractor: MediaExtractor? = null
        var audioFormat: MediaFormat? = null
        if (audioUri != null) {
            try {
                val ex = MediaExtractor()
                ex.setDataSource(this, audioUri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                            ex.selectTrack(i)
                            audioExtractor = ex
                            audioFormat = f
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                audioExtractor = null
            }
        }

        val bitmap = Bitmap.createBitmap(width, vHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val argb = IntArray(width * vHeight)
        val nv12 = ByteArray(width * vHeight * 3 / 2)
        val bufferInfo = MediaCodec.BufferInfo()

        var frameIndex = 0
        var sceneIndex = 0

        for (scene in scenes) {
            if (RenderState.cancelRequested) break
            val sceneFrames = frameCounts[sceneIndex]

            for (f in 0 until sceneFrames) {
                if (RenderState.cancelRequested) break

                val progress = (f + 1).toDouble() / sceneFrames
                WhiteboardCanvas.drawFrame(canvas, width, vHeight, scene, progress)

                bitmapToNv12(bitmap, argb, nv12, width, vHeight)

                var fed = false
                while (!fed) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf: ByteBuffer = encoder.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        inBuf.put(nv12)
                        val pts = frameIndex * 1_000_000L / FRAME_RATE
                        encoder.queueInputBuffer(inIndex, 0, nv12.size, pts, 0)
                        fed = true
                    } else {
                        drainEncoder(encoder, muxer, bufferInfo, false) { fmt ->
                            if (!muxerStarted) {
                                videoTrack = muxer.addTrack(fmt)
                                audioFormat?.let { audioTrack = muxer.addTrack(it) }
                                muxer.start()
                                muxerStarted = true
                            }
                            videoTrack
                        }
                    }
                }

                drainEncoder(encoder, muxer, bufferInfo, false) { fmt ->
                    if (!muxerStarted) {
                        videoTrack = muxer.addTrack(fmt)
                        audioFormat?.let { audioTrack = muxer.addTrack(it) }
                        muxer.start()
                        muxerStarted = true
                    }
                    videoTrack
                }

                frameIndex++

                if (frameIndex % 12 == 0) {
                    val pct = frameIndex * 95 / totalFrames
                    RenderState.progress = pct
                    RenderState.status = "Scene " + (sceneIndex + 1) + " of " +
                        scenes.size + "  (" + pct + "%)"
                    updateNotification(RenderState.status, pct)
                }

                if (lowHeat && frameIndex % 48 == 0) delay(120)
            }

            sceneIndex++
            if (lowHeat) delay(800)
        }

        val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US * 5)
        if (inIndex >= 0) {
            encoder.queueInputBuffer(
                inIndex, 0, 0,
                frameIndex * 1_000_000L / FRAME_RATE,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
        drainEncoder(encoder, muxer, bufferInfo, true) { fmt ->
            if (!muxerStarted) {
                videoTrack = muxer.addTrack(fmt)
                audioFormat?.let { audioTrack = muxer.addTrack(it) }
                muxer.start()
                muxerStarted = true
            }
            videoTrack
        }

        encoder.stop()
        encoder.release()

        if (audioExtractor != null && audioTrack >= 0 && muxerStarted) {
            RenderState.status = "Adding audio..."
            updateNotification(RenderState.status, 96)
            val videoDurationUs = frameIndex * 1_000_000L / FRAME_RATE
            val audioBuf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = audioExtractor.readSampleData(audioBuf, 0)
                if (sampleSize < 0) break
                val time = audioExtractor.sampleTime
                if (time > videoDurationUs) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = time
                info.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(audioTrack, audioBuf, info)
                audioExtractor.advance()
            }
            audioExtractor.release()
        }

        if (muxerStarted) {
            muxer.stop()
        }
        muxer.release()
        bitmap.recycle()

        RenderState.status = "Saving to gallery..."
        updateNotification(RenderState.status, 98)
        val saved = copyToGallery(tempFile)

        RenderState.outputPath = saved
        RenderState.progress = 100
        RenderState.status = if (RenderState.cancelRequested)
            "Cancelled (partial file saved)"
        else
            "Done. Saved in Movies/Whiteboard"
        updateNotification(RenderState.status, 100)
    }

    private inline fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        onFormat: (MediaFormat) -> Int
    ) {
        var track = -1
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = onFormat(encoder.outputFormat)
            } else if (outIndex >= 0) {
                if (track < 0) track = onFormat(encoder.outputFormat)
                val outBuf = encoder.getOutputBuffer(outIndex)
                if (outBuf != null && info.size > 0 &&
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, outBuf, info)
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    private fun bitmapToNv12(
        bitmap: Bitmap, argb: IntArray, out: ByteArray, w: Int, h: Int
    ) {
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        var yIndex = 0
        var uvIndex = w * h
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvIndex++] = u.coerceIn(0, 255).toByte()
                    out[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun sizeFor(height: Int, aspect: String): Pair<Int, Int> {
        return when (aspect) {
            "9:16" -> when (height) {
                480 -> Pair(480, 848)
                1080 -> Pair(1080, 1920)
                else -> Pair(720, 1280)
            }
            "1:1" -> Pair(height, height)
            else -> when (height) {
                480 -> Pair(848, 480)
                1080 -> Pair(1920, 1080)
                else -> Pair(1280, 720)
            }
        }
    }

    private fun copyToGallery(source: File): String {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Whiteboard")
                }
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
                source.delete()
                uri.toString()
            } else {
                source.absolutePath
            }
        } catch (e: Exception) {
            source.absolutePath
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Video Rendering", NotificationManager.IMPORTANCE_LOW
            )
            notifManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whiteboard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        notifManager?.notify(NOTIF_ID, buildNotification(text, progress))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whiteboard:render")
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}


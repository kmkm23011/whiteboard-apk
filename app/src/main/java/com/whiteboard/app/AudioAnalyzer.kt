package com.whiteboard.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

object AudioAnalyzer {

    private const val WINDOW_MS = 50
    private const val MIN_GAP_MS = 320
    private const val MIN_SCENE_MS = 900

    fun sceneDurations(
        context: Context,
        uri: Uri,
        scenes: List<String>
    ): List<Double> {
        val n = scenes.size
        if (n == 0) return emptyList()

        val totalMs = totalDurationMs(context, uri)
        if (totalMs <= 0L) {
            return scenes.map { 5.0 }
        }

        val gaps = try {
            findSilenceGaps(context, uri, totalMs)
        } catch (e: Exception) {
            emptyList()
        }

        if (n == 1) return listOf(totalMs / 1000.0)

        val cuts = pickCuts(gaps, n - 1, totalMs)

        if (cuts.size != n - 1) {
            return splitByTextWeight(scenes, totalMs)
        }

        val bounds = ArrayList<Long>()
        bounds.add(0L)
        bounds.addAll(cuts)
        bounds.add(totalMs)

        val result = ArrayList<Double>()
        for (i in 0 until n) {
            val len = bounds[i + 1] - bounds[i]
            result.add(max(len, MIN_SCENE_MS.toLong()) / 1000.0)
        }
        return result
    }

    private fun totalDurationMs(context: Context, uri: Uri): Long {
        var extractor: MediaExtractor? = null
        return try {
            val ex = MediaExtractor()
            ex.setDataSource(context, uri, null)
            extractor = ex
            var durationUs = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/") && f.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = f.getLong(MediaFormat.KEY_DURATION)
                    break
                }
            }
            durationUs / 1000L
        } catch (e: Exception) {
            0L
        } finally {
            try { extractor?.release() } catch (e: Exception) { }
        }
    }

    private fun findSilenceGaps(
        context: Context,
        uri: Uri,
        totalMs: Long
    ): List<Gap> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }

        if (trackIndex < 0 || format == null) {
            extractor.release()
            return emptyList()
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val levels = ArrayList<Double>()
        val samplesPerWindow = max(1, sampleRate * WINDOW_MS / 1000) * channels

        var sumSquares = 0.0
        var countInWindow = 0
        var peak = 1.0

        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(8000)
                    if (inIndex >= 0) {
                        val inBuf: ByteBuffer? = decoder.getInputBuffer(inIndex)
                        val size = if (inBuf != null)
                            extractor.readSampleData(inBuf, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex, 0, size, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 8000)
                if (outIndex >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = outBuf.asShortBuffer()
                        while (shorts.hasRemaining()) {
                            val v = shorts.get().toDouble()
                            sumSquares += v * v
                            countInWindow++
                            if (countInWindow >= samplesPerWindow) {
                                val rms = Math.sqrt(sumSquares / countInWindow)
                                levels.add(rms)
                                if (rms > peak) peak = rms
                                sumSquares = 0.0
                                countInWindow = 0
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true
                    }
                }
            }
        } catch (e: Exception) {
            // partial analysis is acceptable
        } finally {
            try { decoder.stop() } catch (e: Exception) { }
            try { decoder.release() } catch (e: Exception) { }
            try { extractor.release() } catch (e: Exception) { }
        }

        if (countInWindow > 0) {
            levels.add(Math.sqrt(sumSquares / countInWindow))
        }

        if (levels.size < 4) return emptyList()

        val threshold = peak * 0.10
        val gaps = ArrayList<Gap>()
        var runStart = -1

        for (i in levels.indices) {
            val quiet = levels[i] < threshold
            if (quiet) {
                if (runStart < 0) runStart = i
            } else {
                if (runStart >= 0) {
                    addGap(gaps, runStart, i, levels.size, totalMs)
                    runStart = -1
                }
            }
        }
        if (runStart >= 0) {
            addGap(gaps, runStart, levels.size, levels.size, totalMs)
        }

        return gaps
    }

    private fun addGap(
        out: ArrayList<Gap>,
        startIdx: Int,
        endIdx: Int,
        totalWindows: Int,
        totalMs: Long
    ) {
        val msPerWindow = totalMs.toDouble() / totalWindows
        val startMs = (startIdx * msPerWindow).toLong()
        val endMs = (endIdx * msPerWindow).toLong()
        val lengthMs = endMs - startMs
        if (lengthMs < MIN_GAP_MS) return
        if (startMs < MIN_SCENE_MS) return
        if (endMs > totalMs - MIN_SCENE_MS / 2) return
        out.add(Gap(startMs, endMs, lengthMs))
    }

    private fun pickCuts(gaps: List<Gap>, needed: Int, totalMs: Long): List<Long> {
        if (needed <= 0) return emptyList()
        if (gaps.isEmpty()) return emptyList()

        val ideal = ArrayList<Long>()
        for (k in 1..needed) {
            ideal.add(totalMs * k / (needed + 1))
        }

        val used = HashSet<Int>()
        val chosen = ArrayList<Long>()

        for (target in ideal) {
            var bestIdx = -1
            var bestScore = Double.MAX_VALUE
            for (i in gaps.indices) {
                if (used.contains(i)) continue
                val center = (gaps[i].startMs + gaps[i].endMs) / 2
                val distance = abs(center - target).toDouble()
                val score = distance - gaps[i].lengthMs * 0.4
                if (score < bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }
            if (bestIdx < 0) return emptyList()
            used.add(bestIdx)
            val g = gaps[bestIdx]
            chosen.add((g.startMs + g.endMs) / 2)
        }

        chosen.sort()
        for (i in 1 until chosen.size) {
            if (chosen[i] - chosen[i - 1] < MIN_SCENE_MS) return emptyList()
        }
        return chosen
    }

    private fun splitByTextWeight(scenes: List<String>, totalMs: Long): List<Double> {
        val weights = scenes.map { max(1, it.trim().length) }
        val sum = weights.sum().toDouble()
        return weights.map { (totalMs * it / sum) / 1000.0 }
    }

    private data class Gap(
        val startMs: Long,
        val endMs: Long,
        val lengthMs: Long
    )
}

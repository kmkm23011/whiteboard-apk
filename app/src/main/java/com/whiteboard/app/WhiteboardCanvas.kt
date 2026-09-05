package com.whiteboard.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

object WhiteboardCanvas {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        isSubpixelText = true
    }

    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8B98A")
        style = Paint.Style.FILL
    }

    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C3E50")
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDEDED")
        strokeWidth = 2f
    }

    fun drawFrame(
        canvas: Canvas,
        w: Int,
        h: Int,
        text: String,
        progress: Double
    ) {
        canvas.drawColor(Color.WHITE)

        val margin = w * 0.09f
        canvas.drawLine(margin * 0.5f, 0f, margin * 0.5f, h.toFloat(), linePaint)

        var fontSize = h / 11f
        var lines = wrap(text, fontSize, w - margin * 2f)
        var guard = 0
        while (lines.size * fontSize * 1.55f > h * 0.78f && fontSize > 14f && guard < 40) {
            fontSize *= 0.9f
            lines = wrap(text, fontSize, w - margin * 2f)
            guard++
        }

        textPaint.textSize = fontSize
        val lineHeight = fontSize * 1.55f
        val widths = lines.map { textPaint.measureText(it) }
        val totalWidth = widths.sum().coerceAtLeast(1f)

        val blockHeight = lines.size * lineHeight
        val firstBaseline = (h - blockHeight) / 2f + fontSize

        var remaining = (totalWidth * progress.coerceIn(0.0, 1.0)).toFloat()
        var handX = -1f
        var handY = -1f

        for (i in lines.indices) {
            val line = lines[i]
            val lineWidth = widths[i]
            val startX = (w - lineWidth) / 2f
            val baseline = firstBaseline + i * lineHeight

            if (remaining <= 0f) break

            val shown = if (remaining >= lineWidth) lineWidth else remaining

            canvas.save()
            canvas.clipRect(
                startX,
                baseline - fontSize * 1.3f,
                startX + shown,
                baseline + fontSize * 0.5f
            )
            canvas.drawText(line, startX, baseline, textPaint)
            canvas.restore()

            if (remaining < lineWidth) {
                handX = startX + shown
                handY = baseline
            }
            remaining -= lineWidth
        }

        if (handX > 0f && progress < 0.999) {
            drawHand(canvas, handX, handY, fontSize)
        }
    }

    private fun drawHand(canvas: Canvas, x: Float, y: Float, fontSize: Float) {
        val s = fontSize * 0.9f

        val pen = Path()
        pen.moveTo(x, y + s * 0.1f)
        pen.lineTo(x + s * 0.18f, y + s * 0.45f)
        pen.lineTo(x + s * 0.34f, y + s * 0.32f)
        pen.close()
        canvas.drawPath(pen, penPaint)

        val body = Path()
        body.moveTo(x + s * 0.18f, y + s * 0.45f)
        body.lineTo(x + s * 0.34f, y + s * 0.32f)
        body.lineTo(x + s * 1.25f, y + s * 1.35f)
        body.lineTo(x + s * 1.05f, y + s * 1.55f)
        body.close()
        canvas.drawPath(body, penPaint)

        val fist = Path()
        fist.addCircle(x + s * 1.45f, y + s * 1.75f, s * 0.52f, Path.Direction.CW)
        canvas.drawPath(fist, handPaint)

        val arm = Path()
        arm.moveTo(x + s * 1.15f, y + s * 2.05f)
        arm.lineTo(x + s * 1.95f, y + s * 1.45f)
        arm.lineTo(x + s * 3.6f, y + s * 3.4f)
        arm.lineTo(x + s * 2.6f, y + s * 4.2f)
        arm.close()
        canvas.drawPath(arm, handPaint)
    }

    private fun wrap(text: String, fontSize: Float, maxWidth: Float): List<String> {
        textPaint.textSize = fontSize
        val words = text.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(text)

        val result = ArrayList<String>()
        var current = StringBuilder()

        for (word in words) {
            val candidate = if (current.isEmpty()) word else current.toString() + " " + word
            if (textPaint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) result.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return if (result.isEmpty()) listOf(text) else result
    }
}

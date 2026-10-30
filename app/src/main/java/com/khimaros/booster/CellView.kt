package com.khimaros.booster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

// one grid cell: icon, optional label, optional folder badge and drop highlight, all drawn directly
@SuppressLint("ViewConstructor")
class CellView(ctx: Context, private val scale: Scale, palette: Palette) : View(ctx) {
    var slot: Slot? = null
    var item: Item? = null
    var iconKey: String? = null

    var icon: Drawable? = null
        set(value) {
            field = value
            invalidate()
        }
    var badge: Drawable? = null
        set(value) {
            field = value
            invalidate()
        }
    var label: String = ""
        set(value) {
            field = value
            contentDescription = value.ifEmpty { null }
            invalidate()
        }
    var showLabel = true
    var highlighted = false
        set(value) {
            if (field != value) invalidate()
            field = value
        }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.fg
        textSize = scale.text
        textAlign = Paint.Align.CENTER
        setShadowLayer(scale.p(0), 0f, scale.stroke, Color.BLACK)
    }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.ring }
    private val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.accent
        style = Paint.Style.STROKE
        strokeWidth = scale.stroke
    }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceHi }
    private val badgeOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = scale.stroke
    }
    private val rect = RectF()

    fun clear() {
        item = null
        iconKey = null
        icon = null
        badge = null
        label = ""
        highlighted = false
        isClickable = false
        isLongClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        val size = scale.icon
        val labelled = showLabel && label.isNotEmpty()
        val top = (height - size - if (labelled) scale.labelGap + scale.line else 0) / 2
        val left = (width - size) / 2
        if (highlighted) {
            // never wider than the space around the icon, so the cell bounds do not clip it
            val pad = minOf(scale.tight, top, left).toFloat()
            rect.set(left - pad, top - pad, left + size + pad, top + size + pad)
            canvas.drawRoundRect(rect, scale.p(3), scale.p(3), ringFill)
            canvas.drawRoundRect(rect, scale.p(3), scale.p(3), ringStroke)
        }
        icon?.run {
            setBounds(left, top, left + size, top + size)
            draw(canvas)
        }
        badge?.let { drawBadge(canvas, it, left + size + scale.p(0), top + size + scale.p(0)) }
        if (labelled) {
            val text = TextUtils.ellipsize(label, textPaint, width - 2 * scale.p(0), TextUtils.TruncateAt.END)
            val lineTop = top + size + scale.labelGap
            val baseline = lineTop + scale.line / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, 0, text.length, width / 2f, baseline, textPaint)
        }
    }

    // a small round chip anchored at the icon's bottom right corner
    private fun drawBadge(canvas: Canvas, badge: Drawable, right: Float, bottom: Float) {
        val radius = scale.p(3) / 2
        val cx = right - radius
        val cy = bottom - radius
        canvas.drawCircle(cx, cy, radius, badgeFill)
        canvas.drawCircle(cx, cy, radius, badgeOutline)
        val inner = (radius - scale.stroke).toInt()
        badge.setBounds((cx - inner).toInt(), (cy - inner).toInt(), (cx + inner).toInt(), (cy + inner).toInt())
        badge.draw(canvas)
    }
}

// page indicator: a thin line whose bright segment follows the pager's scroll. it shows only while
// switching pages, so it needs no space of its own.
@SuppressLint("ViewConstructor")
class PageLine(ctx: Context, private val scale: Scale, palette: Palette) : View(ctx) {
    var count = 1
        set(value) {
            field = value
            invalidate()
        }
    // the pager's scroll, in pages
    var position = 0f
        set(value) {
            field = value
            invalidate()
        }
    // while this holds (a finger is paging), the line stays up
    var held: () -> Boolean = { false }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.dim; alpha = OFF_ALPHA }
    private val hide: Runnable = object : Runnable {
        override fun run() {
            if (held()) postDelayed(this, HOLD_MS) else animate().alpha(0f).withEndAction { visibility = INVISIBLE }
        }
    }

    init {
        id = R.id.page_line
        visibility = INVISIBLE
    }

    // shows the line now and fades it out once paging has been still for a moment
    fun flash() {
        if (count < 2) return
        removeCallbacks(hide)
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        postDelayed(hide, HOLD_MS)
    }

    override fun onDraw(canvas: Canvas) {
        if (count < 2) return
        val track = minOf(width, COLUMNS * scale.cell).toFloat()
        val left = (width - track) / 2
        val h = height.toFloat()
        val segment = track / count
        val start = left + position.coerceIn(0f, count - 1f) * segment
        canvas.drawRoundRect(left, 0f, left + track, h, h / 2, h / 2, off)
        canvas.drawRoundRect(start, 0f, start + segment, h, h / 2, h / 2, on)
    }

    private companion object {
        const val OFF_ALPHA = 0x60
        const val HOLD_MS = 600L
    }
}

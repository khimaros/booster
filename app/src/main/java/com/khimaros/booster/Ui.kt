package com.khimaros.booster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val SNAP_MS = 250

fun rounded(color: Int, radius: Float, radii: FloatArray? = null) = GradientDrawable().apply {
    setColor(color)
    if (radii != null) cornerRadii = radii else cornerRadius = radius
}

fun topRounded(color: Int, radius: Float) =
    rounded(color, radius, floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f))

fun Context.label(scale: Scale, color: Int, text: String = "", size: Float = scale.text): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    setTextColor(color)
    includeFontPadding = false
    gravity = Gravity.CENTER_VERTICAL
    isSingleLine = true
    this.text = text
}

// a pill shaped chip, phi^4 tall
fun Context.chip(scale: Scale, palette: Palette, text: String, selected: Boolean = false): TextView =
    label(scale, if (selected) palette.onAccent else palette.fg, text).apply {
        gravity = Gravity.CENTER
        setPadding(scale.gutter, 0, scale.gutter, 0)
        background = rounded(if (selected) palette.accent else palette.surfaceHi, scale.chip / 2f)
        minHeight = scale.chip
        height = scale.chip
    }

// cells of a fixed square size in rows of `cols`, filled left to right like any grid
@SuppressLint("ViewConstructor")
class CellGrid(ctx: Context, private val scale: Scale, var cols: Int) : ViewGroup(ctx) {
    private val cell get() = scale.cell

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
        setMeasuredDimension(cols * cell, scale.rowsHeight(ceil(childCount / cols.toFloat()).toInt()))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val x = (i % cols) * cell
            val y = (i / cols) * (cell + scale.rowGap)
            getChildAt(i).layout(x, y, x + cell, y + cell)
        }
    }

    override fun shouldDelayChildPressedState() = false

    companion object {
        // the most square grid that holds n cells, capped at the home grid width
        fun columnsFor(n: Int) = ceil(Math.sqrt(n.toDouble())).toInt().coerceIn(1, COLUMNS)
    }
}

// horizontal pages with flick snapping. long-pressing empty space reports onEmptyLongPress.
@SuppressLint("ViewConstructor")
class Pager(ctx: Context) : ViewGroup(ctx) {
    var page = 0
        private set
    // the scroll position in pages, on every change
    var onScrolled: (Float) -> Unit = {}
    var onEmptyLongPress: () -> Unit = {}

    private val scroller = OverScroller(ctx)
    private val config = ViewConfiguration.get(ctx)
    private val slop = config.scaledTouchSlop
    private val minFling = config.scaledMinimumFlingVelocity
    private val handler = Handler(Looper.getMainLooper())
    private val longPress = Runnable { onEmptyLongPress() }
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    var dragging = false
        private set

    // long presses on cells should fire on time; horizontal paging is detected separately
    override fun shouldDelayChildPressedState() = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val x = i * w + (w - child.measuredWidth) / 2
            child.layout(x, 0, x + child.measuredWidth, child.measuredHeight)
        }
        if (!scroller.isFinished) return
        page = page.coerceIn(0, maxOf(0, childCount - 1))
        scrollTo(page * w, 0)
    }

    fun snapTo(target: Int, animate: Boolean = true) {
        val next = target.coerceIn(0, maxOf(0, childCount - 1))
        val dx = next * width - scrollX
        if (animate) scroller.startScroll(scrollX, 0, dx, 0, SNAP_MS) else scrollTo(next * width, 0)
        page = next
        invalidate()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (width > 0) onScrolled(l / width.toFloat())
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(ev)
            MotionEvent.ACTION_MOVE -> if (!dragging && abs(ev.x - downX) > slop && abs(ev.x - downX) > abs(ev.y - downY)) {
                dragging = true
                lastX = ev.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish(ev.actionMasked == MotionEvent.ACTION_UP)
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start(ev)
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) handler.removeCallbacks(longPress)
                if (!dragging && abs(ev.x - downX) > slop && abs(ev.x - downX) > abs(ev.y - downY)) {
                    dragging = true
                    lastX = ev.x
                }
                if (dragging) {
                    val max = maxOf(0, (childCount - 1) * width)
                    scrollTo((scrollX + (lastX - ev.x)).roundToInt().coerceIn(0, max), 0)
                    lastX = ev.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish(ev.actionMasked == MotionEvent.ACTION_UP)
        }
        return true
    }

    private fun start(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        lastX = ev.x
        dragging = false
        if (!scroller.isFinished) scroller.abortAnimation()
    }

    private fun track(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) velocity?.clear()
        velocity = (velocity ?: VelocityTracker.obtain()).also { it.addMovement(ev) }
    }

    private fun finish(up: Boolean) {
        handler.removeCallbacks(longPress)
        if (dragging && up) {
            val v = velocity?.run {
                computeCurrentVelocity(1000)
                xVelocity
            } ?: 0f
            val target = when {
                v < -minFling * 2 -> page + 1
                v > minFling * 2 -> page - 1
                else -> (scrollX + width / 2) / maxOf(1, width)
            }
            snapTo(target)
        } else if (dragging) {
            snapTo(page)
        }
        dragging = false
        velocity?.recycle()
        velocity = null
    }
}

fun View.hideKeyboard() {
    context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        ?.hideSoftInputFromWindow(windowToken, 0)
}

fun View.showKeyboard() {
    requestFocus()
    context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        ?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
}

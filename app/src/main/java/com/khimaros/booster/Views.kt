package com.khimaros.booster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

private const val EDGE_FLIP_MS = 600L
private const val SETTLE_MS = 280L
private const val MIN_SETTLE_MS = 120L
private const val MAX_SETTLE_MS = 450L

// a decelerating settle starts at twice its average speed, so this duration makes it begin at the
// finger's release speed and coast to a stop. without a fling toward the target it uses a calm default.
fun settleMs(remainingPx: Float, speedTowardTarget: Float, minFling: Float): Long =
    if (speedTowardTarget <= minFling) SETTLE_MS
    else (2000f * remainingPx / speedTowardTarget).toLong().coerceIn(MIN_SETTLE_MS, MAX_SETTLE_MS)

// home screen: paged grid, page line, dock, and the drop targets shown while dragging
@SuppressLint("ViewConstructor")
class HomeView(ctx: Context, private val scale: Scale, private val palette: Palette) : FrameLayout(ctx) {
    val pager = Pager(ctx).apply { id = R.id.pager }
    val dock = LinearLayout(ctx).apply {
        id = R.id.dock
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val zones = LinearLayout(ctx).apply {
        id = R.id.zones
        orientation = LinearLayout.HORIZONTAL
        visibility = GONE
    }
    // drop targets are row height (phi^5), easier to hit than a chip
    private fun target(text: Int): TextView = context.chip(scale, palette, context.getString(text)).apply {
        height = scale.row
        minHeight = scale.row
        background = rounded(palette.surfaceHi, scale.row / 2f)
    }
    val zoneRemove = target(R.string.zone_remove)
    val zoneEdit = target(R.string.zone_edit)
    val zoneInfo = target(R.string.zone_info)
    val hint = ctx.label(scale, palette.accent).apply {
        gravity = Gravity.CENTER
        visibility = GONE
    }
    private val line = PageLine(ctx, scale, palette).apply { held = { pager.dragging } }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private var flipDir = 0
    private val flip = Runnable {
        pager.snapTo(pager.page + flipDir)
        flipDir = 0
    }
    private var downX = 0f
    private var downY = 0f
    private var drawerDrag = false
    private var velocity: VelocityTracker? = null

    // an upward swipe anywhere on home drags the drawer: distance in px while moving, then release velocity
    var onDrawerDrag: (Float) -> Unit = {}
    var onDrawerRelease: (Float, Float) -> Unit = { _, _ -> }

    init {
        id = R.id.home
        listOf(zoneRemove, zoneEdit, zoneInfo).forEachIndexed { i, chip ->
            zones.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, scale.row))
            if (i < 2) zones.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        }
        addView(pager)
        addView(line)
        addView(zones)
        addView(hint)
        addView(dock)
        pager.onScrolled = {
            line.position = it
            line.flash()
        }
        setInsets(0, 0)
    }

    fun setInsets(top: Int, bottom: Int) {
        val g = scale.gutter
        val aboveDock = bottom + scale.dockHeight + 2 * g
        pager.layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            topMargin = top + g
            bottomMargin = aboveDock
        }
        // the line sits centered in the gutter between the grid and the dock
        line.layoutParams = LayoutParams(MATCH_PARENT, scale.px(0), Gravity.BOTTOM).apply {
            bottomMargin = aboveDock - g + (g - scale.px(0)) / 2
        }
        zones.layoutParams = LayoutParams(MATCH_PARENT, scale.row, Gravity.BOTTOM).apply {
            bottomMargin = aboveDock
            leftMargin = g
            rightMargin = g
        }
        hint.layoutParams = LayoutParams(MATCH_PARENT, scale.line, Gravity.BOTTOM).apply { bottomMargin = aboveDock + scale.row + scale.gap }
        dock.layoutParams = LayoutParams(MATCH_PARENT, scale.dockHeight, Gravity.BOTTOM).apply {
            bottomMargin = bottom + g
            leftMargin = g
            rightMargin = g
        }
    }

    fun setPageCount(count: Int) {
        line.count = count
    }

    fun showDragUi(dragging: Boolean) {
        zones.visibility = if (dragging) VISIBLE else GONE
        if (!dragging) hint.visibility = GONE
    }

    fun showHint(text: String?) {
        hint.text = text ?: ""
        hint.visibility = if (text == null) GONE else VISIBLE
    }

    // a child (a cell or the pager) usually owns the touch, so swipes are recognized while intercepting
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return recognize(ev)
    }

    // touches on bare home space (around the dock) come straight here instead
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        if (!drawerDrag) {
            recognize(ev)
            return true
        }
        val distance = maxOf(0f, downY - ev.y)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onDrawerDrag(distance)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val vy = velocity?.run {
                    computeCurrentVelocity(1000)
                    yVelocity
                } ?: 0f
                drawerDrag = false
                onDrawerRelease(distance, vy)
            }
        }
        return true
    }

    private fun recognize(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                drawerDrag = false
            }
            MotionEvent.ACTION_MOVE -> {
                val up = downY - ev.y
                if (!drawerDrag && up > slop * 2 && up > abs(ev.x - downX)) {
                    // distance stays measured from the touch down, so the drawer edge sits under the finger
                    drawerDrag = true
                    onDrawerDrag(0f)
                    onDrawerDrag(up)
                }
            }
        }
        return drawerDrag
    }

    private fun track(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) velocity?.clear()
        velocity = (velocity ?: VelocityTracker.obtain()).also { it.addMovement(ev) }
    }

    // hovering a drag near the left or right edge turns the page
    override fun dispatchDragEvent(ev: DragEvent): Boolean {
        when (ev.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                val edge = scale.gutter * 2
                val dir = when {
                    ev.x < edge -> -1
                    ev.x > width - edge -> 1
                    else -> 0
                }
                if (dir != flipDir) {
                    handler.removeCallbacks(flip)
                    flipDir = dir
                    if (dir != 0) handler.postDelayed(flip, EDGE_FLIP_MS)
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                handler.removeCallbacks(flip)
                flipDir = 0
            }
        }
        return super.dispatchDragEvent(ev)
    }
}

// app drawer: one plain grid, search pinned at the bottom
@SuppressLint("ViewConstructor")
class DrawerView(ctx: Context, private val scale: Scale, palette: Palette) : FrameLayout(ctx) {
    val grid = GridView(ctx).apply {
        id = R.id.drawer_grid
        numColumns = COLUMNS
        columnWidth = scale.cell
        verticalSpacing = scale.rowGap
        stretchMode = GridView.NO_STRETCH
        gravity = Gravity.CENTER_HORIZONTAL
        selector = ColorDrawable(Color.TRANSPARENT)
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }
    val search = EditText(ctx).apply {
        id = R.id.search
        hint = ctx.getString(R.string.search_apps)
        setHintTextColor(palette.dim)
        setTextColor(palette.fg)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, scale.text)
        background = rounded(palette.surfaceHi, scale.row / 2f)
        setPadding(scale.gutter, 0, scale.gutter, 0)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_GO
    }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var closing = false
    private var velocity: VelocityTracker? = null
    private val decelerate = DecelerateInterpolator()
    // unset until the first setInsets
    private var topInset = -1
    private var bottomInset = -1
    private var results: Int? = null

    // solid strips behind the status bar and the search bar hide icons scrolling under them,
    // and swallow taps meant for icons the user cannot see
    private fun band(bandId: Int) = View(context).apply {
        id = bandId
        setBackgroundColor(Color.BLACK)
        isClickable = true
    }
    private val statusBand = band(R.id.status_band)
    private val searchBand = band(R.id.search_band)

    init {
        id = R.id.drawer
        setBackgroundColor(Color.BLACK)
        addView(grid, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(statusBand, LayoutParams(MATCH_PARENT, 0, Gravity.TOP))
        addView(searchBand, LayoutParams(MATCH_PARENT, 0, Gravity.BOTTOM))
        addView(search, LayoutParams(MATCH_PARENT, scale.row, Gravity.BOTTOM))
        isClickable = true
        setInsets(0, 0)
    }

    // insets arrive repeatedly (keyboard, bars); relayout only when they move
    fun setInsets(top: Int, bottom: Int) {
        if (top == topInset && bottom == bottomInset) return
        topInset = top
        bottomInset = bottom
        (search.layoutParams as LayoutParams).apply {
            leftMargin = scale.gutter
            rightMargin = scale.gutter
            bottomMargin = bottom + scale.gutter
        }
        statusBand.layoutParams = statusBand.layoutParams.apply { height = top }
        searchBand.layoutParams = searchBand.layoutParams.apply { height = bottom + scale.row + 2 * scale.gutter }
        search.requestLayout()
        pad()
    }

    // search results read left to right like the grid, but sit right above the search bar
    fun alignResults(count: Int?) {
        results = count
        pad()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isOpen) translationY = h.toFloat()
        post(::pad)
    }

    private fun pad() {
        val top = topInset + scale.gutter
        val bottom = bottomInset + scale.row + 2 * scale.gutter
        val rows = results?.let { (it + COLUMNS - 1) / COLUMNS } ?: 0
        val free = if (results != null && height > 0) height - top - bottom - scale.rowsHeight(rows) else 0
        // gridview only centers cells within their columns, so center the columns with padding
        val side = maxOf(0, (width - COLUMNS * scale.cell) / 2)
        grid.setPadding(side, top + maxOf(0, free), side, bottom)
    }

    fun setBackdrop(wallpaper: Boolean) = setBackgroundColor(if (wallpaper) Color.argb(0xcc, 0, 0, 0) else Color.BLACK)

    // with animation off the drawer just appears and disappears
    var animated = true

    val isOpen get() = visibility == VISIBLE

    fun close() = settle(open = false)

    // places the drawer's top edge `distance` px above the bottom of the screen, under the finger
    fun follow(distance: Float) {
        animate().cancel()
        visibility = VISIBLE
        translationY = if (animated) (height - distance).coerceIn(0f, height.toFloat()) else 0f
    }

    // releasing the opening drag: upward velocity is negative
    fun release(distance: Float, velocityY: Float) =
        settle(open = !animated || passed(distance, -velocityY), velocityY = velocityY)

    // a drag released past 1/phi^2 of the height, or flung onward, completes; otherwise it falls back
    private fun passed(distance: Float, velocity: Float) =
        velocity > minFling || (velocity >= -minFling && distance > height / (PHI * PHI))

    // velocityY is the finger's release speed in px/s (positive is down); it carries into the settle
    // resetting the search rebuilds the grid, so it waits until the drawer is out of sight
    private fun settle(open: Boolean, velocityY: Float = 0f) {
        animate().cancel()
        visibility = VISIBLE
        if (!open) search.hideKeyboard()
        val target = if (open) 0f else height.toFloat()
        val finish = {
            if (!open) {
                visibility = INVISIBLE
                if (search.text.isNotEmpty()) search.setText("")
                search.clearFocus()
                grid.setSelection(0)
            }
        }
        if (!animated) {
            translationY = target
            finish()
            return
        }
        val towardTarget = if (target > translationY) velocityY else -velocityY
        animate().translationY(target)
            .setInterpolator(decelerate)
            .setDuration(settleMs(abs(target - translationY), towardTarget, minFling))
            .withEndAction(finish)
            .start()
    }

    // swiping down while the grid is scrolled to the top drags the drawer closed under the finger
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return recognize(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        if (!closing) {
            recognize(ev)
            return true
        }
        val distance = maxOf(0f, ev.rawY - downY)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (animated) translationY = distance.coerceAtMost(height.toFloat())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val vy = velocity?.run {
                    computeCurrentVelocity(1000)
                    yVelocity
                } ?: 0f
                closing = false
                settle(open = animated && !passed(distance, vy), velocityY = vy)
            }
        }
        return true
    }

    // screen coordinates: the drawer moves under the finger, so its own coordinates would chase themselves
    private fun recognize(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                closing = false
            }
            MotionEvent.ACTION_MOVE -> {
                // one slop, like the grid: this check runs before the grid sees the move, so the drawer
                // claims a downward swipe at the top even when it starts on an app
                val down = ev.rawY - downY
                if (!closing && down > slop && down > abs(ev.rawX - downX) && !grid.canScrollVertically(-1)) {
                    closing = true
                    animate().cancel()
                    if (animated) translationY = down
                    search.hideKeyboard()
                }
            }
        }
        return closing
    }

    private fun track(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) velocity?.clear()
        val onScreen = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
        velocity = (velocity ?: VelocityTracker.obtain()).also { it.addMovement(onScreen) }
        onScreen.recycle()
    }
}

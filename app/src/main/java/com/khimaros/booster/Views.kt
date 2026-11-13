package com.khimaros.booster

import android.animation.ValueAnimator
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
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round

private const val EDGE_FLIP_MS = 600L
private const val SETTLE_MS = 280L
private const val MIN_SETTLE_MS = 120L
private const val MAX_SETTLE_MS = 450L
private const val EDGE_SCROLL_MS = 16L

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

    // called on every drag move, so only a change touches the view
    fun showHint(text: String?) {
        if (hint.text.toString() != text.orEmpty()) hint.text = text.orEmpty()
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
    // folder stacks: the page on show, and during a move the next page sliding over it on a black sheet
    val stackFront = CellGrid(ctx, scale, COLUMNS).apply {
        id = R.id.stack_grid
        visibility = INVISIBLE
    }
    val stackNext = CellGrid(ctx, scale, COLUMNS)
    private val stackPage = FrameLayout(ctx).apply {
        id = R.id.stack_next
        setBackgroundColor(Color.BLACK)
        visibility = INVISIBLE
        addView(stackNext, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
    }
    private val stackLine = PageLine(ctx, scale, palette).apply {
        id = R.id.stack_line
        held = { paging }
    }
    var bindStack: (CellView, StackItem?) -> Unit = { _, _ -> }
    var onStackSlots: () -> Unit = {}
    var stackSlots = 0
        private set
    private var reels: List<List<StackItem>> = emptyList()
    private var stackPosition = 0f
    private var shownPage = -1
    private var paging = false
    private var pagingFrom = 0f
    private var stackSettle: ValueAnimator? = null
    // from the page's place down to the search bar, where the next page waits out of sight
    private val stackTravel get() = (height - topInset - navInset - scale.row - 3 * scale.gutter).toFloat()
    private val lastPage get() = maxOf(0, (reels.maxOfOrNull { it.size } ?: 1) - 1)
    val stacked get() = stackFront.visibility == VISIBLE
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
    // takes the search bar's place during a drag; hovering it reveals home
    val zoneHome = ctx.chip(scale, palette, ctx.getString(R.string.add_home)).apply {
        id = R.id.drawer_zone
        background = rounded(palette.surfaceHi, scale.row / 2f)
        visibility = GONE
    }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var closing = false
    private val marks = DropMarks()
    private var hoverX = 0f
    private var hoverY = 0f
    private var scrollDir = 0
    private var canLand: (Item) -> Boolean = { false }
    private val edgeScroll: Runnable = object : Runnable {
        override fun run() {
            if (scrollDir == 0) return
            grid.scrollListBy(scrollDir * scale.gap)
            mark()
            postDelayed(this, EDGE_SCROLL_MS)
        }
    }
    private var velocity: VelocityTracker? = null
    private val decelerate = DecelerateInterpolator()
    // unset until the first setInsets
    private var topInset = -1
    private var bottomInset = -1
    private var navInset = -1
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
        addView(stackFront, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(stackPage, LayoutParams(MATCH_PARENT, 0, Gravity.TOP))
        addView(statusBand, LayoutParams(MATCH_PARENT, 0, Gravity.TOP))
        addView(searchBand, LayoutParams(MATCH_PARENT, 0, Gravity.BOTTOM))
        addView(stackLine, LayoutParams(MATCH_PARENT, scale.px(0), Gravity.BOTTOM))
        addView(search, LayoutParams(MATCH_PARENT, scale.row, Gravity.BOTTOM))
        addView(zoneHome, LayoutParams(MATCH_PARENT, scale.row, Gravity.BOTTOM))
        isClickable = true
        setInsets(0, 0, 0)
    }

    // insets arrive repeatedly (keyboard, bars); relayout only when they move. `bottom` includes the keyboard
    // and `nav` does not, because stacks must not change shape as the keyboard comes and goes.
    fun setInsets(top: Int, bottom: Int, nav: Int) {
        if (top == topInset && bottom == bottomInset && nav == navInset) return
        topInset = top
        bottomInset = bottom
        navInset = nav
        (stackLine.layoutParams as LayoutParams).bottomMargin = bottom + scale.row + scale.gutter + (scale.gutter - scale.px(0)) / 2
        listOf(stackPage, stackFront).forEach { (it.layoutParams as LayoutParams).topMargin = top + scale.gutter }
        listOf(search, zoneHome).forEach {
            (it.layoutParams as LayoutParams).apply {
                leftMargin = scale.gutter
                rightMargin = scale.gutter
                bottomMargin = bottom + scale.gutter
            }
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
        val space = maxOf(0, stackTravel.toInt())
        if (stackPage.layoutParams.height != space) stackPage.layoutParams = stackPage.layoutParams.apply { height = space }
        val slots = (space + scale.rowGap) / (scale.cell + scale.rowGap) * COLUMNS
        if (slots != stackSlots) {
            stackSlots = slots
            onStackSlots()
        }
    }

    // keeps the page on show, or the last page if the reels got shorter
    fun showStacks(next: List<List<StackItem>>) {
        reels = next
        stackLine.count = lastPage + 1
        stackFront.visibility = VISIBLE
        grid.visibility = INVISIBLE
        shownPage = -1
        moveStacks(stackPosition.coerceIn(0f, lastPage.toFloat()))
    }

    fun hideStacks() {
        stackSettle?.cancel()
        paging = false
        stackFront.visibility = INVISIBLE
        stackPage.visibility = INVISIBLE
        grid.visibility = VISIBLE
    }

    private fun bindPage(cells: CellGrid, page: Int) {
        for (i in 0 until cells.childCount) {
            bindStack(cells.getChildAt(i) as CellView, reels.getOrNull(i)?.getOrNull(page))
        }
    }

    // a position between two pages shows the later one partway over the earlier
    private fun moveStacks(position: Float) {
        val page = floor(position).toInt()
        val rise = position - page
        stackPosition = position
        stackLine.position = position
        if (page != shownPage) {
            shownPage = page
            bindPage(stackFront, page)
            bindPage(stackNext, page + 1)
        }
        stackPage.visibility = if (rise > 0f) VISIBLE else INVISIBLE
        stackPage.translationY = (1 - rise) * stackTravel
    }

    private fun followStacks(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val from = round(pagingFrom)
                val position = (pagingFrom + (downY - ev.rawY) / stackTravel)
                    .coerceIn(maxOf(0f, from - 1), minOf(lastPage.toFloat(), from + 1))
                moveStacks(if (animated) position else round(position))
                stackLine.flash()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val vy = velocity?.run {
                    computeCurrentVelocity(1000)
                    yVelocity
                } ?: 0f
                paging = false
                settleStacks(vy)
            }
        }
    }

    // a page turns or falls back exactly as the drawer opens or closes, one page per swipe
    private fun settleStacks(velocityY: Float) {
        val from = round(pagingFrom)
        val moved = stackPosition - from
        val turns = passed(abs(moved) * stackTravel, if (moved > 0) -velocityY else velocityY, stackTravel)
        val target = if (moved != 0f && turns) from + if (moved > 0) 1 else -1 else from
        stackLine.flash()
        if (!animated) return moveStacks(target)
        stackSettle = ValueAnimator.ofFloat(stackPosition, target).apply {
            val towardTarget = if (target > stackPosition) -velocityY else velocityY
            duration = settleMs(abs(target - stackPosition) * stackTravel, towardTarget, minFling)
            interpolator = decelerate
            addUpdateListener { moveStacks(it.animatedValue as Float) }
            start()
        }
    }

    fun showDragUi(dragging: Boolean) {
        zoneHome.visibility = if (dragging) VISIBLE else GONE
        if (!dragging) leaveDrag()
    }

    // the entry under a drag and how it lands on it. the grid scrolls cells past a single listener, so it
    // finds the nearest cell itself: gaps between rows go beside it, and below the last cell goes last.
    fun dropAt(x: Float, y: Float, canLand: (Item) -> Boolean): Pair<CellView, Drop>? {
        val cells = (0 until grid.childCount).map { grid.getChildAt(it) as CellView }
        val last = cells.lastOrNull() ?: return null
        if (y > last.bottom) return last to Drop.AFTER
        val cell = cells.minBy { hypot(it.left + it.width / 2f - x, it.top + it.height / 2f - y) }
        val onRow = y >= cell.top && y <= cell.bottom
        return cell to cell.landing(x - cell.left) { onRow && canLand(it) }
    }

    // marks where a drag would land and scrolls while it hovers the top or bottom edge of the grid
    fun hover(x: Float, y: Float, canLand: (Item) -> Boolean) {
        hoverX = x
        hoverY = y
        this.canLand = canLand
        mark()
        val dir = when {
            y < grid.paddingTop + scale.gutter -> -1
            y > grid.height - grid.paddingBottom - scale.gutter -> 1
            else -> 0
        }
        if (dir == scrollDir) return
        scrollDir = dir
        removeCallbacks(edgeScroll)
        if (dir != 0) post(edgeScroll)
    }

    private fun mark() {
        val (cell, drop) = dropAt(hoverX, hoverY, canLand) ?: return
        marks.show(cell, drop)
    }

    fun leaveDrag() {
        scrollDir = 0
        removeCallbacks(edgeScroll)
        marks.clear()
    }

    fun setBackdrop(wallpaper: Boolean) = setBackgroundColor(if (wallpaper) Color.argb(0xcc, 0, 0, 0) else Color.BLACK)

    // with animation off the drawer just appears and disappears
    var animated = true

    val isOpen get() = visibility == VISIBLE

    fun close() = settle(open = false)

    // out of sight already, so there is nothing to animate
    fun closeNow() {
        val wasAnimated = animated
        animated = false
        settle(open = false)
        animated = wasAnimated
    }

    // places the drawer's top edge `distance` px above the bottom of the screen, under the finger
    fun follow(distance: Float) {
        animate().cancel()
        visibility = VISIBLE
        translationY = if (animated) (height - distance).coerceIn(0f, height.toFloat()) else 0f
    }

    // releasing the opening drag: upward velocity is negative
    fun release(distance: Float, velocityY: Float) =
        settle(open = !animated || passed(distance, -velocityY), velocityY = velocityY)

    // a drag released past 1/phi^2 of its span, or flung onward, completes; otherwise it falls back
    private fun passed(distance: Float, velocity: Float, span: Float = height.toFloat()) =
        velocity > minFling || (velocity >= -minFling && distance > span / (PHI * PHI))

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
                stackSettle?.cancel()
                moveStacks(0f)
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
        if (paging) {
            followStacks(ev)
            return true
        }
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
                paging = false
            }
            MotionEvent.ACTION_MOVE -> {
                // one slop, like the grid: this check runs before the grid sees the move, so the drawer
                // claims a downward swipe at the top even when it starts on an app
                val down = ev.rawY - downY
                val vertical = abs(down) > slop && abs(down) > abs(ev.rawX - downX)
                val atTop = if (stacked) stackPosition == 0f else !grid.canScrollVertically(-1)
                if (closing || paging || !vertical) return closing || paging
                if (down > 0 && atTop) {
                    closing = true
                    animate().cancel()
                    if (animated) translationY = down
                    search.hideKeyboard()
                } else if (stacked) {
                    paging = true
                    stackSettle?.cancel()
                    pagingFrom = stackPosition
                    downY = ev.rawY
                }
            }
        }
        return closing || paging
    }

    private fun track(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) velocity?.clear()
        val onScreen = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
        velocity = (velocity ?: VelocityTracker.obtain()).also { it.addMovement(onScreen) }
        onScreen.recycle()
    }
}

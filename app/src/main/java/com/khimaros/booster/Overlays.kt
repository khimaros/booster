package com.khimaros.booster

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max

private const val GRAB_ALPHA_COLOR = 0x40ffffff

data class MenuEntry(val label: String, val danger: Boolean = false, val selected: Boolean = false, val action: () -> Unit)

fun Context.selectable(): android.graphics.drawable.Drawable? {
    val value = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
    return getDrawable(value.resourceId)
}

// a stack of modal layers over the home screen: menus, sheets and the open folder card.
// each layer has its own scrim; tapping it or pressing back dismisses the top layer.
class Overlays(private val root: FrameLayout, private val scale: Scale, private val palette: Palette) {
    private val ctx = root.context
    private val stack = ArrayList<View>()
    private var navInset = 0
    private var imeInset = 0

    val isEmpty get() = stack.isEmpty()

    fun isShowing(layer: View) = layer in stack

    fun pop(): Boolean {
        val top = stack.lastOrNull() ?: return false
        dismiss(top)
        return true
    }

    fun dismiss(layer: View) {
        if (!stack.remove(layer)) return
        layer.hideKeyboard()
        root.removeView(layer)
    }

    fun clear() {
        while (pop()) continue
    }

    fun setInsets(nav: Int, ime: Int) {
        navInset = nav
        imeInset = ime
        stack.forEach(::applyInsets)
    }

    private fun applyInsets(layer: View) {
        layer.setPadding(0, 0, 0, max(0, imeInset - navInset))
        layer.findViewById<View>(R.id.sheet)?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, scale.gutter + navInset) }
    }

    private fun push(layer: View) {
        root.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stack.add(layer)
        applyInsets(layer)
    }

    private fun scrim(): FrameLayout = FrameLayout(ctx).apply {
        setBackgroundColor(palette.scrim)
        isClickable = true
        setOnClickListener { dismiss(this) }
    }

    // a list of actions; anchored above (or below) a view, or centered on screen.
    // a list taller than the screen allows scrolls inside the menu. the selected entry is an accent pill.
    fun menu(entries: List<MenuEntry>, anchor: View? = null): View {
        val layer = scrim()
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, scale.tight, 0, scale.tight)
        }
        val panel = ScrollView(ctx).apply {
            id = R.id.menu
            background = rounded(palette.surfaceHi, scale.p(3))
            clipToOutline = true
            elevation = scale.p(1)
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        entries.forEach { entry ->
            val color = when {
                entry.selected -> palette.onAccent
                entry.danger -> palette.danger
                else -> palette.fg
            }
            list.addView(
                ctx.label(scale, color, entry.label).apply {
                    setPadding(scale.gutter - scale.tight, 0, scale.gutter - scale.tight, 0)
                    foreground = ctx.selectable()
                    isSelected = entry.selected
                    if (entry.selected) background = rounded(palette.accent, scale.row / 2f)
                    setOnClickListener {
                        dismiss(layer)
                        entry.action()
                    }
                },
                LinearLayout.LayoutParams(MATCH_PARENT, scale.row).apply {
                    leftMargin = scale.tight
                    rightMargin = scale.tight
                },
            )
        }
        val w = scale.menuWidth
        val h = minOf(entries.size * scale.row + 2 * scale.tight, root.height - 2 * scale.cell - navInset)
        val lp = FrameLayout.LayoutParams(w, h)
        if (anchor == null) {
            lp.gravity = Gravity.CENTER
        } else {
            val a = IntArray(2).also(anchor::getLocationInWindow)
            val r = IntArray(2).also(root::getLocationInWindow)
            val x = a[0] - r[0] + anchor.width / 2 - w / 2
            val above = a[1] - r[1] - h
            val y = if (above >= scale.cell) above else a[1] - r[1] + anchor.height
            lp.leftMargin = x.coerceIn(scale.gutter, max(scale.gutter, root.width - w - scale.gutter))
            lp.topMargin = y.coerceIn(scale.gutter, max(scale.gutter, root.height - h - scale.gutter - navInset))
        }
        layer.addView(panel, lp)
        push(layer)
        return layer
    }

    // a bottom sheet holding `content`; `scrollable` lets a scrolled list keep its own downward swipes
    fun sheet(content: View, scrollable: View? = null): View {
        val layer = scrim()
        val panel = SheetPanel(ctx, scrollable) { dismiss(layer) }.apply {
            id = R.id.sheet
            orientation = LinearLayout.VERTICAL
            background = topRounded(palette.surface, scale.p(4))
            setPadding(scale.gutter, scale.gutter, scale.gutter, scale.gutter)
            isClickable = true
        }
        panel.addView(
            View(ctx).apply { background = rounded(GRAB_ALPHA_COLOR, scale.p(0)) },
            LinearLayout.LayoutParams(scale.chip, scale.px(0)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = scale.gutter
            },
        )
        panel.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        layer.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        push(layer)
        return layer
    }

    // the open folder: a centered card around its grid
    fun card(grid: View): View {
        val layer = scrim()
        val card = FrameLayout(ctx).apply {
            id = R.id.folder_card
            background = rounded(palette.surface, scale.p(4))
            setPadding(scale.gutter, scale.gutter, scale.gutter, scale.gutter)
            elevation = scale.p(2)
            isClickable = true
            addView(grid)
            // the card's padding and the gaps between its cells still count as inside it
            setOnDragListener { _, _ -> true }
        }
        layer.addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        // a drag that leaves the card closes it, revealing home or the drawer beneath
        layer.setOnDragListener { _, ev ->
            if (ev.action == DragEvent.ACTION_DRAG_LOCATION || ev.action == DragEvent.ACTION_DRAG_ENTERED) {
                layer.post { dismiss(layer) }
            }
            true
        }
        push(layer)
        return layer
    }
}

// a sheet panel that follows a downward swipe and dismisses past 1/phi^2 of its height
@SuppressLint("ViewConstructor")
class SheetPanel(ctx: Context, private val scrollable: View?, private val onDismiss: () -> Unit) : LinearLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                val listAtTop = scrollable?.canScrollVertically(-1) != true
                return dy > slop && dy > abs(ev.rawX - downX) && listAtTop
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> translationY = max(0f, ev.rawY - downY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (translationY > height / (PHI * PHI)) onDismiss() else animate().translationY(0f).start()
        }
        return true
    }
}

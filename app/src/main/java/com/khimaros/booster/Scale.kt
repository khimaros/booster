package com.khimaros.booster

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

const val PHI = 1.618034f

// the screen width holds exactly four grid cells (phi^4 bases) and two gutters (phi^1 bases)
private val SCREEN_BASES = 4 * PHI.pow(4) + 2 * PHI

// keeps tablets from inflating every element; the grid is centered instead
private const val MAX_BASE_DP = 16f

// golden ratio size scale. p(n) = base * phi^(n - 2), so p(2) is the base (body text size).
// every size, gap, radius and stroke in the ui comes from this one ladder.
class Scale(widthPx: Int, density: Float) {
    val base = min(widthPx / SCREEN_BASES, MAX_BASE_DP * density)

    fun p(n: Int): Float = base * PHI.pow(n - 2)

    fun px(n: Int): Int = p(n).roundToInt()

    val stroke get() = p(-1)
    val tight get() = px(1)
    val gap get() = px(2)
    val gutter get() = px(3)
    val chip get() = px(4)
    val icon get() = px(5)
    val row get() = px(5)
    val cell get() = px(6)
    val menuWidth get() = px(8)
    val text get() = p(2)
    val line get() = px(3)
    val dockHeight get() = icon + 2 * gutter

    // a label sits phi^0 under its icon; rows sit phi^2 apart. icon + phi^0 + label leaves phi^1 of slack in
    // a phi^6 cell, so a label's distance to the next row's icon is phi^1 + phi^2 = phi^3, the gutter:
    // clearly farther than its own icon.
    val labelGap get() = px(0)
    val rowGap get() = gap

    fun rowsHeight(rows: Int) = rows * cell + maxOf(0, rows - 1) * rowGap
}

package com.khimaros.booster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build

// colors for every surface. android 12+ uses the wallpaper derived system palette;
// older versions get the fixed fallback below. the background itself is always black.
// the system color ids are only resolved when `dynamic` is true.
@SuppressLint("InlinedApi")
class Palette(ctx: Context) {
    private val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val accent = pick(ctx, android.R.color.system_accent1_200, 0xffb4c5ff.toInt())
    val onAccent = pick(ctx, android.R.color.system_accent1_800, 0xff1a2d60.toInt())
    val surface = pick(ctx, android.R.color.system_neutral1_900, 0xff1d1f26.toInt())
    val surfaceHi = pick(ctx, android.R.color.system_neutral1_800, 0xff2a2d36.toInt())
    val ring = Color.argb(RING_ALPHA, Color.red(accent), Color.green(accent), Color.blue(accent))
    val fg = 0xfff2f2f2.toInt()
    val dim = 0x99ffffff.toInt()
    val field = 0x14ffffff
    val scrim = 0x99000000.toInt()
    val danger = 0xffff8a80.toInt()
    val isDynamic get() = dynamic

    private fun pick(ctx: Context, systemColor: Int, fallback: Int) =
        if (dynamic) ctx.getColor(systemColor) else fallback

    companion object {
        private const val RING_ALPHA = 0x33
    }
}

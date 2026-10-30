package com.khimaros.booster

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

// confirms an app's "add to home" request (a browser pinning a page) as a bottom sheet over that app.
// a foreground service may ask too, so nothing is pinned without the user's yes. accepting tells the
// system, whose shortcut callback then places it in the launcher.
class PinActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcher = getSystemService(LauncherApps::class.java)
        val request = launcher.getPinItemRequest(intent)
        val shortcut = request?.shortcutInfo
        if (request == null || shortcut == null || !request.isValid) return finish()

        val metrics = resources.displayMetrics
        val scale = Scale(min(metrics.widthPixels, metrics.heightPixels), metrics.density)
        val palette = Palette(this)
        val root = FrameLayout(this)
        setContentView(root)
        val overlays = Overlays(root, scale, palette)

        fun button(text: Int, color: Int, action: () -> Unit): TextView = label(scale, color, getString(text)).apply {
            foreground = selectable()
            gravity = Gravity.CENTER
            setOnClickListener {
                action()
                finish()
            }
        }
        val icon = ImageView(this).apply { setImageDrawable(launcher.getShortcutIconDrawable(shortcut, metrics.densityDpi)) }
        val name = label(scale, palette.fg, (shortcut.shortLabel ?: shortcut.longLabel ?: "").toString()).apply { id = R.id.pin_name }
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(scale.icon, scale.icon))
            addView(name, LinearLayout.LayoutParams(0, scale.row, 1f).apply { leftMargin = scale.gutter })
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(R.string.cancel, palette.dim) {}, LinearLayout.LayoutParams(0, scale.row, 1f))
            addView(button(R.string.add_home, palette.accent) { request.accept() }, LinearLayout.LayoutParams(0, scale.row, 1f))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(preview)
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, scale.row).apply { topMargin = scale.gutter })
        }
        // the sheet needs a measured root to sit in; closing it any way declines the request
        root.post {
            overlays.sheet(content).addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) = Unit
                override fun onViewDetachedFromWindow(v: android.view.View) = finish()
            })
        }
    }

    @Deprecated("platform back handling; this launcher has no androidx dispatcher")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = finish()
}

package com.khimaros.booster

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.util.concurrent.Executors

private const val MAX_CACHED = 512
private const val WORKERS = 2
private const val TAG = "booster"

// resolves icons off the main thread: user override, then the global pack's match, then the app's own icon.
class Icons(private val ctx: Context) {
    private val cache = LruCache<String, Drawable.ConstantState>(MAX_CACHED)
    private val packs = HashMap<String, IconPack?>()
    private val executor = Executors.newFixedThreadPool(WORKERS)
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.densityDpi
    private val launcher = ctx.getSystemService(LauncherApps::class.java)

    @Volatile
    var globalPack: String? = null
        set(value) {
            if (field != value) cache.evictAll()
            field = value
        }

    @Synchronized
    fun pack(pkg: String): IconPack? = packs.getOrPut(pkg) {
        try {
            IconPack(ctx, pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "icon pack $pkg is gone", e)
            null
        }
    }

    fun invalidate() = cache.evictAll()

    @Synchronized
    fun forgetPack(pkg: String) {
        packs.remove(pkg)
        cache.evictAll()
    }

    private fun global(): IconPack? = globalPack?.let(::pack)

    fun base(app: AppInfo): Drawable =
        global()?.let { p -> p.match(app.key)?.let(p::drawable) }
            ?: app.info?.getIcon(density)
            ?: app.shortcut?.let { launcher.getShortcutIconDrawable(it, density) }
            ?: ctx.getDrawable(R.drawable.ic_launcher)!!

    fun ref(ref: IconRef, apps: Map<String, AppInfo>): Drawable? =
        if (ref.pack != null) pack(ref.pack)?.drawable(ref.name) else apps[ref.name]?.let(::base)

    fun app(app: AppInfo, override: IconRef?, apps: Map<String, AppInfo>): Drawable =
        override?.let { ref(it, apps) } ?: base(app)

    fun folder(icon: IconRef?, apps: Map<String, AppInfo>): Drawable = icon?.let { ref(it, apps) } ?: folderDefault()

    fun folderDefault(): Drawable = global()?.folder() ?: ctx.getDrawable(R.drawable.ic_folder)!!

    // the cache key must identify everything the resolver depends on
    fun load(cacheKey: String, resolve: () -> Drawable?, deliver: (Drawable?) -> Unit) {
        val key = "$globalPack|$cacheKey"
        cache.get(key)?.let {
            deliver(it.newDrawable())
            return
        }
        executor.execute {
            val drawable = try {
                resolve()
            } catch (e: Exception) {
                Log.w(TAG, "icon $cacheKey failed", e)
                null
            }
            val state = drawable?.constantState
            if (state != null) cache.put(key, state)
            main.post { deliver(state?.newDrawable() ?: drawable) }
        }
    }
}

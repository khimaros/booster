package com.khimaros.booster

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.Calendar

// intent actions icon packs (adw, nova, go, and arcticons) register so launchers can find them
private val PACK_ACTIONS = listOf(
    "org.adw.launcher.THEMES",
    "com.novalauncher.THEME",
    "org.adw.ActivityStarter.THEMES",
    "com.gau.go.launcherex.theme",
)
private const val APPFILTER = "appfilter"
private const val DRAWABLE_LIST = "drawable"
private const val FOLDER_DRAWABLE = "folder"
private const val COMPONENT_PREFIX = "ComponentInfo{"
private const val TAG = "booster"

data class PackInfo(val pkg: String, val label: String)

fun findIconPacks(ctx: Context): List<PackInfo> {
    val pm = ctx.packageManager
    return PACK_ACTIONS.flatMap { action -> pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA) }
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .map { PackInfo(it.packageName, pm.getApplicationLabel(it).toString()) }
        .sortedBy { it.label.lowercase() }
}

// normalizes "ComponentInfo{pkg/.Cls}" to the "pkg/pkg.Cls" form used for app keys
fun parseComponent(raw: String): String? {
    val inner = raw.removePrefix(COMPONENT_PREFIX).removeSuffix("}")
    val pkg = inner.substringBefore('/', "").ifEmpty { return null }
    val cls = inner.substringAfter('/', "").ifEmpty { return null }
    return "$pkg/${if (cls.startsWith(".")) pkg + cls else cls}"
}

// one installed icon pack. parsing is lazy and must happen off the main thread.
class IconPack(ctx: Context, val pkg: String) {
    private val res: Resources = ctx.packageManager.getResourcesForApplication(pkg)

    // app key -> drawable name, from appfilter.xml. calendar entries resolve to today's date.
    val mapping: Map<String, String> by lazy {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        buildMap {
            parse(APPFILTER) { p ->
                val key = p.getAttributeValue(null, "component")?.let(::parseComponent) ?: return@parse
                when (p.name) {
                    "item" -> p.getAttributeValue(null, "drawable")?.let { put(key, it) }
                    "calendar" -> p.getAttributeValue(null, "prefix")?.let { put(key, "$it$day") }
                }
            }
        }
    }

    // every icon the pack offers for manual picking, from drawable.xml when present
    val names: List<String> by lazy {
        val listed = buildList { parse(DRAWABLE_LIST) { p -> if (p.name == "item") p.getAttributeValue(null, "drawable")?.let(::add) } }
        listed.ifEmpty { mapping.values.toList() }.distinct().filter { id(it) != 0 }
    }

    val hasFolder by lazy { id(FOLDER_DRAWABLE) != 0 }

    fun match(key: String): String? = mapping[key]?.takeIf { id(it) != 0 }

    fun drawable(name: String): Drawable? = id(name).takeIf { it != 0 }?.let {
        try {
            res.getDrawable(it, null)
        } catch (e: Resources.NotFoundException) {
            Log.w(TAG, "missing drawable $name in $pkg", e)
            null
        }
    }

    fun folder(): Drawable? = if (hasFolder) drawable(FOLDER_DRAWABLE) else null

    @Suppress("DiscouragedApi")
    private fun id(name: String) = res.getIdentifier(name, "drawable", pkg)

    // reads res/xml/<name>.xml, falling back to assets/<name>.xml
    @Suppress("DiscouragedApi")
    private fun parse(name: String, onTag: (XmlPullParser) -> Unit) {
        val xmlId = res.getIdentifier(name, "xml", pkg)
        try {
            if (xmlId != 0) {
                res.getXml(xmlId).use { walk(it, onTag) }
            } else {
                res.assets.open("$name.xml").use { stream ->
                    walk(Xml.newPullParser().apply { setInput(stream, null) }, onTag)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "no $name in $pkg: ${e.message}")
        }
    }

    private fun walk(p: XmlPullParser, onTag: (XmlPullParser) -> Unit) {
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) onTag(p)
        }
    }
}

package com.khimaros.booster

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings as SystemSettings

// a launchable activity, or a shortcut pinned by an app (a page added to home from a browser).
// both are keyed under their package, so uninstalling the app removes them alike.
data class AppInfo(val key: String, val label: String, val info: LauncherActivityInfo?, val shortcut: ShortcutInfo? = null) {
    val component: ComponentName? get() = info?.componentName ?: shortcut?.activity
    val pkg: String get() = shortcut?.`package` ?: info!!.componentName.packageName
}

private const val SHORTCUT_PREFIX = "shortcut:"

fun appKey(component: ComponentName) = "${component.packageName}/${component.className}"

fun shortcutKey(pkg: String, id: String) = "$pkg/$SHORTCUT_PREFIX$id"

// intents whose default handlers fill the dock on first run, in dock order
private val DEFAULT_DOCK_INTENTS = listOf(
    Intent(Intent.ACTION_DIAL),
    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
    Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
    Intent(SystemSettings.ACTION_SETTINGS),
)

// launchable apps for the current user, sorted by label
fun loadApps(ctx: Context): List<AppInfo> {
    val launcher = ctx.getSystemService(LauncherApps::class.java)
    return launcher.getActivityList(null, Process.myUserHandle())
        .filter { it.componentName.packageName != ctx.packageName }
        .map { AppInfo(appKey(it.componentName), it.label.toString(), it) }
        .sortedBy { it.label.lowercase() }
}

// shortcuts pinned to this launcher. only the default home app may see them.
fun loadShortcuts(ctx: Context): List<AppInfo> {
    val launcher = ctx.getSystemService(LauncherApps::class.java)
    if (!launcher.hasShortcutHostPermission()) return emptyList()
    val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
    return launcher.getShortcuts(query, Process.myUserHandle()).orEmpty()
        .map { AppInfo(shortcutKey(it.`package`, it.id), (it.shortLabel ?: it.longLabel ?: it.id).toString(), null, it) }
}

// a shortcut's own icon or, when it was pinned without one, the icon of the app that published it
fun shortcutIcon(ctx: Context, shortcut: ShortcutInfo, density: Int): Drawable {
    val launcher = ctx.getSystemService(LauncherApps::class.java)
    return launcher.getShortcutIconDrawable(shortcut, density)
        ?: launcher.getActivityList(shortcut.`package`, shortcut.userHandle).firstOrNull()?.getIcon(density)
        ?: ctx.packageManager.defaultActivityIcon
}

// keeps only the given shortcuts pinned for their package
fun pinShortcuts(ctx: Context, pkg: String, ids: List<String>) =
    ctx.getSystemService(LauncherApps::class.java).pinShortcuts(pkg, ids, Process.myUserHandle())

// the system's default apps for the dock, matched to launchable activities
fun defaultDockKeys(ctx: Context, apps: List<AppInfo>): List<String> = DEFAULT_DOCK_INTENTS.mapNotNull { intent ->
    val pkg = ctx.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    apps.firstOrNull { it.pkg == pkg }?.key
}.distinct()

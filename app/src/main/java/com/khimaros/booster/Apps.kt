package com.khimaros.booster

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings as SystemSettings

data class AppInfo(val key: String, val label: String, val info: LauncherActivityInfo) {
    val component: ComponentName get() = info.componentName
    val pkg: String get() = component.packageName
}

fun appKey(component: ComponentName) = "${component.packageName}/${component.className}"

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

// the system's default apps for the dock, matched to launchable activities
fun defaultDockKeys(ctx: Context, apps: List<AppInfo>): List<String> = DEFAULT_DOCK_INTENTS.mapNotNull { intent ->
    val pkg = ctx.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    apps.firstOrNull { it.pkg == pkg }?.key
}.distinct()

package com.khimaros.booster

import org.json.JSONArray
import org.json.JSONObject

private const val LAYOUT_VERSION = 1

fun Layout.toJson(): JSONObject = JSONObject()
    .put("version", LAYOUT_VERSION)
    .put("pages", JSONArray(pages.map { page -> JSONArray(page.map(::itemJson)) }))
    .put("dock", JSONArray(dock.map(::itemJson)))
    .put("drawer_folders", JSONArray(drawerFolders.map(::itemJson)))
    .put("hidden", JSONArray(hidden.sorted()))
    .put("overrides", JSONObject(overrides.toSortedMap().mapValues { (_, o) -> overrideJson(o) }))
    .put(
        "settings",
        JSONObject()
            .put("icon_pack", settings.iconPack ?: JSONObject.NULL)
            .put("rows", settings.rows)
            .put("rows_auto", settings.rowsAuto)
            .put("dock_slots", settings.dockSlots)
            .put("show_labels", settings.showLabels)
            .put("wallpaper", settings.wallpaper)
            .put("animate_drawer", settings.animateDrawer)
            .put("hide_placed", settings.hidePlaced),
    )
    .put("next_id", nextId)

private fun itemJson(item: Item?): Any = when (item) {
    null -> JSONObject.NULL
    is AppItem -> JSONObject().put("app", item.key)
    is FolderLink -> JSONObject().put("folder_link", item.id)
    is Folder -> JSONObject()
        .put("folder", item.id)
        .put("name", item.name)
        .put("icon", iconJson(item.icon))
        .put("apps", JSONArray(item.apps))
}

private fun iconJson(ref: IconRef?): Any =
    ref?.let { JSONObject().put("pack", it.pack ?: JSONObject.NULL).put("name", it.name) } ?: JSONObject.NULL

private fun overrideJson(o: Override) = JSONObject().put("label", o.label ?: JSONObject.NULL).put("icon", iconJson(o.icon))

private fun JSONArray?.items(): List<Any?> = if (this == null) emptyList() else (0 until length()).map { opt(it) }

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)

private fun parseIcon(o: Any?): IconRef? = (o as? JSONObject)?.let { IconRef(it.optStringOrNull("pack"), it.getString("name")) }

private fun parseItem(o: Any?): Item? {
    val obj = o as? JSONObject ?: return null
    return when {
        obj.has("app") -> AppItem(obj.getString("app"))
        obj.has("folder_link") -> FolderLink(obj.getString("folder_link"))
        obj.has("folder") -> Folder(
            obj.getString("folder"),
            obj.optString("name"),
            parseIcon(obj.opt("icon")),
            obj.optJSONArray("apps").items().map { it.toString() },
        )
        else -> null
    }
}

// tolerant of missing fields; pages and dock are resized to the saved settings
fun parseLayout(json: JSONObject): Layout {
    val s = json.optJSONObject("settings") ?: JSONObject()
    val settings = Settings(
        iconPack = s.optStringOrNull("icon_pack"),
        rows = s.optInt("rows", DEFAULT_ROWS).coerceIn(ROW_CHOICES),
        rowsAuto = s.optBoolean("rows_auto", true),
        dockSlots = s.optInt("dock_slots", DEFAULT_DOCK_SLOTS).coerceIn(DOCK_CHOICES),
        showLabels = s.optBoolean("show_labels", true),
        wallpaper = s.optBoolean("wallpaper", false),
        animateDrawer = s.optBoolean("animate_drawer", true),
        hidePlaced = s.optBoolean("hide_placed", false),
    )
    val size = settings.rows * COLUMNS
    val pages = json.optJSONArray("pages").items()
        .map { page -> (page as? JSONArray).items().map(::parseItem).let { items -> List(size) { items.getOrNull(it) } } }
        .ifEmpty { listOf(emptyPage(settings.rows)) }
    val dock = json.optJSONArray("dock").items().map(::parseItem)
    val overrides = json.optJSONObject("overrides")?.let { o ->
        o.keys().asSequence().associateWith { key ->
            val v = o.getJSONObject(key)
            Override(v.optStringOrNull("label"), parseIcon(v.opt("icon")))
        }
    } ?: emptyMap()
    return Layout(
        pages = pages,
        dock = List(settings.dockSlots) { dock.getOrNull(it) },
        drawerFolders = json.optJSONArray("drawer_folders").items().mapNotNull { parseItem(it) as? Folder },
        hidden = json.optJSONArray("hidden").items().map { it.toString() }.toSet(),
        overrides = overrides,
        settings = settings,
        nextId = json.optInt("next_id", 1),
    )
}

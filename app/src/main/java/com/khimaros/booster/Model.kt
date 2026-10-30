package com.khimaros.booster

const val COLUMNS = 4
const val DEFAULT_ROWS = 5
const val DEFAULT_DOCK_SLOTS = 5
val ROW_CHOICES = 3..12
val DOCK_CHOICES = 3..6

// an icon chosen by the user: a drawable in an icon pack, or another app's icon when pack is null
data class IconRef(val pack: String?, val name: String)

sealed interface Item

// apps are keyed by "package/fully.qualified.Activity"
data class AppItem(val key: String) : Item

data class Folder(val id: String, val name: String, val icon: IconRef?, val apps: List<String>) : Item

// a drawer folder shown on home or the dock: the same folder, not a copy
data class FolderLink(val id: String) : Item

data class Override(val label: String? = null, val icon: IconRef? = null)

data class Settings(
    val iconPack: String? = null,
    val rows: Int = DEFAULT_ROWS,
    // fit as many rows as the space above the dock allows; `rows` then follows the screen
    val rowsAuto: Boolean = true,
    val dockSlots: Int = DEFAULT_DOCK_SLOTS,
    val showLabels: Boolean = true,
    val wallpaper: Boolean = false,
    val animateDrawer: Boolean = true,
    val hidePlaced: Boolean = false,
)

// the whole persisted launcher state. every change is a pure function returning a new layout.
data class Layout(
    val pages: List<List<Item?>>,
    val dock: List<Item?>,
    val drawerFolders: List<Folder> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val overrides: Map<String, Override> = emptyMap(),
    val settings: Settings = Settings(),
    val nextId: Int = 1,
)

// where an item lives, used as the source and target of moves
sealed interface Slot
data class HomeSlot(val page: Int, val index: Int) : Slot
data class DockSlot(val index: Int) : Slot
data class FolderSlot(val folderId: String, val key: String) : Slot
data class DrawerSlot(val key: String) : Slot

fun emptyPage(rows: Int): List<Item?> = List(rows * COLUMNS) { null }

fun newLayout(dockKeys: List<String>): Layout {
    val settings = Settings()
    return Layout(
        pages = listOf(emptyPage(settings.rows)),
        dock = List(settings.dockSlots) { dockKeys.getOrNull(it)?.let(::AppItem) },
        settings = settings,
    )
}

private fun <T> List<T>.replaced(index: Int, value: T) = mapIndexed { i, v -> if (i == index) value else v }

fun Layout.itemAt(slot: Slot): Item? = when (slot) {
    is HomeSlot -> pages.getOrNull(slot.page)?.getOrNull(slot.index)
    is DockSlot -> dock.getOrNull(slot.index)
    is FolderSlot -> AppItem(slot.key)
    is DrawerSlot -> AppItem(slot.key)
}

private fun Layout.put(slot: Slot, item: Item?): Layout = when (slot) {
    is HomeSlot -> copy(pages = pages.mapIndexed { p, page -> if (p == slot.page) page.replaced(slot.index, item) else page })
    is DockSlot -> copy(dock = dock.replaced(slot.index, item))
    else -> this
}

fun Layout.homeFolders(): List<Folder> = (pages.flatten() + dock).filterIsInstance<Folder>()

fun Layout.findFolder(id: String): Folder? = allFolders().firstOrNull { it.id == id }

fun Layout.isDrawerFolder(id: String) = drawerFolders.any { it.id == id }

fun Layout.drawerFolderOf(key: String): Folder? = drawerFolders.firstOrNull { key in it.apps }

// swaps a folder for whatever the function returns. a drawer folder only survives as a folder.
private fun Layout.replaceFolder(id: String, replacement: (Folder) -> Item?): Layout {
    fun swap(item: Item?) = if (item is Folder && item.id == id) replacement(item) else item
    return copy(
        pages = pages.map { page -> page.map(::swap) },
        dock = dock.map(::swap),
        drawerFolders = drawerFolders.mapNotNull { if (it.id == id) replacement(it) as? Folder else it },
    )
}

fun Layout.updateFolder(id: String, name: String, icon: IconRef?) = replaceFolder(id) { it.copy(name = name, icon = icon) }

fun Layout.addToFolder(id: String, key: String) =
    replaceFolder(id) { if (key in it.apps) it else it.copy(apps = it.apps + key) }

// moves an app to a new position within its folder
fun Layout.moveInFolder(id: String, key: String, index: Int) = replaceFolder(id) { folder ->
    val rest = folder.apps - key
    folder.copy(apps = rest.toMutableList().apply { add(index.coerceIn(0, rest.size), key) })
}

// a folder left with one app collapses into that app; a drawer folder just goes away
fun Layout.removeFromFolder(id: String, key: String) = replaceFolder(id) { folder ->
    val apps = folder.apps - key
    if (apps.size > 1) folder.copy(apps = apps) else apps.firstOrNull()?.let(::AppItem)
}

fun Layout.dissolve(id: String): Layout {
    val folder = findFolder(id) ?: return this
    if (isDrawerFolder(id)) return copy(drawerFolders = drawerFolders.filter { it.id != id })
    val collapsed = replaceFolder(id) { it.apps.firstOrNull()?.let(::AppItem) }
    return folder.apps.drop(1).fold(collapsed) { layout, key -> layout.placeOnHome(AppItem(key)) }
}

fun Layout.placeOnHome(item: Item): Layout {
    pages.forEachIndexed { p, page ->
        val i = page.indexOf(null)
        if (i >= 0) return put(HomeSlot(p, i), item)
    }
    return copy(pages = pages + listOf(emptyPage(settings.rows).replaced(0, item)))
}

fun Layout.placeInDock(item: Item): Layout? {
    val i = dock.indexOf(null)
    return if (i < 0) null else put(DockSlot(i), item)
}

fun Layout.remove(slot: Slot): Layout = when (slot) {
    is HomeSlot, is DockSlot -> put(slot, null)
    is FolderSlot -> removeFromFolder(slot.folderId, slot.key)
    is DrawerSlot -> this
}

fun Layout.allFolders(): List<Folder> = homeFolders() + drawerFolders

// true when another folder (anywhere) already uses this name
fun Layout.folderNameTaken(name: String, exceptId: String? = null) =
    allFolders().any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }

// "name", then "name 2", "name 3"... whichever is free first
fun Layout.uniqueFolderName(name: String): String =
    generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name $it" }.first { !folderNameTaken(it) }

// creates a folder in a home or dock slot and returns the new layout and folder id
fun Layout.newFolderAt(slot: Slot, keys: List<String>, name: String): Pair<Layout, String> {
    val id = "f$nextId"
    return copy(nextId = nextId + 1).put(slot, Folder(id, uniqueFolderName(name), null, keys)) to id
}

fun Layout.newDrawerFolder(keys: List<String>, name: String): Pair<Layout, String> {
    val id = "f$nextId"
    val cleared = keys.fold(this) { layout, key -> layout.drawerFolderOf(key)?.let { layout.removeFromFolder(it.id, key) } ?: layout }
    val folder = Folder(id, cleared.uniqueFolderName(name), null, keys)
    return cleared.copy(nextId = nextId + 1, drawerFolders = cleared.drawerFolders + folder) to id
}

// apps on the home screen or in the dock, including inside their folders and linked drawer folders
fun Layout.placedKeys(): Set<String> = (pages.flatten() + dock).flatMap {
    when (it) {
        is AppItem -> listOf(it.key)
        is Folder -> it.apps
        is FolderLink -> findFolder(it.id)?.apps.orEmpty()
        null -> emptyList()
    }
}.toSet()

// the folder an item stands for: itself, or the drawer folder a link points at
fun Layout.folderOf(item: Item?): Folder? = when (item) {
    is Folder -> item
    is FolderLink -> drawerFolders.firstOrNull { it.id == item.id }
    else -> null
}

// links to drawer folders that no longer exist are dropped
fun Layout.tidy(): Layout {
    val live = drawerFolders.map { it.id }.toSet()
    fun keep(item: Item?) = if (item is FolderLink && item.id !in live) null else item
    return copy(pages = pages.map { page -> page.map(::keep) }, dock = dock.map(::keep))
}

// moves an app into a drawer folder, taking it out of any other drawer folder first
fun Layout.moveToDrawerFolder(id: String, key: String): Layout {
    val current = drawerFolderOf(key)
    if (current?.id == id) return this
    return (current?.let { removeFromFolder(it.id, key) } ?: this).addToFolder(id, key)
}

// drag and drop: onto an empty slot moves, onto an app makes a folder, onto a folder adds to it,
// and anything else swaps the two home or dock slots.
fun Layout.drop(source: Slot, item: Item, target: Slot, folderName: String): Layout {
    if (source == target || target is FolderSlot || target is DrawerSlot) return this
    val before = folderOf(itemAt(target))
    if (source is FolderSlot && before?.id == source.folderId) return this
    val movable = source is HomeSlot || source is DockSlot
    // home and dock folders give the app up; drawer folders, like the drawer, only lend a shortcut
    val lifted = when (source) {
        is HomeSlot, is DockSlot -> put(source, null)
        is FolderSlot -> if (isDrawerFolder(source.folderId)) this else removeFromFolder(source.folderId, source.key)
        is DrawerSlot -> this
    }
    val existing = lifted.itemAt(target)
    return when {
        existing == null -> lifted.put(target, item)
        existing is AppItem && item is AppItem ->
            if (existing.key == item.key) lifted.put(target, existing)
            else lifted.newFolderAt(target, listOf(existing.key, item.key), folderName).first
        existing is Folder && item is AppItem -> lifted.addToFolder(existing.id, item.key)
        existing is FolderLink && item is AppItem -> lifted.moveToDrawerFolder(existing.id, item.key)
        movable -> lifted.put(target, item).put(source, existing)
        else -> this
    }
}

fun Layout.withOverride(key: String, override: Override): Layout = copy(
    overrides = if (override.label == null && override.icon == null) overrides - key else overrides + (key to override),
)

fun Layout.toggleHidden(key: String) = copy(hidden = if (key in hidden) hidden - key else hidden + key)

// drops every reference to apps that are gone, e.g. after an uninstall
fun Layout.prune(gone: (String) -> Boolean): Layout {
    fun keep(item: Item?): Item? = when (item) {
        is AppItem -> item.takeUnless { gone(it.key) }
        is Folder -> item.copy(apps = item.apps.filterNot(gone)).takeIf { it.apps.isNotEmpty() }
        is FolderLink -> item
        null -> null
    }
    return copy(
        pages = pages.map { page -> page.map(::keep) },
        dock = dock.map(::keep),
        drawerFolders = drawerFolders.mapNotNull { keep(it) as? Folder },
        hidden = hidden.filterNot(gone).toSet(),
        overrides = overrides.filterKeys { !gone(it) },
    ).tidy()
}

fun Layout.addPage() = copy(pages = pages + listOf(emptyPage(settings.rows)))

fun Layout.removePage(page: Int): Layout =
    if (pages.size > 1 && pages.getOrNull(page)?.all { it == null } == true) copy(pages = pages.filterIndexed { i, _ -> i != page })
    else this

// resizing never loses items: anything that no longer fits moves to the first free home slot
fun Layout.withRows(rows: Int): Layout {
    val size = rows * COLUMNS
    val overflow = pages.flatMap { it.drop(size) }.filterNotNull()
    val resized = copy(settings = settings.copy(rows = rows), pages = pages.map { page -> List(size) { page.getOrNull(it) } })
    return overflow.fold(resized) { layout, item -> layout.placeOnHome(item) }
}

fun Layout.withDockSlots(slots: Int): Layout {
    val overflow = dock.drop(slots).filterNotNull()
    val resized = copy(settings = settings.copy(dockSlots = slots), dock = List(slots) { dock.getOrNull(it) })
    return overflow.fold(resized) { layout, item -> layout.placeOnHome(item) }
}

fun Layout.withSettings(update: (Settings) -> Settings) = copy(settings = update(settings))

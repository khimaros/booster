package com.khimaros.booster

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val TAG = "booster"

// what is being dragged, and from where
data class DragPayload(val source: Slot, val item: Item)

class LauncherActivity : Activity() {
    lateinit var scale: Scale
    lateinit var palette: Palette
    lateinit var icons: Icons
    lateinit var overlays: Overlays
    private lateinit var root: FrameLayout
    private lateinit var home: HomeView
    private lateinit var drawer: DrawerView
    private lateinit var store: Store

    var layout = newLayout(emptyList())
        private set
    var apps: Map<String, AppInfo> = emptyMap()
        private set
    private var appList: List<AppInfo> = emptyList()
    var packs: List<PackInfo> = emptyList()
        private set
    private var loaded = false
    private var shownWallpaper: Boolean? = null

    val worker = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    private val drawerAdapter = DrawerAdapter()
    private var drag: DragPayload? = null
    private var armed: Pair<CellView, View>? = null
    private var openCard: View? = null
    private var openFolder: Pair<String, CellGrid>? = null
    private var touchX = 0f
    private var touchY = 0f
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(pkg: String, user: UserHandle) = reloadApps(removed = pkg)
        override fun onPackageAdded(pkg: String, user: UserHandle) = reloadApps(changed = pkg)
        override fun onPackageChanged(pkg: String, user: UserHandle) = reloadApps(changed = pkg)
        override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = reloadApps()
        override fun onPackagesUnavailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = reloadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()
        val metrics = resources.displayMetrics
        scale = Scale(min(metrics.widthPixels, metrics.heightPixels), metrics.density)
        palette = Palette(this)
        icons = Icons(this)
        store = Store(filesDir)
        root = FrameLayout(this)
        home = HomeView(this, scale, palette)
        drawer = DrawerView(this, scale, palette).apply { visibility = View.INVISIBLE }
        root.addView(home)
        root.addView(drawer)
        overlays = Overlays(root, scale, palette)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets) }
        wireHome()
        wireDrawer()
        launcherApps.registerCallback(packageCallback, main)
        worker.execute {
            val saved = store.load()
            val list = loadApps(this)
            val found = findIconPacks(this)
            val initial = saved ?: newLayout(defaultDockKeys(this, list))
            main.post {
                setApps(list, found)
                loaded = true
                commit(initial, persist = saved == null)
                home.post(::fitRows)
            }
        }
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(packageCallback)
        worker.shutdown()
        super.onDestroy()
    }

    // pressing home while already home closes everything and returns to the first page. coming back
    // from an app leaves the drawer, its search and scroll exactly as they were.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val broughtToFront = intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0
        if (Intent.ACTION_MAIN != intent.action || !hasWindowFocus() || broughtToFront) return
        overlays.clear()
        if (drawer.isOpen) closeDrawer() else home.pager.snapTo(0)
    }

    @Deprecated("platform back handling; this launcher has no androidx dispatcher")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (overlays.pop()) return
        closeDrawer()
    }

    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(insets: WindowInsets): WindowInsets {
        val (top, nav, ime) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            Triple(bars.top, bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
        } else {
            Triple(insets.systemWindowInsetTop, insets.stableInsetBottom, insets.systemWindowInsetBottom)
        }
        home.setInsets(top, nav)
        drawer.setInsets(top, max(nav, ime))
        overlays.setInsets(nav, ime)
        return insets
    }

    // --- state ---

    fun commit(next: Layout, persist: Boolean = true) {
        layout = next.tidy()
        render()
        if (persist) {
            val text = Store.encode(layout)
            worker.execute { store.save(text) }
        }
    }

    private fun setApps(list: List<AppInfo>, found: List<PackInfo>) {
        appList = list
        apps = list.associateBy { it.key }
        packs = found
    }

    private fun reloadApps(removed: String? = null, changed: String? = null) {
        worker.execute {
            val list = loadApps(this)
            val found = findIconPacks(this)
            main.post {
                setApps(list, found)
                (removed ?: changed)?.let(icons::forgetPack)
                if (!loaded) return@post
                if (removed != null) commit(layout.prune { it.startsWith("$removed/") }) else render()
            }
        }
    }

    fun labelOf(key: String): String = layout.overrides[key]?.label ?: apps[key]?.label ?: key.substringAfterLast('.')

    // --- rendering ---

    fun render() {
        val settings = layout.settings
        icons.globalPack = settings.iconPack?.takeIf { pkg -> packs.any { it.pkg == pkg } }
        applyBackground(settings.wallpaper)
        val pager = home.pager
        while (pager.childCount > layout.pages.size) pager.removeViewAt(pager.childCount - 1)
        while (pager.childCount < layout.pages.size) pager.addView(CellGrid(this, scale, COLUMNS))
        layout.pages.forEachIndexed { p, page ->
            val grid = pager.getChildAt(p) as CellGrid
            ensureCells(grid, page.size) { null }
            page.forEachIndexed { i, item -> bind(grid.getChildAt(i) as CellView, item, HomeSlot(p, i), settings.showLabels) }
        }
        if (pager.page >= layout.pages.size) pager.snapTo(layout.pages.size - 1, animate = false)
        home.setPageCount(layout.pages.size)
        ensureCells(home.dock, layout.dock.size) { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f) }
        layout.dock.forEachIndexed { i, item -> bind(home.dock.getChildAt(i) as CellView, item, DockSlot(i), false) }
        drawer.setBackdrop(settings.wallpaper)
        drawer.animated = settings.animateDrawer
        refreshDrawer()
        refreshCard()
    }

    private fun ensureCells(group: ViewGroup, count: Int, params: () -> ViewGroup.LayoutParams?) {
        while (group.childCount > count) group.removeViewAt(group.childCount - 1)
        while (group.childCount < count) {
            val cell = newCell().apply { setOnDragListener(dropListener) }
            params()?.let { group.addView(cell, it) } ?: group.addView(cell)
        }
    }

    // window attribute updates relayout the window and can cancel a touch in flight,
    // so only touch them when the setting actually changes
    private fun applyBackground(wallpaper: Boolean) {
        if (wallpaper == shownWallpaper) return
        shownWallpaper = wallpaper
        if (wallpaper) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun newCell(): CellView = CellView(this, scale, palette).apply {
        setOnClickListener { onCellClick(this) }
        setOnLongClickListener {
            onCellLongPress(this)
            true
        }
        setOnTouchListener(dragStarter)
    }

    fun bind(cell: CellView, item: Item?, slot: Slot?, showLabel: Boolean) {
        cell.clear()
        cell.slot = slot
        cell.item = item
        cell.showLabel = showLabel
        cell.isClickable = item != null
        cell.isLongClickable = item != null
        if (item is AppItem) {
            cell.label = labelOf(item.key)
            val ref = layout.overrides[item.key]?.icon
            loadInto(cell, "app:${item.key}:$ref") { apps[item.key]?.let { icons.app(it, ref, apps) } }
            return
        }
        // a folder, or a link to a drawer folder, looks the same everywhere
        val folder = layout.folderOf(item) ?: return
        cell.label = folder.name
        loadInto(cell, "folder:${folder.icon}") { icons.folder(folder.icon, apps) }
        if (folder.icon != null) loadBadge(cell)
    }

    fun loadInto(cell: CellView, key: String, resolve: () -> android.graphics.drawable.Drawable?) {
        cell.iconKey = key
        icons.load(key, resolve) { if (cell.iconKey == key) cell.icon = it }
    }

    private fun loadBadge(cell: CellView) {
        val key = cell.iconKey
        icons.load("badge", { icons.folderDefault() }) { if (cell.iconKey == key) cell.badge = it }
    }

    // --- drawer ---

    private fun wireHome() {
        home.onDrawerDrag = ::dragDrawer
        home.onDrawerRelease = drawer::release
        home.pager.onEmptyLongPress = ::homeMenu
        home.zoneRemove.setOnDragListener(zoneListener { remove(it.source) })
        home.zoneEdit.setOnDragListener(zoneListener { openEdit(it.item) })
        home.zoneInfo.setOnDragListener(zoneListener { (it.item as? AppItem)?.let { app -> appInfo(app.key) } })
        home.pager.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) home.post(::fitRows)
        }
    }

    // rows of cells (with gaps between them) that fit in the space above the dock
    fun fittingRows(): Int = ((home.pager.height + scale.rowGap) / (scale.cell + scale.rowGap)).coerceIn(ROW_CHOICES)

    // auto rows follow the screen; a manual count never exceeds what fits
    private fun fitRows() {
        if (!loaded || home.pager.height == 0) return
        val settings = layout.settings
        val fit = fittingRows()
        val rows = if (settings.rowsAuto) fit else minOf(settings.rows, fit)
        if (rows != settings.rows) commit(layout.withRows(rows))
    }

    private fun wireDrawer() {
        drawer.grid.adapter = drawerAdapter
        drawer.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshDrawer()
        })
        drawer.search.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            drawerAdapter.entries.firstOrNull { it is AppItem }?.let { launch((it as AppItem).key, null) }
            true
        }
    }

    // the drawer follows an upward swipe from anywhere on home, then settles open or closed.
    // render() keeps its contents current, so opening needs no rebuild.
    private fun dragDrawer(distance: Float) {
        if (distance == 0f) overlays.clear()
        drawer.follow(distance)
    }

    private fun closeDrawer() {
        if (drawer.isOpen) drawer.close()
    }

    private fun refreshDrawer() {
        val query = drawer.search.text.toString().trim().lowercase()
        drawerAdapter.entries = drawerEntries(query)
        drawer.alignResults(if (query.isEmpty()) null else drawerAdapter.entries.size)
        drawerAdapter.notifyDataSetChanged()
    }

    // browsing: drawer folders, then loose visible apps. searching: every app, prefix matches first.
    private fun drawerEntries(query: String): List<Item> {
        val byLabel = compareBy<AppInfo> { labelOf(it.key).lowercase() }
        if (query.isEmpty()) {
            val foldered = layout.drawerFolders.flatMap { it.apps }.toSet()
            val folders = layout.drawerFolders.filter { drawerFolderApps(it).isNotEmpty() }.sortedBy { it.name.lowercase() }
            return folders + appList.filter { it.key !in foldered && it.key !in layout.hidden && !hiddenAsPlaced(it.key) }
                .sortedWith(byLabel).map { AppItem(it.key) }
        }
        return appList.filter { labelOf(it.key).lowercase().contains(query) }
            .sortedWith(compareBy<AppInfo> { !labelOf(it.key).lowercase().startsWith(query) }.then(byLabel))
            .map { AppItem(it.key) }
    }

    // with "hide apps on home", the drawer and its folders leave out what is already on home or the dock
    private fun hiddenAsPlaced(key: String) = layout.settings.hidePlaced && key in layout.placedKeys()

    private fun drawerFolderApps(folder: Folder) = folder.apps.filterNot(::hiddenAsPlaced)

    private inner class DrawerAdapter : BaseAdapter() {
        var entries: List<Item> = emptyList()
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = convertView as? CellView ?: newCell().apply { layoutParams = AbsListView.LayoutParams(scale.cell, scale.cell) }
            val item = entries[position]
            bind(cell, item, (item as? AppItem)?.let { DrawerSlot(it.key) }, layout.settings.showLabels)
            return cell
        }
    }

    // --- actions ---

    private fun onCellClick(cell: CellView) {
        val item = cell.item
        if (item is AppItem) launch(item.key, cell) else layout.folderOf(item)?.let(::openFolder)
    }

    fun launch(key: String, from: View?) {
        val app = apps[key] ?: return
        val bounds = from?.let { v ->
            val at = IntArray(2).also(v::getLocationOnScreen)
            Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
        }
        try {
            launcherApps.startMainActivity(app.component, app.info.user, bounds, null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "launch $key failed", e)
        }
    }

    fun appInfo(key: String) {
        val app = apps[key] ?: return
        launcherApps.startAppDetailsActivity(app.component, app.info.user, null, null)
    }

    private fun uninstall(key: String) {
        val pkg = apps[key]?.pkg ?: return
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "uninstall $pkg failed", e)
        }
    }

    private fun remove(slot: Slot) = commit(layout.remove(slot))

    private fun openFolder(folder: Folder) {
        val grid = CellGrid(this, scale, 1)
        openFolder = folder.id to grid
        fillCard(grid, folder)
        openCard = overlays.card(grid, onDragOut = ::closeDrawer)
    }

    private fun fillCard(grid: CellGrid, folder: Folder) {
        val keys = if (layout.isDrawerFolder(folder.id)) drawerFolderApps(folder) else folder.apps
        grid.cols = CellGrid.columnsFor(keys.size)
        ensureCells(grid, keys.size) { null }
        keys.forEachIndexed { i, key ->
            val cell = grid.getChildAt(i) as CellView
            bind(cell, AppItem(key), FolderSlot(folder.id, key), true)
            cell.setOnDragListener(folderDropListener)
        }
        grid.requestLayout()
    }

    // an open folder follows every change: names, icons, order, and apps leaving it
    private fun refreshCard() {
        val (id, grid) = openFolder ?: return
        val card = openCard
        if (card == null || !overlays.isShowing(card)) {
            openFolder = null
            return
        }
        val folder = layout.findFolder(id)
        if (folder == null) overlays.dismiss(card) else fillCard(grid, folder)
    }

    // dropping an app on another app in the same open folder moves it to that position
    private val folderDropListener = View.OnDragListener { v, ev ->
        val cell = v as CellView
        val target = cell.slot as? FolderSlot
        val source = (ev.localState as? DragPayload)?.source as? FolderSlot
        if (target == null || source == null || source.folderId != target.folderId) return@OnDragListener false
        when (ev.action) {
            DragEvent.ACTION_DRAG_ENTERED -> cell.highlighted = true
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> cell.highlighted = false
            DragEvent.ACTION_DROP -> {
                val index = layout.findFolder(target.folderId)?.apps?.indexOf(target.key) ?: return@OnDragListener false
                commit(layout.moveInFolder(target.folderId, source.key, index))
            }
        }
        true
    }

    private fun onCellLongPress(cell: CellView) {
        val item = cell.item ?: return
        // the rest of this gesture belongs to the cell: paging or swiping must not steal the drag
        cell.parent?.requestDisallowInterceptTouchEvent(true)
        val entries = when (item) {
            is AppItem -> appMenu(item.key, cell.slot ?: return)
            is Folder -> folderMenu(item, cell.slot)
            is FolderLink -> linkMenu(layout.folderOf(item) ?: return, cell.slot ?: return)
        }
        armed = cell to overlays.menu(entries, cell)
    }

    private fun appMenu(key: String, slot: Slot): List<MenuEntry> {
        val edit = MenuEntry(getString(R.string.edit)) { openEdit(AppItem(key)) }
        val info = MenuEntry(getString(R.string.app_info)) { appInfo(key) }
        val uninstall = MenuEntry(getString(R.string.uninstall), danger = true) { uninstall(key) }
        val toFolder = MenuEntry(getString(R.string.add_to_folder)) { folderChooser(key, slot) }
        return when (slot) {
            is HomeSlot -> listOf(edit, MenuEntry(getString(R.string.remove_home)) { remove(slot) }, info, toFolder, uninstall)
            is DockSlot -> listOf(edit, MenuEntry(getString(R.string.remove_dock)) { remove(slot) }, info, toFolder, uninstall)
            is FolderSlot -> {
                // an app in a drawer folder can still be placed on home or the dock
                val place = if (layout.isDrawerFolder(slot.folderId)) placeEntries(AppItem(key)) else emptyList()
                place + listOf(edit, MenuEntry(getString(R.string.remove_from_folder)) { remove(slot) }, info, uninstall)
            }
            is DrawerSlot -> placeEntries(AppItem(key)) + listOf(
                edit,
                MenuEntry(getString(if (key in layout.hidden) R.string.unhide else R.string.hide)) { commit(layout.toggleHidden(key)) },
                info,
                toFolder,
                uninstall,
            )
        }
    }

    // a drawer folder (no slot) can also be placed on home or the dock, as a link to itself
    private fun folderMenu(folder: Folder, slot: Slot?): List<MenuEntry> {
        val edit = MenuEntry(getString(R.string.edit_folder)) { openEdit(folder) }
        val dissolve = MenuEntry(getString(R.string.dissolve), danger = true) { commit(layout.dissolve(folder.id)) }
        if (slot != null) return listOf(edit, MenuEntry(getString(R.string.zone_remove)) { remove(slot) }, dissolve)
        return placeEntries(FolderLink(folder.id)) + listOf(edit, dissolve)
    }

    // a linked drawer folder on home or the dock: removing it only removes the link
    private fun linkMenu(folder: Folder, slot: Slot) = listOf(
        MenuEntry(getString(R.string.edit_folder)) { openEdit(folder) },
        MenuEntry(getString(if (slot is DockSlot) R.string.remove_dock else R.string.remove_home)) { remove(slot) },
    )

    private fun placeEntries(item: Item) = listOf(
        MenuEntry(getString(R.string.add_home)) { commit(layout.placeOnHome(item)) },
        MenuEntry(getString(R.string.add_dock)) { addToDock(item) },
    )

    private fun addToDock(item: Item) {
        layout.placeInDock(item)?.let(::commit) ?: Toast.makeText(this, R.string.dock_full, Toast.LENGTH_SHORT).show()
    }

    // folders in the same place as the app (home and dock, or drawer), plus a new one
    private fun folderChooser(key: String, slot: Slot) {
        val name = getString(R.string.folder_default)
        val inDrawer = slot is DrawerSlot
        val folders = (if (inDrawer) layout.drawerFolders else layout.homeFolders()).sortedBy { it.name.lowercase() }
        val entries = folders.map { folder ->
            MenuEntry(folder.name) {
                commit(if (inDrawer) layout.moveToDrawerFolder(folder.id, key) else layout.remove(slot).addToFolder(folder.id, key))
            }
        } + MenuEntry(getString(R.string.new_folder)) {
            val (next, id) = if (inDrawer) layout.newDrawerFolder(listOf(key), name) else layout.newFolderAt(slot, listOf(key), name)
            commit(next)
            next.findFolder(id)?.let(::openEdit)
        }
        overlays.menu(entries)
    }

    private fun homeMenu() {
        if (!loaded || drag != null) return
        overlays.menu(
            listOf(
                MenuEntry(getString(R.string.background)) { commit(layout.withSettings { it.copy(wallpaper = !it.wallpaper) }) },
                MenuEntry(getString(R.string.launcher_settings)) { openSettings() },
                MenuEntry(getString(R.string.add_page)) {
                    commit(layout.addPage())
                    home.pager.snapTo(layout.pages.size - 1)
                },
                MenuEntry(getString(R.string.remove_page), danger = true) { commit(layout.removePage(home.pager.page)) },
            ),
        )
    }

    // --- drag and drop ---

    // after a long press, moving the finger past the touch slop swaps the menu for a drag
    @SuppressLint("ClickableViewAccessibility")
    private val dragStarter = View.OnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = ev.rawX
                touchY = ev.rawY
                armed = null
            }
            MotionEvent.ACTION_MOVE -> armed?.let { (cell, menu) ->
                if (cell === v && hypot(ev.rawX - touchX, ev.rawY - touchY) > slop) {
                    armed = null
                    overlays.dismiss(menu)
                    startDrag(cell)
                }
            }
        }
        false
    }

    private fun startDrag(cell: CellView) {
        val payload = DragPayload(cell.slot ?: return, cell.item ?: return)
        drag = payload
        // drop targets must be showing when the drag starts to receive its events
        home.showDragUi(true)
        // the platform refuses a drag when the finger has already lifted: then nothing changes
        if (!cell.startDragAndDrop(null, View.DragShadowBuilder(cell), payload, 0)) {
            endDrag()
            return
        }
        // an app dragged from an open folder keeps the folder (and the drawer under it) open, so it can be
        // reordered; leaving the card reveals home
        if (payload.source is FolderSlot) return
        overlays.clear()
        closeDrawer()
    }

    private fun endDrag() {
        if (drag == null) return
        drag = null
        home.showDragUi(false)
    }

    private fun hintFor(payload: DragPayload, target: Item?): String? = when {
        payload.item !is AppItem -> null
        target is AppItem && target != payload.item -> getString(R.string.hint_new_folder)
        target is Folder || target is FolderLink -> getString(R.string.hint_add_folder)
        else -> null
    }

    private val dropListener = View.OnDragListener { v, ev ->
        val cell = v as CellView
        val target = cell.slot
        val payload = ev.localState as? DragPayload
        if (payload == null || (target !is HomeSlot && target !is DockSlot)) return@OnDragListener false
        when (ev.action) {
            DragEvent.ACTION_DRAG_ENTERED -> {
                cell.highlighted = true
                home.showHint(hintFor(payload, cell.item))
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                cell.highlighted = false
                home.showHint(null)
            }
            DragEvent.ACTION_DROP -> {
                cell.highlighted = false
                commit(layout.drop(payload.source, payload.item, target, getString(R.string.folder_default)))
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                cell.highlighted = false
                endDrag()
            }
        }
        true
    }

    private fun zoneListener(action: (DragPayload) -> Unit) = View.OnDragListener { v, ev ->
        val payload = ev.localState as? DragPayload ?: return@OnDragListener false
        when (ev.action) {
            DragEvent.ACTION_DRAG_ENTERED -> v.background = rounded(palette.accent, scale.row / 2f)
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                v.background = rounded(palette.surfaceHi, scale.row / 2f)
                if (ev.action == DragEvent.ACTION_DRAG_ENDED) endDrag()
            }
            DragEvent.ACTION_DROP -> action(payload)
        }
        true
    }

    // --- export and import ---

    fun exportLayout() = startActivityForResult(
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(JSON_MIME)
            .putExtra(Intent.EXTRA_TITLE, EXPORT_NAME),
        REQUEST_EXPORT,
    )

    fun importLayout() = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(JSON_MIME),
        REQUEST_IMPORT,
    )

    @Deprecated("platform activity results; this launcher has no androidx result api")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQUEST_EXPORT -> contentResolver.openOutputStream(uri)?.use { it.write(Store.encode(layout).toByteArray()) }
                REQUEST_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }
                    val imported = text?.let(Store::decode)
                    if (imported != null) commit(imported) else Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "layout transfer failed", e)
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val REQUEST_EXPORT = 1
        const val REQUEST_IMPORT = 2
        const val JSON_MIME = "application/json"
        const val EXPORT_NAME = "booster-layout.json"
    }
}

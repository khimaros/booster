package com.khimaros.booster

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

private const val PEN = "✎"
private const val FOLDER_DRAWABLE = "folder"

// --- edit sheet: one sheet for apps and folders. tapping the icon opens the icon picker. ---

fun LauncherActivity.openEdit(item: Item) {
    val folder = (item as? Folder)?.let { layout.findFolder(it.id) }
    val key = (item as? AppItem)?.key
    if (folder == null && (key == null || apps[key] == null)) return
    val original = key?.let { apps[it]?.label }
    var icon: IconRef? = folder?.icon ?: layout.overrides[key]?.icon

    val preview = CellView(this, scale, palette).apply {
        id = R.id.sheet_icon
        showLabel = false
        contentDescription = getString(R.string.zone_edit)
    }
    fun refreshPreview() {
        val ref = icon
        if (folder != null) {
            loadInto(preview, "folder:$ref") { icons.folder(ref, apps) }
        } else {
            val app = apps[key] ?: return
            loadInto(preview, "app:$key:$ref") { icons.app(app, ref, apps) }
        }
    }
    refreshPreview()
    val iconFrame = FrameLayout(this).apply {
        addView(preview, FrameLayout.LayoutParams(scale.cell, scale.cell))
        addView(
            label(scale, palette.onAccent, PEN).apply {
                gravity = Gravity.CENTER
                background = rounded(palette.accent, scale.p(3) / 2)
            },
            FrameLayout.LayoutParams(scale.px(3), scale.px(3), Gravity.TOP or Gravity.END),
        )
        setOnClickListener { openPicker(key, folder != null) { ref -> icon = ref; refreshPreview() } }
    }

    val current = folder?.name ?: layout.overrides[key]?.label ?: original ?: ""
    val nameEdit = sheetEdit(current, original ?: "").apply { id = R.id.sheet_name }
    // a p2 label line and a p3 value line padded by p3/2 make a p5 field
    val field = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.field, scale.p(2))
        setPadding(scale.gap, scale.px(3) / 2, scale.gap, scale.px(3) / 2)
        addView(label(scale, palette.accent, getString(if (folder != null) R.string.folder_name else R.string.name)), LinearLayout.LayoutParams(MATCH_PARENT, scale.gap))
        addView(nameEdit, LinearLayout.LayoutParams(MATCH_PARENT, scale.line))
    }
    val note = if (folder != null) resources.getQuantityString(R.plurals.apps_count, folder.apps.size, folder.apps.size)
    else getString(R.string.original, original)
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(field, LinearLayout.LayoutParams(MATCH_PARENT, scale.row))
        addView(label(scale, palette.dim, note), LinearLayout.LayoutParams(MATCH_PARENT, scale.line).apply { topMargin = scale.tight })
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(iconFrame, LinearLayout.LayoutParams(scale.cell, scale.cell))
        addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = scale.gutter })
    }

    lateinit var sheet: View
    fun finish(next: Layout) {
        commit(next)
        overlays.dismiss(sheet)
    }
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    buttons.addView(textButton(getString(R.string.reset), palette.dim) {
        finish(if (folder != null) layout.updateFolder(folder.id, folder.name, null) else layout.withOverride(key!!, Override()))
    })
    if (folder != null) {
        buttons.addView(
            textButton(getString(R.string.dissolve), palette.danger) { finish(layout.dissolve(folder.id)) },
            LinearLayout.LayoutParams(WRAP_CONTENT, scale.row).apply { leftMargin = scale.chip },
        )
    }
    buttons.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
    buttons.addView(textButton(getString(R.string.done), palette.accent) {
        val name = nameEdit.text.toString().trim()
        if (folder != null && layout.folderNameTaken(name, folder.id)) {
            Toast.makeText(this, getString(R.string.folder_name_taken, name), Toast.LENGTH_SHORT).show()
            return@textButton
        }
        finish(
            if (folder != null) layout.updateFolder(folder.id, name.ifEmpty { folder.name }, icon)
            else layout.withOverride(key!!, Override(name.ifEmpty { null }?.takeIf { it != original }, icon)),
        )
    })

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(row)
        addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, scale.row).apply { topMargin = scale.gutter })
    }
    sheet = overlays.sheet(content)
    // typing replaces the name straight away: whole name selected, keyboard up
    nameEdit.post {
        nameEdit.selectAll()
        nameEdit.showKeyboard()
    }
}

private fun LauncherActivity.textButton(text: String, color: Int, onClick: () -> Unit): TextView =
    label(scale, color, text).apply {
        foreground = selectable()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, scale.row)
    }

private fun LauncherActivity.sheetEdit(value: String, hintText: String) = EditText(this).apply {
    setText(value)
    hint = hintText
    setTextColor(palette.fg)
    setHintTextColor(palette.dim)
    setTextSize(TypedValue.COMPLEX_UNIT_PX, scale.text)
    background = null
    setPadding(0, 0, 0, 0)
    includeFontPadding = false
    isSingleLine = true
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    imeOptions = EditorInfo.IME_ACTION_DONE
}

private fun LauncherActivity.searchField() = EditText(this).apply {
    hint = getString(R.string.search_apps)
    setHintTextColor(palette.dim)
    setTextColor(palette.fg)
    setTextSize(TypedValue.COMPLEX_UNIT_PX, scale.text)
    background = rounded(palette.surfaceHi, scale.row / 2f)
    setPadding(scale.gutter, 0, scale.gutter, 0)
    isSingleLine = true
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    imeOptions = EditorInfo.IME_ACTION_SEARCH
}

private fun EditText.onChange(action: () -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) = action()
})

// --- icon picker: icon grid, then source chips (packs and System), then search ---

private data class Choice(val ref: IconRef, val label: String, val match: Boolean)

fun LauncherActivity.openPicker(forKey: String?, forFolder: Boolean, onPick: (IconRef) -> Unit) {
    var source: PackInfo? = packs.firstOrNull { it.pkg == layout.settings.iconPack } ?: packs.firstOrNull()
    var choices: List<Choice> = emptyList()
    lateinit var sheet: View

    val adapter = object : BaseAdapter() {
        override fun getCount() = choices.size
        override fun getItem(position: Int) = choices[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = convertView as? CellView ?: CellView(this@openPicker, scale, palette).apply {
                layoutParams = AbsListView.LayoutParams(scale.cell, scale.cell)
                setOnClickListener {
                    (tag as? Choice)?.let { onPick(it.ref) }
                    overlays.dismiss(sheet)
                }
            }
            val choice = choices[position]
            cell.clear()
            cell.tag = choice
            cell.isClickable = true
            cell.label = if (choice.match) getString(R.string.match) else choice.label
            cell.highlighted = choice.match
            val ref = choice.ref
            if (ref.pack != null) loadInto(cell, "pick:${ref.pack}:${ref.name}") { icons.pack(ref.pack)?.drawable(ref.name) }
            else loadInto(cell, "pick:system:${ref.name}") { apps[ref.name]?.let(icons::base) }
            return cell
        }
    }
    val maxRows = maxOf(1, (resources.displayMetrics.heightPixels / PHI / (scale.cell + scale.rowGap)).toInt())
    val grid = GridView(this).apply {
        id = R.id.picker_grid
        numColumns = COLUMNS
        columnWidth = scale.cell
        verticalSpacing = scale.rowGap
        stretchMode = GridView.NO_STRETCH
        gravity = Gravity.CENTER_HORIZONTAL
        selector = ColorDrawable(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        this.adapter = adapter
    }
    val search = searchField()
    val chipRow = LinearLayout(this).apply {
        id = R.id.picker_chips
        orientation = LinearLayout.HORIZONTAL
    }

    // the sheet fits its content: as many rows as there are icons, up to 1/phi of the screen
    fun show(result: List<Choice>) {
        choices = result
        val rows = ((result.size + COLUMNS - 1) / COLUMNS).coerceIn(1, maxRows)
        grid.layoutParams = grid.layoutParams.apply { height = scale.rowsHeight(rows) }
        adapter.notifyDataSetChanged()
    }

    fun reload() {
        val query = search.text.toString().trim().lowercase()
        val src = source
        if (src == null) {
            show(
                apps.values.map { Choice(IconRef(null, it.key), labelOf(it.key), false) }
                    .filter { query.isEmpty() || it.label.lowercase().contains(query) }
                    .sortedBy { it.label.lowercase() },
            )
            return
        }
        worker.execute {
            val pack = icons.pack(src.pkg)
            val match = if (forFolder) FOLDER_DRAWABLE.takeIf { pack?.hasFolder == true } else forKey?.let { pack?.match(it) }
            val needle = query.replace(' ', '_')
            val names = pack?.names.orEmpty().filter { it != match && (needle.isEmpty() || it.contains(needle)) }
            val result = listOfNotNull(match?.let { Choice(IconRef(src.pkg, it), it, true) }) +
                names.map { Choice(IconRef(src.pkg, it), it.replace('_', ' '), false) }
            main.post { if (source == src) show(result) }
        }
    }

    fun renderChips() {
        chipRow.removeAllViews()
        (packs + listOf<PackInfo?>(null)).forEachIndexed { i, pack ->
            val chip = chip(scale, palette, pack?.label ?: getString(R.string.system_icons), pack == source).apply {
                setOnClickListener {
                    source = pack
                    renderChips()
                    reload()
                }
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, scale.chip).apply { if (i > 0) leftMargin = scale.tight })
        }
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // the weight lets the grid give up height above the keyboard, so chips and search stay visible
        addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, scale.cell, 1f))
        addView(
            HorizontalScrollView(this@openPicker).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipRow)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, scale.chip).apply { topMargin = scale.gutter },
        )
        addView(search, LinearLayout.LayoutParams(MATCH_PARENT, scale.row).apply { topMargin = scale.gutter })
    }
    renderChips()
    search.onChange(::reload)
    reload()
    sheet = overlays.sheet(content, grid)
    // take focus from the edit sheet's name field, so an open keyboard searches icons instead of renaming
    search.post { search.requestFocus() }
}

// --- settings sheet ---

fun LauncherActivity.openSettings() {
    val refreshers = mutableListOf<() -> Unit>()
    val refresh = { refreshers.forEach { it() } }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun header(title: Int) = list.addView(
        label(scale, palette.accent, getString(title)).apply { setPadding(0, if (list.childCount == 0) 0 else scale.gutter, 0, scale.tight) },
        LinearLayout.LayoutParams(MATCH_PARENT, (if (list.childCount == 0) 0 else scale.gutter) + scale.line + scale.tight),
    )

    fun row(title: Int, value: () -> String, onClick: ((View) -> Unit)?) {
        val valueView = label(scale, palette.accent, value())
        refreshers += { valueView.text = value() }
        list.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(scale, palette.fg, getString(title)), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
                if (onClick != null) {
                    foreground = selectable()
                    setOnClickListener {
                        onClick(it)
                        refresh()
                    }
                }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, scale.row),
        )
    }

    // a setting with a few values: tapping the row pops up all of them, the current one marked
    fun <T> choice(title: Int, options: () -> List<T>, current: () -> T, name: (T) -> String, pick: (T) -> Unit) =
        row(title, { name(current()) }) { anchor ->
            overlays.menu(
                options().map { option ->
                    MenuEntry(name(option), selected = option == current()) {
                        pick(option)
                        refresh()
                    }
                },
                anchor,
            )
        }

    fun onOff(title: Int, current: () -> Boolean, pick: (Settings, Boolean) -> Settings) =
        choice(title, { listOf(true, false) }, current, { getString(if (it) R.string.on else R.string.off) }) { on ->
            commit(layout.withSettings { pick(it, on) })
        }

    header(R.string.section_icons)
    choice(
        R.string.icon_pack,
        { listOf<String?>(null) + packs.map { it.pkg } },
        { layout.settings.iconPack },
        { pkg -> packs.firstOrNull { it.pkg == pkg }?.label ?: getString(R.string.none) },
    ) { pkg -> commit(layout.withSettings { it.copy(iconPack = pkg) }) }
    onOff(R.string.show_labels, { layout.settings.showLabels }) { s, on -> s.copy(showLabels = on) }
    choice(
        R.string.background,
        { listOf(false, true) },
        { layout.settings.wallpaper },
        { getString(if (it) R.string.background_wallpaper else R.string.background_black) },
    ) { on -> commit(layout.withSettings { it.copy(wallpaper = on) }) }
    onOff(R.string.drawer_animation, { layout.settings.animateDrawer }) { s, on -> s.copy(animateDrawer = on) }
    onOff(R.string.hide_placed, { layout.settings.hidePlaced }) { s, on -> s.copy(hidePlaced = on) }
    row(R.string.colors, { getString(if (palette.isDynamic) R.string.colors_dynamic else R.string.colors_fixed) }, null)
    header(R.string.section_layout)
    // null is auto: as many rows as fit. fixed counts never exceed that.
    choice(
        R.string.home_grid,
        { listOf<Int?>(null) + ROW_CHOICES.filter { it <= fittingRows() } },
        { layout.settings.run { if (rowsAuto) null else rows } },
        { rows -> if (rows == null) getString(R.string.grid_size_auto, COLUMNS, fittingRows()) else getString(R.string.grid_size, COLUMNS, rows) },
    ) { rows -> commit(layout.withSettings { it.copy(rowsAuto = rows == null) }.withRows(rows ?: fittingRows())) }
    choice(R.string.dock_slots, { DOCK_CHOICES.toList() }, { layout.settings.dockSlots }, { it.toString() }) { slots ->
        commit(layout.withDockSlots(slots))
    }
    header(R.string.section_backup)
    row(R.string.export_layout, { "" }) { exportLayout() }
    row(R.string.import_layout, { "" }) { importLayout() }

    val scroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(list)
    }
    overlays.sheet(scroll, scroll)
}

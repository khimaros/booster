# design

## shape

one activity, plain android views, no androidx. the only dependency is the
kotlin standard library.

```
LauncherActivity     lifecycle, state, rendering, actions, drag and drop
  HomeView           Pager of CellGrid pages, PageLine, dock, drop targets
  DrawerView         GridView + bottom search field
  Overlays           stack of modal layers: menu, bottom sheet, folder card
Sheets.kt            edit sheet, icon picker sheet, settings sheet
CellView             one cell, drawn directly: icon, label, badge, highlight
Model.kt             immutable Layout and pure functions that change it
Json.kt, Store.kt    layout <-> json, atomic file persistence
PinActivity          confirms another app's request to pin a shortcut
Apps.kt              launchable apps and pinned shortcuts, first-run dock
IconPacks.kt         icon pack discovery and appfilter.xml / drawable.xml parsing
Icons.kt             icon resolution, lru cache, background loading
Scale.kt, Palette.kt golden ratio scale, dynamic colors
```

## state

`Layout` is the whole persisted state: pages of slots, dock slots, drawer
folders, hidden apps, per-app overrides (label, icon), and settings. it is
immutable. every user action is a pure function `Layout -> Layout` in
`Model.kt`; `LauncherActivity.commit` swaps it in, re-renders, and writes the
json on a worker thread with `AtomicFile`.

apps are keyed `package/fully.qualified.Activity`. icon pack components are
normalized to the same form. an `IconRef` is either a pack drawable
(`pack`, `name`) or another app's icon (`pack = null`, `name = app key`).

slots name where an item lives (`HomeSlot`, `DockSlot`, `FolderSlot`,
`DrawerSlot`) and are the source and target of every move, so drag and drop
and the long-press menus share one implementation (`Layout.drop`,
`Layout.remove`, folder functions).

folders: a home or dock folder left with one app collapses into that app; a
drawer folder left with one app dissolves back into the drawer. apps in a
drawer folder are hidden from the drawer's top level but still searchable.

the drawer's order is `Layout.drawerOrder`, app keys and folder ids. empty
means the natural order, folders then apps, each by name. `Layout.arranged`
merges it with the entries that exist now, so stale ids drop out and new apps
join at the end; hidden entries keep their place for when they return.

folder stacks: `stackReels` turns the same drawer entries into what each slot
steps through. `DrawerView` keeps a position in pages and two `CellGrid`s, the
page on show and the next on a black sheet translated over it, so `bind`, menus and
drags need no cases of their own. a swipe moves one page at most and settles
through `passed` and `settleMs`, the drawer's own rule and timing. a stacked
app's slot is its `FolderSlot`, so it has the
folder's menu; with no card open, dragging it closes the drawer. the position
stays when the keyboard opens (`stackSlots` ignores it) and resets on closing.

pinned shortcuts: the system owns which shortcuts are pinned to the default
launcher. `PinActivity` handles `CONFIRM_PIN_SHORTCUT`, shows a sheet over the
requesting app and calls `PinItemRequest.accept()`; it never touches the
layout, which may live in an activity that is not running. instead
`LauncherApps.Callback.onShortcutsChanged`, and every load, reconciles:
a pinned shortcut the layout does not know yet goes to the first free home
slot (`placeShortcuts`), and `commit` unpins any that left the layout, or
they would return as new. a shortcut is an `AppItem` keyed
`package/shortcut:id` and an `AppInfo` with `shortcut` set, so folders,
overrides, the edit sheet and uninstall pruning need no special cases. only
`appList` (the drawer) leaves them out.

the default layout is an ordinary export at `app/src/main/assets/defaults.json`,
read by "import defaults" and pruned of apps the device lacks. debug builds use
the small sample in `app/src/debug/assets/`, so the suite does not depend on
the real one.

## icons

resolution, in order: the item's override, the global pack's appfilter match,
the app's own icon. folders: override, the global pack's `folder` drawable,
the built-in folder icon. a folder with a custom icon gets a small badge with
the default folder icon.

an `IconRef` from a pack is a stored name, resolved at draw time by `Icons.ref`:
the selected pack, its own pack, other installed packs, then `matchingApp`. the
layout is never rewritten, so a pack change or removal loses nothing, and the
lru key already carries the selected pack.

packs are found by the adw / nova / go intent actions, which arcticons also
registers. `appfilter.xml` and `drawable.xml` are read from `res/xml` or
`assets`, lazily and off the main thread. `calendar` entries resolve to
today's date.

drawables are resolved on a two-thread pool and cached as `ConstantState` in
an lru cache; every view gets its own instance. a cell ignores a late result
if it has since been rebound (`iconKey`).

## visual system

every dimension comes from `Scale`: `p(n) = base * phi^(n - 2)`. the base is
derived from the screen width so that four `p(6)` cells and two `p(3)`
gutters fill it exactly (`base = width / 30.65`), capped at 16dp for tablets.
text is `p(2)` on a `p(3)` line; chips are `p(4)` tall; rows, fields and the
search bar are `p(5)`; grid cells are `p(6)` squares; menus are `p(8)` wide.

colors come from `Palette`: android 12+ system tonal palette roles, or a
fixed fallback. the background is black unless the wallpaper option is on.

## input

- `Pager` scrolls horizontally with snapping and reports long presses on
  empty space. it does not delay child pressed state, so cell long presses
  fire on time.
- `HomeView` recognizes an upward swipe both while intercepting (a cell or
  the pager owns the touch) and in its own `onTouchEvent` (bare home space),
  then reports the drag distance to `DrawerView.follow` and the release
  velocity to `DrawerView.release`. `DrawerView` does the same for a downward
  swipe while its grid is at the top. both settle with one rule: past 1/phi^2
  of the height or a fling completes, anything less falls back. with
  `animateDrawer` off the drawer jumps straight to open or closed. a closed
  drawer is INVISIBLE, not GONE, so it stays measured for the first drag.
- `HomeView` flips pages when a drag hovers near an edge.
- `PageLine` sits in the gutter between the grid and the dock, so it costs
  the grid no space. it follows `Pager.onScrolled`, stays up while a finger
  pages, and fades out shortly after paging stops.
- every view shares one set of drag rules. `CellView.landing` turns a point
  into a `Drop`: the outer quarters of a cell go beside it, the middle lands
  on it (`canLand` decides if it may), and an empty cell takes the drag.
  `DropMarks` draws the result, always marking a gap on the cell after it, so
  a gap is one area with one bar however it is approached. `Layout.drop`,
  `dropInFolder` and the drawer's order functions then apply it. home and dock
  rows have fixed slots, so `inserted` makes room by closing a gap: the one the
  item left, or else the nearest.
- dragging from an open folder keeps the card open, and the drawer under a
  drawer folder, so its cells can be rearranged (`Layout.dropInFolder`). the
  card itself accepts the drag, so its padding and row gaps count as inside.
  leaving the card dismisses it (and closes the drawer) so the app can be
  dropped on home or the dock.
- a drag in the drawer stays in the drawer, unless it started from search
  results. the `GridView` holds one drag listener, because cells it recycles
  during a scroll would miss the drag's start. `DrawerView.dropAt` turns a
  point into an entry and a `Drop`. the rest follows the shared rules below.
  `zoneHome` covers the search bar during the drag; entering it closes the
  drawer, so the rest of the drag lands on home cells, which had the drag
  from its start because `startDrag` always shows the home drop targets.
- `onNewIntent` treats home as "already home" only with window focus and
  without `FLAG_ACTIVITY_BROUGHT_TO_FRONT`, as aosp launcher3 does, so
  returning from an app never counts as a press. after leaving for an app,
  `onResume` closes an open folder and drawer and returns to the first page
  unless `rememberDrawer` is on. a stop with the screen off is not leaving.
- settings with a few values open a menu of all of them, the current one an
  accent band (`MenuEntry.selected`). menu rows run edge to edge and the menu
  clips them to its corners, so the band needs no shape of its own.
- long press shows a menu and arms a drag; moving past the touch slop
  dismisses the menu and starts a platform drag (`startDragAndDrop`). home
  and dock cells and the three drop targets are the drop listeners.
- sheets follow a downward swipe and dismiss past 1/phi^2 of their height.

## tests

`e2e/` drives a dedicated emulator (`booster_e2e`, console port 5578) on its
own adb server (port 5056, pinned to a sentinel serial so it never claims
usb devices). identity is asserted by avd name before use, an advisory flock
guards concurrent runs, and only an emulator the run booted is shut down.
the suite reads the saved layout through `run-as` to check effects, and uses
`testpack/`, a minimal adw-format icon pack, as the icon pack fixture, and
`testpin/`, a small app that requests a pinned shortcut like a browser does.

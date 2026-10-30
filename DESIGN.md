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
Apps.kt              launchable apps via LauncherApps, first-run dock
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

## icons

resolution, in order: the item's override, the global pack's appfilter match,
the app's own icon. folders: override, the global pack's `folder` drawable,
the built-in folder icon. a folder with a custom icon gets a small badge with
the default folder icon.

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
- dragging from an open folder keeps the card open, and the drawer under a
  drawer folder: cells in it reorder (`Layout.moveInFolder`). the card itself
  accepts the drag, so its padding and row gaps count as inside. leaving the
  card dismisses it (and closes the drawer) so the app can be dropped on home
  or the dock.
- `onNewIntent` treats home as "already home" only with window focus and
  without `FLAG_ACTIVITY_BROUGHT_TO_FRONT`, as aosp launcher3 does: returning
  from an app keeps the drawer, its search and scroll as they were; pressing
  home again closes it.
- settings with a few values open a menu of all of them, the current one an
  accent pill (`MenuEntry.selected`).
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
`testpack/`, a minimal adw-format icon pack, as the icon pack fixture.

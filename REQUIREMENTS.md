# requirements

booster is a simple, lightweight, performant android home screen launcher.

## platform

- native kotlin with plain android views. no jetpack compose.
- minimal external dependencies. small apk, fast cold start.
- has its own app icon and launcher entry, so it can be opened from another
  home screen.

## layout

- paged home screen grid holding apps and folders.
- a dock at the bottom of every page holding apps and folders.
- the page indicator is a thin horizontal line in the gutter above the dock,
  shown only while switching pages, so it takes no space from the grid.
- plain black background by default. the system wallpaper is opt-in.
- accents and surfaces use the android 12+ dynamic system colors, with a
  fixed fallback palette on older versions.
- a searchable app drawer listing every launchable app as one plain grid
  sorted by name, with no alphabet headers or index.
- solid black strips behind the status bar and behind the search bar (search
  plus a gutter above and below) hide drawer icons scrolling under them.
- folders can live on the home grid, in the dock, and in the app drawer.
- apps in a drawer folder are listed inside that folder rather than at the
  top level of the drawer. drawer search still finds them.

## visual system

- every size, gap, radius, and font size comes from one golden ratio scale:
  `unit * phi^n`. no ad hoc values.
- consistent spacing: one screen gutter, one inner gap, one tight gap, used
  the same way on every screen.
- the home grid fills the space above the dock: by default it has as many rows
  as fit ("auto"); a fixed row count can be chosen but never exceeds what fits.
- within a cell a label sits phi^0 under its icon, and rows sit phi^2 apart, so
  a label is clearly nearer its own icon than the icon below.
- every icon grid (home, drawer, folders, pickers) uses the same square cell
  (phi^6 units). the unit is derived from the screen width so that four
  cells plus two gutters exactly fill it.
- controls have scale heights: chips phi^4, rows / fields / search phi^5.
- the dock has no background; it is a row of icons.
- bottom sheets fit their content; no empty filler space.
- a setting with several values opens a popup listing all of them, with the
  current one clearly marked (an accent pill), instead of cycling on tap.

## gestures

- swiping up from anywhere on the home screen (including over icons and the
  dock) opens the drawer. the drawer follows the finger and settles open or
  closed on release; swiping down closes it the same way.
- the drawer animation is an option, on by default. off, the drawer opens and
  closes at once.
- launching an app leaves the drawer open: returning to the launcher finds it
  exactly as it was (search text, scroll). pressing home while already on the
  launcher closes it.

## organizing

- move apps and folders between the grid, the dock, and the drawer.
- create a folder by dropping one app onto another.
- add to, remove from, rename, and dissolve folders.
- reorder apps inside a folder by dragging them within the open folder. the
  drag may cross the card's padding and gaps; only leaving the card closes it
  (and the drawer, for a drawer folder) to drop on home or the dock. while
  reordering a drawer folder, the drawer stays open behind it.
- the "add to folder" chooser lists folders alphabetically, then "new
  folder", and scrolls when there are many. in app menus it sits just above
  "uninstall".
- folder names are unique: new folders are named "folder", "folder 2", and
  renaming to a name already in use is refused.
- dragging an app out of a drawer folder onto home or the dock places a
  shortcut and keeps it in the folder; home and dock folders give it up.
- a drawer folder can be added to home or the dock from its long-press menu.
  it is the same folder there (a link), not a copy: edits show everywhere,
  removing it from home only removes the link, dissolving it removes its links.
- apps inside a drawer folder offer "add to home" and "add to dock" too.
- setting "hide home apps from drawer" (off by default) leaves apps that are on home or
  the dock, including inside their folders, out of the drawer and drawer
  folders. search still finds them.
- customizations (names, icons, folder order) are stored once and every view
  reflects them immediately, including an open folder.
- every drag action is also reachable from a long-press menu.
- interactive controls (search, drag drop targets, icon pack chips) sit
  toward the bottom of the screen, within thumb reach.
- every secondary pane (edit, icon picker, settings) is a bottom sheet over
  the home screen. no full-screen panes, title bars, or back arrows. swipe
  down, tap outside, or the system back gesture closes a sheet.

## customization

- override the icon of any app or folder (home, dock, and drawer folders).
- a folder always shows one assigned icon, never a preview of its contents.
  new folders default to the icon pack's folder icon.
- folders have no tile or background behind their icon. a folder using a
  custom icon carries a small folder badge so it is not mistaken for an app.
- apps and folders share one edit sheet: tap the icon to open the icon
  picker, edit the name, reset, done (folders also: dissolve).
- the edit sheet (including for a new folder) opens with the current name
  selected and the keyboard up, so typing replaces it.
- open folders are a proper grid in a card centered on screen: ceil(sqrt(n))
  columns, so 4, 9 or 16 apps make a square, and a partial last row starts at
  the left like any grid. no header; the card holds
  only the apps. folders are renamed from their long-press edit sheet.
- drawer search results show apps exactly like the grid, with no folder
  annotation.
- override the name (label) of any app or folder.
- icon sources:
  - installed icon packs (adw / nova / go style `appfilter.xml`).
    arcticons MUST be supported.
  - the icon of any other installed app, labeled "System" in the picker.
- apply one icon pack globally, with per-app overrides on top.
- reset any override back to the default.

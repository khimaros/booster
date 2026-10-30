# <img src="docs/icon.svg" width="40" align="top"> booster

lightweight android launcher where every icon and name is customizable.

<img src="docs/screenshots/1-home.png" width="160"> <img src="docs/screenshots/2-folder.png" width="160"> <img src="docs/screenshots/3-drawer.png" width="160">
<img src="docs/screenshots/4-edit.png" width="160"> <img src="docs/screenshots/5-picker.png" width="160"> <img src="docs/screenshots/6-settings.png" width="160">

## features

- paged home grid (4 columns; as many rows as fit, or a fixed 3 to 12) and a
  dock (3 to 6 slots).
- an app drawer as one plain grid, with the search bar at the bottom. apps
  can be hidden from the grid and stay findable by search.
- folders on the home grid, in the dock, and in the drawer. an open folder
  is a centered card holding a near-square grid.
- custom icons for apps and folders from any installed icon pack
  (adw / nova / go format, including arcticons) or from any other app
  ("System").
- custom names for apps and folders.
- one global icon pack, with per-item overrides on top.
- drag an app onto another to make a folder, onto a folder to add it, or onto
  the remove / edit / app info targets above the dock. every drag action is
  also in the long-press menus.
- android 12+ dynamic colors for accents and sheets; black background, with
  the system wallpaper as an option.
- export and import the whole layout as json.

## using it

| gesture | where | does |
|---|---|---|
| tap | app / folder | launch / open |
| long press | app / folder | menu; keep moving to drag instead |
| long press | empty home space | background, settings, add / remove page |
| swipe up | anywhere on home | open the drawer; it follows your finger |
| swipe down | drawer at top, any sheet | close it; it follows your finger |
| drag within | an open folder | reorder its apps |
| swipe sideways | home | change page |
| back or home | anywhere | close the top sheet, menu, folder or drawer |

launching an app from the drawer leaves it open, so going home returns you to
the drawer as you left it; press home again to close it. the page line above
the dock appears only while you switch pages.

to set an icon: long press an app or folder, choose "edit icon & name", then
tap the icon. pick from an installed pack, or from "System" for another
app's icon. "reset" restores the default.

to use an icon pack everywhere: long press empty home space, "launcher
settings", "icon pack", and pick from the popup. the same settings turn the
drawer animation off and can hide apps that are already on home or the dock
from the drawer ("hide home apps from drawer").

dragging an app out of a drawer folder puts a shortcut on home or the dock and
leaves the folder as it was. folder names are unique.

booster also has an ordinary app icon, so you can try it from your current
home screen before making it the default home app.

## installing

signed apks are attached to each [github release](../../releases), with a
sha256 alongside.

## building

requires [mise](https://mise.jdx.dev) for the pinned toolchain
(java, android sdk, python, ruff).

```
mise install
make            # debug apks: app and the test icon pack
make release    # minified release apk (VERSION=1.2.3 to set the version)
make screenshots # refresh docs/screenshots from the emulator
make test-e2e   # end-to-end suite on the project's own emulator
make precommit  # build, android lint, ruff
```

install with `adb install app/build/outputs/apk/debug/app-debug.apk`, then
choose booster as the home app.

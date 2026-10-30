"""captures the screenshots in docs/screenshots from the real launcher: python3 -m e2e.screenshots [OUTDIR]"""

import os
import subprocess
import sys

from e2e import device as d

APK = "app/build/outputs/apk/debug/app-debug.apk"
OUTDIR = "docs/screenshots"


def shot(outdir, name):
    path = os.path.join(outdir, f"{name}.png")
    with open(path, "wb") as fh:
        fh.write(
            subprocess.run(
                [
                    d.ADB,
                    "-P",
                    d.ADB_PORT,
                    "-s",
                    d.SERIAL,
                    "exec-out",
                    "screencap",
                    "-p",
                ],
                capture_output=True,
                check=True,
            ).stdout
        )
    return path


def add_to_home(label):
    d.swipe_up()
    d.long_press(d.find(desc=label, within="drawer_grid"))
    d.choose("add to home")
    d.back()
    return d.find(desc=label, within="pager")


def main(outdir):
    os.makedirs(outdir, exist_ok=True)
    emulator = d.Emulator()
    emulator.start()
    try:
        d.install(os.environ.get("BOOSTER_APK", APK))
        d.reset_launcher()
        d.find(desc="Phone", within="dock")
        for label in ("Calendar", "Gallery", "Files", "Clock", "Contacts"):
            add_to_home(label)
        d.long_press(d.find(desc="Clock", within="pager"))
        d.choose("add to folder")
        d.choose("new folder")
        d.find(rid="sheet_name")
        d.choose("done")
        d.long_press(d.find(desc="Contacts", within="pager"))
        d.choose("add to folder")
        d.choose("folder")
        d.find(desc="folder", within="pager")
        shot(outdir, "1-home")
        d.tap(d.find(desc="folder", within="pager"))
        d.find(rid="folder_card")
        shot(outdir, "2-folder")
        d.back()
        d.swipe_up()
        d.find(rid="search")
        shot(outdir, "3-drawer")
        d.back()
        d.long_press(d.find(desc="Settings", within="dock"))
        d.choose("edit icon & name")
        d.find(rid="sheet")
        shot(outdir, "4-edit")
        d.tap(d.find(rid="sheet_icon"))
        d.find(rid="picker_grid")
        shot(outdir, "5-picker")
        # the keyboard, the picker and the edit sheet each take one back
        while d.find_all(rid="sheet"):
            d.back()
        pager = d.find(rid="pager")
        left, _top, right, bottom = pager.bounds
        d.long_press(d.Node("", "", "", (left, bottom - 200, right, bottom - 100)))
        d.choose("launcher settings")
        d.tap(d.find(text="home grid"))
        d.find(rid="menu")
        shot(outdir, "6-settings")
    finally:
        emulator.stop()


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else OUTDIR)

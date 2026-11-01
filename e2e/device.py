"""drives this project's own emulator over adb for the end-to-end suite.

one emulator per project, on its own adb server and console port (the
`booster-emulator` lane in the android-adb skill's lanes.json), so nothing here
can land on another project's device. identity is asserted by avd name before any
command that changes state, and only an emulator this run booted is shut down.

the run holds the skill's device lock so other agents can see who is driving the
emulator. that lock is advisory: it binds only agents that take it.
"""

import contextlib
import fcntl
import glob
import importlib.util
import itertools
import json
import os
import re
import subprocess
import time
from dataclasses import dataclass
from xml.etree import ElementTree

AVD = "booster_e2e"
ADB_PORT = "5056"
CONSOLE_PORT = "5578"
SERIAL = f"emulator-{CONSOLE_PORT}"
SERVER_SENTINEL = "BOOSTER_EMULATOR_ONLY"
SYSTEM_IMAGE = "system-images;android-35;default;x86_64"
DEVICE_PROFILE = "pixel_6"
DEVICE_LOCK = os.environ.get(
    "ANDROID_DEVICE_LOCK",
    os.path.expanduser("~/.claude/skills/android-adb/device_lock.py"),
)
LOCK_WHO = "booster-e2e"
LOCK_WAIT = 600
PACKAGE = "com.khimaros.booster"
ACTIVITY = f"{PACKAGE}/.LauncherActivity"
TESTPACK = "com.khimaros.booster.testpack"
TESTPIN = "com.khimaros.booster.testpin"
BOOT_TIMEOUT = 300
UI_TIMEOUT = 10
DUMP_TIMEOUT = 30
LONG_PRESS_MS = 900
DRAG_STEPS = 8
DRAG_SETTLE_MS = 400
ICON_SAMPLE_RADIUS = 40


def sdk_root():
    root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not root:
        root = subprocess.run(
            ["mise", "where", "android-sdk"], capture_output=True, text=True, check=True
        ).stdout.strip()
    return root


SDK = sdk_root()
# avdmanager writes under $XDG_CONFIG_HOME while the emulator reads ~/.android; pin both to one place
os.environ.setdefault(
    "ANDROID_AVD_HOME",
    os.path.join(
        os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")),
        ".android",
        "avd",
    ),
)
ADB = os.path.join(SDK, "platform-tools", "adb")
EMULATOR = os.path.join(SDK, "emulator", "emulator")
AVDMANAGER = max(
    glob.glob(os.path.join(SDK, "cmdline-tools", "*", "bin", "avdmanager"))
)


def run(*args, check=True, timeout=60):
    return subprocess.run(
        args, capture_output=True, text=True, check=check, timeout=timeout
    )


def adb(*args, check=True, timeout=60):
    return run(
        ADB, "-P", ADB_PORT, "-s", SERIAL, *args, check=check, timeout=timeout
    ).stdout


def shell(*args, check=True):
    return adb("shell", *args, check=check)


@dataclass
class Node:
    desc: str
    text: str
    rid: str
    bounds: tuple
    ancestors: tuple = ()
    selected: bool = False

    @property
    def center(self):
        left, top, right, bottom = self.bounds
        return (left + right) // 2, (top + bottom) // 2

    @property
    def width(self):
        return self.bounds[2] - self.bounds[0]


def device_lock():
    """the machine-wide lock from the android-adb skill when it is installed, so
    `device_lock.py list` names this run as the holder. elsewhere, a private flock
    on the same file the skill would use."""
    if os.path.exists(DEVICE_LOCK):
        spec = importlib.util.spec_from_file_location("device_lock", DEVICE_LOCK)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.hold(
            SERIAL, "booster e2e suite", timeout_s=LOCK_WAIT, who=LOCK_WHO
        )
    return private_lock()


@contextlib.contextmanager
def private_lock():
    with open(f"/tmp/android-device-{SERIAL}.lock", "a") as fh:
        try:
            fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as e:
            raise RuntimeError(f"{SERIAL} is held by another run") from e
        yield


def server_pin():
    """the serial the lane's server is pinned to, read from its command line.

    `start-server` against an existing server is a silent no-op, so the server that
    answers may be somebody's unrestricted one; only its cmdline says which."""
    for line in run("ps", "-eo", "args").stdout.splitlines():
        if line.startswith(f"adb -L tcp:{ADB_PORT} "):
            return (
                line.split("--one-device", 1)[1].split()[0]
                if "--one-device" in line
                else None
            )
    return None


class Emulator:
    """boots or reuses the project emulator and holds the device lock for the run."""

    def __init__(self):
        self.booted_here = False
        self.held = contextlib.ExitStack()

    def start(self):
        self.held.enter_context(device_lock())
        run(ADB, "-P", ADB_PORT, "--one-device", SERVER_SENTINEL, "start-server")
        pinned = server_pin()
        if pinned != SERVER_SENTINEL:
            raise RuntimeError(
                f"adb server on {ADB_PORT} is pinned to {pinned!r}, not {SERVER_SENTINEL!r}; refusing to use it"
            )
        name = self.avd_name()
        if name and name != AVD:
            raise RuntimeError(
                f"{SERIAL} is avd {name!r}, not {AVD!r}; refusing to touch it"
            )
        if not name:
            self.ensure_avd()
            self.boot()
        self.wait_booted()

    def stop(self):
        if self.booted_here and self.avd_name() == AVD:
            adb("emu", "kill", check=False)
        self.held.close()

    def avd_name(self):
        out = run(
            ADB, "-P", ADB_PORT, "-s", SERIAL, "emu", "avd", "name", check=False
        ).stdout
        return out.splitlines()[0].strip() if out.strip() else ""

    def ensure_avd(self):
        avds = run(EMULATOR, "-list-avds").stdout.split()
        if AVD in avds:
            return
        subprocess.run(
            [
                AVDMANAGER,
                "create",
                "avd",
                "-n",
                AVD,
                "-k",
                SYSTEM_IMAGE,
                "-d",
                DEVICE_PROFILE,
            ],
            input="no\n",
            text=True,
            check=True,
            capture_output=True,
        )

    def boot(self):
        # the emulator keeps its own copy of the log descriptor after we close ours
        with open(f"/tmp/{AVD}.log", "w") as log:
            subprocess.Popen(
                [
                    EMULATOR,
                    "-avd",
                    AVD,
                    "-port",
                    CONSOLE_PORT,
                    "-no-window",
                    "-no-audio",
                    "-no-boot-anim",
                    "-no-snapshot-save",
                ],
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
                # a booting emulator announces itself to this server rather than the default one on 5037
                env={**os.environ, "ANDROID_ADB_SERVER_PORT": ADB_PORT},
            )
        self.booted_here = True
        deadline = time.time() + BOOT_TIMEOUT
        while time.time() < deadline:
            name = self.avd_name()
            if name:
                if name != AVD:
                    raise RuntimeError(f"{SERIAL} came up as {name!r}, not {AVD!r}")
                return
            time.sleep(1)
        raise RuntimeError(f"{AVD} console never answered")

    def wait_booted(self):
        deadline = time.time() + BOOT_TIMEOUT
        while time.time() < deadline:
            if (
                run(
                    ADB,
                    "-P",
                    ADB_PORT,
                    "-s",
                    SERIAL,
                    "shell",
                    "getprop",
                    "sys.boot_completed",
                    check=False,
                ).stdout.strip()
                == "1"
            ):
                shell("settings", "put", "global", "window_animation_scale", "0")
                shell("settings", "put", "global", "transition_animation_scale", "0")
                shell("settings", "put", "global", "animator_duration_scale", "0")
                return
            time.sleep(2)
        raise RuntimeError(f"{AVD} did not finish booting")


def install(apk):
    adb("install", "-r", "-t", apk, timeout=180)


def uninstall(pkg):
    adb("uninstall", pkg, check=False)


def reset_launcher():
    """clears saved state and makes booster the running home screen."""
    shell("pm", "clear", PACKAGE)
    shell("cmd", "package", "set-home-activity", ACTIVITY)
    go_home()


def go_home():
    shell(
        "am",
        "start",
        "-W",
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.HOME",
    )


def wait_resumed(package, timeout=UI_TIMEOUT):
    deadline = time.time() + timeout
    while time.time() < deadline:
        top = [
            line
            for line in shell("dumpsys", "activity", "activities").splitlines()
            if "topResumedActivity" in line
        ]
        if top and package in top[0]:
            return
        time.sleep(0.3)
    raise AssertionError(f"{package} never resumed; screen at {evidence()}")


def screen_size():
    """the size in effect: `wm size` lists any override after the physical size."""
    width, height = re.findall(r"(\d+)x(\d+)", shell("wm", "size"))[-1]
    return int(width), int(height)


def layout_json():
    out = adb("exec-out", "run-as", PACKAGE, "cat", "files/layout.json", check=False)
    return json.loads(out) if out.strip().startswith("{") else None


def seed_layout(layout):
    """replaces the saved layout and restarts the launcher, for setups too slow to build by hand."""
    shell("am", "force-stop", PACKAGE)
    subprocess.run(
        [
            ADB,
            "-P",
            ADB_PORT,
            "-s",
            SERIAL,
            "shell",
            "run-as",
            PACKAGE,
            "sh",
            "-c",
            "'cat > files/layout.json'",
        ],
        input=json.dumps(layout),
        text=True,
        check=True,
        capture_output=True,
    )
    go_home()


def wait_layout(predicate, timeout=UI_TIMEOUT):
    deadline = time.time() + timeout
    data = None
    while time.time() < deadline:
        data = layout_json()
        if data is not None and predicate(data):
            return data
        time.sleep(0.3)
    raise AssertionError(f"layout never matched; last: {data}")


def dump():
    """the current ui hierarchy. uiautomator occasionally refuses for a few seconds; keep asking."""
    deadline = time.time() + DUMP_TIMEOUT
    out = ""
    while time.time() < deadline:
        result = run(
            ADB,
            "-P",
            ADB_PORT,
            "-s",
            SERIAL,
            "shell",
            "uiautomator",
            "dump",
            "/sdcard/ui.xml",
            check=False,
        )
        out = result.stdout + result.stderr
        if "dumped to" in out:
            xml = adb("exec-out", "cat", "/sdcard/ui.xml")
            return parse(xml)
        time.sleep(0.5)
    raise RuntimeError(f"uiautomator dump never succeeded: {out!r}; {evidence()}")


def parse(xml):
    """flattens the hierarchy, remembering each node's ancestor resource ids."""
    nodes = []

    def walk(el, ancestors):
        rid = el.get("resource-id", "")
        nums = [int(n) for n in re.findall(r"\d+", el.get("bounds", ""))]
        if len(nums) == 4:
            nodes.append(
                Node(
                    el.get("content-desc", ""),
                    el.get("text", ""),
                    rid,
                    tuple(nums),
                    ancestors,
                    el.get("selected") == "true",
                )
            )
        for child in el.findall("node"):
            walk(child, ancestors + ((rid,) if rid else ()))

    for top in ElementTree.fromstring(xml).findall("node"):
        walk(top, ())
    return nodes


def evidence():
    """saves a screenshot and the saved layout when a lookup fails, and returns the path."""
    path = f"/tmp/booster-e2e-{time.strftime('%H%M%S')}"
    with open(f"{path}.png", "wb") as fh:
        fh.write(
            subprocess.run(
                [ADB, "-P", ADB_PORT, "-s", SERIAL, "exec-out", "screencap", "-p"],
                capture_output=True,
                check=False,
            ).stdout
        )
    with open(f"{path}.json", "w") as fh:
        json.dump(layout_json(), fh, indent=2)
    return f"{path}.png"


def screencap():
    """the raw screen: a 16 byte header, then 4 bytes a pixel."""
    return subprocess.run(
        [ADB, "-P", ADB_PORT, "-s", SERIAL, "exec-out", "screencap"],
        capture_output=True,
        check=True,
    ).stdout


def pixel(x, y):
    """the rgb on screen at a point."""
    raw = screencap()
    width = int.from_bytes(raw[:4], "little")
    at = 16 + (y * width + x) * 4
    return tuple(raw[at : at + 3])


def icon_pixels(cell, radius=ICON_SAMPLE_RADIUS):
    """the middle of a cell's icon, which sits above its label, to compare with another cell's."""
    raw = screencap()
    width = int.from_bytes(raw[:4], "little")
    x = cell.center[0]
    middle = cell.bounds[1] + (cell.bounds[3] - cell.bounds[1]) * 2 // 5
    rows = range(middle - radius, middle + radius)
    return [
        raw[16 + (y * width + x - radius) * 4 : 16 + (y * width + x + radius) * 4]
        for y in rows
    ]


def rid_of(name):
    return f"{PACKAGE}:id/{name}"


def find_all(desc=None, text=None, rid=None, nodes=None, within=None):
    """matching nodes; `within` names a resource id that must be an ancestor in the view tree."""
    nodes = nodes if nodes is not None else dump()
    return [
        n
        for n in nodes
        if (desc is None or n.desc == desc)
        and (text is None or n.text == text)
        and (rid is None or n.rid == rid_of(rid))
        and (within is None or rid_of(within) in n.ancestors)
    ]


def find(desc=None, text=None, rid=None, timeout=UI_TIMEOUT, within=None):
    deadline = time.time() + timeout
    while time.time() < deadline:
        hits = find_all(desc, text, rid, within=within)
        if hits:
            return hits[0]
        time.sleep(0.3)
    raise AssertionError(
        f"no node desc={desc!r} text={text!r} rid={rid!r} within={within!r}; screen at {evidence()}"
    )


def absent(desc=None, text=None, rid=None, within=None, timeout=UI_TIMEOUT):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not find_all(desc, text, rid, within=within):
            return True
        time.sleep(0.3)
    raise AssertionError(
        f"node still present desc={desc!r} text={text!r} rid={rid!r} within={within!r}"
    )


def tap(node):
    x, y = node.center
    shell("input", "tap", str(x), str(y))


def choose(text, within=None):
    """taps a menu entry or sheet button and waits for it to go away.

    a click runs on the next ui thread pass, and an input event sent right after it
    (say, back) can be handled first and detach the view, cancelling the click."""
    tap(find(text=text, within=within))
    absent(text=text, within=within)


def long_press(node):
    x, y = node.center
    shell("input", "swipe", str(x), str(y), str(x), str(y), str(LONG_PRESS_MS))


def beside(node, fraction):
    """a point inside `node` at this fraction of its width, for drops that insert rather than join."""
    x = node.bounds[0] + int(node.width * fraction)
    y = node.center[1]
    return Node("", "", "", (x, y, x, y))


def drag(source, target, via=()):
    """press and hold past the long press, then move in steps through `via` and release, like a finger.

    `input draganddrop` starts moving exactly at the long press timeout, which races the
    view's own long press and usually cancels it.
    """
    points = [source.center, *via, target.center]
    shell("input", "motionevent", "DOWN", str(points[0][0]), str(points[0][1]))
    time.sleep(LONG_PRESS_MS / 1000)
    for (x1, y1), (x2, y2) in itertools.pairwise(points):
        for i in range(1, DRAG_STEPS + 1):
            x = x1 + (x2 - x1) * i // DRAG_STEPS
            y = y1 + (y2 - y1) * i // DRAG_STEPS
            shell("input", "motionevent", "MOVE", str(x), str(y))
    time.sleep(DRAG_SETTLE_MS / 1000)
    shell("input", "motionevent", "UP", str(points[-1][0]), str(points[-1][1]))


def wait_keyboard(timeout=UI_TIMEOUT):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if "mInputShown=true" in shell("dumpsys", "input_method"):
            return
        time.sleep(0.3)
    raise AssertionError(f"keyboard never shown; screen at {evidence()}")


def touch(action, x, y):
    """one raw pointer event, for gestures that must pause with the finger down."""
    shell("input", "motionevent", action, str(int(x)), str(int(y)))


def drag_and_hold(x, y_from, y_to, steps=DRAG_STEPS):
    """a vertical drag that leaves the finger down at the end."""
    touch("DOWN", x, y_from)
    for i in range(1, steps + 1):
        touch("MOVE", x, y_from + (y_to - y_from) * i / steps)


def swipe_up():
    w, h = screen_size()
    shell(
        "input", "swipe", str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 4), "200"
    )


def type_text(text):
    shell("input", "text", text.replace(" ", "%s"))


def back():
    shell("input", "keyevent", "KEYCODE_BACK")


def launcher_apps():
    """labels are not available over adb, so return launchable components."""
    out = shell(
        "cmd",
        "package",
        "query-activities",
        "--brief",
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.LAUNCHER",
    )
    return [line.strip() for line in out.splitlines() if "/" in line]

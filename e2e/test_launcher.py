"""end-to-end tests for the booster launcher, driven over adb on the project emulator."""

import os
import time
import unittest

from e2e import device as d

PHI = (1 + 5**0.5) / 2
SCREEN_BASES = 4 * PHI**4 + 2 * PHI
MAX_BASE_DP = 16

CLOCK = "com.android.deskclock/com.android.deskclock.DeskClock"
CONTACTS = "com.android.contacts/com.android.contacts.activities.PeopleActivity"
CALENDAR = "com.android.calendar/com.android.calendar.AllInOneActivity"
SETTINGS = "com.android.settings/com.android.settings.Settings"
DIALER = "com.android.dialer/com.android.dialer.main.impl.MainActivity"
PACK_LABEL = "Booster Test Pack"
TESTPIN_APK = "testpin/build/outputs/apk/debug/testpin-debug.apk"
PINNED = "Pinned Page"
MAGENTA = (255, 0, 255)
CYAN = (0, 255, 255)
COLOR_SLACK = 16
PINNED_KEY = f"{d.TESTPIN}/shortcut:page"

emulator = d.Emulator()


def setUpModule():
    emulator.start()
    d.install(
        os.environ.get("BOOSTER_APK", "app/build/outputs/apk/debug/app-debug.apk")
    )
    d.install(
        os.environ.get(
            "BOOSTER_TESTPACK_APK",
            "testpack/build/outputs/apk/debug/testpack-debug.apk",
        )
    )


def tearDownModule():
    emulator.stop()


def home_items(layout):
    """every item on every home page, flattened, empty slots dropped."""
    return [item for page in layout["pages"] for item in page if item]


def folders(layout):
    return [
        item
        for item in home_items(layout) + [i for i in layout["dock"] if i]
        if "folder" in item
    ]


class LauncherTest(unittest.TestCase):
    def setUp(self):
        d.reset_launcher()
        d.find(desc="Phone", within="dock")

    # --- helpers that drive the ui the way a person would ---

    def open_drawer(self):
        d.swipe_up()
        return d.find(rid="search")

    def add_to_home(self, label):
        self.open_drawer()
        d.long_press(d.find(desc=label, within="drawer_grid"))
        d.choose("add to home")
        d.back()
        return d.find(desc=label, within="pager")

    def menu(self, node, entry):
        d.long_press(node)
        d.choose(entry)

    def rename_in_sheet(self, name):
        d.tap(d.find(rid="sheet_name"))
        d.shell("input", "keyevent", "KEYCODE_MOVE_END", *["KEYCODE_DEL"] * 30)
        d.type_text(name)
        d.choose("done")

    def open_settings(self):
        pager = d.find(rid="pager")
        left, _top, right, bottom = pager.bounds
        d.long_press(d.Node("", "", "", (left, bottom - 200, right, bottom - 100)))
        d.choose("launcher settings")
        d.find(rid="sheet")

    # --- tests ---

    def test_first_run_fills_dock_with_system_defaults(self):
        layout = d.wait_layout(lambda l: any(l["dock"]))
        dock = [i["app"] for i in layout["dock"] if i]
        self.assertIn(DIALER, dock)
        self.assertIn(SETTINGS, dock)

    def test_other_home_screens_can_launch_booster_but_its_drawer_skips_it(self):
        self.assertTrue(
            any(a.startswith(d.PACKAGE + "/") for a in d.launcher_apps()),
            "booster has a launcher entry",
        )
        self.open_drawer()
        d.absent(desc="booster", within="drawer_grid")

    def test_grid_cells_follow_the_golden_ratio_scale(self):
        width, height = d.screen_size()
        density = int(d.shell("wm", "density").split()[-1]) / 160
        base = min(min(width, height) / SCREEN_BASES, MAX_BASE_DP * density)
        self.open_drawer()
        cell = d.find(desc="Clock", within="drawer_grid")
        self.assertAlmostEqual(cell.width, base * PHI**4, delta=1.5)
        dock = d.find(rid="dock")
        dock_height = dock.bounds[3] - dock.bounds[1]
        self.assertAlmostEqual(dock_height, base * PHI**3 + 2 * base * PHI, delta=2)

    def test_swipe_up_from_a_dock_icon_opens_the_drawer(self):
        phone = d.find(desc="Phone", within="dock")
        x, y = phone.center
        d.shell("input", "swipe", str(x), str(y), str(x), str(y // 3), "200")
        d.find(rid="search")
        resumed = [
            line
            for line in d.shell("dumpsys", "activity", "activities").splitlines()
            if "topResumedActivity" in line
        ]
        self.assertIn(d.PACKAGE, resumed[0], "the swipe must not launch the app")

    def test_drawer_follows_the_finger_and_falls_back_on_a_short_drag(self):
        width, height = d.screen_size()
        d.drag_and_hold(width // 2, height * 0.8, height * 0.6)
        drawer = d.find(rid="drawer")
        dragged = height * 0.2
        self.assertAlmostEqual(drawer.bounds[1], height - dragged, delta=height * 0.05)
        d.touch("UP", width // 2, height * 0.6)
        d.absent(rid="search")

    def test_slow_drag_from_mid_screen_opens_the_drawer(self):
        width, height = d.screen_size()
        d.drag_and_hold(width // 2, height * 0.5, height * 0.05, steps=16)
        d.touch("UP", width // 2, height * 0.05)
        drawer = d.find(rid="drawer")
        self.assertEqual(drawer.bounds[1], 0)

    def test_swipe_down_follows_the_finger_and_closes(self):
        self.open_drawer()
        width, height = d.screen_size()
        d.drag_and_hold(width // 2, height * 0.3, height * 0.5)
        drawer = d.find(rid="drawer")
        self.assertAlmostEqual(drawer.bounds[1], height * 0.2, delta=height * 0.05)
        d.touch("UP", width // 2, height * 0.5)
        d.find(rid="search")
        d.drag_and_hold(width // 2, height * 0.2, height * 0.8)
        d.touch("UP", width // 2, height * 0.8)
        d.absent(rid="search")

    def test_slow_swipe_down_starting_on_an_app_closes_the_drawer(self):
        """small steps, like a real finger: the grid must not claim the swipe first."""
        self.open_drawer()
        x, y = d.find(desc="Calendar", within="drawer_grid").center
        _, height = d.screen_size()
        d.drag_and_hold(x, y, y + height * 0.45, steps=90)
        d.touch("UP", x, y + height * 0.45)
        d.absent(rid="search")

    def test_drawer_opens_when_dragged_far_enough(self):
        width, height = d.screen_size()
        d.drag_and_hold(width // 2, height * 0.9, height * 0.4)
        d.touch("UP", width // 2, height * 0.4)
        drawer = d.find(rid="drawer")
        self.assertEqual(drawer.bounds[1], 0, "an open drawer fills the screen")

    def test_settings_choices_pop_up_with_the_current_one_marked(self):
        self.open_settings()
        d.tap(d.find(text="dock slots"))
        d.find(rid="menu")
        entries = {n.text: n.selected for n in d.find_all(within="menu") if n.text}
        self.assertEqual(entries, {"3": False, "4": False, "5": True, "6": False})
        d.choose("4", within="menu")
        d.wait_layout(lambda l: l["settings"]["dock_slots"] == 4)
        d.tap(d.find(text="dock slots"))
        self.assertTrue(d.find(text="4", within="menu").selected)
        d.back()
        d.absent(rid="menu")
        d.tap(d.find(text="home grid"))
        d.find(rid="menu")
        auto = [n for n in d.find_all(within="menu") if "(auto)" in n.text]
        self.assertEqual(len(auto), 1)
        self.assertTrue(auto[0].selected, "auto rows are the default")

    def test_drawer_animation_can_be_turned_off(self):
        self.open_settings()
        d.tap(d.find(text="drawer animation"))
        d.choose("off", within="menu")
        d.wait_layout(lambda l: l["settings"]["animate_drawer"] is False)
        d.back()
        d.absent(rid="sheet")
        width, height = d.screen_size()
        d.drag_and_hold(width // 2, height * 0.8, height * 0.6)
        drawer = d.find(rid="drawer")
        self.assertEqual(
            drawer.bounds[1], 0, "without animation the drawer opens at once"
        )
        d.touch("UP", width // 2, height * 0.6)
        d.find(rid="search")

    def test_page_line_shows_only_while_switching_pages(self):
        pager = d.find(rid="pager")
        left, _top, right, bottom = pager.bounds
        d.long_press(d.Node("", "", "", (left, bottom - 200, right, bottom - 100)))
        d.choose("add page")
        d.absent(rid="page_line")
        y = (pager.bounds[1] + bottom) // 2
        d.touch("DOWN", left + 100, y)
        for x in range(left + 100, right - 100, 40):
            d.touch("MOVE", x, y)
        line = d.find(rid="page_line")
        dock = d.find(rid="dock")
        d.touch("UP", right - 100, y)
        self.assertLess(line.bounds[3] - line.bounds[1], dock.width / PHI**6)
        self.assertGreaterEqual(line.bounds[1], bottom, "the line takes no grid space")
        self.assertLessEqual(line.bounds[3], dock.bounds[1])
        d.absent(rid="page_line")

    def test_grid_rows_have_a_gap_between_them(self):
        """rows sit a phi^2 gap apart, so a label is clearly nearer its own icon than the next row's."""
        self.open_drawer()
        cells = [
            n for n in d.dump() if n.desc and d.rid_of("drawer_grid") in n.ancestors
        ]
        tops = sorted({n.bounds[1] for n in cells})
        cell = cells[0].width
        self.assertGreaterEqual(len(tops), 2)
        self.assertAlmostEqual(tops[1] - tops[0], cell + cell / PHI**4, delta=2)

    def test_drawer_search_filters_by_label(self):
        d.tap(self.open_drawer())
        d.type_text("clo")
        d.find(desc="Clock", within="drawer_grid")
        d.absent(desc="Camera", within="drawer_grid")

    def test_search_bar_sits_on_a_solid_strip_that_hides_scrolled_icons(self):
        search = self.open_drawer()
        band = d.find(rid="search_band")
        cell = d.find(desc="Clock", within="drawer_grid").width
        _, height = d.screen_size()
        self.assertAlmostEqual(
            band.bounds[1], search.bounds[1] - cell / PHI**3, delta=2
        )
        self.assertEqual(band.bounds[3], height, "the strip reaches the bottom edge")

    def test_status_bar_sits_on_a_solid_strip_in_the_drawer(self):
        self.open_drawer()
        band = d.find(rid="status_band")
        first = d.find(desc="Calendar", within="drawer_grid")
        self.assertEqual(band.bounds[1], 0, "the strip starts at the top edge")
        cell = first.width
        self.assertAlmostEqual(band.bounds[3], first.bounds[1] - cell / PHI**3, delta=2)

    def test_search_results_start_left_and_hug_the_search_bar(self):
        search = self.open_drawer()
        d.tap(search)
        d.type_text("clo")
        d.absent(desc="Camera", within="drawer_grid")
        clock = d.find(desc="Clock", within="drawer_grid")
        search = d.find(rid="search")
        width, _ = d.screen_size()
        first_column = (width - 4 * clock.width) / 2
        self.assertAlmostEqual(
            clock.bounds[0],
            first_column,
            delta=2,
            msg="best match sits in the first column",
        )
        gutter = clock.width / PHI**3
        self.assertLessEqual(
            search.bounds[1] - clock.bounds[3],
            gutter + 2,
            "results sit right above the search bar",
        )

    def test_closing_the_drawer_resets_its_search(self):
        self.open_drawer()
        d.tap(d.find(rid="search"))
        d.type_text("cal")
        d.absent(desc="Clock", within="drawer_grid")
        d.back()
        d.back()
        d.absent(rid="search")
        self.open_drawer()
        d.find(desc="Clock", within="drawer_grid")
        self.assertNotEqual(d.find(rid="search").text, "cal")

    def test_returning_from_an_app_finds_home_by_default(self):
        self.open_drawer()
        d.tap(d.find(rid="search"))
        d.type_text("cal")
        d.tap(d.find(desc="Calendar", within="drawer_grid"))
        d.wait_resumed("com.android.calendar")
        d.shell("input", "keyevent", "KEYCODE_HOME")
        d.wait_resumed(d.PACKAGE)
        d.absent(rid="search")
        self.open_drawer()
        self.assertNotEqual(d.find(rid="search").text, "cal")

    def test_returning_from_an_app_finds_the_drawer_as_it_was_left(self):
        self.open_settings()
        d.tap(d.find(text="remember drawer"))
        d.choose("on", within="menu")
        d.wait_layout(lambda l: l["settings"]["remember_drawer"] is True)
        d.back()
        d.absent(rid="sheet")
        self.open_drawer()
        d.tap(d.find(rid="search"))
        d.type_text("cal")
        d.tap(d.find(desc="Calendar", within="drawer_grid"))
        d.wait_resumed("com.android.calendar")
        d.shell("input", "keyevent", "KEYCODE_HOME")
        d.find(text="cal", rid="search")
        d.find(desc="Calendar", within="drawer_grid")
        d.shell("input", "keyevent", "KEYCODE_HOME")
        d.absent(rid="search")

    def test_drawer_has_no_letter_headers(self):
        self.open_drawer()
        nodes = d.dump()
        letters = [n for n in nodes if len(n.text) == 1 and n.text.isalpha()]
        self.assertEqual(letters, [])

    def test_add_app_to_home_from_drawer(self):
        self.add_to_home("Clock")
        layout = d.wait_layout(lambda l: {"app": CLOCK} in home_items(l))
        self.assertIn({"app": CLOCK}, home_items(layout))

    def test_rename_app(self):
        clock = self.add_to_home("Clock")
        self.menu(clock, "edit icon & name")
        self.rename_in_sheet("tick")
        d.find(desc="tick", within="pager")
        layout = d.wait_layout(lambda l: CLOCK in l["overrides"])
        self.assertEqual(layout["overrides"][CLOCK]["label"], "tick")

    def assert_typing_replaces_the_name(self):
        """the sheet opens with the name selected and the keyboard up."""
        d.find(rid="sheet_name")
        d.wait_keyboard()
        d.type_text("fresh")
        d.choose("done")

    def test_new_folder_sheet_selects_the_name_with_keyboard_open(self):
        contacts = self.add_to_home("Contacts")
        self.menu(contacts, "add to folder")
        d.choose("new folder")
        self.assert_typing_replaces_the_name()
        layout = d.wait_layout(
            lambda l: folders(l) and folders(l)[0]["name"] == "fresh"
        )
        self.assertEqual(folders(layout)[0]["apps"], [CONTACTS])

    def test_edit_sheet_selects_the_name_with_keyboard_open(self):
        clock = self.add_to_home("Clock")
        self.menu(clock, "edit icon & name")
        self.assert_typing_replaces_the_name()
        layout = d.wait_layout(lambda l: CLOCK in l["overrides"])
        self.assertEqual(layout["overrides"][CLOCK]["label"], "fresh")

    def test_add_to_folder_sits_just_above_uninstall(self):
        clock = self.add_to_home("Clock")
        d.long_press(clock)
        add = d.find(text="add to folder")
        uninstall = d.find(text="uninstall")
        self.assertEqual(
            add.bounds[3], uninstall.bounds[1], "adjacent, add to folder first"
        )

    def test_folder_chooser_lists_folders_alphabetically(self):
        for label, name in (("Clock", "zeta"), ("Contacts", "alpha")):
            self.menu(self.add_to_home(label), "add to folder")
            d.choose("new folder")
            d.find(rid="sheet_name")
            d.type_text(name)
            d.choose("done")
        self.menu(self.add_to_home("Calendar"), "add to folder")
        alpha, zeta, new = (d.find(text=t) for t in ("alpha", "zeta", "new folder"))
        self.assertLess(alpha.bounds[1], zeta.bounds[1])
        self.assertLess(zeta.bounds[1], new.bounds[1], "new folder stays last")

    def test_folder_chooser_scrolls_when_there_are_many_folders(self):
        layout = d.wait_layout(lambda l: any(l["dock"]))
        many = [
            {
                "folder": f"f{i}",
                "name": f"folder {i:02d}",
                "icon": None,
                "apps": [CONTACTS],
            }
            for i in range(19)
        ]
        layout["pages"][0] = [{"app": CLOCK}, *many]
        layout["next_id"] = 100
        d.seed_layout(layout)
        self.menu(d.find(desc="Clock", within="pager"), "add to folder")
        menu = d.find(rid="menu")
        _, height = d.screen_size()
        self.assertLessEqual(menu.bounds[3], height, "the menu fits on screen")
        for _ in range(5):
            d.shell(
                "input",
                "swipe",
                str(menu.center[0]),
                str(menu.bounds[3] - 50),
                str(menu.center[0]),
                str(menu.bounds[1] + 50),
                "300",
            )
        entry = d.find(text="new folder")
        self.assertLessEqual(
            entry.bounds[3], menu.bounds[3], "scrolling reaches the last entry"
        )
        d.choose("new folder")

    def test_folder_via_menu_is_symmetrical_with_no_header(self):
        self.add_to_home("Clock")
        contacts = self.add_to_home("Contacts")
        self.menu(contacts, "add to folder")
        d.choose("new folder")
        self.rename_in_sheet("util")
        self.menu(d.find(desc="Clock", within="pager"), "add to folder")
        d.choose("util")
        layout = d.wait_layout(lambda l: any(len(f["apps"]) == 2 for f in folders(l)))
        self.assertEqual(sorted(folders(layout)[0]["apps"]), sorted([CLOCK, CONTACTS]))
        d.tap(d.find(desc="util", within="pager"))
        card = d.find(rid="folder_card")
        apps = [
            d.find(desc=label, within="folder_card") for label in ("Clock", "Contacts")
        ]
        self.assertEqual(apps[0].bounds[1], apps[1].bounds[1], "2 apps sit on one row")
        middle = (card.bounds[0] + card.bounds[2]) / 2
        self.assertAlmostEqual(
            (apps[0].center[0] + apps[1].center[0]) / 2, middle, delta=2
        )
        self.assertEqual(
            [n for n in d.dump() if n.text == "util"],
            [],
            "an open folder has no header",
        )

    def test_dropping_an_app_on_another_swaps_them(self):
        clock = self.add_to_home("Clock")
        contacts = self.add_to_home("Contacts")
        d.drag(contacts, clock)
        layout = d.wait_layout(lambda l: l["pages"][0][0] == {"app": CONTACTS})
        self.assertEqual(layout["pages"][0][1], {"app": CLOCK})
        self.assertEqual(folders(layout), [], "dropping never makes a folder")

    def test_dropping_between_home_apps_inserts_there(self):
        clock = self.add_to_home("Clock")
        self.add_to_home("Contacts")
        calendar = self.add_to_home("Calendar")
        d.drag(calendar, d.beside(clock, 0.9))
        layout = d.wait_layout(lambda l: l["pages"][0][1] == {"app": CALENDAR})
        self.assertEqual(
            layout["pages"][0][:3],
            [{"app": CLOCK}, {"app": CALENDAR}, {"app": CONTACTS}],
        )

    def test_dropping_between_dock_apps_inserts_there(self):
        before = d.wait_layout(lambda l: any(l["dock"]))["dock"]
        d.drag(
            d.find(desc="Settings", within="dock"),
            d.beside(d.find(desc="Phone", within="dock"), 0.9),
        )
        layout = d.wait_layout(lambda l: l["dock"][1] == {"app": SETTINGS})
        self.assertEqual(
            layout["dock"], [before[0], before[3], before[1], before[2], None]
        )

    def test_dropping_between_folder_apps_inserts_and_on_one_swaps(self):
        folder = self.make_folder()
        d.drag(self.add_to_home("Calendar"), folder)
        d.wait_layout(lambda l: any(len(f["apps"]) == 3 for f in folders(l)))
        d.tap(d.find(desc="folder", within="pager"))
        d.drag(
            d.find(desc="Calendar", within="folder_card"),
            d.beside(d.find(desc="Clock", within="folder_card"), 0.9),
        )
        d.wait_layout(
            lambda l: any(f["apps"] == [CLOCK, CALENDAR, CONTACTS] for f in folders(l))
        )
        d.drag(
            d.find(desc="Contacts", within="folder_card"),
            d.find(desc="Clock", within="folder_card"),
        )
        d.wait_layout(
            lambda l: any(f["apps"] == [CONTACTS, CALENDAR, CLOCK] for f in folders(l))
        )

    def test_a_drawer_app_dropped_on_a_taken_slot_goes_beside_it(self):
        clock = self.add_to_home("Clock")
        self.open_drawer()
        calendar = d.find(desc="Calendar", within="drawer_grid")
        d.drag(calendar, clock, via=[d.find(rid="search").center])
        d.wait_layout(
            lambda l: (
                home_items(l)
                in (
                    [{"app": CLOCK}, {"app": CALENDAR}],
                    [{"app": CALENDAR}, {"app": CLOCK}],
                )
            )
        )

    def test_a_gap_between_two_entries_is_one_drop_area(self):
        self.open_drawer()
        cells = [n for n in d.find_all(within="drawer_grid") if n.desc]
        cells.sort(key=lambda n: (n.bounds[1], n.bounds[0]))
        left, right = cells[1], cells[2]
        d.touch("DOWN", *cells[5].center)
        time.sleep(d.LONG_PRESS_MS / 1000)
        d.touch("MOVE", cells[5].center[0], cells[5].center[1] + 80)
        marked = []
        for point in (d.beside(left, 0.9), d.beside(right, 0.1)):
            d.touch("MOVE", *point.center)
            time.sleep(0.5)
            marked.append(
                [n.desc for n in d.find_all(within="drawer_grid") if n.selected]
            )
        d.touch("UP", *d.beside(right, 0.1).center)
        self.assertEqual(marked, [[right.desc], [right.desc]])

    def group(self, first, second, name="folder"):
        """two home apps into a new folder, the way a person now has to: through the menus."""
        self.menu(first, "add to folder")
        d.choose("new folder")
        d.find(rid="sheet_name")
        d.choose("done")
        self.menu(second, "add to folder")
        d.choose(name)

    def make_folder(self):
        """clock and contacts in one home folder, in that order; returns the folder node."""
        self.group(self.add_to_home("Clock"), self.add_to_home("Contacts"))
        d.wait_layout(lambda l: any(f["apps"] == [CLOCK, CONTACTS] for f in folders(l)))
        return d.find(desc="folder", within="pager")

    def test_folder_keeps_a_proper_grid_with_the_last_row_left_aligned(self):
        folder = self.make_folder()
        d.drag(self.add_to_home("Calendar"), folder)
        d.wait_layout(lambda l: any(len(f["apps"]) == 3 for f in folders(l)))
        d.tap(d.find(desc="folder", within="pager"))
        first = d.find(desc="Clock", within="folder_card")
        second = d.find(desc="Contacts", within="folder_card")
        third = d.find(desc="Calendar", within="folder_card")
        self.assertEqual(
            first.bounds[1], second.bounds[1], "two columns for three apps"
        )
        self.assertEqual(
            third.bounds[0], first.bounds[0], "the last row starts at the left"
        )
        self.assertGreater(third.bounds[1], first.bounds[1])

    def test_reorder_apps_inside_a_folder(self):
        d.tap(self.make_folder())
        clock = d.find(desc="Clock", within="folder_card")
        d.drag(d.find(desc="Contacts", within="folder_card"), clock)
        layout = d.wait_layout(
            lambda l: any(f["apps"] == [CONTACTS, CLOCK] for f in folders(l))
        )
        self.assertEqual(len(folders(layout)), 1)
        d.find(rid="folder_card")

    def test_reordering_drags_may_cross_the_folder_card_padding(self):
        d.tap(self.make_folder())
        card = d.find(rid="folder_card")
        clock = d.find(desc="Clock", within="folder_card")
        contacts = d.find(desc="Contacts", within="folder_card")
        padding = (card.bounds[3] + contacts.bounds[3]) // 2
        d.drag(
            contacts,
            clock,
            via=[(contacts.center[0], padding), (clock.center[0], padding)],
        )
        d.wait_layout(lambda l: any(f["apps"] == [CONTACTS, CLOCK] for f in folders(l)))
        d.find(rid="folder_card")

    def test_reordering_a_drawer_folder_keeps_the_drawer_open(self):
        folder_id = self.make_drawer_folder("plan")
        self.menu(d.find(desc="Clock", within="drawer_grid"), "add to folder")
        d.choose("plan")
        d.wait_layout(lambda l: l["drawer_folders"][0]["apps"] == [CALENDAR, CLOCK])
        d.tap(d.find(desc="plan", within="drawer_grid"))
        calendar = d.find(desc="Calendar", within="folder_card")
        d.drag(d.find(desc="Clock", within="folder_card"), calendar)
        layout = d.wait_layout(
            lambda l: l["drawer_folders"][0]["apps"] == [CLOCK, CALENDAR]
        )
        self.assertEqual(layout["drawer_folders"][0]["folder"], folder_id)
        d.find(rid="folder_card")
        d.back()
        d.find(desc="plan", within="drawer_grid")

    def test_drag_app_out_of_a_folder_onto_home(self):
        folder = self.make_folder()
        left, top, right, bottom = folder.bounds
        below = d.Node("", "", "", (left, bottom, right, bottom + (bottom - top)))
        d.tap(folder)
        d.drag(d.find(desc="Contacts", within="folder_card"), below)
        layout = d.wait_layout(lambda l: {"app": CONTACTS} in home_items(l))
        self.assertIn(
            {"app": CLOCK}, home_items(layout), "a folder left with one app collapses"
        )

    def test_quick_release_after_starting_a_drag_never_leaves_drop_targets_up(self):
        for _ in range(5):
            self.open_drawer()
            x, y = d.find(desc="Calendar", within="drawer_grid").center
            d.touch("DOWN", x, y)
            time.sleep(d.LONG_PRESS_MS / 1000)
            d.touch("MOVE", x, y + 40)
            d.touch("UP", x, y + 40)
            d.absent(rid="zones")
            d.back()
            d.back()

    def test_drop_targets_are_row_height(self):
        clock = self.add_to_home("Clock")
        x, y = clock.center
        d.touch("DOWN", x, y)
        time.sleep(d.LONG_PRESS_MS / 1000)
        d.touch("MOVE", x, y + 80)
        d.touch("MOVE", x, y + 160)
        remove = d.find(text="remove", within="zones")
        d.touch("UP", x, y + 160)
        self.assertAlmostEqual(
            remove.bounds[3] - remove.bounds[1], clock.width / PHI, delta=3
        )

    def test_home_grid_fills_the_space_above_the_dock(self):
        """on a taller phone (simulated with wm size) the grid gains rows instead of leaving a gap."""
        d.shell("wm", "size", "1080x2800")
        self.addCleanup(d.shell, "wm", "size", "reset")
        d.reset_launcher()
        d.find(desc="Phone", within="dock")
        pager = d.find(rid="pager")
        self.open_drawer()
        cell = d.find(desc="Clock", within="drawer_grid").width
        gap = cell / PHI**4
        fits = int((pager.bounds[3] - pager.bounds[1] + gap) // (cell + gap))
        layout = d.wait_layout(lambda l: l["settings"]["rows"] == fits)
        self.assertEqual(len(layout["pages"][0]), fits * 4)

    def test_drag_app_to_every_row_of_the_home_grid(self):
        clock = self.add_to_home("Clock")
        cell = clock.width
        x0, y0 = clock.bounds[0], clock.bounds[1]
        pitch = cell + cell / PHI**4
        for row in (4, 3, 2, 1):
            top = int(y0 + row * pitch)
            target = d.Node("", "", "", (x0, top, x0 + cell, top + cell))
            d.drag(d.find(desc="Clock", within="pager"), target)
            index = row * 4
            layout = d.wait_layout(
                lambda l, i=index: l["pages"][0][i] == {"app": CLOCK}
            )
            self.assertEqual(sum(1 for x in layout["pages"][0] if x), 1)

    def test_drag_app_into_dock(self):
        clock = self.add_to_home("Clock")
        empty_slot = d.find(rid="dock")
        left, top, right, bottom = empty_slot.bounds
        target = d.Node("", "", "", (right - (right - left) // 5, top, right, bottom))
        d.drag(clock, target)
        layout = d.wait_layout(lambda l: {"app": CLOCK} in l["dock"])
        self.assertNotIn({"app": CLOCK}, home_items(layout))

    def test_drawer_folder_removes_apps_from_main_grid_but_not_search(self):
        self.open_drawer()
        self.menu(d.find(desc="Calendar", within="drawer_grid"), "add to folder")
        d.choose("new folder")
        self.rename_in_sheet("plan")
        layout = d.wait_layout(lambda l: l["drawer_folders"])
        self.assertEqual(layout["drawer_folders"][0]["apps"], [CALENDAR])
        d.find(desc="plan", within="drawer_grid")
        d.absent(desc="Calendar", within="drawer_grid")
        d.tap(d.find(rid="search"))
        d.type_text("cal")
        d.find(desc="Calendar", within="drawer_grid")

    def test_icon_search_sits_above_the_keyboard_and_takes_the_typing(self):
        clock = self.add_to_home("Clock")
        self.menu(clock, "edit icon & name")
        d.wait_keyboard()
        d.tap(d.find(rid="sheet_icon"))
        d.find(rid="picker_grid")
        d.type_text("star")
        search = d.find(text="star")
        self.assertNotEqual(
            search.rid, d.rid_of("sheet_name"), "typing lands in the picker search"
        )
        row = d.find(desc="tp star", within="picker_grid").width / PHI
        self.assertAlmostEqual(
            search.bounds[3] - search.bounds[1],
            row,
            delta=3,
            msg="search is not clipped",
        )
        d.absent(desc="tp clock", within="picker_grid")

    def test_dragging_out_of_a_drawer_folder_keeps_the_app_in_it(self):
        pager = d.find(rid="pager")
        self.open_drawer()
        self.menu(d.find(desc="Calendar", within="drawer_grid"), "add to folder")
        d.choose("new folder")
        self.rename_in_sheet("plan")
        d.wait_layout(lambda l: l["drawer_folders"])
        d.tap(d.find(desc="plan", within="drawer_grid"))
        calendar = d.find(desc="Calendar", within="folder_card")
        cell = calendar.width
        width, _ = d.screen_size()
        left = (width - 4 * cell) // 2
        first = d.Node(
            "", "", "", (left, pager.bounds[1], left + cell, pager.bounds[1] + cell)
        )
        d.drag(calendar, first)
        layout = d.wait_layout(lambda l: {"app": CALENDAR} in home_items(l))
        self.assertEqual(layout["drawer_folders"][0]["apps"], [CALENDAR])
        d.absent(rid="search")

    def drawer_labels(self):
        """the drawer's cells in reading order."""
        cells = d.find_all(within="drawer_grid")
        cells = [n for n in cells if n.desc]
        return [n.desc for n in sorted(cells, key=lambda n: (n.bounds[1], n.bounds[0]))]

    def test_dragging_between_drawer_apps_rearranges_them_for_good(self):
        self.open_drawer()
        self.assertLess(
            self.drawer_labels().index("Calendar"), self.drawer_labels().index("Clock")
        )
        clock = d.find(desc="Clock", within="drawer_grid")
        d.drag(clock, d.beside(d.find(desc="Calendar", within="drawer_grid"), 0.1))
        layout = d.wait_layout(lambda l: l["drawer_order"])
        order = layout["drawer_order"]
        self.assertEqual(order.index(CLOCK), order.index(CALENDAR) - 1)
        self.assertEqual(d.find(rid="drawer").bounds[1], 0, "the drawer stays open")
        d.shell("am", "force-stop", d.PACKAGE)
        d.go_home()
        d.find(desc="Phone", within="dock")
        self.open_drawer()
        labels = self.drawer_labels()
        self.assertEqual(labels.index("Clock"), labels.index("Calendar") - 1)

    def test_a_drawer_folder_rearranges_among_the_apps(self):
        self.make_drawer_folder("plan")
        self.assertEqual(self.drawer_labels()[0], "plan", "folders start out first")
        folder = d.find(desc="plan", within="drawer_grid")
        d.drag(folder, d.beside(d.find(desc="Clock", within="drawer_grid"), 0.9))
        d.wait_layout(lambda l: l["drawer_order"])
        labels = self.drawer_labels()
        self.assertEqual(labels.index("plan"), labels.index("Clock") + 1)

    def test_dropping_on_the_middle_of_a_drawer_app_swaps_them(self):
        self.open_drawer()
        before = self.drawer_labels()
        d.drag(
            d.find(desc="Calendar", within="drawer_grid"),
            d.find(desc="Contacts", within="drawer_grid"),
        )
        layout = d.wait_layout(lambda l: l["drawer_order"])
        self.assertEqual(layout["drawer_folders"], [], "dropping never makes a folder")
        after = self.drawer_labels()
        self.assertEqual(after.index("Calendar"), before.index("Contacts"))
        self.assertEqual(after.index("Contacts"), before.index("Calendar"))

    def test_dropping_an_app_on_a_drawer_folder_adds_it(self):
        self.make_drawer_folder("plan")
        d.drag(
            d.find(desc="Clock", within="drawer_grid"),
            d.find(desc="plan", within="drawer_grid"),
        )
        d.wait_layout(lambda l: l["drawer_folders"][0]["apps"] == [CALENDAR, CLOCK])
        d.absent(desc="Clock", within="drawer_grid")

    def test_dragging_out_of_a_drawer_folder_reaches_the_dock(self):
        dock = d.find(rid="dock")
        left, top, right, bottom = dock.bounds
        last_slot = d.Node(
            "", "", "", (right - (right - left) // 5, top, right, bottom)
        )
        self.make_drawer_folder("plan")
        d.tap(d.find(desc="plan", within="drawer_grid"))
        d.drag(d.find(desc="Calendar", within="folder_card"), last_slot)
        layout = d.wait_layout(lambda l: {"app": CALENDAR} in l["dock"])
        self.assertEqual(layout["drawer_folders"][0]["apps"], [CALENDAR])

    def test_dragging_to_the_search_bar_reveals_home(self):
        pager = d.find(rid="pager")
        search = self.open_drawer()
        calendar = d.find(desc="Calendar", within="drawer_grid")
        d.touch("DOWN", *calendar.center)
        time.sleep(d.LONG_PRESS_MS / 1000)
        d.touch("MOVE", calendar.center[0], calendar.center[1] + 80)
        d.find(text="add to home", rid="drawer_zone")
        self.assertEqual(d.find(rid="drawer").bounds[1], 0, "still in the drawer")
        for x, y in (search.center, (pager.center[0], pager.bounds[1] + 100)):
            d.touch("MOVE", x, y)
            time.sleep(0.3)
        d.touch("UP", pager.center[0], pager.bounds[1] + 100)
        d.wait_layout(lambda l: {"app": CALENDAR} in home_items(l))
        d.absent(rid="search")

    def test_a_drawer_folder_dragged_to_home_becomes_a_link(self):
        folder_id = self.make_drawer_folder("plan")
        pager = d.find(rid="pager")
        d.drag(
            d.find(desc="plan", within="drawer_grid"),
            d.Node("", "", "", (*pager.bounds[:2], *pager.center)),
            via=[d.find(rid="search").center],
        )
        d.wait_layout(lambda l: {"folder_link": folder_id} in home_items(l))

    def test_a_drawer_drag_scrolls_at_the_edges(self):
        d.shell("wm", "size", "1080x1000")
        self.addCleanup(d.shell, "wm", "size", "reset")
        d.reset_launcher()
        d.find(desc="Phone", within="dock")
        self.open_drawer()
        second_row = self.drawer_labels()[4]
        top = d.find(desc=second_row, within="drawer_grid").bounds[1]
        calendar = d.find(desc="Calendar", within="drawer_grid")
        search = d.find(rid="search")
        d.touch("DOWN", *calendar.center)
        time.sleep(d.LONG_PRESS_MS / 1000)
        x = calendar.center[0]
        d.touch("MOVE", x, calendar.center[1] + 80)
        d.touch("MOVE", x, search.bounds[1] - 20)
        time.sleep(2)
        moved = d.find_all(desc=second_row, within="drawer_grid")
        d.touch("MOVE", x, search.bounds[1] - 200)
        d.touch("UP", x, search.bounds[1] - 200)
        self.assertTrue(
            not moved or moved[0].bounds[1] < top,
            "holding near the bottom scrolls the grid",
        )

    def test_drawer_order_can_return_to_sorted_by_name(self):
        self.open_drawer()
        clock = d.find(desc="Clock", within="drawer_grid")
        d.drag(clock, d.beside(d.find(desc="Calendar", within="drawer_grid"), 0.1))
        d.wait_layout(lambda l: l["drawer_order"])
        d.back()
        d.absent(rid="search")
        self.open_settings()
        d.tap(d.find(text="drawer order"))
        self.assertTrue(d.find(text="custom", within="menu").selected)
        d.choose("by name", within="menu")
        d.wait_layout(lambda l: not l["drawer_order"])
        d.back()
        d.absent(rid="sheet")
        self.open_drawer()
        labels = self.drawer_labels()
        self.assertLess(labels.index("Calendar"), labels.index("Clock"))

    def test_drawer_entries_move_from_their_menu(self):
        self.open_drawer()
        self.menu(d.find(desc="Clock", within="drawer_grid"), "move in drawer")
        d.choose("to the top")
        d.wait_layout(lambda l: l["drawer_order"])
        self.assertEqual(self.drawer_labels()[0], "Clock")

    def request_pin(self, icon="bitmap"):
        """a foreground app asks to pin a shortcut, as a browser's add to home does."""
        d.install(os.environ.get("BOOSTER_TESTPIN_APK", TESTPIN_APK))
        self.addCleanup(d.uninstall, d.TESTPIN)
        d.shell(
            "am",
            "start",
            "-W",
            "-n",
            f"{d.TESTPIN}/.PinActivity",
            "--es",
            "pin",
            icon,
        )
        d.find(text=PINNED, rid="pin_name")

    def pin(self, icon="bitmap"):
        self.request_pin(icon)
        d.choose("add to home")
        d.wait_layout(lambda l: {"app": PINNED_KEY} in home_items(l))
        d.go_home()
        return d.find(desc=PINNED, within="pager")

    def assert_icon_color(self, cell, rgb):
        """the middle of the cell's icon, which sits above its label, is this color."""
        x = cell.center[0]
        y = cell.bounds[1] + (cell.bounds[3] - cell.bounds[1]) * 2 // 5
        deadline = time.time() + 5
        while time.time() < deadline:
            seen = d.pixel(x, y)
            if all(abs(a - b) < COLOR_SLACK for a, b in zip(seen, rgb, strict=True)):
                return
            time.sleep(0.3)
        self.fail(f"icon center is {seen}, not {rgb}; screen at {d.evidence()}")

    def test_a_pinned_bitmap_icon_shows(self):
        self.assert_icon_color(self.pin(), MAGENTA)

    def test_a_pinned_adaptive_icon_shows(self):
        self.assert_icon_color(self.pin("adaptive"), MAGENTA)

    def test_a_shortcut_without_an_icon_shows_its_apps_icon(self):
        self.assert_icon_color(self.pin("none"), CYAN)

    def test_a_pinned_icon_survives_a_restart(self):
        self.pin()
        d.shell("am", "force-stop", d.PACKAGE)
        d.go_home()
        self.assert_icon_color(d.find(desc=PINNED, within="pager"), MAGENTA)

    def test_add_to_home_from_an_app_places_a_shortcut_that_opens_it(self):
        self.request_pin()
        d.choose("add to home")
        d.wait_layout(lambda l: {"app": PINNED_KEY} in home_items(l))
        d.wait_resumed(d.TESTPIN)
        d.go_home()
        d.wait_resumed(d.PACKAGE)
        d.tap(d.find(desc=PINNED, within="pager"))
        d.wait_resumed(d.TESTPIN)

    def test_a_declined_add_to_home_places_nothing(self):
        self.request_pin()
        d.choose("cancel")
        d.wait_resumed(d.TESTPIN)
        d.go_home()
        d.find(desc="Phone", within="dock")
        d.absent(desc=PINNED, within="pager")
        self.assertNotIn({"app": PINNED_KEY}, home_items(d.layout_json()))

    def test_a_removed_shortcut_stays_gone(self):
        self.menu(self.pin(), "remove from home")
        d.absent(desc=PINNED, within="pager")
        d.shell("am", "force-stop", d.PACKAGE)
        d.go_home()
        d.find(desc="Phone", within="dock")
        time.sleep(1)
        self.assertNotIn({"app": PINNED_KEY}, home_items(d.layout_json()))

    def test_shortcuts_rename_and_offer_no_uninstall(self):
        shortcut = self.pin()
        d.long_press(shortcut)
        d.find(text="edit icon & name")
        d.absent(text="uninstall", timeout=1)
        d.choose("edit icon & name")
        self.rename_in_sheet("page")
        d.find(desc="page", within="pager")

    def test_a_shortcut_leaves_with_its_app(self):
        self.pin()
        d.uninstall(d.TESTPIN)
        d.absent(desc=PINNED, within="pager")
        d.wait_layout(lambda l: {"app": PINNED_KEY} not in home_items(l))

    def make_drawer_folder(self, name, label="Calendar"):
        self.open_drawer()
        self.menu(d.find(desc=label, within="drawer_grid"), "add to folder")
        d.choose("new folder")
        self.rename_in_sheet(name)
        return d.wait_layout(lambda l: l["drawer_folders"])["drawer_folders"][0][
            "folder"
        ]

    def test_drawer_folder_on_home_and_dock_is_the_same_folder(self):
        folder_id = self.make_drawer_folder("plan")
        self.menu(d.find(desc="plan", within="drawer_grid"), "add to home")
        self.menu(d.find(desc="plan", within="drawer_grid"), "add to dock")
        layout = d.wait_layout(
            lambda l: (
                {"folder_link": folder_id} in home_items(l)
                and {"folder_link": folder_id} in l["dock"]
            )
        )
        self.assertEqual(len(layout["drawer_folders"]), 1, "linked, not copied")
        d.back()
        d.tap(d.find(desc="plan", within="pager"))
        d.find(desc="Calendar", within="folder_card")
        d.back()
        self.menu(d.find(desc="plan", within="pager"), "edit folder")
        self.rename_in_sheet("agenda")
        d.find(desc="agenda", within="dock")
        self.open_drawer()
        d.find(desc="agenda", within="drawer_grid")

    def test_apps_inside_a_drawer_folder_can_go_to_home_and_dock(self):
        self.make_drawer_folder("plan")
        d.tap(d.find(desc="plan", within="drawer_grid"))
        self.menu(d.find(desc="Calendar", within="folder_card"), "add to home")
        self.menu(d.find(desc="Calendar", within="folder_card"), "add to dock")
        layout = d.wait_layout(
            lambda l: (
                {"app": CALENDAR} in home_items(l) and {"app": CALENDAR} in l["dock"]
            )
        )
        self.assertEqual(
            layout["drawer_folders"][0]["apps"], [CALENDAR], "still in the folder"
        )

    def test_dissolving_a_drawer_folder_removes_its_links(self):
        folder_id = self.make_drawer_folder("plan")
        self.menu(d.find(desc="plan", within="drawer_grid"), "add to home")
        d.wait_layout(lambda l: {"folder_link": folder_id} in home_items(l))
        self.menu(d.find(desc="plan", within="drawer_grid"), "dissolve")
        layout = d.wait_layout(lambda l: not l["drawer_folders"])
        self.assertNotIn({"folder_link": folder_id}, home_items(layout))

    def test_hide_home_apps_from_the_drawer_and_its_folders(self):
        self.add_to_home("Clock")
        self.open_drawer()
        self.menu(d.find(desc="Calendar", within="drawer_grid"), "add to folder")
        d.choose("new folder")
        self.rename_in_sheet("plan")
        self.menu(d.find(desc="Clock", within="drawer_grid"), "add to folder")
        d.choose("plan")
        d.back()
        d.absent(rid="search")
        self.open_settings()
        d.tap(d.find(text="hide home apps from drawer"))
        d.choose("on", within="menu")
        d.wait_layout(lambda l: l["settings"]["hide_placed"] is True)
        d.back()
        d.absent(rid="sheet")
        self.open_drawer()
        d.absent(desc="Settings", within="drawer_grid")
        d.find(desc="Gallery", within="drawer_grid")
        d.tap(d.find(desc="plan", within="drawer_grid"))
        d.find(desc="Calendar", within="folder_card")
        d.absent(desc="Clock", within="folder_card")
        d.back()
        d.tap(d.find(rid="search"))
        d.type_text("clo")
        d.find(desc="Clock", within="drawer_grid")

    def test_a_new_home_folder_ignores_drawer_folder_names(self):
        self.make_drawer_folder("folder")
        d.back()
        d.absent(rid="search")
        self.group(self.add_to_home("Clock"), self.add_to_home("Contacts"))
        layout = d.wait_layout(lambda l: folders(l) and len(folders(l)[0]["apps"]) == 2)
        self.assertEqual(folders(layout)[0]["name"], "folder")
        self.assertEqual(layout["drawer_folders"][0]["name"], "folder")
        self.assertEqual(layout["drawer_folders"][0]["apps"], [CALENDAR])

    def test_a_home_folder_may_be_renamed_like_a_drawer_folder(self):
        self.make_drawer_folder("plan")
        d.back()
        d.absent(rid="search")
        self.group(self.add_to_home("Clock"), self.add_to_home("Contacts"))
        self.menu(d.find(desc="folder", within="pager"), "edit folder")
        self.rename_in_sheet("plan")
        layout = d.wait_layout(lambda l: folders(l)[0]["name"] == "plan")
        self.assertEqual(layout["drawer_folders"][0]["name"], "plan")
        self.assertNotEqual(
            folders(layout)[0]["folder"], layout["drawer_folders"][0]["folder"]
        )

    def test_folder_names_stay_unique(self):
        self.group(self.add_to_home("Clock"), self.add_to_home("Contacts"))
        self.group(
            self.add_to_home("Calendar"), self.add_to_home("Gallery"), "folder 2"
        )
        layout = d.wait_layout(
            lambda l: (
                len(folders(l)) == 2 and all(len(f["apps"]) == 2 for f in folders(l))
            )
        )
        names = sorted(f["name"] for f in folders(layout))
        self.assertEqual(
            names, ["folder", "folder 2"], "new folders get distinct names"
        )
        self.menu(d.find(desc="folder 2", within="pager"), "edit folder")
        d.find(rid="sheet_name")
        d.wait_keyboard()
        d.type_text("folder")
        d.tap(d.find(text="done"))
        d.find(rid="sheet_name")
        self.assertEqual(
            sorted(f["name"] for f in folders(d.layout_json())),
            ["folder", "folder 2"],
            "a taken name is refused and the sheet stays open",
        )

    def test_customizations_show_in_every_view(self):
        self.add_to_home("Clock")
        self.menu(d.find(desc="Clock", within="pager"), "edit icon & name")
        self.rename_in_sheet("tick")
        self.open_drawer()
        d.find(desc="tick", within="drawer_grid")
        self.menu(d.find(desc="tick", within="drawer_grid"), "edit icon & name")
        self.rename_in_sheet("tock")
        d.back()
        d.find(desc="tock", within="pager")
        contacts = self.add_to_home("Contacts")
        self.group(d.find(desc="tock", within="pager"), contacts)
        d.tap(d.find(desc="folder", within="pager"))
        self.menu(d.find(desc="tock", within="folder_card"), "edit icon & name")
        self.rename_in_sheet("tack")
        d.find(desc="tack", within="folder_card")

    def test_global_icon_pack_and_per_app_icon(self):
        self.open_settings()
        d.tap(d.find(text="icon pack"))
        d.tap(d.find(text=PACK_LABEL))
        layout = d.wait_layout(lambda l: l["settings"]["icon_pack"])
        self.assertEqual(layout["settings"]["icon_pack"], d.TESTPACK)
        d.back()
        self.menu(d.find(desc="Settings", within="dock"), "edit icon & name")
        d.tap(d.find(rid="sheet_icon"))
        match = d.find(desc="match", within="picker_grid")
        grid = d.find(rid="picker_grid")
        self.assertAlmostEqual(
            grid.bounds[3] - grid.bounds[1],
            match.width,
            delta=2,
            msg="the picker fits its content: 4 icons make one row",
        )
        d.tap(d.find(desc="tp star", within="picker_grid"))
        d.absent(rid="picker_grid")
        d.choose("done")
        layout = d.wait_layout(lambda l: SETTINGS in l["overrides"])
        self.assertEqual(
            layout["overrides"][SETTINGS]["icon"],
            {"pack": d.TESTPACK, "name": "tp_star"},
        )

    def test_system_icons_and_folder_custom_icon(self):
        folder = self.make_folder()
        self.menu(folder, "edit folder")
        d.tap(d.find(rid="sheet_icon"))
        d.tap(d.find(text="System"))
        d.tap(d.find(desc="Camera", within="picker_grid"))
        d.absent(rid="picker_grid")
        d.choose("done")
        layout = d.wait_layout(lambda l: folders(l) and folders(l)[0]["icon"])
        self.assertEqual(folders(layout)[0]["icon"]["pack"], None)
        self.assertTrue(
            folders(layout)[0]["icon"]["name"].startswith("com.android.camera2/")
        )

    def test_layout_survives_restart(self):
        self.add_to_home("Clock")
        d.wait_layout(lambda l: {"app": CLOCK} in home_items(l))
        d.shell("am", "force-stop", d.PACKAGE)
        d.go_home()
        d.find(desc="Clock", within="pager")


if __name__ == "__main__":
    unittest.main()

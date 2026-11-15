package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The new menu's panels on the emulator (AVD tv34): starting focus, each panel opening VISIBLE with focus inside and
 * every other panel GONE, Back returning focus to the bar button, Search's keyboard and pad, the 0-list, and that
 * value changes never rebuild rows. Keys go through the activity's dispatchKeyEvent, as the remote's do.
 *
 * Each test starts from cleared preferences (no favourites, default settings) and the bundled presets. Views the
 * menu builds without ids (Find's and the 0-list's cards, Search's state) are reached by reflection, so the app
 * needs no test-only code. Run: ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4.class)
public class MenuPanelsTest {
    @Rule
    public GrantPermissionRule audio = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    private ActivityScenario<MainActivity> scenario;

    @Before
    public void launch() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[]{"settings", "state", "auto_picture", "too_slow"}) {
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        }
        scenario = ActivityScenario.launch(MainActivity.class);
        long until = SystemClock.uptimeMillis() + 30_000;
        boolean ready = false;
        while (!ready && SystemClock.uptimeMillis() < until) {
            // Keys sent before the window has input focus are dropped.
            ready = onUi(a -> field(a, "menu") != null && field(a, "shownPreset") != null && a.hasWindowFocus());
            if (!ready) SystemClock.sleep(200);
        }
        assertTrue("visuals did not start", ready);
    }

    @After
    public void close() {
        if (scenario != null) scenario.close();
    }

    // --- tests ---

    @Test
    public void okOpensOnFavouriteLeftReachesPrevAndHideIsOnTheFarRight() {
        assertFalse(onUi(a -> menu(a).isOpen()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertFocus(R.id.b_fav);
        assertOnlyPanel(null);
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertFocus(R.id.b_next);
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertFocus(R.id.b_prev);
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertFocus(R.id.b_prev);  // the start of the bar
        for (int i = 0; i < 12; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocus(R.id.b_hide);
        key(KeyEvent.KEYCODE_BACK);
        assertFalse(onUi(a -> menu(a).isOpen()));
        assertEquals(View.GONE, (int) onUi(a -> a.findViewById(R.id.menu).getVisibility()));
    }

    @Test
    public void settingsAdvancedAndAboutOpenAloneWithFocusInsideAndBackReturns() {
        openBarButton(R.id.b_settings, 5);
        assertPanel(MenuPanel.SETTINGS, R.id.card_settings);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertFocus(R.id.row_live);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertPanel(MenuPanel.ADVANCED, R.id.card_advanced);
        key(KeyEvent.KEYCODE_BACK);
        assertPanel(MenuPanel.SETTINGS, R.id.card_settings);
        assertFocus(R.id.row_live);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertPanel(MenuPanel.ABOUT, R.id.about);
        assertEquals(View.GONE, (int) onUi(a -> a.findViewById(R.id.bar).getVisibility()));
        assertEquals(View.GONE, (int) onUi(a -> a.findViewById(R.id.menu_scrim).getVisibility()));
        key(KeyEvent.KEYCODE_BACK);
        assertPanel(MenuPanel.SETTINGS, R.id.card_settings);
        assertFocus(R.id.row_about);
        key(KeyEvent.KEYCODE_BACK);
        assertOnlyPanel(null);
        assertFocus(R.id.b_settings);
    }

    @Test
    public void findOpensAloneAStylePageOpensFromStylesAndBackWalksOut() {
        openBarButton(R.id.b_find, 3);
        assertEquals(MenuPanel.LISTS, (int) onUi(a -> menu(a).panel()));
        View find = onUi(a -> findPanel(a).view());
        assertOnlyPanel(find);
        assertFocusInside(find);
        assertEquals(FindPanel.FAVOURITES, (int) onUi(a -> (Integer) field(findPanel(a), "list")));

        for (int i = 0; i < FindPanel.STYLES; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(FindPanel.STYLES, (int) onUi(a -> (Integer) field(findPanel(a), "list")));
        key(KeyEvent.KEYCODE_DPAD_CENTER);  // into the styles
        View styleRow = onUi(a -> a.getCurrentFocus());
        assertFocusInside(find);
        key(KeyEvent.KEYCODE_DPAD_CENTER);  // a style's page
        assertEquals(MenuPanel.FIND, (int) onUi(a -> menu(a).panel()));
        View page = onUi(a -> a.findViewById(R.id.card_find));
        assertOnlyPanel(page);
        assertFocusInside(page);

        key(KeyEvent.KEYCODE_BACK);  // back to Styles, on that style
        assertEquals(MenuPanel.LISTS, (int) onUi(a -> menu(a).panel()));
        assertOnlyPanel(find);
        assertSame(styleRow, onUi(a -> a.getCurrentFocus()));

        key(KeyEvent.KEYCODE_BACK);
        assertOnlyPanel(null);
        assertFocus(R.id.b_find);
    }

    @Test
    public void searchOpensAloneTypingFiltersAndRightLeftCrossTheResults() {
        openBarButton(R.id.b_search, 4);
        assertPanel(MenuPanel.SEARCH, R.id.card_search);
        assertEquals(View.VISIBLE, (int) onUi(a -> a.findViewById(R.id.s_mode).getVisibility()));

        // A word from a bundled preset's name, so the results cannot be empty.
        String word = onUi(a -> {
            for (File f : library(a).all) {
                for (String w : SearchIndex.normalize(PresetLibrary.displayName(f)).split(" ")) {
                    if (w.length() >= 4 && w.matches("[a-z]+")) return w.substring(0, 4);
                }
            }
            return null;
        });
        assertNotNull(word);
        int total = onUi(a -> library(a).all.size());
        int before = total;
        for (char c : word.toCharArray()) {
            key(KeyEvent.KEYCODE_A + (c - 'a'));
            int n = onUi(a -> ((List<?>) field(search(a), "results")).size());
            assertTrue("typing '" + c + "' should narrow: " + n + " after " + before, n > 0 && n <= before);
            before = n;
        }
        assertTrue(before < total);
        assertEquals(word, onUi(a -> ((TextView) field(search(a), "field")).getText().toString()));
        View row0 = onUi(a -> a.findViewById(R.id.s_result0));
        assertEquals(View.VISIBLE, (int) onUi(a -> row0.getVisibility()));
        String shown = onUi(a -> ((TextView[]) ((List<?>) field(search(a), "resultTexts")).get(0))[1].getText().toString());
        assertTrue(shown + " should hold " + word, SearchIndex.normalize(shown).contains(word));

        // Right from the key column enters the results; Left returns to the key.
        View micOrMode = onUi(a -> {
            View mic = a.findViewById(R.id.s_mic);
            View k = mic.getVisibility() == View.VISIBLE ? mic : a.findViewById(R.id.s_mode);
            if (!k.isFocused()) k.requestFocus();
            return k;
        });
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocus(R.id.s_result0);
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertSame(micOrMode, onUi(a -> a.getCurrentFocus()));

        key(KeyEvent.KEYCODE_DEL);
        assertEquals(word.substring(0, 3), onUi(a -> ((TextView) field(search(a), "field")).getText().toString()));

        key(KeyEvent.KEYCODE_BACK);
        assertOnlyPanel(null);
        assertFocus(R.id.b_search);
    }

    @Test
    public void search123SwapsToThePadInPlace() {
        openBarButton(R.id.b_search, 4);
        onUi(a -> a.findViewById(R.id.s_mode).requestFocus());
        assertFocus(R.id.s_mode);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(View.VISIBLE, (int) onUi(a -> ((View) field(search(a), "pad")).getVisibility()));
        assertEquals(View.GONE, (int) onUi(a -> ((View) field(search(a), "letters")).getVisibility()));
        assertEquals(View.GONE, (int) onUi(a -> ((View) field(search(a), "micColumn")).getVisibility()));
        assertSame(onUi(a -> field(search(a), "firstDigit")), onUi(a -> a.getCurrentFocus()));
        assertPanel(MenuPanel.SEARCH, R.id.card_search);
    }

    @Test
    public void searchPadZeroOpensFindOnFavourites() {
        padCodeOpensFind(1, FindPanel.FAVOURITES);
    }

    @Test
    public void searchPadDoubleZeroOpensFindOnThePlaylists() {
        padCodeOpensFind(2, SmartLists.FEATURED + 1);
    }

    private void padCodeOpensFind(int zeros, int list) {
        openBarButton(R.id.b_search, 4);
        for (int i = 0; i < zeros; i++) key(KeyEvent.KEYCODE_0);
        // The remote's digits swap in the pad with focus on the number, so OK goes straight on.
        assertEquals(View.VISIBLE, (int) onUi(a -> ((View) field(search(a), "pad")).getVisibility()));
        assertFocus(R.id.s_pad_play);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(MenuPanel.LISTS, (int) onUi(a -> menu(a).panel()));
        View find = onUi(a -> findPanel(a).view());
        assertOnlyPanel(find);
        assertFocusInside(find);
        assertEquals(list, (int) onUi(a -> (Integer) field(findPanel(a), "list")));
        key(KeyEvent.KEYCODE_BACK);
        assertOnlyPanel(null);
        assertFocus(R.id.b_find);
    }

    @Test
    public void zeroWithTheMenuClosedOpensTheZeroListAndDigitsMoveFocus() {
        addFavourites(3);
        key(KeyEvent.KEYCODE_0);
        assertEquals(MenuPanel.ZERO, (int) onUi(a -> menu(a).panel()));
        View card = onUi(a -> zeroPanel(a).view());
        assertOnlyPanel(card);
        ListRows favs = onUi(a -> (ListRows) field(zeroPanel(a), "favs"));
        ListRows playlists = onUi(a -> (ListRows) field(zeroPanel(a), "playlists"));
        assertSame(favs.rows[0], onUi(a -> a.getCurrentFocus()));

        key(KeyEvent.KEYCODE_2);  // 02: the second favourite
        assertSame(favs.rows[1], onUi(a -> a.getCurrentFocus()));
        key(KeyEvent.KEYCODE_0);  // 020: no favourite 20, the focus stays
        assertSame(favs.rows[1], onUi(a -> a.getCurrentFocus()));
        key(KeyEvent.KEYCODE_0);  // the code is full, so a new one starts: 00, the playlists
        assertSame(playlists.rows[0], onUi(a -> a.getCurrentFocus()));
        key(KeyEvent.KEYCODE_3);  // 003: New to you
        assertSame(playlists.rows[2], onUi(a -> a.getCurrentFocus()));
        assertTrue(onUi(a -> menu(a).isOpen()));

        key(KeyEvent.KEYCODE_BACK);
        assertFalse(onUi(a -> menu(a).isOpen()));
        // The card itself stays VISIBLE inside the GONE menu (open() resets it); nothing of it is drawn.
        assertEquals(View.GONE, (int) onUi(a -> a.findViewById(R.id.menu).getVisibility()));
        assertFalse(onUi(a -> zeroPanel(a).view().isShown()));
    }

    @Test
    public void zeroListWithNoFavouritesFocusesThePlaylists() {
        key(KeyEvent.KEYCODE_0);
        View card = onUi(a -> zeroPanel(a).view());
        assertOnlyPanel(card);
        ListRows playlists = onUi(a -> (ListRows) field(zeroPanel(a), "playlists"));
        assertSame(playlists.rows[0], onUi(a -> a.getCurrentFocus()));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);  // past the last line: the 0-list keeps the focus
        assertFocusInside(card);
    }

    @Test
    public void zeroListClosesItselfAfterTenSecondsIdle() {
        key(KeyEvent.KEYCODE_0);
        assertTrue(onUi(a -> menu(a).isOpen()));
        SystemClock.sleep(ZeroPanel.IDLE_MS / 2);
        key(KeyEvent.KEYCODE_DPAD_DOWN);  // a key restarts the wait
        long kicked = SystemClock.uptimeMillis();
        SystemClock.sleep(ZeroPanel.IDLE_MS * 7 / 10);  // past the first wait's end, inside the new one
        assertTrue("a key should restart the idle wait", onUi(a -> menu(a).isOpen()));
        long until = kicked + ZeroPanel.IDLE_MS + 5_000;
        while (onUi(a -> menu(a).isOpen()) && SystemClock.uptimeMillis() < until) SystemClock.sleep(200);
        assertFalse("still open after the idle wait", onUi(a -> menu(a).isOpen()));
        assertTrue("closed too early", SystemClock.uptimeMillis() - kicked >= ZeroPanel.IDLE_MS - 500);
        assertEquals(View.GONE, (int) onUi(a -> a.findViewById(R.id.menu).getVisibility()));
        assertTrue("the visuals take the keys again", onUi(a -> a.getCurrentFocus() instanceof android.opengl.GLSurfaceView));
    }

    @Test
    public void valueChangesAndAPresetChangeKeepEveryRowView() {
        openBarButton(R.id.b_settings, 5);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_CENTER);  // Live and advanced
        assertPanel(MenuPanel.ADVANCED, R.id.card_advanced);
        View focused = onUi(a -> a.getCurrentFocus());
        List<View> before = onUi(a -> tree(a.findViewById(R.id.menu)));

        File was = onUi(a -> (File) field(a, "shownPreset"));
        key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);  // the remote's next, with the menu open
        long until = SystemClock.uptimeMillis() + 10_000;
        while (was.equals(onUi(a -> field(a, "shownPreset"))) && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(100);
        }
        assertNotEquals("the preset did not change", was, onUi(a -> field(a, "shownPreset")));
        onUi(a -> {
            menu(a).refresh();
            menu(a).tick();
            return null;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        List<View> after = onUi(a -> tree(a.findViewById(R.id.menu)));
        assertSameViews(before, after);
        assertSame(focused, onUi(a -> a.getCurrentFocus()));
    }

    @Test
    public void searchTypingAndZeroListDigitsKeepEveryRowView() {
        List<View> start = onUi(a -> tree(a.findViewById(R.id.menu)));
        openBarButton(R.id.b_search, 4);
        key(KeyEvent.KEYCODE_E);
        key(KeyEvent.KEYCODE_R);
        key(KeyEvent.KEYCODE_DEL);
        key(KeyEvent.KEYCODE_BACK);
        key(KeyEvent.KEYCODE_BACK);
        addFavourites(2);
        key(KeyEvent.KEYCODE_0);
        key(KeyEvent.KEYCODE_2);
        key(KeyEvent.KEYCODE_0);
        key(KeyEvent.KEYCODE_0);
        key(KeyEvent.KEYCODE_BACK);
        assertSameViews(start, onUi(a -> tree(a.findViewById(R.id.menu))));
    }

    @Test
    public void liveAndAdvancedTogglesKeepFocusOnTheToggledRow() {
        openBarButton(R.id.b_settings, 5);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertPanel(MenuPanel.ADVANCED, R.id.card_advanced);
        for (String label : new String[]{"Switch on big beats", "Skip heavy presets", "Skip deaf presets",
                "Show what's playing", "Play demo songs"}) {
            View row = onUi(a -> {
                View r = rowLabelled(a.findViewById(R.id.card_advanced), label);
                r.requestFocus();
                return r;
            });
            assertNotNull(label, row);
            assertSame(label, row, onUi(a -> a.getCurrentFocus()));
            boolean on = onUi(a -> row.isActivated());
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            assertSame(label + ": focus moved", row, onUi(a -> a.getCurrentFocus()));
            if (!label.equals("Play demo songs")) {
                assertEquals(label + ": switch did not flip", !on, onUi(a -> row.isActivated()));
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER);  // and back
            assertSame(label + ": focus moved", row, onUi(a -> a.getCurrentFocus()));
            assertEquals(label, on, onUi(a -> row.isActivated()));
            assertPanel(MenuPanel.ADVANCED, R.id.card_advanced);
        }
    }

    // --- helpers ---

    private interface UiCall<T> {
        T call(MainActivity a) throws Exception;
    }

    private <T> T onUi(UiCall<T> call) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        scenario.onActivity(a -> {
            try {
                out.set(call.call(a));
            } catch (Throwable t) {
                err.set(t);
            }
        });
        if (err.get() != null) throw new AssertionError(err.get());
        return out.get();
    }

    private static void key(int code) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** OK opens the bar on Favourite; `rights` steps reach the button; OK presses it. */
    private void openBarButton(int id, int rights) {
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        for (int i = 0; i < rights; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocus(id);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    private void addFavourites(int n) {
        onUi(a -> {
            Settings s = (Settings) field(a, "settings");
            List<File> all = library(a).all;
            for (int i = 0; i < n; i++) s.setFavourite(all.get(i).getName(), true);
            return null;
        });
    }

    private void assertFocus(int id) {
        int focused = onUi(a -> a.getCurrentFocus() == null ? View.NO_ID : a.getCurrentFocus().getId());
        String name = InstrumentationRegistry.getInstrumentation().getTargetContext().getResources()
                .getResourceEntryName(id);
        assertEquals("focus should be on " + name, id, focused);
    }

    private void assertFocusInside(View card) {
        View f = onUi(a -> a.getCurrentFocus());
        assertNotNull("nothing focused", f);
        assertTrue("focus " + f + " is outside the panel", inside(f, card));
    }

    /** Panel `p` is open, its card is the only one shown, and the focus is inside it. */
    private void assertPanel(int p, int cardId) {
        assertEquals(p, (int) onUi(a -> menu(a).panel()));
        View card = onUi(a -> a.findViewById(cardId));
        assertOnlyPanel(card);
        assertFocusInside(card);
    }

    /** The menu is open with `card` VISIBLE and every other card GONE (null: just the bar). */
    private void assertOnlyPanel(View card) {
        onUi(a -> {
            assertEquals("menu", View.VISIBLE, a.findViewById(R.id.menu).getVisibility());
            List<View> cards = new ArrayList<>();
            for (int id : new int[]{R.id.card_settings, R.id.card_advanced, R.id.card_find, R.id.card_search,
                    R.id.about}) {
                cards.add(a.findViewById(id));
            }
            cards.add(findPanel(a).view());
            cards.add(zeroPanel(a).view());
            for (View c : cards) {
                int want = c == card ? View.VISIBLE : View.GONE;
                assertEquals(c + (want == View.GONE ? " should be GONE" : " should be VISIBLE"), want, c.getVisibility());
            }
            if (card == null) assertEquals(MenuPanel.NONE, menu(a).panel());
            return null;
        });
    }

    private static void assertSameViews(List<View> before, List<View> after) {
        assertEquals("view count changed", before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertSame("view " + i + " was rebuilt: " + before.get(i), before.get(i), after.get(i));
        }
    }

    /** Every view under `root`, depth first. */
    private static List<View> tree(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) out.addAll(tree(g.getChildAt(i)));
        }
        return out;
    }

    /** A focusable row whose first text is `label`. */
    private static View rowLabelled(View root, String label) {
        for (View v : tree(root)) {
            if (v instanceof LinearLayout && v.isFocusable() && ((LinearLayout) v).getChildCount() > 0
                    && ((LinearLayout) v).getChildAt(0) instanceof TextView
                    && label.contentEquals(((TextView) ((LinearLayout) v).getChildAt(0)).getText())) {
                return v;
            }
        }
        return null;
    }

    private static boolean inside(View v, View ancestor) {
        if (v == ancestor) return true;
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static MenuPanel menu(MainActivity a) {
        return (MenuPanel) field(a, "menu");
    }

    private static FindPanel findPanel(MainActivity a) {
        return (FindPanel) field(menu(a), "lists");
    }

    private static ZeroPanel zeroPanel(MainActivity a) {
        return (ZeroPanel) field(menu(a), "zero");
    }

    private static SearchPanel search(MainActivity a) {
        return (SearchPanel) field(menu(a), "searchPanel");
    }

    private static PresetLibrary library(MainActivity a) {
        return (PresetLibrary) field(a, "library");
    }

    private static Object field(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(name, e);
        }
    }
}

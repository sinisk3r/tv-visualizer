package io.github.sinisk3r.visualizer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE;
import static androidx.test.espresso.matcher.ViewMatchers.hasFocus;
import static androidx.test.espresso.matcher.ViewMatchers.isActivated;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewGroup;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The transport bar on the emulator (AVD tv34): focus order, the settings card and its pages, Back. Keys go
 * through the activity's dispatchKeyEvent, as the remote's do. Run: ./gradlew connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4.class)
public class MenuBarTest {
    @Rule
    public GrantPermissionRule audio = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    @Rule
    public ActivityScenarioRule<MainActivity> rule = new ActivityScenarioRule<>(MainActivity.class);

    @Before
    public void waitForVisuals() {
        // The preset scan runs off the main thread; the menu exists once the GL view is on the stage. Keys sent
        // before the window has input focus are dropped (the first OK was lost on a fast emulator), so wait for both.
        long until = SystemClock.uptimeMillis() + 30_000;
        boolean[] ready = {false};
        while (!ready[0] && SystemClock.uptimeMillis() < until) {
            rule.getScenario().onActivity(a -> ready[0] = ((ViewGroup) a.findViewById(R.id.stage)).getChildCount() > 0
                    && a.hasWindowFocus());
            if (!ready[0]) SystemClock.sleep(200);
        }
        assertTrue("visuals did not start", ready[0]);
    }

    private static void key(int code) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void focused(int id) {
        onView(withId(id)).check(matches(hasFocus()));
    }

    private static void shown(int id) {
        onView(withId(id)).check(matches(withEffectiveVisibility(VISIBLE)));
    }

    private static void gone(int id) {
        onView(withId(id)).check(matches(withEffectiveVisibility(GONE)));
    }

    private static void openSettingsCard() {
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        for (int i = 0; i < 5; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        focused(R.id.b_settings);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        shown(R.id.card_settings);
    }

    @Test
    public void okOpensTheBarOnFavouriteAndLeftRightWalkIt() {
        gone(R.id.menu);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        shown(R.id.menu);
        focused(R.id.b_fav);

        int[] right = {R.id.b_hold, R.id.b_shuffle, R.id.b_find, R.id.b_search, R.id.b_settings, R.id.b_hide};
        for (int id : right) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            focused(id);
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        focused(R.id.b_hide);  // the end of the bar

        for (int id : new int[]{R.id.b_settings, R.id.b_search, R.id.b_find, R.id.b_shuffle, R.id.b_hold,
                R.id.b_fav, R.id.b_next, R.id.b_prev}) {
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            focused(id);
        }
        key(KeyEvent.KEYCODE_DPAD_UP);
        focused(R.id.m_prev);  // the music card above the bar
        gone(R.id.m_seek);     // no notification access on the emulator: no scrubber, no hint
        gone(R.id.m_hint);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        focused(R.id.m_next);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        focused(R.id.m_ask);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        focused(R.id.b_prev);  // back to the button Up came from

        key(KeyEvent.KEYCODE_BACK);
        gone(R.id.menu);
    }

    @Test
    public void settingsCardOpensOnTheTimeChoiceAndBackClosesItThenTheBar() {
        openSettingsCard();
        onView(withId(R.id.b_settings)).check(matches(isActivated()));
        gone(R.id.bar_time);
        shown(R.id.bar_hint);

        key(KeyEvent.KEYCODE_DPAD_DOWN);  // time chips to picture chips
        key(KeyEvent.KEYCODE_DPAD_DOWN);  // to the two rows
        key(KeyEvent.KEYCODE_DPAD_DOWN);  // off the card: back to the button that opened it
        focused(R.id.b_settings);
        shown(R.id.card_settings);

        key(KeyEvent.KEYCODE_BACK);
        gone(R.id.card_settings);
        shown(R.id.menu);
        shown(R.id.bar_time);
        focused(R.id.b_settings);

        key(KeyEvent.KEYCODE_BACK);
        gone(R.id.menu);
    }

    @Test
    public void liveAndAdvancedAndAboutOpenFromTheCardAndBackReturnsToIt() {
        openSettingsCard();
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        focused(R.id.row_live);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        shown(R.id.card_advanced);
        gone(R.id.card_settings);
        shown(R.id.fixed_size);

        key(KeyEvent.KEYCODE_BACK);
        shown(R.id.card_settings);
        gone(R.id.card_advanced);
        focused(R.id.row_live);

        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        focused(R.id.row_about);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        shown(R.id.about);
        gone(R.id.bar);
        gone(R.id.card_settings);

        key(KeyEvent.KEYCODE_BACK);
        gone(R.id.about);
        shown(R.id.bar);
        shown(R.id.card_settings);
        focused(R.id.row_about);
    }

    @Test
    public void findOpensAndBackClosesIt() {
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        for (int i = 0; i < 3; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        focused(R.id.b_find);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        // Find is FindPanel's card (no id; MenuPanelsTest checks it); card_find is only a style's page now.
        onView(withId(R.id.b_find)).check(matches(isActivated()));
        gone(R.id.card_find);
        key(KeyEvent.KEYCODE_BACK);
        gone(R.id.card_find);
        focused(R.id.b_find);
    }

    @Test
    public void aPresetChangeShowsTheNowPlayingTag() {
        // The tag from the launch may still be up: wait for it to fade (4 s and a 0.6 s fade).
        long until = SystemClock.uptimeMillis() + 8_000;
        boolean[] up = {true};
        while (up[0] && SystemClock.uptimeMillis() < until) {
            rule.getScenario().onActivity(a -> up[0] = a.findViewById(R.id.overlay).getVisibility() == android.view.View.VISIBLE);
            if (up[0]) SystemClock.sleep(250);
        }
        gone(R.id.overlay);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        // The renderer reports the change from the GL thread; give it a moment.
        SystemClock.sleep(1000);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        shown(R.id.overlay);
    }
}

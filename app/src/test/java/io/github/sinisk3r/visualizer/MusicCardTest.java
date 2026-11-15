package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

/** The music card's focus path and the notification-access flow (no access in tests). Robolectric has no window
 * focus, so the focused view is read with findFocus(). */
@RunWith(RobolectricTestRunner.class)
public class MusicCardTest {
    private Activity act;
    private FrameLayout root;
    private LinearLayout bar;
    private View scrim, fav, settingsButton;
    private MusicCard card;

    @Before
    public void setUp() {
        // A TV has no touch mode; in touch mode only focusableInTouchMode views take focus.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        act = Robolectric.buildActivity(Activity.class).setup().get();
        root = new FrameLayout(act);
        scrim = new View(act);
        root.addView(scrim);
        bar = new LinearLayout(act);
        fav = focusable(R.id.b_fav);
        settingsButton = focusable(R.id.b_settings);
        bar.addView(fav, new LinearLayout.LayoutParams(50, 50));
        bar.addView(settingsButton, new LinearLayout.LayoutParams(50, 50));
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60));
        act.setContentView(root);
        card = new MusicCard(act, root, bar, scrim, () -> false);
        card.show();
        ShadowLooper.idleMainLooper();  // laid out: views take focus
        assertTrue(fav.requestFocus());
    }

    private View focusable(int id) {
        View v = new View(act);
        v.setId(id);
        v.setFocusable(true);
        return v;
    }

    private boolean press(int code) {
        return card.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, code), root.findFocus());
    }

    private View view(int id) {
        return act.findViewById(id);
    }

    @Test
    public void withoutAScrubberUpLandsOnPreviousAndDownGoesBackThere() {
        settingsButton.requestFocus();
        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(R.id.m_prev, root.findFocus().getId());
        // Without access, Down passes the "See song and album art" row first.
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(R.id.m_ask, root.findFocus().getId());
        assertTrue("Up from the ask row: Previous/Next", press(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(R.id.m_prev, root.findFocus().getId());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(settingsButton, root.findFocus());
    }

    @Test
    public void leftAndRightStayOnPreviousAndNext() {
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue("no wrap or escape at the left end", press(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(R.id.m_prev, root.findFocus().getId());
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(R.id.m_next, root.findFocus().getId());
        assertTrue("no play/pause or mute: Next ends the row", press(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(R.id.m_next, root.findFocus().getId());
        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals("Up on the top row stays", R.id.m_next, root.findFocus().getId());
        press(KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(R.id.m_prev, root.findFocus().getId());
    }

    @Test
    public void scrubberHintIsGoneWithoutTheScrubber() {
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(View.GONE, view(R.id.m_hint).getVisibility());
    }

    @Test
    public void barLeftAndRightAreLeftToTheBar() {
        assertFalse(press(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertFalse(press(KeyEvent.KEYCODE_DPAD_DOWN));
    }

    @Test
    public void hiddenCardIgnoresKeysAndIsGone() {
        card.hide();
        assertFalse(press(KeyEvent.KEYCODE_DPAD_UP));
        assertFalse(view(R.id.m_prev).isShown());
    }

    @Test
    public void askingExplainsFirstThenOpensNotificationAccessSettings() {
        view(R.id.m_ask).performClick();
        assertEquals(View.GONE, bar.getVisibility());
        assertEquals(R.id.m_open_settings, root.findFocus().getId());
        assertTrue("arrows stay in the explainer", press(KeyEvent.KEYCODE_DPAD_UP));
        assertTrue(card.back());  // Back closes it, back on the row
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(R.id.m_ask, root.findFocus().getId());
        assertFalse(card.back());

        view(R.id.m_ask).performClick();
        view(R.id.m_open_settings).performClick();
        Intent next = Shadows.shadowOf(act).getNextStartedActivity();
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, next.getAction());

        // Once explained, the row goes straight to Settings.
        view(R.id.m_ask).performClick();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                Shadows.shadowOf(act).getNextStartedActivity().getAction());
    }

    @Test
    public void withoutAccessShowsPreviousNextAndTheAskRowButNoScrubber() {
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, view(R.id.m_ask).getVisibility());
        assertTrue(view(R.id.m_prev).isShown());
        assertTrue(view(R.id.m_next).isShown());
        assertEquals(View.GONE, view(R.id.m_seek).getVisibility());
        card.tick();
        assertEquals(View.GONE, view(R.id.m_seek).getVisibility());
    }
}

package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
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

import java.util.ArrayList;
import java.util.List;

/** The music card with notification access and a playing app (screen 1s-E): the scrubber's keys and values. */
@RunWith(RobolectricTestRunner.class)
public class MusicCardScrubTest {
    private Activity act;
    private FrameLayout root;
    private View fav, settingsButton;
    private MusicCard card;
    private MediaSession session;
    private final List<String> calls = new ArrayList<>();

    @Before
    public void setUp() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        act = Robolectric.buildActivity(Activity.class).setup().get();
        Settings.Secure.putString(act.getContentResolver(), "enabled_notification_listeners",
                act.getPackageName() + "/" + MusicListener.class.getName());

        session = new MediaSession(act, "test");
        session.setActive(true);
        MediaController c = new MediaController(act, session.getSessionToken());
        Shadows.shadowOf(c).setPackageName("com.spotify.tv.android");
        Shadows.shadowOf(c).setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Song")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Album")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 213_000).build());
        Shadows.shadowOf(c).setPlaybackState(state(PlaybackState.STATE_PLAYING, 141_000));
        Shadows.shadowOf(act.getSystemService(MediaSessionManager.class)).addController(c);
        controller = c;

        root = new FrameLayout(act);
        View scrim = new View(act);
        root.addView(scrim);
        LinearLayout bar = new LinearLayout(act);
        fav = focusable(R.id.b_fav);
        settingsButton = focusable(R.id.b_settings);
        bar.addView(fav, new LinearLayout.LayoutParams(50, 50));
        bar.addView(settingsButton, new LinearLayout.LayoutParams(50, 50));
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60));
        act.setContentView(root);
        card = new MusicCard(act, root, bar, scrim, () -> false);
        card.transport = (ctl, command, pos) -> calls.add(command.equals("seek") ? "seek " + pos : command);
        card.show();
        ShadowLooper.idleMainLooper();
        assertTrue(settingsButton.requestFocus());
    }

    private MediaController controller;

    /** Paused or playing at `pos`, reported now, able to seek. */
    private static PlaybackState state(int s, long pos) {
        return new PlaybackState.Builder()
                .setState(s, pos, 0f, SystemClock.elapsedRealtime())  // speed 0: the position stays put
                .setActions(PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_PLAY_PAUSE).build();
    }

    private View focusable(int id) {
        View v = new View(act);
        v.setId(id);
        v.setFocusable(true);
        return v;
    }

    private boolean key(int action, int code) {
        return card.handleKey(new KeyEvent(action, code), root.findFocus());
    }

    private boolean press(int code) {
        return key(KeyEvent.ACTION_DOWN, code);
    }

    private ScrubView scrub() {
        return act.findViewById(R.id.m_seek);
    }

    @Test
    public void upLandsOnTheScrubberWithItsHintAndUpAgainOnPrevious() {
        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(R.id.m_seek, root.findFocus().getId());
        assertEquals(View.VISIBLE, act.findViewById(R.id.m_hint).getVisibility());
        assertEquals(View.GONE, act.findViewById(R.id.m_ask).getVisibility());

        press(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(R.id.m_prev, root.findFocus().getId());
        assertEquals("the hint only while the scrubber has focus", View.GONE,
                act.findViewById(R.id.m_hint).getVisibility());
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(R.id.m_next, root.findFocus().getId());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(R.id.m_seek, root.findFocus().getId());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("back to the bar button Up came from", settingsButton, root.findFocus());
    }

    @Test
    public void showsTimesAndProgress() {
        assertEquals("2:21", scrub().elapsedText());
        assertEquals("3:33", scrub().totalText());
        assertEquals(NowPlaying.progress(141_000, 213_000), scrub().progress());
    }

    @Test
    public void pausedReadsPaused() {
        Shadows.shadowOf(controller).setPlaybackState(state(PlaybackState.STATE_PAUSED, 141_000));
        card.tick();
        assertEquals("Paused · 2:21", scrub().elapsedText());
    }

    @Test
    public void okPausesThroughTheSession() {
        press(KeyEvent.KEYCODE_DPAD_UP);
        root.findFocus().performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("pause", calls.get(0));
    }

    @Test
    public void heldRightShowsTheTargetAndSeeksOnlyWhenLetGo() {
        press(KeyEvent.KEYCODE_DPAD_UP);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("2:41", scrub().pillText());
        assertEquals("2:41", scrub().elapsedText());
        ShadowLooper.idleMainLooper();
        assertTrue("no seek while held: " + calls, calls.isEmpty());

        assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
        ShadowLooper.idleMainLooper();
        assertEquals("seek 161000", calls.get(0));
        assertNull(scrub().pillText());
    }

    @Test
    public void aHeldSeekIsSentEvenIfTheKeyUpIsLost() {
        press(KeyEvent.KEYCODE_DPAD_UP);
        press(KeyEvent.KEYCODE_DPAD_LEFT);
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("seek 131000", calls.get(0));
    }

    @Test
    public void withoutASessionTheScrubberGoesAndFocusMovesToPrevious() {
        press(KeyEvent.KEYCODE_DPAD_UP);
        Shadows.shadowOf(act.getSystemService(MediaSessionManager.class)).clearControllers();
        card.tick();
        assertEquals(View.GONE, scrub().getVisibility());
        assertEquals(R.id.m_prev, root.findFocus().getId());
    }
}

package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.session.PlaybackState;

import org.junit.Test;

/** The music card's logic: which session, play state, text. */
public class NowPlayingTest {
    private static final String OWN = "io.github.sinisk3r.visualizer";

    @Test
    public void playbackStatesMapToThePlayButton() {
        assertEquals(NowPlaying.PLAYING, NowPlaying.state(PlaybackState.STATE_PLAYING));
        assertEquals(NowPlaying.PLAYING, NowPlaying.state(PlaybackState.STATE_BUFFERING));
        assertEquals(NowPlaying.PLAYING, NowPlaying.state(PlaybackState.STATE_SKIPPING_TO_NEXT));
        assertEquals(NowPlaying.PLAYING, NowPlaying.state(PlaybackState.STATE_CONNECTING));
        assertEquals(NowPlaying.PAUSED, NowPlaying.state(PlaybackState.STATE_PAUSED));
        assertEquals(NowPlaying.STOPPED, NowPlaying.state(PlaybackState.STATE_STOPPED));
        assertEquals(NowPlaying.STOPPED, NowPlaying.state(PlaybackState.STATE_NONE));
        assertEquals(NowPlaying.STOPPED, NowPlaying.state(PlaybackState.STATE_ERROR));
    }

    @Test
    public void picksThePlayingSessionOverAPausedOneListedFirst() {
        String[] p = {"com.google.android.youtube.tvmusic", "com.spotify.tv.android"};
        int[] s = {PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING};
        assertEquals(1, NowPlaying.pick(p, s, OWN));
    }

    @Test
    public void withNothingPlayingPicksTheFirstPausedThenAnyInSystemOrder() {
        String[] p = {"a.stopped", "b.paused", "c.paused"};
        assertEquals(1, NowPlaying.pick(p, new int[]{PlaybackState.STATE_STOPPED, PlaybackState.STATE_PAUSED,
                PlaybackState.STATE_PAUSED}, OWN));
        assertEquals(0, NowPlaying.pick(p, new int[]{PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE,
                PlaybackState.STATE_ERROR}, OWN));
    }

    @Test
    public void twoPlayingKeepsTheSystemsMostRecentFirst() {
        String[] p = {"first", "second"};
        int[] s = {PlaybackState.STATE_PLAYING, PlaybackState.STATE_PLAYING};
        assertEquals(0, NowPlaying.pick(p, s, OWN));
    }

    @Test
    public void ourOwnSessionIsNeverShown() {
        String[] p = {OWN, "com.spotify.tv.android"};
        int[] s = {PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED};
        assertEquals(1, NowPlaying.pick(p, s, OWN));
        assertEquals(-1, NowPlaying.pick(new String[]{OWN}, new int[]{PlaybackState.STATE_PLAYING}, OWN));
        assertEquals(-1, NowPlaying.pick(new String[0], new int[0], OWN));
    }

    @Test
    public void accessIsReadFromTheEnabledListenersSetting() {
        assertTrue(NowPlaying.hasAccess("com.other/.L:" + OWN + "/" + OWN + ".MusicListener", OWN));
        assertTrue(NowPlaying.hasAccess(OWN + "/.MusicListener", OWN));
        assertFalse(NowPlaying.hasAccess("com.other/.L", OWN));
        assertFalse("a package that only starts with ours", NowPlaying.hasAccess(OWN + "2/.X", OWN));
        assertFalse(NowPlaying.hasAccess(null, OWN));
        assertFalse(NowPlaying.hasAccess("", OWN));
    }

    @Test
    public void titleFallsBackToDisplayTitleThenTheApp() {
        assertEquals("Song", NowPlaying.title(" Song ", "Display", "Spotify"));
        assertEquals("Display", NowPlaying.title("", "Display", "Spotify"));
        assertEquals("Spotify", NowPlaying.title(null, null, "Spotify"));
        assertEquals("Something is playing", NowPlaying.title(null, " ", ""));
    }

    @Test
    public void whoJoinsArtistAndAlbum() {
        assertEquals("Artist · Album", NowPlaying.who("Artist", "Album"));
        assertEquals("Artist", NowPlaying.who("Artist", null));
        assertEquals("Album", NowPlaying.who(" ", "Album"));
        assertEquals("", NowPlaying.who(null, ""));
    }

    @Test
    public void appNameFromPackageSkipsGenericWords() {
        assertEquals("Spotify", NowPlaying.appFromPackage("com.spotify.tv.android"));
        assertEquals("Soundcloud", NowPlaying.appFromPackage("com.soundcloud.android"));
        assertEquals("", NowPlaying.appFromPackage(null));
    }

    // --- the scrubber ---

    @Test
    public void scrubberNeedsAccessSeekAndALength() {
        long seek = PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_PLAY_PAUSE;
        assertEquals(PlaybackState.ACTION_SEEK_TO, NowPlaying.ACTION_SEEK_TO);
        assertTrue(NowPlaying.canSeek(true, seek, 200_000));
        assertFalse("no access", NowPlaying.canSeek(false, seek, 200_000));
        assertFalse("no seek", NowPlaying.canSeek(true, PlaybackState.ACTION_PLAY_PAUSE, 200_000));
        assertFalse("no length (a stream)", NowPlaying.canSeek(true, seek, 0));
        assertFalse(NowPlaying.canSeek(true, seek, -1));
    }

    @Test
    public void positionMovesOnWhilePlaying() {
        assertEquals(15_000, NowPlaying.position(10_000, 1_000, 1f, true, 6_000, 200_000));
        assertEquals(20_000, NowPlaying.position(10_000, 1_000, 2f, true, 6_000, 200_000));
        assertEquals("paused: as reported", 10_000, NowPlaying.position(10_000, 1_000, 1f, false, 6_000, 200_000));
        assertEquals("never past the end", 200_000, NowPlaying.position(199_000, 1_000, 1f, true, 9_000, 200_000));
        assertEquals("unknown counts as 0", 0, NowPlaying.position(-1, 1_000, 0f, false, 6_000, 200_000));
        assertEquals("a report from the future does not go back", 10_000,
                NowPlaying.position(10_000, 7_000, 1f, true, 6_000, 200_000));
    }

    @Test
    public void heldKeysStepFurther() {
        assertEquals(10_000, NowPlaying.seekStep(0));
        assertEquals(10_000, NowPlaying.seekStep(1_499));
        assertEquals(30_000, NowPlaying.seekStep(1_500));
        assertEquals(30_000, NowPlaying.seekStep(9_000));
    }

    @Test
    public void quickStepsBuildOnTheLastTarget() {
        assertEquals("the app has not caught up", 40_000, NowPlaying.seekBase(40_000, 1_000, 1_400, 31_000));
        assertEquals(31_000, NowPlaying.seekBase(40_000, 1_000, 2_600, 31_000));
        assertEquals("no seek yet", 31_000, NowPlaying.seekBase(-1, 0, 100, 31_000));
    }

    @Test
    public void seekStaysInsideTheSong() {
        assertEquals(40_000, NowPlaying.seekTarget(30_000, 10_000, 200_000));
        assertEquals(0, NowPlaying.seekTarget(5_000, -10_000, 200_000));
        assertEquals(200_000, NowPlaying.seekTarget(195_000, 30_000, 200_000));
    }

    @Test
    public void progressAndTimes() {
        assertEquals(500, NowPlaying.progress(100_000, 200_000));
        assertEquals(1000, NowPlaying.progress(300_000, 200_000));
        assertEquals(0, NowPlaying.progress(5_000, 0));
        assertEquals("0:00", NowPlaying.time(0));
        assertEquals("0:07", NowPlaying.time(7_900));
        assertEquals("3:25", NowPlaying.time(205_000));
        assertEquals("59:59", NowPlaying.time(3_599_000));
        assertEquals("1:02:03", NowPlaying.time(3_723_000));
        assertEquals("0:00", NowPlaying.time(-5));
    }

    @Test
    public void pausedTimeSaysSo() {
        assertEquals("2:21", NowPlaying.elapsedLabel(141_000, false));
        assertEquals("Paused · 2:21", NowPlaying.elapsedLabel(141_000, true));
        assertEquals("OK pause · ◀ ▶ 10 s · ▲ previous / next", NowPlaying.scrubHint(false));
        assertTrue(NowPlaying.scrubHint(true).startsWith("OK play ·"));
    }

    @Test
    public void thumbSitsAtThePlayheadInsideTheTrack() {
        assertEquals(10f, NowPlaying.thumbX(0, 10, 210), 0.001f);
        assertEquals(110f, NowPlaying.thumbX(500, 10, 210), 0.001f);
        assertEquals(210f, NowPlaying.thumbX(1000, 10, 210), 0.001f);
        assertEquals("clamped", 210f, NowPlaying.thumbX(1200, 10, 210), 0.001f);
        assertEquals("clamped", 10f, NowPlaying.thumbX(-5, 10, 210), 0.001f);
    }

    @Test
    public void seekPillCentresOnTheThumbButStaysInside() {
        assertEquals(80f, NowPlaying.pillLeft(100, 40, 0, 220), 0.001f);
        assertEquals("left end", 0f, NowPlaying.pillLeft(5, 40, 0, 220), 0.001f);
        assertEquals("right end", 180f, NowPlaying.pillLeft(215, 40, 0, 220), 0.001f);
    }

    @Test
    public void scrubberShowsForAPlayingOrPausedSessionOrOneThatSeeks() {
        assertTrue(NowPlaying.showScrubber(PlaybackState.STATE_PLAYING, 0, 0));
        assertTrue(NowPlaying.showScrubber(PlaybackState.STATE_PAUSED, 0, 0));
        assertTrue(NowPlaying.showScrubber(PlaybackState.STATE_STOPPED, NowPlaying.ACTION_SEEK_TO, 200_000));
        assertFalse("a Bluetooth session in error", NowPlaying.showScrubber(PlaybackState.STATE_ERROR, 0, 0));
        assertFalse(NowPlaying.showScrubber(PlaybackState.STATE_NONE, NowPlaying.ACTION_SEEK_TO, 0));
    }
}

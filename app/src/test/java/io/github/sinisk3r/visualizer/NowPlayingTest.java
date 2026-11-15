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
}

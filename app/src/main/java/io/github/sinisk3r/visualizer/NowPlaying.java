package io.github.sinisk3r.visualizer;

/**
 * The music card's logic, kept free of views and media sessions so it can be tested on the JVM: which app's
 * session to show, what its playback state means for the play/pause button, the card's text, and the scrubber's
 * time maths (position, seek steps, clamping, formatting).
 */
final class NowPlaying {
    /** What the play/pause button shows: STOPPED and PAUSED show Play, PLAYING shows Pause. */
    static final int STOPPED = 0, PAUSED = 1, PLAYING = 2;

    private NowPlaying() {
    }

    /** android.media.session.PlaybackState's STATE_* values, mapped to the three the card knows. */
    static int state(int playbackState) {
        switch (playbackState) {
            case 3:   // PLAYING
            case 4:   // FAST_FORWARDING
            case 5:   // REWINDING
            case 6:   // BUFFERING
            case 8:   // CONNECTING
            case 9:   // SKIPPING_TO_PREVIOUS
            case 10:  // SKIPPING_TO_NEXT
            case 11:  // SKIPPING_TO_QUEUE_ITEM
                return PLAYING;
            case 2:   // PAUSED
                return PAUSED;
            default:  // NONE, STOPPED, ERROR
                return STOPPED;
        }
    }

    /**
     * The session to show, from the active sessions in the system's order (most recently active first): the first
     * one playing, else the first one paused, else the first one at all. Our own app is skipped. -1 when none.
     */
    static int pick(String[] packages, int[] states, String own) {
        int paused = -1, other = -1;
        for (int i = 0; i < packages.length; i++) {
            if (own != null && own.equals(packages[i])) continue;
            int s = state(states[i]);
            if (s == PLAYING) return i;
            if (s == PAUSED && paused < 0) paused = i;
            if (other < 0) other = i;
        }
        return paused >= 0 ? paused : other;
    }

    /**
     * True when our listener is in Settings.Secure "enabled_notification_listeners": flattened component names
     * ("pkg/cls" or "pkg/.cls") separated by ':'.
     */
    static boolean hasAccess(String enabled, String pkg) {
        if (enabled == null || pkg == null || enabled.isEmpty()) return false;
        for (String entry : enabled.split(":")) {
            if (entry.startsWith(pkg + "/")) return true;
        }
        return false;
    }

    /** The card's first line: the song's title, else the app's name, else a plain fallback. */
    static String title(String title, String displayTitle, String app) {
        if (!blank(title)) return title.trim();
        if (!blank(displayTitle)) return displayTitle.trim();
        if (!blank(app)) return app.trim();
        return "Something is playing";
    }

    /** The second line: "Artist · Album", or whichever of the two is known; "" when neither. */
    static String who(String artist, String album) {
        boolean a = !blank(artist), b = !blank(album);
        if (a && b) return artist.trim() + " · " + album.trim();
        if (a) return artist.trim();
        if (b) return album.trim();
        return "";
    }

    // --- the scrubber ---

    /** PlaybackState.ACTION_SEEK_TO. */
    static final long ACTION_SEEK_TO = 1L << 8;
    /** Left/Right on the scrubber: 10 s a press; held for this long, the step grows to 30 s. */
    static final long STEP_MS = 10_000, BIG_STEP_MS = 30_000, BIG_AFTER_MS = 1_500;
    /** Steps this soon after the last seek build on its target, not on the app's position (which lags). */
    static final long SEEK_CHAIN_MS = 1_500;

    /** The scrubber shows only with access, a session that can seek, and a known song length. */
    static boolean canSeek(boolean access, long actions, long durationMs) {
        return access && (actions & ACTION_SEEK_TO) != 0 && durationMs > 0;
    }

    /**
     * Position now: the session's last report, moved on by speed times the time since (only while it plays; times
     * on the elapsedRealtime clock). Clamped to 0..duration. An unknown position (-1) counts as 0.
     */
    static long position(long reported, long reportedAt, float speed, boolean playing, long now, long durationMs) {
        long pos = Math.max(0, reported);
        if (playing && reportedAt > 0 && now > reportedAt) pos += (long) (speed * (now - reportedAt));
        return clamp(pos, durationMs);
    }

    /** The step for a press held this long (0: a single press). */
    static long seekStep(long heldMs) {
        return heldMs >= BIG_AFTER_MS ? BIG_STEP_MS : STEP_MS;
    }

    /** Where a seek starts: the last seek's target while steps follow quickly, else the position now. */
    static long seekBase(long lastTarget, long lastSeekAt, long now, long position) {
        return lastTarget >= 0 && now - lastSeekAt < SEEK_CHAIN_MS ? lastTarget : position;
    }

    /** `from` moved by `delta`, kept inside the song. */
    static long seekTarget(long from, long delta, long durationMs) {
        return clamp(from + delta, durationMs);
    }

    /** The bar's fill, 0..1000. */
    static int progress(long pos, long durationMs) {
        if (durationMs <= 0) return 0;
        return (int) (clamp(pos, durationMs) * 1000 / durationMs);
    }

    /** "0:07", "3:25", "1:02:03". */
    static String time(long ms) {
        long sec = Math.max(0, ms) / 1000;
        long h = sec / 3600, m = sec / 60 % 60, s = sec % 60;
        String ss = (s < 10 ? "0" : "") + s;
        if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + ss;
        return m + ":" + ss;
    }

    private static long clamp(long pos, long durationMs) {
        if (pos < 0) return 0;
        return durationMs > 0 && pos > durationMs ? durationMs : pos;
    }

    /**
     * An app's name when the system cannot tell us (package not visible): the last word of the package that is not
     * a generic one, capitalised. "com.spotify.tv.android" gives "Spotify".
     */
    static String appFromPackage(String pkg) {
        if (blank(pkg)) return "";
        String[] parts = pkg.split("\\.");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (p.isEmpty() || GENERIC.contains("," + p.toLowerCase() + ",")) continue;
            return Character.toUpperCase(p.charAt(0)) + p.substring(1);
        }
        return pkg;
    }

    private static final String GENERIC = ",com,org,net,io,android,tv,app,apps,mobile,leanback,atv,google,amazon,";

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

package io.github.sinisk3r.visualizer;

/**
 * The music card's logic, kept free of views and media sessions so it can be tested on the JVM: which app's
 * session to show, what its playback state means for the play/pause button, and the card's text.
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

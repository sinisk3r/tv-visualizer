package io.github.sinisk3r.visualizer;

/**
 * Demo songs for trying the visuals with no music app playing ("Play demo songs" in Live and advanced). They
 * stream from the composer's site when chosen; nothing ships in the app. Kevin MacLeod (incompetech.com),
 * licensed CC BY 4.0: credited in About and on screen while one plays.
 */
final class DemoSongs {
    static final String CREDIT = "Kevin MacLeod (incompetech.com), CC BY 4.0";
    private static final String BASE = "https://incompetech.com/music/royalty-free/mp3-royaltyfree/";

    static final String[] TITLES = {
            "Monkeys Spinning Monkeys",
            "Sneaky Snitch",
            "Pixel Peeker Polka - faster",
            "Local Forecast - Elevator",
    };

    private DemoSongs() {
    }

    static String url(int i) {
        return BASE + TITLES[Math.floorMod(i, TITLES.length)].replace(" ", "%20") + ".mp3";
    }

    static String title(int i) {
        return TITLES[Math.floorMod(i, TITLES.length)];
    }

    /** The song after this one; the list repeats. */
    static int next(int i) {
        return Math.floorMod(i + 1, TITLES.length);
    }
}

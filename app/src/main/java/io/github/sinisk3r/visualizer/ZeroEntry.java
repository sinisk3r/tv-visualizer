package io.github.sinisk3r.visualizer;

/**
 * Numbers that start with 0 (the 0-list): "0" is Favourites, "0n" / "0nn" the n-th favourite, "00" the playlists,
 * "00n" / "00nn" the n-th playlist (001 Featured, 002 Most watched, 003 New to you, 004 Recently played). Presets
 * keep 1 to 9,791 (ChannelEntry). No Android types, so it is tested on the JVM.
 */
final class ZeroEntry {
    enum Kind { FAVOURITES, PLAYLISTS, FAVOURITE, PLAYLIST }

    /** What the digits typed so far point at. `index` is 0-based (favourite or playlist); -1 for a whole list. */
    static final class Target {
        final Kind kind;
        final int index;

        Target(Kind kind, int index) {
            this.kind = kind;
            this.index = index;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Target && ((Target) o).kind == kind && ((Target) o).index == index;
        }

        @Override
        public int hashCode() {
            return kind.hashCode() * 31 + index;
        }

        @Override
        public String toString() {
            return kind + " " + index;
        }
    }

    /** "0" plus up to two digits: favourites 01 to 099. Further digits start over at "0". */
    static final int MAX_FAV_DIGITS = 3;
    /** "00" plus up to two digits: playlists 001 to 0099. */
    static final int MAX_PLAYLIST_DIGITS = 4;

    private String typed = "0";

    /** Starts over at "0" (the key that opened the list). */
    void reset() {
        typed = "0";
    }

    String typed() {
        return typed;
    }

    /** The digits with a cursor: "00_". */
    String shown() {
        return typed + "_";
    }

    /** Adds a digit and returns the new target. A digit that would make the code too long starts a new one. */
    Target add(int digit) {
        if (digit < 0 || digit > 9) throw new IllegalArgumentException("digit " + digit);
        int max = typed.startsWith("00") ? MAX_PLAYLIST_DIGITS : MAX_FAV_DIGITS;
        // "0" + "0" makes "00" (the playlists), which has room for two more digits.
        if (typed.length() >= max) typed = "0";
        typed += digit;
        return target();
    }

    Target target() {
        return parse(typed);
    }

    /** "0" Favourites, "00" Playlists, "01".."099" a favourite, "001".."0099" a playlist. */
    static Target parse(String code) {
        if (code == null || !code.startsWith("0")) throw new IllegalArgumentException(code);
        if (code.equals("0")) return new Target(Kind.FAVOURITES, -1);
        if (code.equals("00")) return new Target(Kind.PLAYLISTS, -1);
        if (code.startsWith("00")) {
            int n = Integer.parseInt(code.substring(2));
            return n == 0 ? new Target(Kind.PLAYLISTS, -1) : new Target(Kind.PLAYLIST, n - 1);  // "000": still the list
        }
        return new Target(Kind.FAVOURITE, Integer.parseInt(code.substring(1)) - 1);
    }

    /**
     * What OK does: true plays the highlighted line. That is the line the arrows moved to, or, for "0" and "00"
     * (a whole list), its first line, highlighted when the list opened (favourite 01, or 001 Featured). False plays
     * the typed code ("02", "003"), which may name a line that does not exist.
     */
    static boolean okPlaysHighlighted(Target t, boolean movedSinceDigit) {
        return movedSinceDigit || t.kind == Kind.FAVOURITES || t.kind == Kind.PLAYLISTS;
    }

    /** A favourite's number as shown: 01, 02 ... 12. */
    static String favouriteNumber(int index) {
        return String.format(java.util.Locale.ROOT, "%02d", index + 1);
    }

    /** A playlist's number as shown: 001, 002 ... */
    static String playlistNumber(int index) {
        return String.format(java.util.Locale.ROOT, "%03d", index + 1);
    }
}

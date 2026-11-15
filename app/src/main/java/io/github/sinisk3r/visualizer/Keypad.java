package io.github.sinisk3r.visualizer;

/**
 * The Search panel's number pad: the digits typed so far and where they lead. Same rules as the remote's digits:
 * 0 = Favourites, 0n = favourite n, 00 = playlists, 00n = playlist n, anything else = preset n. No Android types,
 * so it is tested on the JVM.
 */
final class Keypad {
    /** Longest entry: presets have up to 4 digits; 0nn and 00n fit too. Further digits are ignored. */
    static final int MAX_DIGITS = ChannelEntry.MAX_DIGITS;

    enum Kind { NONE, FAVOURITES, FAVOURITE, PLAYLISTS, PLAYLIST, PRESET }

    /** Where the typed digits lead: a list, one entry of a list (number = its line), or a preset (its channel). */
    static final class Target {
        final Kind kind;
        final int number;

        Target(Kind kind, int number) {
            this.kind = kind;
            this.number = number;
        }

        /** Text for list targets ("Favourites", "Favourite 3", "Playlist 1"); null for a preset or nothing. */
        String label() {
            switch (kind) {
                case FAVOURITES: return "Favourites";
                case FAVOURITE: return "Favourite " + number;
                case PLAYLISTS: return "Playlists";
                case PLAYLIST: return "Playlist " + number;
                default: return null;
            }
        }
    }

    private String digits = "";

    String digits() {
        return digits;
    }

    /** Adds a digit; returns false (and changes nothing) when the entry is full. */
    boolean add(int digit) {
        if (digit < 0 || digit > 9) throw new IllegalArgumentException("digit " + digit);
        if (digits.length() >= MAX_DIGITS) return false;
        digits += digit;
        return true;
    }

    void delete() {
        if (!digits.isEmpty()) digits = digits.substring(0, digits.length() - 1);
    }

    void clear() {
        digits = "";
    }

    Target target() {
        return target(digits);
    }

    static Target target(String digits) {
        if (digits.isEmpty()) return new Target(Kind.NONE, 0);
        if (digits.startsWith("00")) {
            int n = number(digits.substring(2));
            return n == 0 ? new Target(Kind.PLAYLISTS, 0) : new Target(Kind.PLAYLIST, n);
        }
        if (digits.startsWith("0")) {
            int n = number(digits.substring(1));
            return n == 0 ? new Target(Kind.FAVOURITES, 0) : new Target(Kind.FAVOURITE, n);
        }
        return new Target(Kind.PRESET, Integer.parseInt(digits));
    }

    private static int number(String s) {
        return s.isEmpty() ? 0 : Integer.parseInt(s);
    }
}

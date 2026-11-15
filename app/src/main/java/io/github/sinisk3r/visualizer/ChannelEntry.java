package io.github.sinisk3r.visualizer;

/**
 * A channel number being typed on the remote's number keys. No Android types, so it is tested on the JVM.
 * The activity shows {@link #shown()}, plays {@link #take()} after a pause or on OK.
 */
final class ChannelEntry {
    /** Longest number: the full pack has 4-digit numbers. A further digit starts a new number. */
    static final int MAX_DIGITS = 4;
    private String typed = "";

    /** Adds a digit (0-9) and returns the number typed so far. */
    int add(int digit) {
        if (digit < 0 || digit > 9) throw new IllegalArgumentException("digit " + digit);
        if (typed.length() >= MAX_DIGITS) typed = "";
        typed += digit;
        return Integer.parseInt(typed);
    }

    boolean isActive() {
        return !typed.isEmpty();
    }

    /** The digits as typed, with a cursor: "24_". */
    String shown() {
        return typed + "_";
    }

    /** Ends the entry and returns the number, or -1 when nothing was typed. */
    int take() {
        if (typed.isEmpty()) return -1;
        int n = Integer.parseInt(typed);
        typed = "";
        return n;
    }
}

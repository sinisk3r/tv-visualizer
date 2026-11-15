package io.github.sinisk3r.visualizer;

/**
 * When the open menu closes itself: 10 s after the last key with only the bar (and the music card) up, 30 s with a
 * card or panel open (settings, Live and advanced, About, Find, a style's page, Search, the music card's explainer).
 * The 0-list keeps its own 10 s (ZeroPanel). Plain logic, tested on the JVM.
 */
final class MenuIdle {
    static final long BAR_MS = 10_000;
    static final long CARD_MS = 30_000;

    private MenuIdle() {
    }

    static long limit(boolean cardOpen) {
        return cardOpen ? CARD_MS : BAR_MS;
    }

    /** Ms left before the menu closes, from the last key (uptime ms); 0 or less: close now. */
    static long remaining(long lastKeyAt, long now, boolean cardOpen) {
        return lastKeyAt + limit(cardOpen) - now;
    }
}

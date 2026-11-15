package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class WatchHistoryTest {
    private static final long DAY = WatchHistory.DAY_MS;
    private static final long NOW = 20_000 * DAY + 5_000;

    @Test
    public void underTenSecondsIsNotWatched() {
        WatchHistory h = new WatchHistory();
        assertFalse(h.add("a.milk", NOW, 9));
        assertTrue(h.add("a.milk", NOW, 10));
        assertEquals(10, h.totalSeconds("a.milk"));
    }

    @Test
    public void sameDayAddsUpIntoOneRow() {
        WatchHistory h = new WatchHistory();
        h.add("a.milk", NOW, 30);
        h.add("a.milk", NOW + 1000, 20);
        h.add("a.milk", NOW - 3 * DAY, 15);
        assertEquals(2, h.size());
        assertEquals(65, h.totalSeconds("a.milk"));
    }

    @Test
    public void mostWatchedLooksBackOnlyTheGivenDays() {
        WatchHistory h = new WatchHistory();
        h.add("old.milk", NOW - 40 * DAY, 3000);  // two months ago: in 3 months, not in the month
        h.add("a.milk", NOW, 100);
        h.add("b.milk", NOW - 29 * DAY, 200);
        h.add("c.milk", NOW, 100);
        assertEquals(Arrays.asList("b.milk", "a.milk", "c.milk"), h.mostWatched(NOW, 30, 10));
        assertEquals(Arrays.asList("old.milk", "b.milk"), h.mostWatched(NOW, 91, 2));
    }

    @Test
    public void recentlyPlayedIsLastShownFirstOncePerPreset() {
        WatchHistory h = new WatchHistory();
        h.add("a.milk", NOW - 2 * DAY, 20);
        h.add("b.milk", NOW - DAY, 20);
        h.add("a.milk", NOW, 20);
        assertEquals(Arrays.asList("a.milk", "b.milk"), h.recentlyPlayed(10));
    }

    @Test
    public void newToYouIsNeverShownFirstThenRarely() {
        WatchHistory h = new WatchHistory();
        h.add("rare.milk", NOW, 20);
        h.add("seen.milk", NOW, 600);
        assertEquals(Arrays.asList("fresh.milk", "rare.milk"),
                h.newToYou(Arrays.asList("seen.milk", "rare.milk", "fresh.milk")));
    }

    @Test
    public void pruneDropsRowsOlderThanThreeMonths() {
        WatchHistory h = new WatchHistory();
        h.add("a.milk", NOW - 100 * DAY, 20);
        h.add("b.milk", NOW, 20);
        h.prune(NOW);
        assertEquals(Collections.singletonList("b.milk"), h.recentlyPlayed(10));
    }

    @Test
    public void roundTripsThroughTheFileAndSkipsBadLines() throws Exception {
        WatchHistory h = new WatchHistory();
        h.add("name with spaces & tabs.milk", NOW, 42);
        h.add("b.milk", NOW - DAY, 12);
        assertTrue(h.isDirty());
        StringWriter w = new StringWriter();
        h.write(w);
        assertFalse(h.isDirty());
        WatchHistory back = WatchHistory.read(new StringReader(w + "garbage\n12\tx\n"));
        assertEquals(2, back.size());
        assertEquals(42, back.totalSeconds("name with spaces & tabs.milk"));
        assertEquals(h.recentlyPlayed(5), back.recentlyPlayed(5));
    }
}

package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChannelEntryTest {
    @Test
    public void digitsBuildANumber() {
        ChannelEntry e = new ChannelEntry();
        assertFalse(e.isActive());
        assertEquals(2, e.add(2));
        assertEquals(24, e.add(4));
        assertEquals(240, e.add(0));
        assertEquals(2403, e.add(3));
        assertEquals("2403_", e.shown());
        assertTrue(e.isActive());
    }

    @Test
    public void aFifthDigitStartsANewNumber() {
        ChannelEntry e = new ChannelEntry();
        for (int d : new int[]{9, 7, 9, 1}) e.add(d);
        assertEquals(5, e.add(5));
        assertEquals("5_", e.shown());
    }

    @Test
    public void takeEndsTheEntry() {
        ChannelEntry e = new ChannelEntry();
        e.add(4);
        e.add(2);
        assertEquals(42, e.take());
        assertFalse(e.isActive());
        assertEquals("nothing typed", -1, e.take());
    }

    @Test
    public void leadingZeroIsDroppedToday() {
        // Today 05 plays preset 5. Agreed for the menu redesign: 0 and 00 start favourites and playlists;
        // change this test with that feature.
        ChannelEntry e = new ChannelEntry();
        e.add(0);
        assertEquals(5, e.add(5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void onlyDigits() {
        new ChannelEntry().add(10);
    }
}

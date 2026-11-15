package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DemoSongsTest {
    @Test
    public void urlsAreHttpsOnTheComposersSiteWithSpacesEncoded() {
        for (int i = 0; i < DemoSongs.TITLES.length; i++) {
            String u = DemoSongs.url(i);
            assertTrue(u, u.startsWith("https://incompetech.com/"));
            assertTrue(u, u.endsWith(".mp3"));
            assertFalse(u, u.contains(" "));
        }
        assertEquals("https://incompetech.com/music/royalty-free/mp3-royaltyfree/Sneaky%20Snitch.mp3",
                DemoSongs.url(1));
    }

    @Test
    public void theListRepeats() {
        int last = DemoSongs.TITLES.length - 1;
        assertEquals(0, DemoSongs.next(last));
        assertEquals(DemoSongs.title(0), DemoSongs.title(DemoSongs.TITLES.length));
    }

    @Test
    public void creditNamesTheComposerAndLicence() {
        assertTrue(DemoSongs.CREDIT.contains("Kevin MacLeod"));
        assertTrue(DemoSongs.CREDIT.contains("CC BY 4.0"));
    }
}

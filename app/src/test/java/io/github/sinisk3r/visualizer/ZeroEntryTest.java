package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ZeroEntryTest {
    private static ZeroEntry.Target t(ZeroEntry.Kind k, int i) {
        return new ZeroEntry.Target(k, i);
    }

    @Test
    public void zeroAloneIsFavouritesAndDoubleZeroThePlaylists() {
        ZeroEntry z = new ZeroEntry();
        assertEquals(t(ZeroEntry.Kind.FAVOURITES, -1), z.target());
        assertEquals("0_", z.shown());
        assertEquals(t(ZeroEntry.Kind.PLAYLISTS, -1), z.add(0));
    }

    @Test
    public void favouritesAreZeroThenTheirNumber() {
        ZeroEntry z = new ZeroEntry();
        assertEquals(t(ZeroEntry.Kind.FAVOURITE, 0), z.add(1));   // 01
        assertEquals(t(ZeroEntry.Kind.FAVOURITE, 11), z.add(2));  // 012
        assertEquals("012", z.typed());
    }

    @Test
    public void playlistsAreDoubleZeroThenTheirNumber() {
        ZeroEntry z = new ZeroEntry();
        z.add(0);
        assertEquals(t(ZeroEntry.Kind.PLAYLIST, 3), z.add(4));  // 004 Recently played
        assertEquals(t(ZeroEntry.Kind.PLAYLIST, 40), z.add(1)); // 0041
    }

    @Test
    public void tooManyDigitsStartOverAtZero() {
        ZeroEntry z = new ZeroEntry();
        z.add(1);
        z.add(2);                                               // 012
        assertEquals(t(ZeroEntry.Kind.FAVOURITE, 2), z.add(3)); // 03
        assertEquals("03", z.typed());
        z.reset();
        assertEquals("0", z.typed());
    }

    @Test
    public void tripleZeroIsStillThePlaylists() {
        assertEquals(t(ZeroEntry.Kind.PLAYLISTS, -1), ZeroEntry.parse("000"));
        assertEquals(t(ZeroEntry.Kind.PLAYLIST, 0), ZeroEntry.parse("0001"));
    }

    @Test
    public void numbersAsShown() {
        assertEquals("01", ZeroEntry.favouriteNumber(0));
        assertEquals("12", ZeroEntry.favouriteNumber(11));
        assertEquals("001", ZeroEntry.playlistNumber(0));
        assertEquals("004", ZeroEntry.playlistNumber(3));
    }

    @Test
    public void okRightAfterZeroPlaysTheHighlightedLine() {
        ZeroEntry z = new ZeroEntry();
        assertTrue("0 then OK plays favourite 01", ZeroEntry.okPlaysHighlighted(z.target(), false));
        assertTrue("00 then OK plays Featured", ZeroEntry.okPlaysHighlighted(z.add(0), false));
    }

    @Test
    public void okAfterACodePlaysTheCodeUnlessTheArrowsMoved() {
        ZeroEntry z = new ZeroEntry();
        assertFalse(ZeroEntry.okPlaysHighlighted(z.add(2), false));  // 02
        assertTrue(ZeroEntry.okPlaysHighlighted(z.target(), true));
        z.reset();
        z.add(0);
        assertFalse(ZeroEntry.okPlaysHighlighted(z.add(3), false));  // 003
    }
}

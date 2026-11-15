package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeypadTest {
    private static void is(String digits, Keypad.Kind kind, int number, String label) {
        Keypad.Target t = Keypad.target(digits);
        assertEquals(digits, kind, t.kind);
        assertEquals(digits, number, t.number);
        assertEquals(digits, label, t.label());
    }

    @Test
    public void sameRulesAsTheRemote() {
        is("", Keypad.Kind.NONE, 0, null);
        is("0", Keypad.Kind.FAVOURITES, 0, "Favourites");
        is("03", Keypad.Kind.FAVOURITE, 3, "Favourite 3");
        is("012", Keypad.Kind.FAVOURITE, 12, "Favourite 12");
        is("00", Keypad.Kind.PLAYLISTS, 0, "Playlists");
        is("000", Keypad.Kind.PLAYLISTS, 0, "Playlists");
        is("001", Keypad.Kind.PLAYLIST, 1, "Playlist 1");
        is("4", Keypad.Kind.PRESET, 4, null);
        is("43", Keypad.Kind.PRESET, 43, null);
        is("9791", Keypad.Kind.PRESET, 9791, null);
    }

    @Test
    public void digitsStopAtFourAndDeleteOneAtATime() {
        Keypad k = new Keypad();
        for (int d : new int[]{1, 2, 3, 4}) assertTrue(k.add(d));
        assertFalse(k.add(5));
        assertEquals("1234", k.digits());
        k.delete();
        assertEquals("123", k.digits());
        k.clear();
        k.delete();
        assertEquals("", k.digits());
        assertEquals(Keypad.Kind.NONE, k.target().kind);
    }
}

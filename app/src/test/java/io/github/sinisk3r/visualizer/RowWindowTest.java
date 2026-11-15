package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RowWindowTest {
    @Test
    public void shortListLeavesRowsEmpty() {
        RowWindow w = new RowWindow(4);
        w.reset(2);
        assertEquals(1, w.item(1));
        assertEquals(-1, w.item(2));
        assertEquals(0, w.below());
        assertFalse("nothing below the last item", w.scroll(1, 1));
    }

    @Test
    public void downOnTheLastRowScrollsAndUpOnTheFirstScrollsBack() {
        RowWindow w = new RowWindow(3);
        w.reset(10);
        assertFalse("focus moves within the rows", w.scroll(1, 1));
        assertTrue(w.scroll(2, 1));
        assertEquals(1, w.offset());
        assertEquals(3, w.item(2));
        assertEquals(6, w.below());
        assertFalse("row 1 is not the first row", w.scroll(1, -1));
        assertTrue(w.scroll(0, -1));
        assertEquals(0, w.offset());
        assertFalse("top of the list", w.scroll(0, -1));
    }

    @Test
    public void showScrollsTheLeastNeeded() {
        RowWindow w = new RowWindow(5);
        w.reset(30);
        assertEquals(4, w.show(11));  // typed "012": item 11 in the last row
        assertEquals(7, w.offset());
        assertEquals(0, w.show(2));
        assertEquals(2, w.offset());
        assertEquals("clamped to the list", 4, w.show(99));
        assertEquals(29, w.item(4));
    }

    @Test
    public void resetGoesBackToTheTop() {
        RowWindow w = new RowWindow(2);
        w.reset(5);
        w.show(4);
        w.reset(5);
        assertEquals(0, w.offset());
    }
}

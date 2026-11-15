package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MenuIdleTest {
    @Test
    public void theBarAloneWaitsTenSecondsACardThirty() {
        assertEquals(10_000, MenuIdle.limit(false));
        assertEquals(30_000, MenuIdle.limit(true));
    }

    @Test
    public void remainingCountsFromTheLastKey() {
        assertEquals(4_000, MenuIdle.remaining(1_000, 7_000, false));
        assertTrue(MenuIdle.remaining(1_000, 11_000, false) <= 0);
        assertEquals(20_000, MenuIdle.remaining(1_000, 11_000, true));
    }

    @Test
    public void openingACardAfterTheWaitStartedGivesItTheLongerWait() {
        // The bar's 10 s check finds a card open: it waits on to 30 s from the same key.
        long key = 5_000;
        long left = MenuIdle.remaining(key, key + MenuIdle.BAR_MS, true);
        assertEquals(MenuIdle.CARD_MS - MenuIdle.BAR_MS, left);
    }
}

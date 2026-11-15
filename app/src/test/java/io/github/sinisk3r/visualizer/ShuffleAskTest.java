package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShuffleAskTest {
    @Test
    public void asksOnlyWhenShufflingEverythingAndASetWasPicked() {
        ShuffleAsk a = new ShuffleAsk();
        assertFalse(a.chosen(Settings.SHUFFLE_SET, "Geometric", 0));
        assertFalse(a.chosen(Settings.SHUFFLE_OFF, "Geometric", 0));
        assertFalse("All is everything already", a.chosen(Settings.SHUFFLE_ALL, PresetLibrary.ALL, 0));
        assertTrue(a.chosen(Settings.SHUFFLE_ALL, "Geometric", 0));
        assertTrue(a.chosen(Settings.SHUFFLE_ALL, PresetLibrary.FAVOURITES, 0));
        assertTrue(a.chosen(Settings.SHUFFLE_ALL, "Most watched", 0));
    }

    @Test
    public void okWithinFourSecondsSaysYes() {
        ShuffleAsk a = new ShuffleAsk();
        a.chosen(Settings.SHUFFLE_ALL, "Geometric", 1_000);
        assertTrue(a.isAsking(4_999));
        assertTrue(a.key(true, 4_999));
        assertFalse("answered once", a.isAsking(5_000));
        assertFalse(a.key(true, 5_000));
    }

    @Test
    public void okAfterTheWaitDoesNothing() {
        ShuffleAsk a = new ShuffleAsk();
        a.chosen(Settings.SHUFFLE_ALL, "Geometric", 1_000);
        assertFalse(a.isAsking(5_000));
        assertFalse(a.key(true, 5_000));
    }

    @Test
    public void anyOtherKeyKeepsEverything() {
        ShuffleAsk a = new ShuffleAsk();
        a.chosen(Settings.SHUFFLE_ALL, "Geometric", 0);
        assertFalse(a.key(false, 100));
        assertFalse(a.isAsking(200));
        assertFalse("OK after another key is the menu again", a.key(true, 300));
    }

    @Test
    public void aNewPickAsksAgain() {
        ShuffleAsk a = new ShuffleAsk();
        a.chosen(Settings.SHUFFLE_ALL, "Geometric", 0);
        a.key(false, 100);  // Up again
        a.chosen(Settings.SHUFFLE_ALL, "Hypnotic", 150);
        assertTrue(a.key(true, 3_000));
    }

    @Test
    public void aPickWithoutEverythingClearsAPendingQuestion() {
        ShuffleAsk a = new ShuffleAsk();
        a.chosen(Settings.SHUFFLE_ALL, "Geometric", 0);
        a.chosen(Settings.SHUFFLE_SET, "Geometric", 100);
        assertFalse(a.isAsking(200));
    }
}

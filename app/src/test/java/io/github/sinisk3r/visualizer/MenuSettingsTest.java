package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** The settings the transport bar and its cards add (menu redesign, phase 1). */
@RunWith(RobolectricTestRunner.class)
public class MenuSettingsTest {
    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
    }

    @Test
    public void defaultsKeepTodaysBehaviour() {
        Settings s = new Settings(ctx);
        assertEquals("the app always shuffled within the style", Settings.SHUFFLE_SET, s.shuffle());
        assertTrue(s.showPlaying());
    }

    @Test
    public void shuffleCyclesOffThisSetEverythingAndIsSaved() {
        Settings s = new Settings(ctx);
        s.cycleShuffle();
        assertEquals(Settings.SHUFFLE_ALL, s.shuffle());
        s.cycleShuffle();
        assertEquals(Settings.SHUFFLE_OFF, s.shuffle());
        s.cycleShuffle();
        assertEquals(Settings.SHUFFLE_SET, s.shuffle());
        s.cycleShuffle();
        assertEquals(Settings.SHUFFLE_ALL, new Settings(ctx).shuffle());
    }

    @Test
    public void shuffleLabels() {
        assertEquals("Off", Settings.shuffleLabel(Settings.SHUFFLE_OFF));
        assertEquals("This set", Settings.shuffleLabel(Settings.SHUFFLE_SET));
        assertEquals("Everything", Settings.shuffleLabel(Settings.SHUFFLE_ALL));
        assertEquals("Shuffle: this set", BarText.shuffleTip(Settings.SHUFFLE_SET));
        assertNull(BarText.shuffleBadge(Settings.SHUFFLE_OFF));
        assertEquals("SET", BarText.shuffleBadge(Settings.SHUFFLE_SET));
        assertEquals("ALL", BarText.shuffleBadge(Settings.SHUFFLE_ALL));
    }

    @Test
    public void showPlayingIsSaved() {
        Settings s = new Settings(ctx);
        s.setShowPlaying(false);
        assertFalse(new Settings(ctx).showPlaying());
    }

    @Test
    public void durationChipSetsAndSaves() {
        Settings s = new Settings(ctx);
        s.setDuration(120);
        assertEquals(120, new Settings(ctx).durationSec());
        assertEquals("2 min", Settings.durationLabel(120));
    }

    @Test
    public void smoothAndSharpChipsStillForgetLearnedSizes() {
        Settings s = new Settings(ctx);
        s.learn("a.milk", 720);
        s.setPicture(Settings.AUTO_SHARP);
        assertEquals(0, s.learnedCount());
        s.learn("a.milk", 720);
        s.setPicture(Settings.AUTO_SHARP);  // same choice again: keep what was learned
        assertEquals(1, s.learnedCount());
    }

    @Test
    public void fixedPictureSizeCyclesOffThrough1080() {
        assertEquals(480, Settings.nextFixed(Settings.AUTO));
        assertEquals(480, Settings.nextFixed(Settings.AUTO_SHARP));
        assertEquals(540, Settings.nextFixed(480));
        assertEquals(720, Settings.nextFixed(540));
        assertEquals(1080, Settings.nextFixed(720));
        assertEquals(Settings.AUTO, Settings.nextFixed(1080));
    }
}

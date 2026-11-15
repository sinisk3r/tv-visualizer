package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class SettingsTest {
    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
    }

    @Test
    public void freshInstallDefaults() {
        Settings s = new Settings(ctx);
        assertEquals(Settings.AUTO, s.renderHeight());
        assertTrue(s.isAuto());
        assertEquals(30, s.durationSec());
        assertTrue(s.beatCuts());
        assertTrue(s.skipHeavy());
        assertTrue(s.skipDeaf());
        assertFalse("nothing learned yet, so nothing to relearn", s.relearning);
    }

    @Test
    public void smoothHolds60AndSharpAccepts24() {
        Settings s = new Settings(ctx);
        assertEquals(55f, s.autoSlowFps(), 0);
        s.stepPicture(1);
        assertEquals(Settings.AUTO_SHARP, s.renderHeight());
        assertEquals(24f, s.autoSlowFps(), 0);
    }

    @Test
    public void pictureStepsClampAtBothEnds() {
        Settings s = new Settings(ctx);
        s.stepPicture(-1);
        assertEquals(Settings.AUTO, s.renderHeight());
        for (int i = 0; i < 10; i++) s.stepPicture(1);
        assertEquals(1080, s.renderHeight());
        assertFalse(s.isAuto());
    }

    @Test
    public void switchingSmoothAndSharpForgetsLearnedSizes() {
        Settings s = new Settings(ctx);
        s.learn("a.milk", 720);
        s.markTooSlow("b.milk", 12f);
        s.stepPicture(1);  // Smooth to Sharp
        assertEquals(0, s.learnedCount());
        assertEquals(0, s.tooSlowCount());
    }

    @Test
    public void newRendererGenerationRelearnsOnce() {
        Settings s = new Settings(ctx);
        s.learn("a.milk", 720);
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putInt("rendererGeneration", Settings.RENDERER_GENERATION - 1).commit();
        Settings updated = new Settings(ctx);
        assertTrue(updated.relearning);
        assertEquals(0, updated.learnedCount());
        assertFalse("second start on the same renderer", new Settings(ctx).relearning);
    }

    @Test
    public void unknownPresetStartsAtTheMostCommonLearnedHeight() {
        Settings s = new Settings(ctx);
        assertEquals("fresh install", 1080, s.learnedHeight("new.milk"));
        s.learn("a.milk", 540);
        s.learn("b.milk", 540);
        s.learn("c.milk", 720);
        assertEquals(540, s.learnedHeight("new.milk"));
        assertEquals(720, s.learnedHeight("c.milk"));
    }

    @Test
    public void hidingAFavouriteRemovesItFromFavourites() {
        Settings s = new Settings(ctx);
        s.setFavourite("a.milk", true);
        s.hide("a.milk");
        assertFalse(s.isFavourite("a.milk"));
        assertTrue(s.isHidden("a.milk"));
        s.unhideAll();
        assertEquals(0, s.hiddenCount());
    }

    @Test
    public void choicesSurviveARestart() {
        Settings s = new Settings(ctx);
        s.stepDuration(1);
        s.setBeatCuts(false);
        s.setFavourite("a.milk", true);
        s.setStyle("Dancer", false);
        Settings again = new Settings(ctx);
        assertEquals(60, again.durationSec());
        assertFalse(again.beatCuts());
        assertTrue(again.isFavourite("a.milk"));
        assertFalse(again.isStyleOn("Dancer"));
    }

    @Test
    public void sizeHelpers() {
        assertEquals(854, Settings.widthFor(480));
        assertEquals(1920, Settings.widthFor(1080));
        assertEquals(480, Settings.lower(540));
        assertEquals(480, Settings.lower(480));
        assertEquals(720, Settings.higher(540));
        assertEquals(1080, Settings.higher(1080));
        assertEquals("30 s", Settings.durationLabel(30));
        assertEquals("2 min", Settings.durationLabel(120));
    }
}

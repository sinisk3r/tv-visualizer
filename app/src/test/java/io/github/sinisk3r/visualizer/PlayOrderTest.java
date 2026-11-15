package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

public class PlayOrderTest {
    /** Two styles of three presets; shuffle reverses the list, so "shuffled" orders are known. */
    private static final class Fake implements PlayOrder.Source {
        final Map<String, List<File>> styles = new LinkedHashMap<>();
        final Set<String> off = new HashSet<>(), hidden = new HashSet<>(), favs = new HashSet<>(),
                skipped = new HashSet<>();
        int shuffle = Settings.SHUFFLE_SET;
        final Set<String> favStyles = new HashSet<>();

        Fake() {
            styles.put(PresetLibrary.ALL, new ArrayList<>());
            styles.put("Dancer", files("Dancer", "a1", "a2", "a3"));
            styles.put("Fractal", files("Fractal", "b1", "b2", "b3"));
        }

        static List<File> files(String dir, String... names) {
            List<File> out = new ArrayList<>();
            for (String n : names) out.add(new File(dir + "/" + n + ".milk"));
            return out;
        }

        public List<String> styles() { return new ArrayList<>(styles.keySet()); }
        public List<File> presets(String style) { return styles.getOrDefault(style, Collections.emptyList()); }
        public List<File> all() {
            List<File> out = new ArrayList<>();
            for (List<File> l : styles.values()) out.addAll(l);
            return out;
        }
        public boolean styleOn(String style) { return !off.contains(style); }
        public boolean hidden(File f) { return hidden.contains(f.getName()); }
        public boolean favourite(File f) { return favs.contains(f.getName()); }
        public boolean skipped(String style, File f) {
            return hidden(f) || skipped.contains(f.getName());
        }
        public int shuffleMode() { return shuffle; }
        public List<File> shuffle(List<File> list) {
            List<File> out = new ArrayList<>(list);
            Collections.reverse(out);
            return out;
        }
        public boolean favouriteStyle(String style) { return favStyles.contains(style); }
    }

    private Fake lib;

    @Before
    public void setUp() {
        lib = new Fake();
    }

    private static List<String> names(PlayOrder o) {
        List<String> out = new ArrayList<>();
        File first = o.step(1);
        if (first == null) return out;
        out.add(first.getName());
        for (int i = 1; i < o.size(); i++) out.add(o.step(1).getName());
        return out;
    }

    private static String name(File f) {
        return f == null ? null : f.getName();
    }

    @Test
    public void thisSetShufflesWithinTheStyle() {
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild(null);
        assertEquals(Arrays.asList("a3.milk", "a2.milk", "a1.milk"), names(o));
    }

    @Test
    public void offKeepsLibraryOrderAndEverythingMixesStylesThatAreOn() {
        lib.shuffle = Settings.SHUFFLE_OFF;
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild(null);
        assertEquals(Arrays.asList("a1.milk", "a2.milk", "a3.milk"), names(o));

        lib.shuffle = Settings.SHUFFLE_ALL;
        lib.off.add("Fractal");
        o.rebuild(null);
        assertEquals("Fractal is off", Arrays.asList("a3.milk", "a2.milk", "a1.milk"), names(o));
        lib.off.clear();
        o.rebuild(null);
        assertEquals(6, o.size());
    }

    @Test
    public void filtersSkipPresetsAndEmptyStylesDisappear() {
        lib.skipped.addAll(Arrays.asList("b1.milk", "b2.milk", "b3.milk"));
        lib.hidden.add("a2.milk");
        PlayOrder o = new PlayOrder(lib, "Fractal");
        o.rebuild(null);
        assertEquals("no Fractal left, back to All", PresetLibrary.ALL, o.category());
        assertFalse(o.categories().contains("Fractal"));
        assertEquals(Arrays.asList("a3.milk", "a1.milk"), names(o));
    }

    @Test
    public void favouritesPlayEvenFromStylesThatAreOff() {
        lib.favs.add("b2.milk");
        lib.off.add("Fractal");
        PlayOrder o = new PlayOrder(lib, PresetLibrary.FAVOURITES);
        o.rebuild(null);
        assertEquals(Arrays.asList(PresetLibrary.ALL, PresetLibrary.FAVOURITES, "Dancer"), o.categories());
        assertEquals(Collections.singletonList("b2.milk"), names(o));
    }

    @Test
    public void rebuildKeepsTheShownPresetFirst() {
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild("Dancer/a1.milk");
        assertEquals("a1.milk", name(o.current()));
        assertEquals("a3.milk", name(o.next()));
    }

    @Test
    public void aHeldPresetStaysEvenWhenFiltered() {
        lib.skipped.add("a1.milk");
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild("Dancer/a1.milk");
        assertNull(o.current());
        o.hold(new File("Dancer/a1.milk"));
        assertEquals("a1.milk", name(o.current()));
    }

    @Test
    public void playSlotsAPresetFromElsewhereAfterTheCurrentOne() {
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild(null);
        o.step(1);                                   // a3
        o.play(new File("Fractal/b1.milk"));
        assertEquals("b1.milk", name(o.current()));
        assertEquals("Back returns to where we were", "a3.milk", name(o.step(-1)));
        assertEquals("b1.milk", name(o.step(1)));
        assertEquals("then the style goes on", "a2.milk", name(o.step(1)));
        o.play(new File("Dancer/a3.milk"));
        assertEquals("already in the order: no copy", 4, o.size());
    }

    @Test
    public void stepWrapsAndChangeCategoryStartsOver() {
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild(null);
        assertEquals("a3.milk", name(o.step(1)));
        assertEquals("Back from the first wraps to the last", "a1.milk", name(o.step(-1)));
        o.changeCategory(1);
        assertEquals("Fractal", o.category());
        assertEquals("b3.milk", name(o.step(1)));
        o.changeCategory(1);
        assertEquals("wraps to All", PresetLibrary.ALL, o.category());
    }

    @Test
    public void dropTakesThePresetOutButNeverTheLastOne() {
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild(null);
        o.step(1);
        assertEquals("a3.milk", name(o.dropCurrent()));
        assertEquals("moves on to the next", "a2.milk", name(o.step(1)));
        assertEquals(2, o.size());
        o.dropCurrent();
        o.step(1);
        assertNull("the last one stays", o.dropCurrent());
        assertTrue(o.size() == 1);
    }

    @Test
    public void nothingLeftGivesNull() {
        lib.skipped.addAll(Arrays.asList("a1.milk", "a2.milk", "a3.milk", "b1.milk", "b2.milk", "b3.milk"));
        PlayOrder o = new PlayOrder(lib, PresetLibrary.ALL);
        o.rebuild(null);
        assertNull(o.step(1));
        assertNull(o.next());
    }

    @Test
    public void weightedShuffleKeepsEveryPresetAndFavoursHeavyWeights() {
        List<File> list = Fake.files("x", "strong", "a", "b", "c");
        java.util.Random rnd = new java.util.Random(1);
        int strongFirst = 0;
        for (int i = 0; i < 2000; i++) {
            List<File> out = PresetLibrary.weightedShuffle(list, f -> f.getName().startsWith("strong") ? 2 : 1, rnd);
            assertEquals(new HashSet<>(list), new HashSet<>(out));
            if (out.get(0).getName().startsWith("strong")) strongFirst++;
        }
        // weight 2 among 2+1+1+1: first 40% of the time (vs 25% unweighted)
        assertTrue("strong first " + strongFirst, strongFirst > 700 && strongFirst < 900);
    }

    private static List<File> list(String... paths) {
        List<File> out = new ArrayList<>();
        for (String p : paths) out.add(new File(p + ".milk"));
        return out;
    }

    @Test
    public void favouritesHoldSinglePresetsThenWholeStyles() {
        lib.favs.add("b2.milk");
        lib.favStyles.add("Dancer");
        lib.skipped.add("a2.milk");  // a favourite style's filters still apply
        lib.shuffle = Settings.SHUFFLE_OFF;
        PlayOrder o = new PlayOrder(lib, PresetLibrary.FAVOURITES);
        o.rebuild(null);
        assertEquals(Arrays.asList("b2.milk", "a1.milk", "a3.milk"), names(o));
    }

    @Test
    public void aListPlaysInOrderFromThePickedPreset() {
        lib.shuffle = Settings.SHUFFLE_OFF;
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild(null);
        o.playList("Featured", list("Fractal/b3", "Dancer/a1", "Fractal/b1"), new File("Dancer/a1.milk"));
        assertEquals("Featured", o.category());
        assertTrue(o.isList());
        assertEquals("a1.milk", name(o.step(1)));
        assertEquals("b1.milk", name(o.step(1)));
        assertEquals("wraps within the list", "b3.milk", name(o.step(1)));
        assertEquals(3, o.size());
    }

    @Test
    public void thisSetShufflesTheListAndPutsThePickFirst() {
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild(null);
        o.playList("Recently played", list("Fractal/b3", "Dancer/a1", "Fractal/b1"), new File("Fractal/b3.milk"));
        // the fake shuffle reverses: b1, a1, b3; the pick moves to the front
        assertEquals(Arrays.asList("b3.milk", "b1.milk", "a1.milk"), names(o));
    }

    @Test
    public void everythingShufflesEveryStyleAndTheListOnlyPicksTheFirst() {
        lib.shuffle = Settings.SHUFFLE_ALL;
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild(null);
        o.playList("Featured", list("Dancer/a2"), null);
        assertEquals(6, o.size());
        assertEquals("a2.milk", name(o.step(1)));
    }

    @Test
    public void aListSurvivesASettingsChangeAndDropsHiddenPresets() {
        lib.shuffle = Settings.SHUFFLE_OFF;
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild(null);
        o.playList("Most watched", list("Fractal/b3", "Dancer/a1", "Fractal/b1"), null);
        lib.hidden.add("a1.milk");
        o.rebuild(null);
        assertEquals("Most watched", o.category());
        assertEquals(Arrays.asList("b3.milk", "b1.milk"), names(o));
    }

    @Test
    public void upDownFromAListGoesBackToTheStyles() {
        PlayOrder o = new PlayOrder(lib, "Dancer");
        o.rebuild(null);
        o.playList("Featured", list("Fractal/b3"), null);
        o.changeCategory(1);
        assertFalse(o.isList());
        assertEquals("Dancer", o.category());
    }

    @Test
    public void playCategoryStartsAStyleAtThePickedPreset() {
        lib.shuffle = Settings.SHUFFLE_OFF;
        PlayOrder o = new PlayOrder(lib, PresetLibrary.ALL);
        o.rebuild(null);
        o.playCategory("Fractal", new File("Fractal/b2.milk"));
        assertEquals("Fractal", o.category());
        assertEquals("b2.milk", name(o.step(1)));
        assertEquals("b3.milk", name(o.step(1)));
    }
}

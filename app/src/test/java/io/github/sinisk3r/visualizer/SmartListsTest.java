package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class SmartListsTest {
    private static final long NOW = 20_000 * WatchHistory.DAY_MS;

    private static final class Lib implements SmartLists.Source {
        final Map<String, File> files = new LinkedHashMap<>();
        final Set<String> hidden = new HashSet<>();

        Lib(String... names) {
            for (String n : names) files.put(n, new File("S/" + n));
        }

        public List<File> all() { return new ArrayList<>(files.values()); }
        public File byName(String name) { return files.get(name); }
        public boolean hidden(File f) { return hidden.contains(f.getName()); }
    }

    private static List<String> names(List<File> fs) {
        List<String> out = new ArrayList<>();
        for (File f : fs) out.add(f.getName());
        return out;
    }

    @Test
    public void featuredIsMissingUntilTheFileShips() {
        SmartLists s = new SmartLists(new Lib("a.milk"), new WatchHistory(), null);
        assertFalse(s.hasFeatured());
        assertTrue(s.playlist(SmartLists.FEATURED, NOW).isEmpty());
    }

    @Test
    public void featuredKeepsFileOrderAndDropsPresetsNotOnThisTv() throws Exception {
        List<String> picked = SmartLists.parseFeatured(new StringReader(
                "# picks\nb.milk\n\n  a.milk \nnot-downloaded.milk\nb.milk\n"));
        assertEquals(Arrays.asList("b.milk", "a.milk", "not-downloaded.milk", "b.milk"), picked);
        Lib lib = new Lib("a.milk", "b.milk", "c.milk");
        SmartLists s = new SmartLists(lib, new WatchHistory(), picked);
        assertTrue(s.hasFeatured());
        assertEquals(Arrays.asList("b.milk", "a.milk"), names(s.featured()));
        lib.hidden.add("b.milk");
        assertEquals(Arrays.asList("a.milk"), names(s.featured()));
    }

    @Test
    public void smartListsComeFromTheHistory() {
        Lib lib = new Lib("a.milk", "b.milk", "c.milk", "d.milk");
        WatchHistory h = new WatchHistory();
        h.add("a.milk", NOW - 60 * WatchHistory.DAY_MS, 900);
        h.add("b.milk", NOW - 1000, 300);
        h.add("c.milk", NOW, 20);
        SmartLists s = new SmartLists(lib, h, null);
        assertEquals(Arrays.asList("b.milk", "c.milk"), names(s.playlist(SmartLists.MOST_WATCHED, NOW)));
        assertEquals(Arrays.asList("a.milk", "b.milk", "c.milk"),
                names(s.mostWatched(NOW, SmartLists.QUARTER_DAYS)));
        assertEquals(Arrays.asList("c.milk", "b.milk", "a.milk"), names(s.playlist(SmartLists.RECENT, NOW)));
        assertEquals("d never shown, c only 20 s", Arrays.asList("d.milk", "c.milk"),
                names(s.playlist(SmartLists.NEW_TO_YOU, NOW)));
    }

    @Test
    public void favouriteOrderKeepsAddedOrderAndAppendsUnknownOnesByName() {
        Set<String> favs = new HashSet<>(Arrays.asList("z.milk", "b.milk", "a.milk"));
        assertEquals(Arrays.asList("z.milk", "b.milk", "a.milk"),
                SmartLists.favouriteOrder(Arrays.asList("z.milk", "gone.milk", "b.milk", "a.milk"), favs));
        assertEquals("older install, no saved order", Arrays.asList("a.milk", "b.milk", "z.milk"),
                SmartLists.favouriteOrder(new ArrayList<>(), favs));
    }
}

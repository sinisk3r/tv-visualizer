package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SearchIndexTest {
    private static SearchIndex.Entry e(int number, String file) {
        File f = new File("/p/" + file + ".milk");
        return new SearchIndex.Entry(f, number, PresetLibrary.displayName(f), "Style");
    }

    private static final SearchIndex INDEX = new SearchIndex(Arrays.asList(
            e(1234, "martin - a different view of the green machine"),
            e(391, "flexi - green glass fractal"),
            e(2876, "zylot - evergreen spiral"),
            e(0, "my own green thing"),
            e(43, "aderrasi - airhandler (kali mix)"),
            e(430, "flexi - mindblob mix"),
            e(431, "flexi - mindblob [shiny mix]"),
            e(4300, "geiss - flame"),
            e(5, "Jc - I can't stand it"),
            e(6, "eo_fuck_this_mix")));

    private static List<Integer> numbers(List<SearchIndex.Entry> found) {
        List<Integer> out = new ArrayList<>();
        for (SearchIndex.Entry x : found) out.add(x.number);
        return out;
    }

    @Test
    public void everyWordMustMatchInAnyOrderAndCase() {
        assertEquals(Arrays.asList(391), numbers(INDEX.search("Fractal GREEN")));
        assertEquals(Arrays.asList(430, 431), numbers(INDEX.search("mindblob flexi")));
        assertTrue(INDEX.search("green nothing").isEmpty());
    }

    @Test
    public void wordStartsComeFirstThenByNumberUnnumberedLast() {
        // 391, 1234 and the unnumbered one start a word with "green"; evergreen only holds it.
        assertEquals(Arrays.asList(391, 1234, 0, 2876), numbers(INDEX.search("green")));
    }

    @Test
    public void emptyOrBlankQueryFindsNothing() {
        assertTrue(INDEX.search("").isEmpty());
        assertTrue(INDEX.search("   ").isEmpty());
    }

    @Test
    public void punctuationAndUnderscoresAreSpacesApostrophesDropped() {
        assertEquals(Arrays.asList(5), numbers(INDEX.search("cant stand")));
        assertEquals(Arrays.asList(43), numbers(INDEX.search("kali")));
        assertEquals(Arrays.asList(431), numbers(INDEX.search("shiny")));
    }

    @Test
    public void maskedWordsStillMatchButShowMasked() {
        List<SearchIndex.Entry> found = INDEX.search("fuck");
        assertEquals(Arrays.asList(6), numbers(found));
        assertEquals("eo f**k this mix", found.get(0).name);
    }

    @Test
    public void spokenTextIsNormalised() {
        assertEquals("green machine 43", SearchIndex.normalize("  Green, Machine! 43 "));
    }

    @Test
    public void byNumberPutsTheChannelFirstThenItsPrefixes() {
        assertEquals(Arrays.asList(43, 430, 431, 4300), numbers(INDEX.byNumber(43)));
        assertEquals(Arrays.asList(430, 4300), numbers(INDEX.byNumber(430)));
        assertTrue(INDEX.byNumber(0).isEmpty());
        assertTrue(INDEX.byNumber(99).isEmpty());
    }

    @Test
    public void typingBuildsAQuery() {
        String q = "";
        q = SearchIndex.typed(q, ' ');
        assertEquals("no leading space", "", q);
        q = SearchIndex.typed(q, 'G');
        q = SearchIndex.typed(q, 'o');
        q = SearchIndex.typed(q, ' ');
        q = SearchIndex.typed(q, ' ');
        assertEquals("go ", q);
        assertEquals("go", SearchIndex.deleted(q));
        assertEquals("", SearchIndex.deleted(""));
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < SearchIndex.MAX_QUERY; i++) full.append('a');
        assertEquals(full.toString(), SearchIndex.typed(full.toString(), 'b'));
    }
}

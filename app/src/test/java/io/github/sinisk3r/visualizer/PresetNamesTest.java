package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;

public class PresetNamesTest {
    @Test
    public void cleanNamesAreUnchanged() {
        assertEquals("martin - the sewers", PresetLibrary.displayName(new File("x/martin - the sewers.milk")));
    }

    @Test
    public void underscoresBecomeSpaces() {
        assertEquals("flexi - mindblob mix", PresetLibrary.displayName(new File("flexi_-_mindblob_mix.milk")));
    }

    @Test
    public void swearingIsMaskedInsideWordsAndAnyCase() {
        assertEquals("amandio c - hat track - f**kingdelic", PresetLibrary.masked("amandio c - hat track - fuckingdelic"));
        assertEquals("S**T and b***h", PresetLibrary.masked("SHIT and bitch"));
        assertEquals("mindf**k", PresetLibrary.masked("mindfuck"));
    }

    @Test
    public void maskingIsBySpellingNotMeaning() {  // "shitake" is masked too: accepted
        assertEquals("shitake is masked: s**takeX", "shitake is masked: " + PresetLibrary.masked("shitakeX"));
        assertEquals("class assignment", PresetLibrary.masked("class assignment"));
    }
}

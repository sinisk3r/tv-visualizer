package io.github.sinisk3r.visualizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class SpeechPickTest {
    @Test
    public void googleTvVoiceWinsOverSpeechServices() {
        // Seen on the TV, 15-Nov-2026: both answer RECOGNIZE_SPEECH; only Google TV's voice input works.
        assertEquals(SearchPanel.TV_VOICE, SearchPanel.pickSpeechPackage(
                Arrays.asList("com.google.android.tts", "com.google.android.katniss")));
    }

    @Test
    public void otherwiseAndroidChooses() {
        assertNull(SearchPanel.pickSpeechPackage(Collections.singletonList("com.google.android.tts")));
        assertNull(SearchPanel.pickSpeechPackage(Collections.emptyList()));
    }
}

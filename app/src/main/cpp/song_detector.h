// Song-aware switching, as plain C++ (no Android, no projectM), so a host test can feed it level curves:
// tools/tests/song_detector_test.cpp.
//
// Levels are the raw capture RMS (before the gain), smoothed over three time spans.
// A gap: the 0.25 s level stays under 10% of the 12 s level for 1.2 s or more, then comes back. That is a new
// song or a drop (many EDM drops follow a second or two of silence); loudness cannot tell them apart (on Martin
// Garrix "Animals" the drop came back at 0.46x the loud build before it), and both are good moments to switch.
// The switch fires the moment sound returns, so it lands on the first beat.
// A drop without a gap: the 1.5 s level reaches twice the 12 s level (a loud section after a quieter build).
#pragma once

#include <algorithm>
#include <cmath>

struct SongDetector {
    static constexpr int SONG = 8;   // a new song started (a gap of near-silence, then sound)
    static constexpr int DROP = 16;  // the music got much louder at once (a "drop")

    static constexpr float SILENCE = 0.004f;      // below this level it is silence
    static constexpr float GAP_RATIO = 0.10f;
    static constexpr float GAP_SECONDS = 1.2f;    // song gaps on Spotify were 1.6-10 s; pauses inside songs 0.5-1.1 s
    static constexpr float BACK_RATIO = 0.30f;    // sound is back when the 0.25 s level passes 30% of the 12 s level
    static constexpr float DROP_RATIO = 2.0f;
    static constexpr float MIN_PRESET_S = 8.0f;   // never switch a preset that has just come on
    static constexpr float DROP_COOLDOWN_S = 20.0f;

    float lvFast = 0, lvMid = 0, lvLong = 0;
    float quietFor = 0;    // seconds the fast level has been in a gap
    float gapLength = 0;   // the last gap long enough to count, waiting for sound to come back
    float gapTrend = 0;    // 1.5 s level against the 12 s level as the gap began (< 1: a fade out), logged
    float sinceSong = 1e9f, sinceDrop = 1e9f;
    // For the log: what the last event saw.
    float lastGap = 0, lastTrend = 0, lastDropLevel = 0, lastDropRatio = 0;
    int seen = 0;  // SONG or DROP seen in the last update, reported or not (for the log)

    static void follow(float& level, float rms, float dt, float seconds) {
        float a = std::exp(-dt / seconds);
        level = level == 0.0f ? rms : level * a + rms * (1.0f - a);
    }

    /** One capture block: its RMS, the seconds since the last one, and how long the preset has been on.
     *  Returns SONG, DROP or 0. A song or drop seen while the preset is too new is not reported. */
    int update(float rms, float dt, float presetAge) {
        seen = 0;
        follow(lvFast, rms, dt, 0.25f);
        follow(lvMid, rms, dt, 1.5f);
        sinceSong += dt;
        sinceDrop += dt;
        float gapLevel = std::max(SILENCE, lvLong * GAP_RATIO);
        if (lvFast < gapLevel) {
            if (quietFor == 0) gapTrend = lvMid / std::max(lvLong, 1e-6f);
            quietFor += dt;
            if (quietFor >= GAP_SECONDS) gapLength = quietFor;
            return 0;  // keep the 12 s level from the music before the gap
        }
        quietFor = 0;
        follow(lvLong, rms, dt, 12.0f);
        if (gapLength > 0 && lvFast > lvLong * BACK_RATIO) {
            lastGap = gapLength;
            lastTrend = gapTrend;
            gapLength = 0;
            sinceSong = 0;
            lvMid = lvFast;  // the jump back from the gap is not a second drop
            seen = SONG;
            return presetAge >= MIN_PRESET_S ? SONG : 0;
        }
        if (lvMid > lvLong * DROP_RATIO && sinceDrop > DROP_COOLDOWN_S && sinceSong > 5.0f) {
            lastDropLevel = lvMid;
            lastDropRatio = lvMid / lvLong;
            sinceDrop = 0;
            seen = DROP;
            return presetAge >= MIN_PRESET_S ? DROP : 0;
        }
        return 0;
    }
};

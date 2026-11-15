// Host test for app/src/main/cpp/song_detector.h, fed with level curves shaped like real music: blocks every
// 1/60 s (the capture rate), RMS levels like the TV's capture (~0.05-0.2 for music). Run by test_song_detector.py.
#include "../../app/src/main/cpp/song_detector.h"

#include <cstdio>
#include <functional>
#include <vector>

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL %s: %s\n", __func__, msg); failures++; } } while (0)

struct Event { float t; int kind; };

// Plays `seconds` of a level curve level(t) through a detector; the preset came on at presetStart.
static std::vector<Event> play(SongDetector& d, float from, float seconds, std::function<float(float)> level,
                               float presetStart = -100) {
    std::vector<Event> out;
    const float dt = 1.0f / 60;
    for (float t = from; t < from + seconds; t += dt) {
        // A beat: louder for 0.1 s of each 0.5 s, so levels move like music rather than a flat tone.
        float beat = std::fmod(t, 0.5f) < 0.1f ? 1.4f : 0.9f;
        int e = d.update(level(t) * beat, dt, t - presetStart);
        if (e) out.push_back({t, e});
    }
    return out;
}

static void gapBetweenSongsSwitchesOnTheFirstBeat() {
    SongDetector d;
    auto ev = play(d, 0, 60, [](float t) { return t >= 30 && t < 33 ? 0.001f : 0.1f; });
    CHECK(ev.size() == 1, "one event");
    CHECK(!ev.empty() && ev[0].kind == SongDetector::SONG, "a song");
    CHECK(!ev.empty() && ev[0].t >= 33 && ev[0].t < 33.3f, "when sound comes back");
}

static void shortPauseInsideASongIsNotASong() {
    SongDetector d;
    auto ev = play(d, 0, 60, [](float t) { return t >= 30 && t < 30.9f ? 0.001f : 0.1f; });
    CHECK(ev.empty(), "a 0.9 s pause is part of the song");
}

static void dropAfterAQuietBuildIsADrop() {
    SongDetector d;
    auto ev = play(d, 0, 60, [](float t) { return t < 40 ? 0.04f : 0.2f; });
    CHECK(ev.size() == 1 && ev[0].kind == SongDetector::DROP, "one drop");
    CHECK(!ev.empty() && ev[0].t >= 40 && ev[0].t < 42, "within 2 s");
}

static void steadyMusicNeverSwitches() {
    SongDetector d;
    auto ev = play(d, 0, 300, [](float) { return 0.1f; });
    CHECK(ev.empty(), "5 minutes of steady music");
}

static void dropsAreSpacedByTheCooldown() {
    SongDetector d;
    // Quiet, loud, quiet, loud 10 s apart: the second drop falls inside the 20 s cooldown.
    auto ev = play(d, 0, 80, [](float t) {
        return (t >= 40 && t < 45) || (t >= 55 && t < 60) ? 0.2f : 0.04f;
    });
    CHECK(ev.size() == 1, "one drop in 20 s");
}

static void aNewPresetIsNotSwitchedAway() {
    SongDetector d;
    // The preset came on 3 s before the gap ends: the song is seen but not reported.
    auto ev = play(d, 0, 60, [](float t) { return t >= 30 && t < 33 ? 0.001f : 0.1f; }, 30);
    CHECK(ev.empty(), "preset too new");
    CHECK(d.lastGap >= 2.0f && d.lastGap <= 3.0f, "the gap was still measured");
    std::printf("measured gap %.2f s for 3 s of silence\n", d.lastGap);
}

static void silenceAtTheStartIsNotAGapForever() {
    SongDetector d;
    // The TV starts with nothing playing, then music begins: a song start, no drop.
    auto ev = play(d, 0, 40, [](float t) { return t < 10 ? 0.0f : 0.1f; });
    for (auto& e : ev) CHECK(e.kind != SongDetector::DROP, "no drop from silence");
}

int main() {
    gapBetweenSongsSwitchesOnTheFirstBeat();
    shortPauseInsideASongIsNotASong();
    dropAfterAQuietBuildIsADrop();
    steadyMusicNeverSwitches();
    dropsAreSpacedByTheCooldown();
    aNewPresetIsNotSwitchedAway();
    silenceAtTheStartIsNotAGapForever();
    if (failures) return 1;
    std::printf("song detector: all passed\n");
    return 0;
}

// Renders each preset for a few seconds to synthetic music (kick, snare, hats, bass, pad) and prints one line:
//   name \t colourful \t bright \t dark \t motion \t ms \t ok
// colourful: Hasler-Suesstrunk colourfulness; bright: mean luma 0-255; dark: share of near-black pixels;
// motion: mean luma change between two frames 0.1 s apart; ms: wall time per frame on this Mac (relative only).
// The best-looking late frame is saved as <out>/<name>.png (384x216; convert with sips).
// Usage: mac-render <texture dir> <out dir> < list of .milk paths
#include <projectM-4/projectM.h>
#include <SDL.h>
#include <OpenGL/gl3.h>
// stb_image_write itself is compiled into projectM (SOIL2).
#include "stb_image_write.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <iostream>
#include <random>
#include <string>
#include <vector>

static const int W = 768, H = 432, TW = 384, TH = 216;
static const double RATE = 44100, SECONDS = 6.0;
static bool failed;

struct Synth {
    double t = 0;
    std::mt19937 rng{7};
    std::uniform_real_distribution<float> noise{-1, 1};
    void fill(std::vector<float>& out, int frames) {
        out.resize(frames * 2);
        const double beat = 0.5;  // 120 bpm
        static const double roots[4] = {55.0, 43.65, 49.0, 41.2};
        for (int i = 0; i < frames; i++, t += 1 / RATE) {
            double b = std::fmod(t, beat), bar = std::fmod(t, beat * 4);
            int chord = int(t / (beat * 8)) % 4;
            double kick = std::exp(-b * 18) * std::sin(2 * M_PI * (50 + 120 * std::exp(-b * 30)) * b);
            double sn = (int(t / beat) % 2 == 1) ? std::exp(-b * 25) * noise(rng) * 0.6 : 0;
            double hb = std::fmod(t, beat / 2);
            double hat = std::exp(-hb * 120) * noise(rng) * 0.25;
            double f = roots[chord];
            double bass = 0.35 * (2 * std::fmod(t * f, 1.0) - 1) * (0.6 + 0.4 * std::exp(-b * 4));
            double pad = 0.12 * (std::sin(2 * M_PI * f * 4 * t) + std::sin(2 * M_PI * f * 5 * t) + std::sin(2 * M_PI * f * 6 * t));
            double swell = 0.6 + 0.4 * std::sin(2 * M_PI * bar / (beat * 4));
            float s = float((kick * 0.9 + sn + hat + bass + pad) * swell * 0.7);
            out[i * 2] = s;
            out[i * 2 + 1] = s * 0.9f + 0.1f * float(pad);
        }
    }
};

struct Scores { double colourful, bright, dark; };

static Scores score(const std::vector<unsigned char>& px) {
    double sum = 0, rgm = 0, rgs = 0, ybm = 0, ybs = 0; int dark = 0, n = 0;
    for (size_t i = 0; i + 3 < px.size(); i += 4 * 4) {  // every 4th pixel
        double r = px[i], g = px[i + 1], b = px[i + 2];
        double y = 0.299 * r + 0.587 * g + 0.114 * b;
        sum += y; if (y < 16) dark++;
        double rg = r - g, yb = 0.5 * (r + g) - b;
        rgm += rg; rgs += rg * rg; ybm += yb; ybs += yb * yb; n++;
    }
    rgm /= n; ybm /= n;
    double sdrg = std::sqrt(std::max(0.0, rgs / n - rgm * rgm)), sdyb = std::sqrt(std::max(0.0, ybs / n - ybm * ybm));
    double c = std::sqrt(sdrg * sdrg + sdyb * sdyb) + 0.3 * std::sqrt(rgm * rgm + ybm * ybm);
    return {c, sum / n, double(dark) / n};
}

static double motion(const std::vector<unsigned char>& a, const std::vector<unsigned char>& b) {
    double d = 0; int n = 0;
    for (size_t i = 0; i + 3 < a.size(); i += 4 * 4, n++) {
        double ya = 0.299 * a[i] + 0.587 * a[i + 1] + 0.114 * a[i + 2];
        double yb = 0.299 * b[i] + 0.587 * b[i + 1] + 0.114 * b[i + 2];
        d += std::fabs(ya - yb);
    }
    return d / n;
}

static void grab(std::vector<unsigned char>& px) {
    px.resize(W * H * 4);
    glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
}

static void saveThumb(const std::vector<unsigned char>& px, const std::string& path) {
    std::vector<unsigned char> t(TW * TH * 3);
    for (int y = 0; y < TH; y++)
        for (int x = 0; x < TW; x++)
            for (int c = 0; c < 3; c++) {
                int sy = H - 1 - y * 2, sx = x * 2, s = 0;  // flip: GL rows start at the bottom
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) s += px[((sy - dy) * W + sx + dx) * 4 + c];
                t[(y * TW + x) * 3 + c] = (unsigned char)(s / 4);
            }
    stbi_write_png(path.c_str(), TW, TH, 3, t.data(), TW * 3);
}

int main(int argc, char** argv) {
    if (argc < 3) { std::cerr << "usage: mac-render <texture dir> <out dir> < presets\n"; return 2; }
    SDL_Init(SDL_INIT_VIDEO);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_FLAGS, SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG);
    SDL_Window* win = SDL_CreateWindow("mac-render", 0, 0, W, H, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
    SDL_GLContext ctx = SDL_GL_CreateContext(win);
    if (!ctx) { std::cerr << SDL_GetError() << "\n"; return 1; }
    SDL_GL_SetSwapInterval(0);
    std::string out = argv[2];
    Synth synth;
    std::vector<float> pcm;
    std::vector<unsigned char> px, a, best;
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        projectm_handle pm = projectm_create();
        const char* paths[] = {argv[1]};
        projectm_set_texture_search_paths(pm, paths, 1);
        projectm_set_window_size(pm, W, H);
        projectm_set_mesh_size(pm, 32, 24);
        projectm_set_preset_duration(pm, 1000);
        projectm_set_hard_cut_enabled(pm, false);
        projectm_set_preset_locked(pm, true);
        projectm_set_fps(pm, 60);
        failed = false;
        projectm_set_preset_switch_failed_event_callback(pm, [](const char*, const char*, void*) { failed = true; }, nullptr);
        projectm_load_preset_file(pm, line.c_str(), false);
        auto start = std::chrono::steady_clock::now(), last = start;
        int frames = 0; double bestScore = -1, mot = 0; Scores bestS{0, 0, 1};
        bool tookA = false, tookB = false; double nextScore = 3.0;
        while (!failed) {
            auto now = std::chrono::steady_clock::now();
            double el = std::chrono::duration<double>(now - start).count();
            if (el >= SECONDS) break;
            int n = int(std::chrono::duration<double>(now - last).count() * RATE);
            last = now;
            if (n > 0) { synth.fill(pcm, n); projectm_pcm_add_float(pm, pcm.data(), n, PROJECTM_STEREO); }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, W, H);
            projectm_opengl_render_frame(pm);
            glFinish();
            frames++;
            if (!tookA && el >= 5.0) { grab(a); tookA = true; }
            else if (tookA && !tookB && el >= 5.1) { grab(px); mot = motion(a, px); tookB = true; }
            if (el >= nextScore) {
                grab(px);
                Scores s = score(px);
                double v = s.colourful * (1 - s.dark) * std::min(1.0, s.bright / 60);
                if (v > bestScore) { bestScore = v; bestS = s; best = px; }
                nextScore += 0.75;
            }
            SDL_Event e; while (SDL_PollEvent(&e)) {}
        }
        double ms = frames ? SECONDS * 1000 / frames : 0;
        std::string name = line.substr(line.find_last_of('/') + 1);
        if (!failed && !best.empty()) saveThumb(best, out + "/" + name.substr(0, name.size() - 5) + ".png");
        std::printf("%s\t%.1f\t%.1f\t%.2f\t%.2f\t%.2f\t%d\n", name.c_str(), bestS.colourful, bestS.bright, bestS.dark, mot, ms, failed ? 0 : 1);
        std::fflush(stdout);
        projectm_destroy(pm);
    }
    SDL_GL_DeleteContext(ctx);
    SDL_DestroyWindow(win);
    SDL_Quit();
}

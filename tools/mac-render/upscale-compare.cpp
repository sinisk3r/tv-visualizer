// Upscale comparison for docs/next.md S7 ("Sharpen the stretch"): does a sharpening upscaler (AMD FidelityFX CAS,
// FSR 1 EASU+RCAS) make a small projectM inner size look like a bigger one?
//
// Two modes, run by tools/mac-render/upscale-compare.sh:
//   upscale-compare render <texture dir> <preset.milk> <w> <h> <frames> <out.png>
//     Renders the preset into an RGBA8 framebuffer at w x h for <frames> frames of synthetic music and saves the
//     last frame. Each render runs in its own process with a fake clock (exactly 1/60 s per frame) and fixed
//     random numbers, so 480p, 720p and 1080p renders show the same moment of the same preset. Rendering itself
//     still differs by size (that is the point), and feedback presets drift apart over time, so keep <frames> short.
//   upscale-compare compare <out dir> <name> <ref.png> <low.png>...
//     Upscales each low-res render to the reference size with linear (glBlitFramebuffer, as native.cpp does today),
//     CAS (one pass) and FSR 1 (EASU then RCAS), scores each against the reference on luma, writes 2x crops of the
//     busiest 480x270 region, and prints one TSV row per size and method:
//       name size method psnr ssim sharp over% under% ringOver% ringUnder% gpu_ms
//     Each size also gets a control row "<size>-ds": the reference shrunk by area average, then upscaled, which
//     scores the upscaler alone (no render differences). No crops or timing for those.
//     sharp: mean Sobel gradient magnitude / the reference's (1.0 = as crisp as the reference).
//     over/under: share of pixels near bright detail (5x5 reference max >= 96) that are 16+ levels brighter than
//       the reference's 5x5 max, or darker than its 5x5 min (halo against the real picture; includes render drift).
//     ringOver/ringUnder: same test against the linear upscale's 5x5 max/min instead of the reference: what the
//       filter itself adds beyond its input's local range (linear is 0 by definition).
//     gpu_ms: Mac time of one pass (median wall time of run + glFinish, minus an empty glFinish); relative only.
//   Env: CAS_SHARP (0-1, default 0.5), RCAS_STOPS (0 = max, default 0.2).
// Shaders live in tools/mac-render/upscale/ (GLSL 330 / GLES 300 bodies; see the README there for source and licence).
#include <projectM-4/projectM.h>
#include <SDL.h>
#include <OpenGL/gl3.h>
// stb_image and stb_image_write are compiled into projectM (SOIL2).
#include "stb_image.h"
#include "stb_image_write.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <random>
#include <sstream>
#include <string>
#include <time.h>
#include <vector>

#ifndef UPSCALE_SHADER_DIR
#define UPSCALE_SHADER_DIR "upscale"
#endif

// ---- Determinism ------------------------------------------------------------------------------------------------
// projectM reads the clock (TimeKeeper, noise seeds, transitions), std::random_device (hue offsets, random
// textures) and rand() seeded with time. These definitions replace the libc++/libc ones at link time for this
// binary only (projectM is a static library, so its references bind here first).
static double fakeSeconds = 1000.0;
static unsigned fakeRandom = 12345;

namespace std { inline namespace __1 { namespace chrono {
steady_clock::time_point steady_clock::now() noexcept {
    return time_point(duration_cast<duration>(std::chrono::duration<double>(fakeSeconds)));
}
system_clock::time_point system_clock::now() noexcept {
    return time_point(duration_cast<duration>(std::chrono::duration<double>(fakeSeconds)));
}
}  // namespace chrono
unsigned random_device::operator()() { return fakeRandom = fakeRandom * 1664525u + 1013904223u; }
}}  // namespace std::__1
extern "C" void srand(unsigned) {}  // rand() keeps its default seed (1)

// ---- Synthetic music (same as render.cpp) -------------------------------------------------------------------------
static const double RATE = 44100;
struct Synth {
    double t = 0;
    std::mt19937 rng{7};
    std::uniform_real_distribution<float> noise{-1, 1};
    void fill(std::vector<float>& out, int frames) {
        out.resize(frames * 2);
        const double beat = 0.5;
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

// ---- GL helpers ---------------------------------------------------------------------------------------------------
static SDL_Window* win;

static void initGl() {
    SDL_Init(SDL_INIT_VIDEO);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_FLAGS, SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG);
    win = SDL_CreateWindow("upscale-compare", 0, 0, 64, 64, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
    if (!SDL_GL_CreateContext(win)) { std::cerr << SDL_GetError() << "\n"; std::exit(1); }
    SDL_GL_SetSwapInterval(0);
}

struct Target { GLuint fbo = 0, tex = 0; int w = 0, h = 0; };

static Target makeTarget(int w, int h, const unsigned char* data = nullptr) {
    Target t; t.w = w; t.h = h;
    glGenTextures(1, &t.tex);
    glBindTexture(GL_TEXTURE_2D, t.tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &t.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { std::cerr << "fbo incomplete\n"; std::exit(1); }
    return t;
}

// Reads a target as top-down RGB.
static std::vector<unsigned char> readRgb(const Target& t) {
    std::vector<unsigned char> px(t.w * t.h * 4), rgb(t.w * t.h * 3);
    glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
    glReadPixels(0, 0, t.w, t.h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    for (int y = 0; y < t.h; y++)
        for (int x = 0; x < t.w; x++)
            for (int c = 0; c < 3; c++) rgb[(y * t.w + x) * 3 + c] = px[((t.h - 1 - y) * t.w + x) * 4 + c];
    return rgb;
}

static std::string readFile(const std::string& path) {
    std::ifstream f(path);
    if (!f) { std::cerr << "cannot read " << path << "\n"; std::exit(1); }
    std::stringstream s; s << f.rdbuf(); return s.str();
}

static GLuint compile(GLenum type, const std::string& body, const std::string& name) {
    // Desktop header here; the app would use "#version 300 es\nprecision highp float;\nprecision highp int;\n".
    std::string src = "#version 330 core\n" + body;
    const char* p = src.c_str();
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &p, nullptr);
    glCompileShader(s);
    GLint ok; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[4096]; glGetShaderInfoLog(s, sizeof log, nullptr, log); std::cerr << name << ": " << log; std::exit(1); }
    return s;
}

static GLuint program(const std::string& frag) {
    std::string dir = UPSCALE_SHADER_DIR;
    GLuint p = glCreateProgram();
    glAttachShader(p, compile(GL_VERTEX_SHADER, readFile(dir + "/fullscreen.vert"), "fullscreen.vert"));
    glAttachShader(p, compile(GL_FRAGMENT_SHADER, readFile(dir + "/" + frag), frag));
    glLinkProgram(p);
    GLint ok; glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) { char log[4096]; glGetProgramInfoLog(p, sizeof log, nullptr, log); std::cerr << frag << ": " << log; std::exit(1); }
    return p;
}

// ---- Render mode --------------------------------------------------------------------------------------------------
static int renderMode(const char* texDir, const char* preset, int w, int h, int frames, const char* out) {
    initGl();
    Target t = makeTarget(w, h);
    bool failed = false;
    projectm_handle pm = projectm_create();
    const char* paths[] = {texDir};
    projectm_set_texture_search_paths(pm, paths, 1);
    projectm_set_window_size(pm, w, h);
    projectm_set_mesh_size(pm, 32, 24);  // as native.cpp
    projectm_set_aspect_correction(pm, true);
    projectm_set_fps(pm, 60);
    projectm_set_preset_duration(pm, 1000);
    projectm_set_hard_cut_enabled(pm, false);
    projectm_set_preset_locked(pm, true);
    projectm_set_preset_switch_failed_event_callback(pm, [](const char*, const char*, void* f) { *(bool*)f = true; }, &failed);
    projectm_load_preset_file(pm, preset, false);
    Synth synth;
    std::vector<float> pcm;
    const int perFrame = int(RATE / 60);
    for (int i = 0; i < frames && !failed; i++) {
        fakeSeconds += 1.0 / 60;
        synth.fill(pcm, perFrame);
        projectm_pcm_add_float(pm, pcm.data(), perFrame, PROJECTM_STEREO);
        glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
        glViewport(0, 0, w, h);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        projectm_opengl_render_frame_fbo(pm, t.fbo);
    }
    glFinish();
    if (failed) { std::cerr << "preset failed: " << preset << "\n"; return 1; }
    auto rgb = readRgb(t);
    stbi_write_png(out, w, h, 3, rgb.data(), w * 3);
    projectm_destroy(pm);
    return 0;
}

// ---- Metrics ------------------------------------------------------------------------------------------------------
using Plane = std::vector<float>;

static Plane luma(const std::vector<unsigned char>& rgb) {
    Plane y(rgb.size() / 3);
    for (size_t i = 0; i < y.size(); i++) y[i] = 0.299f * rgb[i * 3] + 0.587f * rgb[i * 3 + 1] + 0.114f * rgb[i * 3 + 2];
    return y;
}

static Plane gaussBlur(const Plane& in, int w, int h) {
    static std::vector<float> k;
    if (k.empty()) { float s = 0; for (int i = -5; i <= 5; i++) { k.push_back(std::exp(-i * i / (2 * 1.5f * 1.5f))); s += k.back(); } for (auto& v : k) v /= s; }
    Plane tmp(in.size()), out(in.size());
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            float s = 0;
            for (int i = -5; i <= 5; i++) s += k[i + 5] * in[y * w + std::clamp(x + i, 0, w - 1)];
            tmp[y * w + x] = s;
        }
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            float s = 0;
            for (int i = -5; i <= 5; i++) s += k[i + 5] * tmp[std::clamp(y + i, 0, h - 1) * w + x];
            out[y * w + x] = s;
        }
    return out;
}

static double ssim(const Plane& a, const Plane& b, int w, int h) {
    Plane aa(a.size()), bb(a.size()), ab(a.size());
    for (size_t i = 0; i < a.size(); i++) { aa[i] = a[i] * a[i]; bb[i] = b[i] * b[i]; ab[i] = a[i] * b[i]; }
    Plane ma = gaussBlur(a, w, h), mb = gaussBlur(b, w, h), saa = gaussBlur(aa, w, h), sbb = gaussBlur(bb, w, h), sab = gaussBlur(ab, w, h);
    const double C1 = 6.5025, C2 = 58.5225;  // (0.01*255)^2, (0.03*255)^2
    double sum = 0;
    for (size_t i = 0; i < a.size(); i++) {
        double va = saa[i] - ma[i] * ma[i], vb = sbb[i] - mb[i] * mb[i], cv = sab[i] - ma[i] * mb[i];
        sum += ((2 * ma[i] * mb[i] + C1) * (2 * cv + C2)) / ((ma[i] * ma[i] + mb[i] * mb[i] + C1) * (va + vb + C2));
    }
    return sum / a.size();
}

static double psnr(const Plane& a, const Plane& b) {
    double se = 0;
    for (size_t i = 0; i < a.size(); i++) { double d = a[i] - b[i]; se += d * d; }
    double mse = se / a.size();
    return mse <= 1e-9 ? 99.0 : 10 * std::log10(255.0 * 255.0 / mse);
}

static Plane gradient(const Plane& p, int w, int h) {
    Plane g(p.size(), 0);
    for (int y = 1; y < h - 1; y++)
        for (int x = 1; x < w - 1; x++) {
            auto at = [&](int dx, int dy) { return p[(y + dy) * w + x + dx]; };
            float gx = at(1, -1) + 2 * at(1, 0) + at(1, 1) - at(-1, -1) - 2 * at(-1, 0) - at(-1, 1);
            float gy = at(-1, 1) + 2 * at(0, 1) + at(1, 1) - at(-1, -1) - 2 * at(0, -1) - at(1, -1);
            g[y * w + x] = std::sqrt(gx * gx + gy * gy);
        }
    return g;
}

static double mean(const Plane& p) { double s = 0; for (float v : p) s += v; return s / p.size(); }

// 5x5 max (or min) filter.
static Plane extreme5(const Plane& p, int w, int h, bool mx) {
    Plane tmp(p.size()), out(p.size());
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            float v = p[y * w + x];
            for (int i = -2; i <= 2; i++) { float q = p[y * w + std::clamp(x + i, 0, w - 1)]; v = mx ? std::max(v, q) : std::min(v, q); }
            tmp[y * w + x] = v;
        }
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            float v = tmp[y * w + x];
            for (int i = -2; i <= 2; i++) { float q = tmp[std::clamp(y + i, 0, h - 1) * w + x]; v = mx ? std::max(v, q) : std::min(v, q); }
            out[y * w + x] = v;
        }
    return out;
}

// Share (%) of pixels near bright detail (mask) that go 16+ levels above hi or below lo.
static void halo(const Plane& out, const Plane& hi, const Plane& lo, const Plane& mask, double& over, double& under) {
    long n = 0, o = 0, u = 0;
    for (size_t i = 0; i < out.size(); i++) {
        if (mask[i] < 96) continue;
        n++;
        if (out[i] > hi[i] + 16) o++;
        if (out[i] < lo[i] - 16) u++;
    }
    over = n ? 100.0 * o / n : 0;
    under = n ? 100.0 * u / n : 0;
}

// ---- Compare mode -------------------------------------------------------------------------------------------------
static std::vector<unsigned char> loadRgb(const std::string& path, int& w, int& h) {
    int c;
    unsigned char* d = stbi_load(path.c_str(), &w, &h, &c, 3);
    if (!d) { std::cerr << "cannot load " << path << "\n"; std::exit(1); }
    std::vector<unsigned char> v(d, d + w * h * 3);
    stbi_image_free(d);
    return v;
}

static Target uploadRgb(const std::vector<unsigned char>& rgb, int w, int h) {
    std::vector<unsigned char> px(w * h * 4);
    for (int y = 0; y < h; y++)  // flip: GL rows start at the bottom
        for (int x = 0; x < w; x++) {
            for (int c = 0; c < 3; c++) px[(y * w + x) * 4 + c] = rgb[((h - 1 - y) * w + x) * 3 + c];
            px[(y * w + x) * 4 + 3] = 255;
        }
    return makeTarget(w, h, px.data());
}

// Area-average resize (box filter with fractional edge weights), for the control input.
static std::vector<unsigned char> areaDown(const std::vector<unsigned char>& in, int W, int H, int w, int h) {
    std::vector<unsigned char> out(w * h * 3);
    double sx = double(W) / w, sy = double(H) / h;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            double acc[3] = {0, 0, 0}, wsum = 0;
            double x0 = x * sx, x1 = x0 + sx, y0 = y * sy, y1 = y0 + sy;
            for (int yy = int(y0); yy < std::min(H, int(std::ceil(y1))); yy++) {
                double wy = std::min(y1, yy + 1.0) - std::max(y0, double(yy));
                for (int xx = int(x0); xx < std::min(W, int(std::ceil(x1))); xx++) {
                    double wgt = wy * (std::min(x1, xx + 1.0) - std::max(x0, double(xx)));
                    for (int c = 0; c < 3; c++) acc[c] += wgt * in[(yy * W + xx) * 3 + c];
                    wsum += wgt;
                }
            }
            for (int c = 0; c < 3; c++) out[(y * w + x) * 3 + c] = (unsigned char)std::lround(acc[c] / wsum);
        }
    return out;
}

static void writeCrop(const std::vector<unsigned char>& rgb, int w, int cx, int cy, const std::string& path) {
    const int CW = 480, CH = 270, Z = 2;
    std::vector<unsigned char> out(CW * Z * CH * Z * 3);
    for (int y = 0; y < CH * Z; y++)
        for (int x = 0; x < CW * Z; x++)
            for (int c = 0; c < 3; c++) out[(y * CW * Z + x) * 3 + c] = rgb[((cy + y / Z) * w + cx + x / Z) * 3 + c];
    stbi_write_png(path.c_str(), CW * Z, CH * Z, 3, out.data(), CW * Z * 3);
}

struct Upscaler {
    GLuint cas, easu, rcas, vao;
    float casSharp, rcasStops;
    Target mid;  // EASU output at reference size

    static void con0(GLuint p, int inW, int inH, int outW, int outH) {
        glUniform4f(glGetUniformLocation(p, "uCon0"), float(inW) / outW, float(inH) / outH, 0.5f * inW / outW - 0.5f,
                    0.5f * inH / outH - 0.5f);
    }
    void draw(GLuint p, const Target& src, const Target& dst) {
        glBindFramebuffer(GL_FRAMEBUFFER, dst.fbo);
        glViewport(0, 0, dst.w, dst.h);
        glUseProgram(p);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, src.tex);
        glUniform1i(glGetUniformLocation(p, "uSrc"), 0);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }
    // method 0 linear, 1 CAS, 2 FSR (EASU + RCAS); stage -1 = all, 0 = first pass only, 1 = second pass only.
    void run(int method, const Target& src, const Target& dst, int stage = -1) {
        if (method == 0) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, src.fbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, dst.fbo);
            glBlitFramebuffer(0, 0, src.w, src.h, 0, 0, dst.w, dst.h, GL_COLOR_BUFFER_BIT, GL_LINEAR);
        } else if (method == 1) {
            glUseProgram(cas);
            con0(cas, src.w, src.h, dst.w, dst.h);
            glUniform1f(glGetUniformLocation(cas, "uPeak"), -1.0f / (8.0f + (5.0f - 8.0f) * casSharp));
            draw(cas, src, dst);
        } else {
            if (stage != 1) {
                glUseProgram(easu);
                con0(easu, src.w, src.h, dst.w, dst.h);
                draw(easu, src, mid);
            }
            if (stage != 0) {
                glUseProgram(rcas);
                glUniform1f(glGetUniformLocation(rcas, "uSharp"), std::exp2(-rcasStops));
                draw(rcas, mid, dst);
            }
        }
    }
    // Wall time per pass, glFinish after every run, minus the cost of an empty glFinish. Batched runs are useless
    // here: GL timer queries gave 0 ms for a blit, and a tile GPU (Apple; Mali has Forward Pixel Kill) skips
    // shading of full-screen draws that a later draw covers. std::chrono clocks are faked above, so this uses the
    // raw uptime clock.
    static double finishMs() {
        static double base = -1;
        if (base < 0) {
            glFinish();
            uint64_t t0 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
            for (int i = 0; i < 200; i++) glFinish();
            base = (clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - t0) / 1e6 / 200;
        }
        return base;
    }
    double gpuMs(int method, const Target& src, const Target& dst, int stage = -1) {
        for (int i = 0; i < 10; i++) { run(method, src, dst, stage); glFinish(); }
        const int N = 100;
        std::vector<double> t(N);
        for (int i = 0; i < N; i++) {
            uint64_t t0 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
            run(method, src, dst, stage);
            glFinish();
            t[i] = (clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - t0) / 1e6;
        }
        std::sort(t.begin(), t.end());
        return std::max(0.0, t[N / 2] - finishMs());  // median
    }
};

static int compareMode(const std::string& outDir, const std::string& name, const std::string& refPath, std::vector<std::string> lows) {
    initGl();
    Upscaler up;
    up.cas = program("cas.frag");
    up.easu = program("easu.frag");
    up.rcas = program("rcas.frag");
    glGenVertexArrays(1, &up.vao);
    up.casSharp = getenv("CAS_SHARP") ? std::atof(getenv("CAS_SHARP")) : 0.5f;
    up.rcasStops = getenv("RCAS_STOPS") ? std::atof(getenv("RCAS_STOPS")) : 0.2f;

    int W, H;
    auto refRgb = loadRgb(refPath, W, H);
    Plane ref = luma(refRgb), refG = gradient(ref, W, H);
    Plane refMax = extreme5(ref, W, H, true), refMin = extreme5(ref, W, H, false);
    double refSharp = mean(refG);
    up.mid = makeTarget(W, H);
    Target dst = makeTarget(W, H);

    // Busiest 480x270 region of the reference (sum of gradient), on a 30-pixel grid.
    int cx = 0, cy = 0; double best = -1;
    for (int y = 0; y + 270 <= H; y += 30)
        for (int x = 0; x + 480 <= W; x += 30) {
            double s = 0;
            for (int yy = y; yy < y + 270; yy += 2) for (int xx = x; xx < x + 480; xx += 2) s += refG[yy * W + xx];
            if (s > best) { best = s; cx = x; cy = y; }
        }
    writeCrop(refRgb, W, cx, cy, outDir + "/" + name + "_ref.png");

    static const char* methods[] = {"linear", "cas", "fsr1"};
    struct Input { std::string label; std::vector<unsigned char> rgb; int w, h; bool crops; };
    std::vector<Input> inputs;
    for (auto& low : lows) {
        Input in;
        in.rgb = loadRgb(low, in.w, in.h);
        in.label = std::to_string(in.h) + "p";
        in.crops = true;
        inputs.push_back(in);
        // Control: the reference shrunk to the same size (area average). Upscaling this shows the upscaler alone,
        // without the differences that come from projectM drawing at a lower size.
        inputs.push_back({in.label + "-ds", areaDown(refRgb, W, H, in.w, in.h), in.w, in.h, false});
    }
    for (auto& in : inputs) {
        Target src = uploadRgb(in.rgb, in.w, in.h);
        Plane linMax, linMin;
        for (int m = 0; m < 3; m++) {
            up.run(m, src, dst);
            glFinish();
            auto rgb = readRgb(dst);
            Plane y = luma(rgb);
            if (m == 0) { linMax = extreme5(y, W, H, true); linMin = extreme5(y, W, H, false); }
            double over, under, rOver, rUnder;
            halo(y, refMax, refMin, refMax, over, under);
            halo(y, linMax, linMin, refMax, rOver, rUnder);
            double ms = in.crops ? up.gpuMs(m, src, dst) : 0;
            std::string extra;
            if (m == 2 && in.crops) {
                char b[64];
                std::snprintf(b, sizeof b, " (easu %.3f + rcas %.3f)", up.gpuMs(m, src, dst, 0), up.gpuMs(m, src, dst, 1));
                extra = b;
            }
            std::printf("%s\t%s\t%s\t%.2f\t%.4f\t%.3f\t%.2f\t%.2f\t%.2f\t%.2f\t%.3f%s\n", name.c_str(), in.label.c_str(),
                        methods[m], psnr(ref, y), ssim(ref, y, W, H), mean(gradient(y, W, H)) / refSharp, over, under, rOver,
                        rUnder, ms, extra.c_str());
            std::fflush(stdout);
            if (in.crops) writeCrop(rgb, W, cx, cy, outDir + "/" + name + "_" + in.label + "_" + methods[m] + ".png");
        }
        glDeleteFramebuffers(1, &src.fbo);
        glDeleteTextures(1, &src.tex);
    }
    std::fprintf(stderr, "%s crop at %d,%d\n", name.c_str(), cx, cy);
    return 0;
}

int main(int argc, char** argv) {
    std::string mode = argc > 1 ? argv[1] : "";
    if (mode == "render" && argc == 8) return renderMode(argv[2], argv[3], std::atoi(argv[4]), std::atoi(argv[5]), std::atoi(argv[6]), argv[7]);
    if (mode == "compare" && argc >= 6) return compareMode(argv[2], argv[3], argv[4], std::vector<std::string>(argv + 5, argv + argc));
    std::cerr << "usage: upscale-compare render <texture dir> <preset.milk> <w> <h> <frames> <out.png>\n"
                 "       upscale-compare compare <out dir> <name> <ref.png> <low.png>...\n";
    return 2;
}

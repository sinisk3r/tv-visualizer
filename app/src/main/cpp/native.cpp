// JNI bridge: one projectM instance, driven from the GLSurfaceView render thread only.
#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <projectM-4/projectM.h>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <fstream>
#include <iterator>
#include <mutex>
#include <string>
#include <thread>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "Visualizer", __VA_ARGS__)

namespace {
projectm_handle pm = nullptr;
// Events from projectM callbacks, handed back to Java after each frame as bit flags.
std::atomic<int> events{0};
constexpr int EV_SWITCH = 1;     // projectM wants a new preset (timer or beat)
constexpr int EV_HARD_CUT = 2;   // ...and it was a hard cut (switch without a blend)
constexpr int EV_FAILED = 4;     // last preset failed to load or compile
constexpr int EV_SONG = 8;       // a new song started (a gap of near-silence, then sound)
constexpr int EV_DROP = 16;      // the music got much louder at once (a "drop")
std::chrono::steady_clock::time_point presetLoadedAt;

void onSwitch(bool hard, void*) { events |= EV_SWITCH | (hard ? EV_HARD_CUT : 0); }
void onFailed(const char* file, const char* msg, void*) {
    LOG("preset failed: %s: %s", file ? file : "?", msg ? msg : "?");
    events |= EV_FAILED;
}
// --- shader warmer ---
// Loading a preset blocks the render thread while its warp and composite shaders compile (25-220 ms on a
// Mali-G52), which shows as a freeze at every change. The GPU driver keeps compiled shaders in Android's
// process-wide shader cache, so a second, tiny projectM on its own thread and GL context loads the *next*
// preset ahead of time; the real load then finds its shaders compiled (measured ~100 ms down to ~35 ms).
std::mutex warmLock;
std::condition_variable warmWake;
std::string warmWanted;      // latest path asked for; older requests are dropped
std::string warmTexDir;
bool warmStarted = false;

bool usesSharedMemory(const std::string& path) {
    // gmegabuf is one buffer shared by every projectM instance, without a lock: never run such presets here.
    std::ifstream in(path, std::ios::binary);
    std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    return text.find("gmegabuf") != std::string::npos;
}

void warmLoop() {
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(dpy, nullptr, nullptr);
    const EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                              EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
    EGLConfig cfg;
    EGLint n = 0;
    if (!eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n) || n < 1) { LOG("warmer: no EGL config"); return; }
    const EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttr);
    const EGLint pbAttr[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, pbAttr);
    if (ctx == EGL_NO_CONTEXT || surf == EGL_NO_SURFACE || !eglMakeCurrent(dpy, surf, surf, ctx)) {
        LOG("warmer: no GL context");
        return;
    }
    projectm_handle w = projectm_create();
    if (!w) { LOG("warmer: projectm_create failed"); return; }
    projectm_set_window_size(w, 64, 36);
    projectm_set_preset_locked(w, true);
    {
        std::lock_guard<std::mutex> g(warmLock);
        const char* paths[] = {warmTexDir.c_str()};
        projectm_set_texture_search_paths(w, paths, 1);
    }
    for (;;) {
        std::string path;
        {
            std::unique_lock<std::mutex> g(warmLock);
            warmWake.wait(g, [] { return !warmWanted.empty(); });
            path.swap(warmWanted);
        }
        if (usesSharedMemory(path)) continue;
        auto t0 = std::chrono::steady_clock::now();
        projectm_load_preset_file(w, path.c_str(), false);
        glFinish();
        long ms = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t0).count();
        LOG("warmed %ld ms  %s", ms, path.c_str() + path.rfind('/') + 1);
    }
}
}  // namespace

extern "C" {

// Compiles a preset's shaders in the background so loading it later is quick. Any thread.
JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_warm(JNIEnv* env, jclass, jstring path, jstring textureDir) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    const char* t = env->GetStringUTFChars(textureDir, nullptr);
    {
        std::lock_guard<std::mutex> g(warmLock);
        warmWanted = p;
        if (!warmStarted) {
            warmStarted = true;
            warmTexDir = t;
            std::thread(warmLoop).detach();
        }
    }
    warmWake.notify_one();
    env->ReleaseStringUTFChars(path, p);
    env->ReleaseStringUTFChars(textureDir, t);
}

// Variable resolution: projectM can draw at an internal size below the surface's, into our own texture, which
// render() then stretches onto the surface (patch 0003 adds projectm_opengl_render_frame_fbo). Same size: projectM
// draws straight to the surface, at no extra cost.
static int surfaceW, surfaceH, innerW, innerH;
static GLuint innerFbo, innerTex;
static int innerTexW, innerTexH;

static void setProjectMSize();
JNIEXPORT jboolean JNICALL
Java_io_github_sinisk3r_visualizer_Native_create(JNIEnv* env, jclass, jint w, jint h, jstring textureDir,
                                       jfloat presetSeconds) {
    if (pm) projectm_destroy(pm);
    pm = projectm_create();
    if (!pm) { LOG("projectm_create failed (no GLES3 context?)"); return JNI_FALSE; }
    innerFbo = innerTex = 0;  // a new GL context: the old objects are gone with it
    innerTexW = innerTexH = 0;
    surfaceW = w;
    surfaceH = h;
    setProjectMSize();
    projectm_set_fps(pm, 60);
    projectm_set_mesh_size(pm, 32, 24);
    projectm_set_aspect_correction(pm, true);
    projectm_set_preset_duration(pm, presetSeconds);
    projectm_set_soft_cut_duration(pm, 3.0);
    projectm_set_hard_cut_enabled(pm, true);
    projectm_set_hard_cut_duration(pm, 20.0);
    projectm_set_beat_sensitivity(pm, 1.0f);
    const char* tex = env->GetStringUTFChars(textureDir, nullptr);
    const char* paths[] = {tex};
    projectm_set_texture_search_paths(pm, paths, 1);
    env->ReleaseStringUTFChars(textureDir, tex);
    projectm_set_preset_switch_requested_event_callback(pm, onSwitch, nullptr);
    projectm_set_preset_switch_failed_event_callback(pm, onFailed, nullptr);
    events = 0;
    char* v = projectm_get_version_string();
    LOG("projectM %s, %dx%d, %s", v, w, h, reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
    projectm_free_string(v);
    return JNI_TRUE;
}

static void setProjectMSize() {
    if (pm) projectm_set_window_size(pm, innerW > 0 ? innerW : surfaceW, innerH > 0 ? innerH : surfaceH);
}

static bool useInner() {
    return innerW > 0 && innerH > 0 && (innerW != surfaceW || innerH != surfaceH);
}

static void ensureInnerTarget() {
    if (innerFbo && innerTexW == innerW && innerTexH == innerH) return;
    if (!innerFbo) { glGenFramebuffers(1, &innerFbo); glGenTextures(1, &innerTex); }
    glBindTexture(GL_TEXTURE_2D, innerTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, innerW, innerH, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glBindFramebuffer(GL_FRAMEBUFFER, innerFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, innerTex, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    innerTexW = innerW;
    innerTexH = innerH;
}

static void releaseInnerTarget() {
    if (innerFbo) { glDeleteFramebuffers(1, &innerFbo); glDeleteTextures(1, &innerTex); }
    innerFbo = innerTex = 0;
    innerTexW = innerTexH = 0;
}

JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_resize(JNIEnv*, jclass, jint w, jint h) {
    surfaceW = w;
    surfaceH = h;
    setProjectMSize();
}

// The size projectM draws at; 0 x 0 means the surface size.
JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_setInnerSize(JNIEnv*, jclass, jint w, jint h) {
    if (w == innerW && h == innerH) return;
    innerW = w;
    innerH = h;
    setProjectMSize();
}

JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_loadPreset(JNIEnv* env, jclass, jstring path, jboolean smooth,
                                                    jfloat blendSec) {
    if (!pm) return;
    if (smooth) projectm_set_soft_cut_duration(pm, blendSec);
    const char* p = env->GetStringUTFChars(path, nullptr);
    projectm_load_preset_file(pm, p, smooth);
    presetLoadedAt = std::chrono::steady_clock::now();
    env->ReleaseStringUTFChars(path, p);
}

JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_setTiming(JNIEnv*, jclass, jfloat presetSeconds, jboolean beatCuts) {
    if (!pm) return;
    projectm_set_preset_duration(pm, presetSeconds);
    projectm_set_hard_cut_enabled(pm, beatCuts);
    // A hard cut may only come after this long on one preset, so short durations stay calm too.
    projectm_set_hard_cut_duration(pm, presetSeconds < 20 ? presetSeconds : 20.0);
}

JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_setLocked(JNIEnv*, jclass, jboolean locked) {
    if (pm) projectm_set_preset_locked(pm, locked);
}

// Slow automatic gain. The capture comes "as played", so quiet songs or a low app volume give small waves. The
// gain follows the level over ~4 s: it fills the screen for quiet music but keeps the beat-to-beat swings that
// projectM's bass/mid/treb (current level against a ~2 s average) and beat detection depend on.
constexpr float AGC_TARGET_RMS = 0.25f;   // a little under the normalized level, so drum hits do not clip
constexpr float AGC_SECONDS = 4.0f;
constexpr float AGC_MAX_GAIN = 8.0f;
constexpr float AGC_SILENCE = 0.004f;     // below this level it is silence: do not pump up noise
float agcLevel = 0.0f;
float pcmBuf[4096];
std::chrono::steady_clock::time_point agcLast;
// Log once per 10 s: input level and gain, to check the audio path on a new TV.
std::chrono::steady_clock::time_point agcLogAt;
float agcLogIn = 0, agcLogGain = 0;
int agcLogN = 0;

// Song-aware switching. Levels are the raw capture RMS (before the gain), smoothed over three time spans.
// A gap: the 0.25 s level stays under 10% of the 12 s level for 1.2 s or more, then comes back. That is a new
// song or a drop (many EDM drops follow a second or two of silence); loudness cannot tell them apart (on Martin
// Garrix "Animals" the drop came back at 0.46x the loud build before it), and both are good moments to switch.
// The switch fires the moment sound returns, so it lands on the first beat.
// A drop without a gap: the 1.5 s level reaches twice the 12 s level (a loud section after a quieter build).
constexpr float SONG_GAP_RATIO = 0.10f;
constexpr float SONG_GAP_SECONDS = 1.2f;  // song gaps on Spotify were 1.6-10 s; pauses inside songs 0.5-1.1 s
constexpr float SONG_BACK_RATIO = 0.30f;  // sound is back when the 0.25 s level passes 30% of the 12 s level
constexpr float DROP_RATIO = 2.0f;
constexpr float SONG_MIN_PRESET_S = 8.0f;  // never switch a preset that has just come on
constexpr float DROP_MIN_PRESET_S = 8.0f;
constexpr float DROP_COOLDOWN_S = 20.0f;
float lvFast = 0, lvMid = 0, lvLong = 0;
float quietFor = 0;         // seconds the fast level has been in a gap
float gapLength = 0;        // the last gap long enough to count, waiting for sound to come back
float gapTrend = 0;         // 1.5 s level against the 12 s level as the gap began (< 1: a fade out), logged
float sinceSong = 1e9f, sinceDrop = 1e9f;

void follow(float& level, float rms, float dt, float seconds) {
    float a = std::exp(-dt / seconds);
    level = level == 0.0f ? rms : level * a + rms * (1.0f - a);
}

void detectSong(float rms, float dt, std::chrono::steady_clock::time_point now) {
    follow(lvFast, rms, dt, 0.25f);
    follow(lvMid, rms, dt, 1.5f);
    sinceSong += dt;
    sinceDrop += dt;
    float presetAge = std::chrono::duration<float>(now - presetLoadedAt).count();
    float gapLevel = std::max(AGC_SILENCE, lvLong * SONG_GAP_RATIO);
    if (lvFast < gapLevel) {
        if (quietFor == 0) gapTrend = lvMid / std::max(lvLong, 1e-6f);
        quietFor += dt;
        if (quietFor >= SONG_GAP_SECONDS) gapLength = quietFor;
        return;  // keep the 12 s level from the music before the gap
    }
    quietFor = 0;
    follow(lvLong, rms, dt, 12.0f);
    if (gapLength > 0 && lvFast > lvLong * SONG_BACK_RATIO) {
        LOG("gap: %.1f s, level before it %.2fx the 12 s level", gapLength, gapTrend);
        gapLength = 0;
        sinceSong = 0;
        lvMid = lvFast;  // the jump back from the gap is not a second drop
        if (presetAge >= SONG_MIN_PRESET_S) events |= EV_SONG;
        return;
    }
    if (lvMid > lvLong * DROP_RATIO && sinceDrop > DROP_COOLDOWN_S && sinceSong > 5.0f) {
        LOG("drop: 1.5 s level %.3f, %.1fx the 12 s level", lvMid, lvMid / lvLong);
        sinceDrop = 0;
        if (presetAge >= DROP_MIN_PRESET_S) events |= EV_DROP;
    }
}

// 8-bit unsigned mono PCM, straight from android.media.audiofx.Visualizer.getWaveForm().
JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_addPcm(JNIEnv* env, jclass, jbyteArray data, jint count) {
    if (!pm || count <= 0) return;
    if (count > 4096) count = 4096;
    jbyte* b = env->GetByteArrayElements(data, nullptr);
    const auto* u = reinterpret_cast<const uint8_t*>(b);
    float sum = 0;
    for (int i = 0; i < count; i++) {
        float x = (static_cast<float>(u[i]) - 128.0f) / 128.0f;
        pcmBuf[i] = x;
        sum += x * x;
    }
    env->ReleaseByteArrayElements(data, b, JNI_ABORT);
    float rms = std::sqrt(sum / static_cast<float>(count));

    auto now = std::chrono::steady_clock::now();
    float dt = agcLast.time_since_epoch().count() == 0
            ? 0.016f : std::chrono::duration<float>(now - agcLast).count();
    agcLast = now;
    if (dt > 0.5f) dt = 0.5f;
    float a = std::exp(-dt / AGC_SECONDS);
    agcLevel = agcLevel == 0.0f ? rms : agcLevel * a + rms * (1.0f - a);
    float gain = agcLevel < AGC_SILENCE ? 1.0f : std::min(AGC_MAX_GAIN, std::max(1.0f, AGC_TARGET_RMS / agcLevel));
    for (int i = 0; i < count; i++) pcmBuf[i] = std::max(-1.0f, std::min(1.0f, pcmBuf[i] * gain));
    projectm_pcm_add_float(pm, pcmBuf, count, PROJECTM_MONO);
    detectSong(rms, dt, now);

    agcLogIn += rms;
    agcLogGain += gain;
    agcLogN++;
    if (now >= agcLogAt) {
        if (agcLogN > 0 && agcLogAt.time_since_epoch().count() != 0) {
            LOG("audio: level %.3f, gain %.1f (10 s average)", agcLogIn / agcLogN, agcLogGain / agcLogN);
        }
        agcLogAt = now + std::chrono::seconds(10);
        agcLogIn = agcLogGain = 0;
        agcLogN = 0;
    }
}

// Draws one frame and returns the callback events raised since the last frame.
JNIEXPORT jint JNICALL
Java_io_github_sinisk3r_visualizer_Native_render(JNIEnv*, jclass) {
    if (!pm) return 0;
    glClearColor(0, 0, 0, 1);
    if (!useInner()) {
        glClear(GL_COLOR_BUFFER_BIT);
        projectm_opengl_render_frame(pm);
        return events.exchange(0);
    }
    ensureInnerTarget();
    glBindFramebuffer(GL_FRAMEBUFFER, innerFbo);
    glViewport(0, 0, innerW, innerH);
    glClear(GL_COLOR_BUFFER_BIT);
    projectm_opengl_render_frame_fbo(pm, innerFbo);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, innerFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glBlitFramebuffer(0, 0, innerW, innerH, 0, 0, surfaceW, surfaceH, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, surfaceW, surfaceH);
    return events.exchange(0);
}

JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_destroy(JNIEnv*, jclass) {
    if (pm) { projectm_destroy(pm); pm = nullptr; }
    releaseInnerTarget();
}

}  // extern "C"

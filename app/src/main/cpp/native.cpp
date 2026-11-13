// JNI bridge: one projectM instance, driven from the GLSurfaceView render thread only.
#include <jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>
#include <projectM-4/projectM.h>
#include <atomic>
#include <string>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "Visualizer", __VA_ARGS__)

namespace {
projectm_handle pm = nullptr;
// Events from projectM callbacks, handed back to Java after each frame as bit flags.
std::atomic<int> events{0};
constexpr int EV_SWITCH = 1;     // projectM wants a new preset (timer or beat)
constexpr int EV_HARD_CUT = 2;   // ...and it was a hard cut (switch without a blend)
constexpr int EV_FAILED = 4;     // last preset failed to load or compile

void onSwitch(bool hard, void*) { events |= EV_SWITCH | (hard ? EV_HARD_CUT : 0); }
void onFailed(const char* file, const char* msg, void*) {
    LOG("preset failed: %s: %s", file ? file : "?", msg ? msg : "?");
    events |= EV_FAILED;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_sinisk3r_visualizer_Native_create(JNIEnv* env, jclass, jint w, jint h, jstring textureDir,
                                       jfloat presetSeconds) {
    if (pm) projectm_destroy(pm);
    pm = projectm_create();
    if (!pm) { LOG("projectm_create failed (no GLES3 context?)"); return JNI_FALSE; }
    projectm_set_window_size(pm, w, h);
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
    LOG("projectM %s, %dx%d", v, w, h);
    projectm_free_string(v);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_resize(JNIEnv*, jclass, jint w, jint h) {
    if (pm) projectm_set_window_size(pm, w, h);
}

JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_loadPreset(JNIEnv* env, jclass, jstring path, jboolean smooth) {
    if (!pm) return;
    const char* p = env->GetStringUTFChars(path, nullptr);
    projectm_load_preset_file(pm, p, smooth);
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

// 8-bit unsigned mono PCM, straight from android.media.audiofx.Visualizer.getWaveForm().
JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_addPcm(JNIEnv* env, jclass, jbyteArray data, jint count) {
    if (!pm || count <= 0) return;
    jbyte* b = env->GetByteArrayElements(data, nullptr);
    projectm_pcm_add_uint8(pm, reinterpret_cast<const uint8_t*>(b), count, PROJECTM_MONO);
    env->ReleaseByteArrayElements(data, b, JNI_ABORT);
}

// Draws one frame and returns the callback events raised since the last frame.
JNIEXPORT jint JNICALL
Java_io_github_sinisk3r_visualizer_Native_render(JNIEnv*, jclass) {
    if (!pm) return 0;
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    projectm_opengl_render_frame(pm);
    return events.exchange(0);
}

JNIEXPORT void JNICALL
Java_io_github_sinisk3r_visualizer_Native_destroy(JNIEnv*, jclass) {
    if (pm) { projectm_destroy(pm); pm = nullptr; }
}

}  // extern "C"

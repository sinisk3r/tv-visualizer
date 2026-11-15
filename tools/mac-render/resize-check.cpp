// Checks patch 0004: a size change keeps the picture. Renders a preset for 3 s at 768x432, switches projectM to
// 512x288, renders one frame, and prints mean brightness before and after. Without the patch "after" drops
// towards black (feedback restarts); with it they stay close.
// Usage: resize-check <texture dir> <preset.milk>
#include <projectM-4/projectM.h>
#include <SDL.h>
#include <OpenGL/gl3.h>
#include <chrono>
#include <cstdio>
#include <vector>

static double meanLuma(int w, int h) {
    std::vector<unsigned char> px(w * h * 4);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    double s = 0;
    for (size_t i = 0; i < px.size(); i += 4) s += 0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2];
    return s / (w * h);
}

int main(int argc, char** argv) {
    if (argc < 3) return 2;
    SDL_Init(SDL_INIT_VIDEO);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_FLAGS, SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG);
    SDL_Window* win = SDL_CreateWindow("resize-check", 0, 0, 768, 432, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
    SDL_GLContext ctx = SDL_GL_CreateContext(win);
    projectm_handle pm = projectm_create();
    const char* paths[] = {argv[1]};
    projectm_set_texture_search_paths(pm, paths, 1);
    projectm_set_window_size(pm, 768, 432);
    projectm_set_preset_locked(pm, true);
    projectm_load_preset_file(pm, argv[2], false);
    std::vector<float> pcm(1470 * 2);
    auto start = std::chrono::steady_clock::now();
    int frame = 0;
    while (std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count() < 3.0) {
        for (size_t i = 0; i < pcm.size(); i++) pcm[i] = ((frame * 1470 + i / 2) % 220 < 110 ? 0.5f : -0.5f) * (frame % 30 < 4);
        projectm_pcm_add_float(pm, pcm.data(), 1470, PROJECTM_STEREO);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        projectm_opengl_render_frame(pm);
        frame++;
    }
    glFinish();
    double before = meanLuma(768, 432);
    projectm_set_window_size(pm, 512, 288);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    projectm_opengl_render_frame(pm);
    glFinish();
    double after = meanLuma(512, 288);
    std::printf("before %.1f  after %.1f  kept %.0f%%\n", before, after, before > 0 ? 100 * after / before : 0);
    projectm_destroy(pm);
    SDL_GL_DeleteContext(ctx);
    SDL_Quit();
    return 0;
}

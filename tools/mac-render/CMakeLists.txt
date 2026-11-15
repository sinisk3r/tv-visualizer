# Mac preset renderer: renders presets offscreen to test music and writes a thumbnail plus scores per preset.
# Build: cmake -S tools/mac-render -B tools/mac-render/build -DCMAKE_BUILD_TYPE=Release && cmake --build tools/mac-render/build -j
# The projectM submodule must already carry patches/projectm (an Android build applies them).
cmake_minimum_required(VERSION 3.21)
project(mac_render LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
set(PROJECTM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/projectm)
set(ENABLE_PLAYLIST OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_GLM OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${PROJECTM_DIR} projectm)
find_package(SDL2 REQUIRED)
add_executable(mac-render render.cpp)
target_include_directories(mac-render PRIVATE ${PROJECTM_DIR}/vendor/SOIL2)
target_compile_definitions(mac-render PRIVATE GL_SILENCE_DEPRECATION)
target_link_libraries(mac-render PRIVATE libprojectM::projectM SDL2::SDL2 "-framework OpenGL")

# Checks patch 0004 (the picture survives a size change).
add_executable(resize-check resize-check.cpp)
target_compile_definitions(resize-check PRIVATE GL_SILENCE_DEPRECATION)
target_link_libraries(resize-check PRIVATE libprojectM::projectM SDL2::SDL2 "-framework OpenGL")

# Upscale comparison (docs/next.md S7): linear vs CAS vs FSR 1 from 480p/720p to 1080p. Run upscale-compare.sh.
add_executable(upscale-compare upscale-compare.cpp)
target_include_directories(upscale-compare PRIVATE ${PROJECTM_DIR}/vendor/SOIL2)
target_compile_definitions(upscale-compare PRIVATE GL_SILENCE_DEPRECATION UPSCALE_SHADER_DIR="${CMAKE_CURRENT_SOURCE_DIR}/upscale")
target_link_libraries(upscale-compare PRIVATE libprojectM::projectM SDL2::SDL2 "-framework OpenGL")

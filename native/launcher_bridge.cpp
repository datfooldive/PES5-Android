// The launcher's native side: it validates the game and reads and writes the curated options before SDL starts, so
// the launcher reaches the same verdict the game would (native/game_files.cpp) and writes the very settings.toml the
// game loads at boot (sdk/runtime/app/settings.cpp). None of this touches SDL or the game's globals, so it is safe to
// call from LauncherActivity, which only loads the library and never runs main().
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <string>

#include <toml++/toml.hpp>

#include "game_files.h"

namespace {

// the curated values' order, the same indices LauncherActivity.java has
enum Curated : int { kRenderPercent, kFrameRate, kAspect, kVsync, kAntiAlias, kCuratedCount };

// anti-aliasing as the one index the launcher shows, from the two settings it is made of (as settings_bridge.cpp)
int AntiAliasIndex(bool smaa, int msaa) {
    if (msaa >= 4) return 3;
    if (msaa >= 2) return 2;
    return smaa ? 1 : 0;
}

std::filesystem::path SettingsFile(JNIEnv* env, jstring jdir) {
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    std::filesystem::path path = std::filesystem::path(dir ? dir : "") / "settings.toml";
    if (dir) env->ReleaseStringUTFChars(jdir, dir);
    return path;
}

// the folder (or image) the last probe was about, read right after the status
std::string g_probe_path;

}  // namespace

// NotFound 0, Extracted 1, Iso 2, WrongRelease 3: the ordinal of app::Probe. filesDir is the app's own files folder,
// so the probe reaches the same verdict the game would without SDL (the game's own assets live in filesDir/assets).
extern "C" JNIEXPORT jint JNICALL
Java_com_datfooldive_pes5_LauncherActivity_nativeProbeGame(JNIEnv* env, jclass, jstring jdir) {
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    std::filesystem::path own_assets = std::filesystem::path(dir ? dir : "") / "assets";
    if (dir) env->ReleaseStringUTFChars(jdir, dir);
    app::ProbeResult r = app::ProbeGame(own_assets);
    g_probe_path = r.dir.string();
    return static_cast<jint>(r.status);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_datfooldive_pes5_LauncherActivity_nativeProbePath(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_probe_path.c_str());
}

// the curated values the game would boot with, defaults where the file or a key is absent (as WriteDefaultSettings and
// the options' own defaults)
extern "C" JNIEXPORT jintArray JNICALL
Java_com_datfooldive_pes5_LauncherActivity_nativeLauncherGetSettings(JNIEnv* env, jclass, jstring jdir) {
    int render = 67, frameRate = 0, aspect = 0;
    bool vsync = true, smaa = false;
    int msaa = 0;
    try {
        toml::table t = toml::parse_file(SettingsFile(env, jdir).string());
        render = t["render_percent"].value_or(render);
        frameRate = t["frame_rate"].value_or(frameRate);
        aspect = t["aspect"].value_or(aspect);
        vsync = t["vsync"].value_or(vsync);
        smaa = t["smaa"].value_or(smaa);
        msaa = t["msaa"].value_or(msaa);
    } catch (...) {
    }
    jint v[kCuratedCount];
    v[kRenderPercent] = render;
    v[kFrameRate] = frameRate;
    v[kAspect] = aspect;
    v[kVsync] = vsync ? 1 : 0;
    v[kAntiAlias] = AntiAliasIndex(smaa, msaa);
    jintArray arr = env->NewIntArray(kCuratedCount);
    if (arr) env->SetIntArrayRegion(arr, 0, kCuratedCount, v);
    return arr;
}

// the curated values into settings.toml, every other key and the controls-layout table left as they were
extern "C" JNIEXPORT void JNICALL
Java_com_datfooldive_pes5_LauncherActivity_nativeLauncherSetSettings(JNIEnv* env, jclass, jstring jdir,
                                                                     jintArray jvals) {
    if (env->GetArrayLength(jvals) < kCuratedCount) return;
    jint v[kCuratedCount];
    env->GetIntArrayRegion(jvals, 0, kCuratedCount, v);
    std::filesystem::path file = SettingsFile(env, jdir);

    toml::table t;
    try {
        t = toml::parse_file(file.string());
    } catch (...) {
    }
    t.insert_or_assign("render_percent", int64_t(std::clamp<jint>(v[kRenderPercent], 20, 200)));
    t.insert_or_assign("frame_rate", int64_t(std::clamp<jint>(v[kFrameRate], 0, 1)));
    t.insert_or_assign("aspect", int64_t(std::clamp<jint>(v[kAspect], 0, 3)));
    t.insert_or_assign("vsync", v[kVsync] != 0);
    const int aa = std::clamp<jint>(v[kAntiAlias], 0, 3);
    t.insert_or_assign("smaa", aa == 1);
    t.insert_or_assign("msaa", int64_t(aa == 2 ? 2 : aa == 3 ? 4 : 0));

    std::ofstream f(file);
    if (f) f << t << "\n";
}

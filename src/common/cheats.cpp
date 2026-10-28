// The settings panel's CHEATS tab: Pro Evolution Soccer 5 has no cheat codes of its own to press for the player, so the
// list is empty and the panel leaves the tab out (SettingsDialog.java).
#include <jni.h>

#include "core/input.h"

namespace game {

// GameInfo::pad: no code is ever being played
void Cheats(input::Pad&) {}

}  // namespace game

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_datfooldive_pes5_PES5Activity_nativeCheats(JNIEnv* env, jclass) {
    return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_datfooldive_pes5_PES5Activity_nativeCheatsAvailable(JNIEnv*, jclass) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_com_datfooldive_pes5_PES5Activity_nativeCheat(JNIEnv*, jclass, jint) {}

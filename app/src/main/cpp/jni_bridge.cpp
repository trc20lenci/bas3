#include <jni.h>

#include <string>

#include "timeline_engine.h"

using base::TimelineEngine;

namespace {
inline TimelineEngine* E(jlong h) { return reinterpret_cast<TimelineEngine*>(h); }
inline std::string Str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}
}  // namespace

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_base_editor_core_NativeTimeline_##name

FN(jlong, nCreate)(JNIEnv*, jobject) { return reinterpret_cast<jlong>(new TimelineEngine()); }
FN(void, nDestroy)(JNIEnv*, jobject, jlong h) { delete E(h); }

FN(jlong, nAddClip)(JNIEnv* env, jobject, jlong h, jint row, jint type, jstring uri, jlong srcDur, jlong len) {
    return E(h)->addClip(row, type, Str(env, uri), srcDur, len);
}
FN(jboolean, nMove)(JNIEnv*, jobject, jlong h, jlong id, jlong start, jlong thr, jlong extra) {
    return E(h)->moveClip(id, start, thr, extra);
}
FN(jboolean, nTrimStart)(JNIEnv*, jobject, jlong h, jlong id, jlong ms) { return E(h)->trimStart(id, ms); }
FN(jboolean, nTrimEnd)(JNIEnv*, jobject, jlong h, jlong id, jlong ms) { return E(h)->trimEnd(id, ms); }
FN(jlong, nSplit)(JNIEnv*, jobject, jlong h, jlong id, jlong at) { return E(h)->split(id, at); }
FN(jboolean, nRemove)(JNIEnv*, jobject, jlong h, jlong id) { return E(h)->remove(id); }

FN(void, nCheckpoint)(JNIEnv*, jobject, jlong h) { E(h)->checkpoint(); }
FN(void, nDiscardIfNoop)(JNIEnv*, jobject, jlong h) { E(h)->discardCheckpointIfNoop(); }
FN(jboolean, nUndo)(JNIEnv*, jobject, jlong h) { return E(h)->undo(); }
FN(jboolean, nRedo)(JNIEnv*, jobject, jlong h) { return E(h)->redo(); }
FN(jboolean, nCanUndo)(JNIEnv*, jobject, jlong h) { return E(h)->canUndo(); }
FN(jboolean, nCanRedo)(JNIEnv*, jobject, jlong h) { return E(h)->canRedo(); }

FN(jlong, nTotalMs)(JNIEnv*, jobject, jlong h) { return E(h)->totalMs(); }
FN(jstring, nSerialize)(JNIEnv* env, jobject, jlong h) { return env->NewStringUTF(E(h)->serialize().c_str()); }
FN(jboolean, nDeserialize)(JNIEnv* env, jobject, jlong h, jstring data) { return E(h)->deserialize(Str(env, data)); }

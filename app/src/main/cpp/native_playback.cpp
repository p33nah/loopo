#include <jni.h>

#include <memory>

#include "PlaybackEngine.h"

namespace {
using EngineHandle = std::shared_ptr<PlaybackEngine>;

EngineHandle* engineFrom(jlong handle) {
	return reinterpret_cast<EngineHandle*>(handle);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_create(JNIEnv*, jobject, jint sampleRate,
																				 jint channels, jint capacityFrames) {
	if (sampleRate <= 0 || channels < 1 || channels > 2 || capacityFrames < 1) return 0;
	auto engine = std::make_shared<PlaybackEngine>(sampleRate, channels, capacityFrames);
	if (!engine->open()) return 0;
	return reinterpret_cast<jlong>(new EngineHandle(std::move(engine)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_destroy(JNIEnv*, jobject, jlong handle) {
	auto* engine = engineFrom(handle);
	if (!engine) return;
	(*engine)->close();
	delete engine;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_write(JNIEnv* env, jobject, jlong handle,
																	  jfloatArray samples, jint offsetFrames,
													  jint frames, jint channels, jlong sourceStartFrame) {
	auto* engine = engineFrom(handle);
	if (!engine || !samples || frames <= 0 || offsetFrames < 0 || channels != (*engine)->channelCount()) return 0;
	const auto requiredSamples = (offsetFrames + frames) * channels;
	if (env->GetArrayLength(samples) < requiredSamples) return 0;
	auto* values = env->GetFloatArrayElements(samples, nullptr);
	if (!values) return 0;
	const auto written = (*engine)->write(values + offsetFrames * channels, frames, sourceStartFrame);
	env->ReleaseFloatArrayElements(samples, values, JNI_ABORT);
	return written;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_resetAtFrame(JNIEnv*, jobject, jlong handle, jlong frame) {
	if (auto* engine = engineFrom(handle)) return (*engine)->resetAtFrame(frame) ? JNI_TRUE : JNI_FALSE;
	return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_play(JNIEnv*, jobject, jlong handle) {
	if (auto* engine = engineFrom(handle)) return (*engine)->play() ? JNI_TRUE : JNI_FALSE;
	return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_pause(JNIEnv*, jobject, jlong handle) {
	if (auto* engine = engineFrom(handle)) return (*engine)->pauseAndWait() ? JNI_TRUE : JNI_FALSE;
	return JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_writeSpace(JNIEnv*, jobject, jlong handle) {
	if (auto* engine = engineFrom(handle)) return (*engine)->writeSpace();
	return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_queuedFrames(JNIEnv*, jobject, jlong handle) {
	if (auto* engine = engineFrom(handle)) return (*engine)->queuedFrames();
	return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_setEndOfStream(JNIEnv*, jobject, jlong handle) {
	if (auto* engine = engineFrom(handle)) (*engine)->setEndOfStream(true);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_positionFrame(JNIEnv*, jobject, jlong handle) {
	if (auto* engine = engineFrom(handle)) return (*engine)->positionFrame();
	return 0;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_estimatedLatencyMillis(JNIEnv*, jobject, jlong handle) {
	if (auto* engine = engineFrom(handle)) return (*engine)->estimatedLatencyMillis();
	return -1.0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_underrunCount(JNIEnv*, jobject, jlong handle) {
	if (auto* engine = engineFrom(handle)) return (*engine)->underrunCount();
	return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_isPlaying(JNIEnv*, jobject, jlong handle) {
	if (auto* engine = engineFrom(handle)) return (*engine)->isPlaying() ? JNI_TRUE : JNI_FALSE;
	return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_p33nah_loopo_platform_audio_NativePlaybackBridge_isEnded(JNIEnv*, jobject, jlong handle) {
	if (auto* engine = engineFrom(handle)) return (*engine)->isEnded() ? JNI_TRUE : JNI_FALSE;
	return JNI_FALSE;
}

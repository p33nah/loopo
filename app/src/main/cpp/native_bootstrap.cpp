#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_p33nah_loopo_platform_audio_NativeBootstrap_apiVersion(JNIEnv*, jobject) {
	return 1;
}


#include <jni.h>
#include <string>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_regon_sadagpscamera_MainActivity_getNativeMessage(
        JNIEnv* env,
        jobject /* this */) {

    std::string message = "SADA GPS Camera Native Engine Ready";
    return env->NewStringUTF(message.c_str());
}

// android/src/main/cpp/stockfish_jni.cpp
#include <jni.h>
#include <string>
#include <android/log.h>
#include <cstdlib>

// Include the C++ bridge header
#include "stockfish_bridge.h"

#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, "StockfishNative", __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, "StockfishNative", __VA_ARGS__))

extern "C" {

JNIEXPORT jint JNICALL Java_com_dawikk_stockfish_RNStockfishModule_nativeInit(
        JNIEnv *env, jobject instance) {
    LOGI("Initializing Stockfish");
    
    // Get filesDir from Java
    jclass cls = env->GetObjectClass(instance);
    jmethodID getFilesDir = env->GetMethodID(cls, "getFilesDir", "()Ljava/lang/String;");
    jstring jFilesDir = (jstring) env->CallObjectMethod(instance, getFilesDir);
    
    if (jFilesDir != NULL) {
        const char *filesDir = env->GetStringUTFChars(jFilesDir, NULL);
        LOGI("Setting STOCKFISH_FILES_DIR to: %s", filesDir);
        setenv("STOCKFISH_FILES_DIR", filesDir, 1);
        env->ReleaseStringUTFChars(jFilesDir, filesDir);
    } else {
        LOGE("Failed to get filesDir from Java");
    }
    
    return stockfish_init();
}

JNIEXPORT jint JNICALL Java_com_dawikk_stockfish_RNStockfishModule_nativeMain(
        JNIEnv *env, jobject instance) {
    LOGI("Starting Stockfish main");
    return stockfish_main();
}

JNIEXPORT jstring JNICALL Java_com_dawikk_stockfish_RNStockfishModule_nativeReadOutput(
        JNIEnv *env, jobject instance) {
    const char *output = stockfish_stdout_read();
    if (output != NULL && output[0] != '\0') {
        return env->NewStringUTF(output);
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL Java_com_dawikk_stockfish_RNStockfishModule_nativeSendCommand(
        JNIEnv *env, jobject instance, jstring command) {
    const char *cmd = env->GetStringUTFChars(command, NULL);
    if (cmd == NULL) {
        return JNI_FALSE;
    }
    
    int success = stockfish_stdin_write(cmd);
    env->ReleaseStringUTFChars(command, cmd);
    
    return success ? JNI_TRUE : JNI_FALSE;
}

}
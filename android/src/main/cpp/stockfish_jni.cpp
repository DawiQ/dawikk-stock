// stockfish_jni.cpp
// JNI glue between the Kotlin RNStockfishModule and the C bridge API.

#include <jni.h>
#include <string>

#include "stockfish_bridge.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_com_dawikk_stockfish_RNStockfishModule_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/) {
    return stockfish_init();
}

JNIEXPORT void JNICALL
Java_com_dawikk_stockfish_RNStockfishModule_nativeMain(JNIEnv* /*env*/, jobject /*thiz*/) {
    // Blocks until a "quit" command is received.
    stockfish_main();
}

JNIEXPORT jstring JNICALL
Java_com_dawikk_stockfish_RNStockfishModule_nativeReadStdout(JNIEnv* env, jobject /*thiz*/) {
    const char* output = stockfish_stdout_read();
    if (output == nullptr) {
        return nullptr;
    }
    return env->NewStringUTF(output);
}

JNIEXPORT jint JNICALL
Java_com_dawikk_stockfish_RNStockfishModule_nativeWriteStdin(JNIEnv* env, jobject /*thiz*/, jstring data) {
    if (data == nullptr) {
        return 0;
    }
    const char* nativeData = env->GetStringUTFChars(data, nullptr);
    int result = stockfish_stdin_write(nativeData);
    env->ReleaseStringUTFChars(data, nativeData);
    return result;
}

} // extern "C"

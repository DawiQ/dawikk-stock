#include <stdio.h>
#include <unistd.h>
#include <jni.h>
#include <string>
#include <vector>
#include <array>
#include <algorithm>

#include "../stockfish/src/bitboard.h"
#include "../stockfish/src/misc.h"
#include "../stockfish/src/position.h"
#include "../stockfish/src/types.h"
#include "../stockfish/src/uci.h"
#include "../stockfish/src/tune.h"

constexpr int NUM_PIPES = 2;
constexpr int PARENT_WRITE_PIPE = 0;
constexpr int PARENT_READ_PIPE = 1;
constexpr int READ_FD = 0;
constexpr int WRITE_FD = 1;

constexpr size_t BUFFER_SIZE = 4096;

const char *QUITOK = "quitok\n";
std::array<std::array<int, 2>, NUM_PIPES> pipes;
std::vector<char> buffer(BUFFER_SIZE);

#define PARENT_READ_FD (pipes[PARENT_READ_PIPE][READ_FD])
#define PARENT_WRITE_FD (pipes[PARENT_WRITE_PIPE][WRITE_FD])

int stockfish_init()
{
    pipe(pipes[PARENT_READ_PIPE].data());
    pipe(pipes[PARENT_WRITE_PIPE].data());
    return 0;
}

int stockfish_main()
{
    dup2(pipes[PARENT_WRITE_PIPE][READ_FD], STDIN_FILENO);
    dup2(pipes[PARENT_READ_PIPE][WRITE_FD], STDOUT_FILENO);

    Stockfish::Bitboards::init();
    Stockfish::Position::init();

    int argc = 1;
    char* argv[] = {const_cast<char*>("")};
    Stockfish::UCIEngine uci(argc, argv);
    Stockfish::Tune::init(uci.engine_options());

    uci.loop();

    std::cout << QUITOK << std::flush;
    return 0;
}

ssize_t stockfish_stdin_write(const char *data)
{
    return write(PARENT_WRITE_FD, data, strlen(data));
}

const char* stockfish_stdout_read()
{
    static std::string output;
    output.clear();

    ssize_t bytesRead;
    while ((bytesRead = read(PARENT_READ_FD, buffer.data(), BUFFER_SIZE)) > 0) {
        output.append(buffer.data(), bytesRead);
        if (output.back() == '\n') {
            break;
        }
    }

    if (bytesRead < 0) {
        // Handle error
        return nullptr;
    }

    return output.c_str();
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_reactnativestockfishchessengine_StockfishChessEngineModule_init(JNIEnv*, jobject) {
    stockfish_init();
}

JNIEXPORT void JNICALL
Java_com_reactnativestockfishchessengine_StockfishChessEngineModule_main(JNIEnv*, jobject) {
    stockfish_main();
}

JNIEXPORT jstring JNICALL
Java_com_reactnativestockfishchessengine_StockfishChessEngineModule_readStdOut(JNIEnv* env, jobject) {
    const char* output = stockfish_stdout_read();
    return output ? env->NewStringUTF(output) : nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_reactnativestockfishchessengine_StockfishChessEngineModule_writeStdIn(JNIEnv* env, jobject, jstring command) {
    const char* str = env->GetStringUTFChars(command, nullptr);
    ssize_t result = stockfish_stdin_write(str);
    env->ReleaseStringUTFChars(command, str);
    return result >= 0 ? JNI_TRUE : JNI_FALSE;
}

}
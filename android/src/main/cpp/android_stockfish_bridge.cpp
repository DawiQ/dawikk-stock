// android/src/main/cpp/android_stockfish_bridge.cpp
// This is a copy of stockfish_bridge.cpp with Android-specific modifications

#include "stockfish_bridge.h"

// Now we include all necessary C++ headers in the implementation file
#include <stdio.h>
#include <unistd.h>
#include <string>
#include <vector>
#include <array>
#include <algorithm>
#include <iostream>
#include <cstring>

#ifdef __ANDROID__
#include <android/log.h>
#include "android_nnue_loader.h"
#define ANDROID_LOG_INFO 4
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, "StockfishBridge", __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, "StockfishBridge", __VA_ARGS__))
#endif

// Definitions for C++ implementation
namespace {
    constexpr int NUM_PIPES = 2;
    constexpr int PARENT_WRITE_PIPE = 0;
    constexpr int PARENT_READ_PIPE = 1;
    constexpr int READ_FD = 0;
    constexpr int WRITE_FD = 1;

    constexpr size_t BUFFER_SIZE = 4096;

    const char* QUITOK = "quitok\n";
    std::array<std::array<int, 2>, NUM_PIPES> pipes;
    std::vector<char> buffer(BUFFER_SIZE);

    #define PARENT_READ_FD (pipes[PARENT_READ_PIPE][READ_FD])
    #define PARENT_WRITE_FD (pipes[PARENT_WRITE_PIPE][WRITE_FD])
}

// Forward declaration for helper functions
#ifdef NO_INCBIN
extern "C" {
    // These variables will be normally used by Stockfish
    const unsigned char        gEvalFile[]        = {0};
    const unsigned char* const gEvalFileDefaultBig = gEvalFile;
    const unsigned char* const gEvalFileDefaultSmall = gEvalFile;
    const size_t               gEvalFileDefaultBigSize = 0;
    const size_t               gEvalFileDefaultSmallSize = 0;
}
#endif

// Include Stockfish headers
#include "../stockfish/bitboard.h"
#include "../stockfish/misc.h"
#include "../stockfish/position.h"
#include "../stockfish/types.h"
#include "../stockfish/uci.h"
#include "../stockfish/tune.h"

// Implementation of helper function to find NNUE files
std::string find_nnue_file(const std::string& filename) {
#ifdef __ANDROID__
    // Use Android-specific implementation
    return find_nnue_file_android(filename);
#else
    // Use the original implementation for other platforms
    // First try to find the file in the current working directory
    FILE* f = fopen(filename.c_str(), "rb");
    if (f) {
        fclose(f);
        return filename;
    }
    
    // If not found, return the original name
    return filename;
#endif
}

// Add workaround for NNUE
#ifdef NO_INCBIN
namespace Stockfish {
namespace Eval {
namespace NNUE {
// These functions will be used by Stockfish to load NNUE files
std::string get_big_nnue_path() {
    return find_nnue_file("nn-1111cefa1111.nnue");
}

std::string get_small_nnue_path() {
    return find_nnue_file("nn-37f18f62d772.nnue");
}
}
}
}
#endif

// Implementation of C API
extern "C" {

int stockfish_init(void) {
#ifdef __ANDROID__
    LOGI("Stockfish bridge initializing");
#endif
    // Create communication pipes
    pipe(pipes[PARENT_READ_PIPE].data());
    pipe(pipes[PARENT_WRITE_PIPE].data());
    return 0;
}

int stockfish_main(void) {
#ifdef __ANDROID__
    LOGI("Stockfish main starting");
#endif
    // Redirect stdin and stdout through our pipes
    dup2(pipes[PARENT_WRITE_PIPE][READ_FD], STDIN_FILENO);
    dup2(pipes[PARENT_READ_PIPE][WRITE_FD], STDOUT_FILENO);

    // Initialize Stockfish components
    Stockfish::Bitboards::init();
    Stockfish::Position::init();

    // Start the UCI engine
    int argc = 1;
    char* argv[] = {const_cast<char*>("")};
    Stockfish::UCIEngine uci(argc, argv);
    Stockfish::Tune::init(uci.engine_options());

    // This will block until the engine receives the "quit" command
    uci.loop();

    std::cout << QUITOK << std::flush;
    return 0;
}

const char* stockfish_stdout_read(void) {
    static std::string output;
    output.clear();

    ssize_t bytesRead;
    while ((bytesRead = read(PARENT_READ_FD, buffer.data(), BUFFER_SIZE)) > 0) {
        output.append(buffer.data(), bytesRead);
        if (output.back() == '\n' || output.find(QUITOK) != std::string::npos) {
            break;
        }
    }

    if (bytesRead < 0) {
        // Handle error
#ifdef __ANDROID__
        LOGE("Error reading from stdout: %s", strerror(errno));
#endif
        return nullptr;
    }

    return output.c_str();
}

int stockfish_stdin_write(const char* data) {
    ssize_t bytesWritten = write(PARENT_WRITE_FD, data, strlen(data));
    // Ensure proper line ending
    if (bytesWritten > 0 && data[strlen(data) - 1] != '\n') {
        write(PARENT_WRITE_FD, "\n", 1);
    }
    return bytesWritten >= 0 ? 1 : 0;
}

} // extern "C"
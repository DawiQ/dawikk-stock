// stockfish_bridge.cpp
// Bridge implementation for Stockfish 19 (NNUE, network loaded from file)

#include "stockfish_bridge.h"

#include <stdio.h>
#include <unistd.h>
#include <string>
#include <vector>
#include <array>
#include <iostream>
#include <memory>
#include <cstring>
#include <fcntl.h>
#include <poll.h>

#ifdef __ANDROID__
#include <android/log.h>
// Debug logging - disabled by default for production
#ifdef STOCKFISH_DEBUG
#define BRIDGE_LOG(...) do { __android_log_print(ANDROID_LOG_INFO, "StockfishNative", __VA_ARGS__); } while(0)
#else
#define BRIDGE_LOG(...) do {} while(0)
#endif
#else
#define BRIDGE_LOG(...) do { fprintf(stderr, __VA_ARGS__); fflush(stderr); } while(0)
#endif

// Stockfish 19 headers. Stockfish 19 moved the attack-table initialisation out
// of bitboard.h into attacks.h, and Bitboards::init() no longer exists.
#include "attacks.h"
#include "position.h"
#include "tune.h"
#include "uci.h"
#include "misc.h"

namespace {
    constexpr int NUM_PIPES = 2;
    constexpr int PARENT_WRITE_PIPE = 0;
    constexpr int PARENT_READ_PIPE = 1;
    constexpr int READ_FD = 0;
    constexpr int WRITE_FD = 1;

    constexpr size_t BUFFER_SIZE = 8192;

    const char* QUITOK = "quitok\n";
    std::array<std::array<int, 2>, NUM_PIPES> pipes;
    std::vector<char> buffer(BUFFER_SIZE);

    #define PARENT_READ_FD (pipes[PARENT_READ_PIPE][READ_FD])
    #define PARENT_WRITE_FD (pipes[PARENT_WRITE_PIPE][WRITE_FD])

    // Output accumulator for handling partial reads
    std::string outputBuffer;

    // Whether `pipes` holds descriptors from a previous stockfish_init(). A
    // restart used to overwrite them without closing, four fds per restart.
    bool pipesOpen = false;
}

// Implementation of C API
extern "C" {

int stockfish_init(void) {
    BRIDGE_LOG("stockfish_init() starting");

    if (pipesOpen) {
        for (auto& p : pipes) {
            for (int fd : p) {
                if (fd >= 0) close(fd);
            }
        }
        pipesOpen = false;
    }

    // Create communication pipes
    if (pipe(pipes[PARENT_READ_PIPE].data()) < 0) {
        BRIDGE_LOG("Failed to create read pipe");
        return -1;
    }
    if (pipe(pipes[PARENT_WRITE_PIPE].data()) < 0) {
        BRIDGE_LOG("Failed to create write pipe");
        close(pipes[PARENT_READ_PIPE][READ_FD]);
        close(pipes[PARENT_READ_PIPE][WRITE_FD]);
        return -1;
    }
    pipesOpen = true;

    BRIDGE_LOG("Pipes created: read_fd=%d, write_fd=%d", PARENT_READ_FD, PARENT_WRITE_FD);

    // Set read end to non-blocking
    int flags = fcntl(PARENT_READ_FD, F_GETFL, 0);
    fcntl(PARENT_READ_FD, F_SETFL, flags | O_NONBLOCK);

    outputBuffer.clear();
    BRIDGE_LOG("stockfish_init() completed");
    return 0;
}

int stockfish_main(void) {
    using namespace Stockfish;
    BRIDGE_LOG("stockfish_main() starting");

    // Redirect stdin to read from our pipe (this is needed for input)
    dup2(pipes[PARENT_WRITE_PIPE][READ_FD], STDIN_FILENO);
    BRIDGE_LOG("stdin redirected");

    // Redirect stdout through our pipe (this is needed for output)
    dup2(pipes[PARENT_READ_PIPE][WRITE_FD], STDOUT_FILENO);
    BRIDGE_LOG("stdout redirected");

    // Stockfish 19 startup sequence (mirrors src/main.cpp).
    // The NNUE network is NOT embedded in the binary (built with NNUE_EMBEDDING_OFF);
    // the native layer points EvalFile at the downloaded .nnue file by sending a
    // "setoption" command immediately after the engine starts. Stockfish 19 retired
    // the second (small) network, so there is only one file and one option now.
    BRIDGE_LOG("Initializing attack tables...");
    Attacks::init();
    BRIDGE_LOG("Initializing Position...");
    Position::init();

    // argv[0] is only used to derive a binary directory we don't rely on,
    // since the network path is provided as an absolute path at runtime.
    // CommandLine keeps the char** as-is (it does not copy on POSIX), and
    // UCIEngine stores it, so argv has to outlive `uci` — hence the named local.
    char  arg0[] = "stockfish";
    char* argv[] = {arg0, nullptr};

    BRIDGE_LOG("Creating UCI engine...");
    auto uci = std::make_unique<UCIEngine>(CommandLine(1, argv));

    Tune::init(uci->engine_options());

    std::cout << engine_info() << std::endl;

    BRIDGE_LOG("Starting UCI loop...");
    // Start the UCI loop - this will block until "quit" command
    uci->loop();

    BRIDGE_LOG("UCI loop ended, cleaning up...");
    std::cout << QUITOK << std::flush;
    BRIDGE_LOG("stockfish_main() finished");
    return 0;
}

const char* stockfish_stdout_read(void) {
    static std::string result;
    result.clear();

    // Use poll to check for available data with timeout
    struct pollfd pfd;
    pfd.fd = PARENT_READ_FD;
    pfd.events = POLLIN;

    // Poll with 10ms timeout
    int ret = poll(&pfd, 1, 10);

    if (ret > 0 && (pfd.revents & POLLIN)) {
        // Data available - read it
        ssize_t bytesRead;
        while ((bytesRead = read(PARENT_READ_FD, buffer.data(), BUFFER_SIZE - 1)) > 0) {
            buffer[bytesRead] = '\0';
            outputBuffer.append(buffer.data(), bytesRead);
        }
    }

    // Check if we have complete lines in the buffer
    size_t newlinePos = outputBuffer.find('\n');
    if (newlinePos != std::string::npos) {
        // Extract all complete lines
        result = outputBuffer.substr(0, newlinePos + 1);
        outputBuffer.erase(0, newlinePos + 1);

        // Return additional complete lines if available
        while ((newlinePos = outputBuffer.find('\n')) != std::string::npos) {
            result += outputBuffer.substr(0, newlinePos + 1);
            outputBuffer.erase(0, newlinePos + 1);
        }

        return result.c_str();
    }

    // Check for quitok
    if (outputBuffer.find(QUITOK) != std::string::npos) {
        result = outputBuffer;
        outputBuffer.clear();
        return result.c_str();
    }

    return nullptr;
}

int stockfish_stdin_write(const char* data) {
    if (data == nullptr) {
        return 0;
    }

    size_t len = strlen(data);
    ssize_t bytesWritten = write(PARENT_WRITE_FD, data, len);

    // Ensure proper line ending
    if (bytesWritten > 0 && len > 0 && data[len - 1] != '\n') {
        write(PARENT_WRITE_FD, "\n", 1);
    }

    return bytesWritten >= 0 ? 1 : 0;
}

} // extern "C"

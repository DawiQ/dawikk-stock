#include "stockfish_bridge.h"

// Teraz dołączamy wszystkie nagłówki C++ w pliku implementacyjnym
#include <stdio.h>
#include <unistd.h>
#include <string>
#include <vector>
#include <array>
#include <algorithm>
#include <iostream>
#include <cstring>
#include <cstdlib>
#include <limits.h>

#ifdef __APPLE__
#include <CoreFoundation/CoreFoundation.h>
#endif

// Definicje dla implementacji C++
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

// Forward declaration dla funkcji pomocniczych
#ifdef NO_INCBIN
extern "C" {
    // Te zmienne będą normalnie wykorzystywane przez Stockfish
    const unsigned char        gEvalFile[]        = {0};
    const unsigned char* const gEvalFileDefaultBig = gEvalFile;
    const unsigned char* const gEvalFileDefaultSmall = gEvalFile;
    const size_t               gEvalFileDefaultBigSize = 0;
    const size_t               gEvalFileDefaultSmallSize = 0;
}
#endif

// Dołączenie nagłówków Stockfisha - używamy nazw bez ścieżki bo include_directories są ustawione
#include "bitboard.h"
#include "misc.h"
#include "position.h"
#include "types.h"
#include "uci.h"
#include "tune.h"

// Implementacja funkcji pomocniczej do znajdowania plików NNUE
std::string find_nnue_file(const std::string& filename) {
    // Najpierw spróbuj znaleźć plik w obecnym katalogu roboczym
    FILE* f = fopen(filename.c_str(), "rb");
    if (f) {
        fclose(f);
        return filename;
    }
    
    #ifdef __APPLE__
    // Spróbuj znaleźć w iOS/macOS bundle
    CFBundleRef mainBundle = CFBundleGetMainBundle();
    if (mainBundle) {
        CFStringRef cfFilename = CFStringCreateWithCString(NULL, filename.c_str(), kCFStringEncodingUTF8);
        CFURLRef resourceURL = CFBundleCopyResourceURL(mainBundle, cfFilename, NULL, NULL);
        
        if (resourceURL) {
            char path[PATH_MAX];
            if (CFURLGetFileSystemRepresentation(resourceURL, true, (UInt8*)path, PATH_MAX)) {
                CFRelease(resourceURL);
                CFRelease(cfFilename);
                return std::string(path);
            }
            CFRelease(resourceURL);
        }
        CFRelease(cfFilename);
    }
    #endif
    
    // Spróbuj znaleźć w katalogu ustawionym przez środowisko (Android)
    const char* filesDir = std::getenv("STOCKFISH_FILES_DIR");
    if (filesDir) {
        std::string path = std::string(filesDir) + "/" + filename;
        f = fopen(path.c_str(), "rb");
        if (f) {
            fclose(f);
            return path;
        }
    }
    
    // Jeśli nie znaleziono, zwróć oryginalną nazwę
    return filename;
}

// Dodajemy obejście problemu z NNUE
#ifdef NO_INCBIN
namespace Stockfish {
namespace Eval {
namespace NNUE {
// Te funkcje będą używane przez Stockfisha do ładowania plików NNUE
std::string get_big_nnue_path() {
    return find_nnue_file("nn-1c0000000000.nnue");
}

std::string get_small_nnue_path() {
    return find_nnue_file("nn-37f18f62d772.nnue");
}
}
}
}
#endif

// Implementacja API C
extern "C" {

int stockfish_init(void) {
    // Create communication pipes
    pipe(pipes[PARENT_READ_PIPE].data());
    pipe(pipes[PARENT_WRITE_PIPE].data());
    return 0;
}

int stockfish_main(void) {
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
#ifndef ANDROID_NNUE_LOADER_H
#define ANDROID_NNUE_LOADER_H

#include <string>
#include <cstdlib>
#include <cstdio>

// Android-specific implementation for finding NNUE files
inline std::string find_nnue_file_android(const std::string& filename) {
    // First try to find the file in the current working directory
    FILE* f = fopen(filename.c_str(), "rb");
    if (f) {
        fclose(f);
        return filename;
    }

    // Try to find in the directory set by the environment (set by Java/Kotlin code)
    const char* filesDir = std::getenv("STOCKFISH_FILES_DIR");
    if (filesDir) {
        std::string path = std::string(filesDir) + "/" + filename;
        f = fopen(path.c_str(), "rb");
        if (f) {
            fclose(f);
            return path;
        }
    }

    // Try common Android app directories
    const char* internalStorage = std::getenv("INTERNAL_STORAGE");
    if (internalStorage) {
        std::string path = std::string(internalStorage) + "/" + filename;
        f = fopen(path.c_str(), "rb");
        if (f) {
            fclose(f);
            return path;
        }
    }

    // If not found, return the original name - Stockfish will handle the error
    return filename;
}

#endif // ANDROID_NNUE_LOADER_H

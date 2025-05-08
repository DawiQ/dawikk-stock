#ifndef NNUE_LOADER_H
#define NNUE_LOADER_H

#include <string>
#include <CoreFoundation/CoreFoundation.h>

// Funkcja pomocnicza do znajdowania plików NNUE w pakiecie iOS
std::string find_nnue_file(const std::string& filename) {
    // Najpierw spróbuj znaleźć plik w obecnym katalogu roboczym
    FILE* f = fopen(filename.c_str(), "rb");
    if (f) {
        fclose(f);
        return filename;
    }
    
    // Jeśli nie znaleziono, spróbuj znaleźć w pakiecie aplikacji
    CFBundleRef mainBundle = CFBundleGetMainBundle();
    CFStringRef cfFilename = CFStringCreateWithCString(NULL, filename.c_str(), kCFStringEncodingUTF8);
    
    // Najpierw spróbuj znaleźć jako zasób
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
    
    // Nie znaleziono pliku
    return filename; // Zwróć oryginalną nazwę, Stockfish obsłuży błąd
}

#endif // NNUE_LOADER_H
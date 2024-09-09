@echo off
"C:\\Users\\Dawid\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1\\bin\\cmake.exe" ^
  "-HD:\\_Mobile\\ChessEngine\\node_modules\\react-native-stockfish-android\\android" ^
  "-DCMAKE_SYSTEM_NAME=Android" ^
  "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON" ^
  "-DCMAKE_SYSTEM_VERSION=23" ^
  "-DANDROID_PLATFORM=android-23" ^
  "-DANDROID_ABI=x86" ^
  "-DCMAKE_ANDROID_ARCH_ABI=x86" ^
  "-DANDROID_NDK=C:\\Users\\Dawid\\AppData\\Local\\Android\\Sdk\\ndk\\26.1.10909125" ^
  "-DCMAKE_ANDROID_NDK=C:\\Users\\Dawid\\AppData\\Local\\Android\\Sdk\\ndk\\26.1.10909125" ^
  "-DCMAKE_TOOLCHAIN_FILE=C:\\Users\\Dawid\\AppData\\Local\\Android\\Sdk\\ndk\\26.1.10909125\\build\\cmake\\android.toolchain.cmake" ^
  "-DCMAKE_MAKE_PROGRAM=C:\\Users\\Dawid\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1\\bin\\ninja.exe" ^
  "-DCMAKE_CXX_FLAGS=-O2 -frtti -fexceptions -Wall -fstack-protector-all" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=D:\\_Mobile\\ChessEngine\\node_modules\\react-native-stockfish-android\\android\\build\\intermediates\\cxx\\Debug\\304g3u4q\\obj\\x86" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=D:\\_Mobile\\ChessEngine\\node_modules\\react-native-stockfish-android\\android\\build\\intermediates\\cxx\\Debug\\304g3u4q\\obj\\x86" ^
  "-DCMAKE_BUILD_TYPE=Debug" ^
  "-BD:\\_Mobile\\ChessEngine\\node_modules\\react-native-stockfish-android\\android\\.cxx\\Debug\\304g3u4q\\x86" ^
  -GNinja

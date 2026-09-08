cmake_minimum_required(VERSION 3.22.1)
project(stockfish)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SF_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/stockfish)
set(BRIDGE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/bridge)

# Stockfish 19 sources (exclude main.cpp, which defines its own main()).
#
# The globs deliberately do NOT reach ${SF_DIR}/universal: that directory holds
# the entry points and section-rewriting scripts for upstream's multi-arch
# "universal binary", which dispatches on runtime CPU detection. We ship one .so
# per ABI and let the packager pick, so those sources are not vendored at all.
file(GLOB SF_SOURCES
  "${SF_DIR}/*.cpp"
  "${SF_DIR}/nnue/*.cpp"
  "${SF_DIR}/nnue/features/*.cpp"
  "${SF_DIR}/syzygy/*.cpp"
)
list(REMOVE_ITEM SF_SOURCES "${SF_DIR}/main.cpp")

add_library(stockfish SHARED
  ${SF_SOURCES}
  ${BRIDGE_DIR}/stockfish_bridge.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/stockfish_jni.cpp
)

target_include_directories(stockfish PRIVATE
  ${SF_DIR}
  ${BRIDGE_DIR}
)

# Support Android 16 KB memory page sizes (required by Google Play for apps
# targeting Android 15+). Align the shared library's LOAD segments to 16 KB so
# libstockfish.so loads on devices that use 16 KB pages. NDK r28+ does this by
# default; the explicit linker flag keeps it correct on older NDKs too.
target_link_options(stockfish PRIVATE "-Wl,-z,max-page-size=16384")

# Common defines. NNUE_EMBEDDING_OFF keeps the .so small: the network is loaded
# from the downloaded .nnue file via the EvalFile UCI option instead of being
# embedded with incbin.
#
# STOCKFISH_EMBEDDED switches off the std::exit(1) that Stockfish 19 added to
# UCIEngine::terminate_on_critical_error. Upstream runs as its own process, so
# ending it on a malformed FEN or an illegal move costs nothing; here the engine
# shares the app's process and that exit would take the app down with it. See
# the LOCAL PATCH comments in cpp/stockfish/uci.{h,cpp}.
#
# USE_POPCNT is safe on every ABI here: outside MSVC it resolves to
# __builtin_popcountll, which Clang implements on 32-bit ARM too. Upstream's own
# armv7-neon target sets popcnt=yes for the same reason.
target_compile_definitions(stockfish PRIVATE
  NDEBUG
  USE_PTHREADS
  USE_POPCNT
  NNUE_EMBEDDING_OFF
  STOCKFISH_EMBEDDED
)

# Stockfish 19 builds its attack tables at compile time and blows past Clang's
# default 1,048,576-step constexpr budget doing it. Upstream's own Makefile
# passes exactly this for every clang target; without it the NDK build fails in
# attacks.cpp with "constexpr evaluation hit maximum step limit".
target_compile_options(stockfish PRIVATE -fconstexpr-steps=500000000)

# IS_64BIT is 64-bit only. It selects the __int128 path in mul_hi64() and sets
# Stockfish's Is64Bit constant; on a 32-bit ABI it would be a lie, and mul_hi64
# already carries a correct 32-bit fallback.
if(${ANDROID_ABI} STREQUAL "arm64-v8a" OR ${ANDROID_ABI} STREQUAL "x86_64")
  target_compile_definitions(stockfish PRIVATE IS_64BIT)
endif()

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
  # ARMv8 NEON SIMD. Dot-product is left off for broad device compatibility.
  target_compile_definitions(stockfish PRIVATE USE_NEON=8 USE_PREFETCH)

elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
  # Mirrors upstream's own `armv7-neon` target, which sets arm_version=7,
  # bits=32, prefetch=yes, popcnt=yes and passes -DUSE_NEON=$(arm_version).
  #
  # The level matters: Stockfish keeps every ARMv8-only intrinsic behind
  # `USE_NEON >= 8` (vaddvq, vmull_high, vpaddq) or USE_NEON_DOTPROD, and ships a
  # 32-bit polyfill for vmovl_high_s8 under `#ifndef __aarch64__`. Declaring 8
  # here would not build.
  target_compile_definitions(stockfish PRIVATE USE_NEON=7 USE_PREFETCH)

  # 32-bit ARM has no native 64-bit atomics, and Stockfish uses them for the
  # transposition table. Upstream links libatomic on armv7 for exactly this.
  target_link_libraries(stockfish atomic)
endif()
# x86_64 (emulator) builds with the scalar NNUE path; no SIMD defines required.

find_library(log-lib log)
target_link_libraries(stockfish ${log-lib})

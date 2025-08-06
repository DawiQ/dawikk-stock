# android/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.8)
project(dawikk-stockfish)

# Set the path to Stockfish source files
set(STOCKFISH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/stockfish)
set(BRIDGE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/bridge)

# Compiler flags for Stockfish
set (CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_CXX_STANDARD 17)

# Define specific flags for ARM architectures

set (NNUE_SMALL_NAME nn-37f18f62d772.nnue)
set (NNUE_BIG_NAME nn-1c0000000000.nnue)
file(COPY ../cpp/${NNUE_SMALL_NAME} DESTINATION ${CMAKE_BINARY_DIR})
file(COPY ../cpp/${NNUE_BIG_NAME} DESTINATION ${CMAKE_BINARY_DIR})


# Include directories
include_directories(
    ${STOCKFISH_DIR}
    ${BRIDGE_DIR}
)

# Find all Stockfish source files
file(GLOB STOCKFISH_SOURCES 
    ${STOCKFISH_DIR}/*.cpp
    ${STOCKFISH_DIR}/nnue/*.cpp
    ${STOCKFISH_DIR}/nnue/features/*.cpp
    ${STOCKFISH_DIR}/syzygy/*.cpp
)

# Exclude main.cpp from Stockfish sources if it exists
list(FILTER STOCKFISH_SOURCES EXCLUDE REGEX "main\\.cpp$")

# Create a shared library
add_library(
    stockfish-lib
    SHARED
    
    # JNI implementation
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/stockfish_jni.cpp
    
    # Bridge implementation
    ${BRIDGE_DIR}/stockfish_bridge.cpp
    
    # Stockfish sources
    ${STOCKFISH_SOURCES}
)

# Link against required libraries
target_link_libraries(
    stockfish-lib
    android
    log
)
# android/CMakeLists.txt
cmake_minimum_required(VERSION 3.22)
project(dawikk-stockfish)

# Set the path to Stockfish source files
set(STOCKFISH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/stockfish)
set(BRIDGE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/bridge)

# Compiler flags for Stockfish
set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_CXX_STANDARD 17)

# Add 16KB page size support flags
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Download NNUE files if they don't exist
set(NNUE_SMALL_NAME nn-37f18f62d772.nnue)
set(NNUE_BIG_NAME nn-1c0000000000.nnue)
set(NNUE_SMALL_URL "https://tests.stockfishchess.org/api/nn/${NNUE_SMALL_NAME}")
set(NNUE_BIG_URL "https://tests.stockfishchess.org/api/nn/${NNUE_BIG_NAME}")

# Download NNUE files
if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/${NNUE_SMALL_NAME})
    message(STATUS "Downloading ${NNUE_SMALL_NAME}...")
    file(DOWNLOAD ${NNUE_SMALL_URL} ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/${NNUE_SMALL_NAME}
         SHOW_PROGRESS
         STATUS download_status)
    list(GET download_status 0 status_code)
    if(NOT status_code EQUAL 0)
        message(FATAL_ERROR "Failed to download ${NNUE_SMALL_NAME}")
    endif()
endif()

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/${NNUE_BIG_NAME})
    message(STATUS "Downloading ${NNUE_BIG_NAME}...")
    file(DOWNLOAD ${NNUE_BIG_URL} ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/${NNUE_BIG_NAME}
         SHOW_PROGRESS
         STATUS download_status)
    list(GET download_status 0 status_code)
    if(NOT status_code EQUAL 0)
        message(FATAL_ERROR "Failed to download ${NNUE_BIG_NAME}")
    endif()
endif()

# Copy NNUE files to build directory
file(COPY ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/${NNUE_SMALL_NAME} DESTINATION ${CMAKE_BINARY_DIR})
file(COPY ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/${NNUE_BIG_NAME} DESTINATION ${CMAKE_BINARY_DIR})

# Include directories
include_directories(
    ${STOCKFISH_DIR}
    ${BRIDGE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp
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

# Add compile definitions
target_compile_definitions(stockfish-lib PRIVATE
    USE_PTHREADS
    NDEBUG
    IS_64BIT
    NO_INCBIN
)

# Set 16KB page alignment for the library
target_link_options(stockfish-lib PRIVATE 
    "-Wl,-z,max-page-size=16384"
)

# Link against required libraries
target_link_libraries(
    stockfish-lib
    android
    log
)
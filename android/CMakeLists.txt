cmake_minimum_required(VERSION 3.8)
project(stockfish)

set (CMAKE_VERBOSE_MAKEFILE ON)
set (CMAKE_CXX_STANDARD 17)

# Include stockfish NNUE
set (NNUE_NAME nn-1111cefa1111.nnue)
file(DOWNLOAD https://tests.stockfishchess.org/api/nn/${NNUE_NAME} ../cpp/${NNUE_NAME} SHOW_PROGRESS)
file(COPY ../cpp/${NNUE_NAME} DESTINATION ${CMAKE_BINARY_DIR})

set (NNUE_NAME_SMALL nn-37f18f62d772.nnue)
file(DOWNLOAD https://tests.stockfishchess.org/api/nn/${NNUE_NAME_SMALL} ../cpp/${NNUE_NAME_SMALL} SHOW_PROGRESS)
file(COPY ../cpp/${NNUE_NAME_SMALL} DESTINATION ${CMAKE_BINARY_DIR})

file(GLOB_RECURSE stockfishCppPaths "../cpp/stockfish/src/*.cpp")
file(GLOB_RECURSE bridgeCppPaths "../cpp/bridge/*.cpp")

add_library(stockfish
            SHARED
            ${stockfishCppPaths}
            ${bridgeCppPaths}
)

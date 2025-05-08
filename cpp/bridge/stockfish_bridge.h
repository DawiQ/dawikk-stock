#ifndef STOCKFISH_BRIDGE_H
#define STOCKFISH_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

// Public API functions
int stockfish_init(void);
int stockfish_main(void);
const char* stockfish_stdout_read(void);
int stockfish_stdin_write(const char* data);

#ifdef __cplusplus
}
#endif

#endif // STOCKFISH_BRIDGE_H
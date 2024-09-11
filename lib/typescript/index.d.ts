/**
Starts the main loop, and runs forever, unless the command 'quit' is sent !
So don't forget to send 'quit' command when you're about to exit !
Also, you'd better launch this command in a new "thread".
*/
export declare function mainLoop(): Promise<void>;
/**
Disposes Stockfish engine.
*/
export declare function shutdownStockfish(): Promise<void>;
/**
 * Sends ac command to the stockfish process input. Sending several commands without reading them
 * will simply have them queued one after the other.
 * @param command - String - command to be sent to the stockfish process input.
 */
export declare function sendCommand(command: string): Promise<void>;
//# sourceMappingURL=index.d.ts.map
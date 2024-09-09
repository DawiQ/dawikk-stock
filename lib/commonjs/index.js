"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.mainLoop = mainLoop;
exports.sendCommand = sendCommand;
exports.shutdownStockfish = shutdownStockfish;
var _reactNative = require("react-native");
const LINKING_ERROR = `The package 'react-native-stockfish-android' doesn't seem to be linked. Make sure: \n\n` + _reactNative.Platform.select({
  ios: "- You have run 'pod install'\n",
  default: ''
}) + '- You rebuilt the app after installing the package\n' + '- You are not using Expo Go\n';
const StockfishChessEngine = _reactNative.NativeModules.StockfishChessEngine ? _reactNative.NativeModules.StockfishChessEngine : new Proxy({}, {
  get() {
    throw new Error(LINKING_ERROR);
  }
});

/**
Starts the main loop, and runs forever, unless the command 'quit' is sent !
So don't forget to send 'quit' command when you're about to exit !
Also, you'd better launch this command in a new "thread".
*/
async function mainLoop() {
  await StockfishChessEngine.mainLoop();
}

/**
Disposes Stockfish engine.
*/
async function shutdownStockfish() {
  await StockfishChessEngine.shutdownStockfish();
}

/**
 * Sends ac command to the stockfish process input. Sending several commands without reading them
 * will simply have them queued one after the other.
 * @param command - String - command to be sent to the stockfish process input.
 */
async function sendCommand(command) {
  await StockfishChessEngine.sendCommand(command);
}
//# sourceMappingURL=index.js.map
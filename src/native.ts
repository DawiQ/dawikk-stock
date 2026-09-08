// src/native.ts
// Single resolution point for the native module + event emitter, so the engine
// wrapper and the NNUE downloader share one instance.

import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const LINKING_ERROR =
  `The package 'dawikk-stockfish' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

export const isNativeModuleAvailable = !!NativeModules.RNStockfishModule;

export const StockfishModule = NativeModules.RNStockfishModule
  ? NativeModules.RNStockfishModule
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

export const StockfishEventEmitter = new NativeEventEmitter(StockfishModule);

// RNStockfishModule.h
// Native module for Stockfish chess engine

#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

@interface RNStockfishModule : RCTEventEmitter <RCTBridgeModule>

@property (nonatomic, assign) BOOL isEngineRunning;
@property (nonatomic, assign) BOOL shouldStopPolling;

@end

// RNStockfishModule.m
#import "RNStockfishModule.h"
#import <React/RCTLog.h>
#import <React/RCTUtils.h>
#import <pthread.h>

// Import the C++ bridge header
#include "stockfish_bridge.h"

@implementation RNStockfishModule {
    pthread_t engineThread;
    pthread_t listenerThread;
    bool engineRunning;
    bool listenerRunning;
}

RCT_EXPORT_MODULE()

+ (BOOL)requiresMainQueueSetup
{
    return NO;
}

- (NSArray<NSString *> *)supportedEvents
{
    return @[@"stockfish-output", @"stockfish-analyzed-output"];
}

- (instancetype)init
{
    self = [super init];
    if (self) {
        engineRunning = false;
        listenerRunning = false;
    }
    return self;
}

void *engineThreadFunction(void *arg)
{
    // Call the C++ function to start the Stockfish engine
    stockfish_main();
    return NULL;
}

void *listenerThreadFunction(void *arg)
{
    RNStockfishModule *module = (__bridge RNStockfishModule *)arg;
    char buffer[4096];
    
    while ([module isListenerRunning]) {
        const char *output = stockfish_stdout_read();
        if (output != NULL) {
            NSString *outputString = [NSString stringWithUTF8String:output];
            if (outputString.length > 0) {
                [module processEngineOutput:outputString];
            }
        }
        // Add a small delay to avoid high CPU usage
        [NSThread sleepForTimeInterval:0.01];
    }
    
    return NULL;
}

- (void)processEngineOutput:(NSString *)output
{
    if (output.length == 0) return;
    
    // Process the output lines
    NSArray *lines = [output componentsSeparatedByString:@"\n"];
    for (NSString *line in lines) {
        if (line.length == 0) continue;
        
        // Process analysis output (info depth, bestmove, etc.)
        if ([line hasPrefix:@"info"] && [line containsString:@"score"] && [line containsString:@"pv"]) {
            // Parse and send analyzed output
            [self sendAnalyzedOutput:line];
        } else if ([line hasPrefix:@"bestmove"]) {
            // Parse and send bestmove output as structured data
            [self sendBestMoveOutput:line];
        } else {
            // Send regular output
            [self sendEventWithName:@"stockfish-output" body:line];
        }
    }
}

- (void)sendBestMoveOutput:(NSString *)line
{
    // Format: "bestmove e7e6 ponder c2c3"
    NSMutableDictionary *result = [NSMutableDictionary dictionary];
    result[@"type"] = @"bestmove";
    
    // Extract the best move
    NSArray *parts = [line componentsSeparatedByString:@" "];
    if (parts.count >= 2) {
        result[@"move"] = parts[1];
        
        // Extract ponder move if available
        if (parts.count >= 4 && [parts[2] isEqualToString:@"ponder"]) {
            result[@"ponder"] = parts[3];
        }
    }
    
    [self sendEventWithName:@"stockfish-analyzed-output" body:result];
}

- (void)sendAnalyzedOutput:(NSString *)line
{
    // Parse the UCI output line (simplified for now)
    NSMutableDictionary *result = [NSMutableDictionary dictionary];
    result[@"type"] = @"info";
    
    // Extract depth
    NSRegularExpression *depthRegex = [NSRegularExpression regularExpressionWithPattern:@"depth (\\d+)" options:0 error:nil];
    NSTextCheckingResult *depthMatch = [depthRegex firstMatchInString:line options:0 range:NSMakeRange(0, line.length)];
    if (depthMatch) {
        NSString *depthStr = [line substringWithRange:[depthMatch rangeAtIndex:1]];
        result[@"depth"] = @([depthStr intValue]);
    }
    
    // Extract score
    NSRegularExpression *scoreRegex = [NSRegularExpression regularExpressionWithPattern:@"score (cp|mate) (-?\\d+)" options:0 error:nil];
    NSTextCheckingResult *scoreMatch = [scoreRegex firstMatchInString:line options:0 range:NSMakeRange(0, line.length)];
    if (scoreMatch) {
        NSString *scoreType = [line substringWithRange:[scoreMatch rangeAtIndex:1]];
        NSString *scoreValue = [line substringWithRange:[scoreMatch rangeAtIndex:2]];
        
        if ([scoreType isEqualToString:@"cp"]) {
            float score = [scoreValue floatValue] / 100.0;
            result[@"score"] = @(score);
        } else {
            // Handle mate score
            result[@"mate"] = @([scoreValue intValue]);
        }
    }
    
    // Extract best move (first move in pv)
    NSRegularExpression *pvRegex = [NSRegularExpression regularExpressionWithPattern:@"pv ([a-h][1-8][a-h][1-8][qrbnk]?)" options:0 error:nil];
    NSTextCheckingResult *pvMatch = [pvRegex firstMatchInString:line options:0 range:NSMakeRange(0, line.length)];
    if (pvMatch) {
        NSString *bestMove = [line substringWithRange:[pvMatch rangeAtIndex:1]];
        result[@"bestMove"] = bestMove;
    }
    
    // Extract full pv line
    NSRange pvRange = [line rangeOfString:@"pv "];
    if (pvRange.location != NSNotFound) {
        NSString *pv = [line substringFromIndex:pvRange.location + pvRange.length];
        result[@"line"] = pv;
    }
    
    [self sendEventWithName:@"stockfish-analyzed-output" body:result];
}

- (bool)isListenerRunning
{
    return listenerRunning;
}

RCT_EXPORT_METHOD(initEngine:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
    if (engineRunning) {
        resolve(@(YES));
        return;
    }
    
    // Initialize the Stockfish engine
    stockfish_init();
    
    // Start the engine thread
    int status = pthread_create(&engineThread, NULL, engineThreadFunction, NULL);
    if (status != 0) {
        NSString *errorMsg = [NSString stringWithFormat:@"Failed to create engine thread: %d", status];
        reject(@"THREAD_ERROR", errorMsg, nil);
        return;
    }
    engineRunning = true;
    
    // Start the listener thread
    listenerRunning = true;
    status = pthread_create(&listenerThread, NULL, listenerThreadFunction, (__bridge void *)self);
    if (status != 0) {
        NSString *errorMsg = [NSString stringWithFormat:@"Failed to create listener thread: %d", status];
        reject(@"THREAD_ERROR", errorMsg, nil);
        return;
    }
    
    resolve(@(YES));
}

RCT_EXPORT_METHOD(sendCommand:(NSString *)command
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
    if (!engineRunning) {
        reject(@"ENGINE_NOT_RUNNING", @"Stockfish engine is not running", nil);
        return;
    }
    
    const char *cmd = [command UTF8String];
    bool success = stockfish_stdin_write(cmd);
    
    if (success) {
        resolve(@(YES));
    } else {
        reject(@"COMMAND_FAILED", @"Failed to send command to Stockfish", nil);
    }
}

RCT_EXPORT_METHOD(shutdownEngine:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
    if (!engineRunning) {
        resolve(@(YES));
        return;
    }
    
    // Send the quit command to Stockfish
    stockfish_stdin_write("quit\n");
    
    // Stop the listener thread
    listenerRunning = false;
    
    // Wait for threads to finish
    if (engineRunning) {
        pthread_join(engineThread, NULL);
        engineRunning = false;
    }
    
    if (listenerRunning) {
        pthread_join(listenerThread, NULL);
        listenerRunning = false;
    }
    
    resolve(@(YES));
}

- (void)invalidate
{
    [self shutdownEngine:^(id result) {} rejecter:^(NSString *code, NSString *message, NSError *error) {}];
    [super invalidate];
}

@end
// RNStockfishModule.mm
// Native module implementation for Stockfish chess engine

#import "RNStockfishModule.h"
#import "NnueDownloader.h"
#import <React/RCTLog.h>

// C++ bridge functions
extern "C" {
    int stockfish_init(void);
    int stockfish_main(void);
    const char* stockfish_stdout_read(void);
    int stockfish_stdin_write(const char* data);
}

@implementation RNStockfishModule {
    dispatch_queue_t _engineQueue;
    dispatch_queue_t _pollingQueue;
    dispatch_queue_t _nnueQueue;
    NSMutableString *_currentLine;
    BOOL _hasListeners;
    // The networks are downloaded after install rather than bundled into the
    // IPA — see NnueDownloader.
    NnueDownloader *_nnue;
    BOOL _nnueDownloading;
    // Raised for the whole of a start so a second initEngine queued behind
    // the first cannot start a second engine on the same stdin.
    BOOL _isStarting;
    // Bumped per engine start. The main-loop block compares its own copy
    // before clearing isEngineRunning, so a loop from BEFORE a restart cannot
    // switch off the engine that replaced it.
    NSUInteger _engineGeneration;
    // Signalled when the main loop returns; shutdown waits on it.
    dispatch_semaphore_t _engineDone;
}

RCT_EXPORT_MODULE();

- (instancetype)init {
    self = [super init];
    if (self) {
        _isEngineRunning = NO;
        _shouldStopPolling = NO;
        _hasListeners = NO;
        _currentLine = [NSMutableString new];
        _engineQueue = dispatch_queue_create("com.dawikk.stockfish.engine", DISPATCH_QUEUE_SERIAL);
        _pollingQueue = dispatch_queue_create("com.dawikk.stockfish.polling", DISPATCH_QUEUE_SERIAL);
        _nnueQueue = dispatch_queue_create("com.dawikk.stockfish.nnue", DISPATCH_QUEUE_SERIAL);
        _nnue = [NnueDownloader new];
        _nnueDownloading = NO;
        _isStarting = NO;
        _engineGeneration = 0;
        _engineDone = nil;
    }
    return self;
}

// The React instance is going away (reload, teardown). Without this the
// engine, its polling loop and the loaded networks outlive the JS that owned
// them, and the next instance starts a second engine beside them.
- (void)invalidate {
    if (_isEngineRunning) {
        [self stopEngineAndWait];
    }
    [super invalidate];
}

// Ask the engine to quit and wait for its loop to return. "quit" is only read
// when the UCI loop is back at its prompt, and loading a 100 MB network keeps
// it away for longer than the 200 ms this used to wait — after which a restart
// handed the old loop the new engine's stdin.
- (void)stopEngineAndWait {
    _shouldStopPolling = YES;
    stockfish_stdin_write("quit");
    dispatch_semaphore_t done = _engineDone;
    if (done) {
        dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3 * NSEC_PER_SEC)));
    }
    _isEngineRunning = NO;
}

+ (BOOL)requiresMainQueueSetup {
    return NO;
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"stockfish-output",
             @"stockfish-analyzed-output",
             @"stockfish-error",
             @"stockfish-nnue-progress",
             @"stockfish-nnue-ready"];
}

- (NSDictionary *)constantsToExport {
    return @{
        @"nnueDirectory": [_nnue directory],
        @"nnueName": [NnueDownloader netName],
        @"nnueApproxTotalBytes": @([NnueDownloader approxTotalBytes]),
        @"engineAvailable": @YES
    };
}

#pragma mark - NNUE networks

// Points the engine at the downloaded network. Returns NO (and emits a
// "stockfish-error" event) when it has not been fetched yet.
- (BOOL)configureNNUENetworks {
    if (![_nnue isReady]) {
        if (_hasListeners) {
            [self sendEventWithName:@"stockfish-error"
                               body:@{@"code": @"NNUE_MISSING",
                                      @"message": @"Stockfish neural network files have not been downloaded yet."}];
        }
        return NO;
    }

    NSString *netPath = [_nnue pathForName:[NnueDownloader netName]];

    // Point the engine at the downloaded network before any analysis runs, so
    // the engine never falls back to a missing embedded net (which would abort).
    // Stockfish 19 retired the second (small) network, so EvalFileSmall is gone
    // and there is one option to set.
    NSString *netCmd = [NSString stringWithFormat:@"setoption name EvalFile value %@", netPath];
    stockfish_stdin_write([netCmd UTF8String]);
    return YES;
}

RCT_EXPORT_METHOD(getNnueStatus:(RCTPromiseResolveBlock)resolve
                       rejecter:(RCTPromiseRejectBlock)reject) {
    resolve([_nnue status]);
}

/**
 * Downloads the networks that are missing. `options`:
 *   sources      - array of URL prefixes to try in order (defaults to the
 *                  official Stockfish sources)
 *   allowMetered - download over cellular / Low Data Mode (default false)
 * Progress is emitted as "stockfish-nnue-progress".
 */
RCT_EXPORT_METHOD(downloadNnueNetworks:(NSDictionary *)options
                              resolver:(RCTPromiseResolveBlock)resolve
                              rejecter:(RCTPromiseRejectBlock)reject) {
    if (_nnueDownloading) {
        reject(@"NNUE_DOWNLOAD_BUSY", @"A network download is already running", nil);
        return;
    }
    _nnueDownloading = YES;

    NSArray *sources = [options[@"sources"] isKindOfClass:[NSArray class]] ? options[@"sources"] : nil;
    BOOL allowMetered = [options[@"allowMetered"] boolValue];

    dispatch_async(_nnueQueue, ^{
        NSError *error = nil;
        BOOL ok = [self->_nnue downloadWithSources:sources
                                      allowMetered:allowMetered
                                          progress:^(NSString *name, NSInteger index, NSInteger count,
                                                     long long written, long long total) {
            if (!self->_hasListeners) return;
            [self sendEventWithName:@"stockfish-nnue-progress"
                               body:@{@"name": name,
                                      @"index": @(index),
                                      @"count": @(count),
                                      @"bytesWritten": @(written),
                                      @"bytesTotal": @(total),
                                      @"progress": @(total > 0 ? (double)written / (double)total : 0)}];
        }
                                             error:&error];

        self->_nnueDownloading = NO;

        dispatch_async(dispatch_get_main_queue(), ^{
            if (ok) {
                if (self->_hasListeners) {
                    [self sendEventWithName:@"stockfish-nnue-ready" body:[self->_nnue status]];
                }
                resolve([self->_nnue status]);
                return;
            }

            NSString *code = error.userInfo[@"code"] ?: @"NNUE_DOWNLOAD_FAILED";
            NSString *message = error.localizedDescription ?: @"Download failed";
            if (self->_hasListeners && ![code isEqualToString:@"NNUE_CANCELLED"]) {
                [self sendEventWithName:@"stockfish-error"
                                   body:@{@"code": code, @"message": message}];
            }
            reject(code, message, error);
        });
    });
}

RCT_EXPORT_METHOD(cancelNnueDownload:(RCTPromiseResolveBlock)resolve
                            rejecter:(RCTPromiseRejectBlock)reject) {
    [_nnue cancel];
    resolve(@YES);
}

RCT_EXPORT_METHOD(deleteNnueNetworks:(RCTPromiseResolveBlock)resolve
                            rejecter:(RCTPromiseRejectBlock)reject) {
    if (_isEngineRunning) {
        reject(@"ENGINE_RUNNING", @"Shut the engine down before deleting its networks", nil);
        return;
    }
    // Cancelling asks the transfer to stop; it does not wait. Deleting on the
    // spot let a net that finished in that window rename its .part back over
    // the file the user had just asked to be gone. _nnueQueue is serial and is
    // where the download runs, so getting in line behind it IS the wait.
    [_nnue cancel];
    dispatch_async(_nnueQueue, ^{
        BOOL deleted = [self->_nnue deleteNetworks];
        dispatch_async(dispatch_get_main_queue(), ^{
            resolve(@(deleted));
        });
    });
}

/// Full sha256 re-check of what is on disk. Slow (~94 MB read).
RCT_EXPORT_METHOD(verifyNnueNetworks:(RCTPromiseResolveBlock)resolve
                            rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(_nnueQueue, ^{
        BOOL ok = YES;
        for (NSString *name in [NnueDownloader nets]) {
            if (![self->_nnue verifyName:name]) ok = NO;
        }
        resolve(@(ok));
    });
}

- (void)startObserving {
    _hasListeners = YES;
}

- (void)stopObserving {
    _hasListeners = NO;
}

#pragma mark - Engine Lifecycle

RCT_EXPORT_METHOD(initEngine:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    if (_isEngineRunning) {
        resolve(@YES);
        return;
    }

    dispatch_async(_engineQueue, ^{
        RCTLogInfo(@"[Stockfish] Initializing engine...");

        // Re-checked here, on the serial queue: the check above ran before
        // this block was queued, and a second initEngine queued behind a
        // first would otherwise start a second engine.
        if (self->_isEngineRunning || self->_isStarting) {
            dispatch_async(dispatch_get_main_queue(), ^{
                resolve(@YES);
            });
            return;
        }
        self->_isStarting = YES;

        // Refuse to start without the NNUE networks: a missing net would make
        // Stockfish abort the whole process on the first "go" command. They are
        // downloaded after install, so "missing" here means "not fetched yet" —
        // the JS side turns this code into the download prompt.
        if (![self->_nnue isReady]) {
            RCTLogWarn(@"[Stockfish] NNUE networks have not been downloaded yet");
            dispatch_async(dispatch_get_main_queue(), ^{
                if (self->_hasListeners) {
                    [self sendEventWithName:@"stockfish-error"
                                       body:@{@"code": @"NNUE_MISSING",
                                              @"message": @"Stockfish neural network files have not been downloaded yet."}];
                }
                reject(@"NNUE_MISSING", @"Stockfish neural network files have not been downloaded yet", nil);
            });
            self->_isStarting = NO;
            return;
        }

        // A previous loop still winding down must be gone before a new one
        // takes over stdin/stdout, or the two would share one pipe.
        if (self->_engineDone) {
            dispatch_semaphore_wait(self->_engineDone, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2 * NSEC_PER_SEC)));
        }

        // Initialize pipes
        int initResult = stockfish_init();
        if (initResult != 0) {
            dispatch_async(dispatch_get_main_queue(), ^{
                reject(@"INIT_ERROR", @"Failed to initialize Stockfish pipes", nil);
            });
            self->_isStarting = NO;
            return;
        }

        // Point the engine at the downloaded networks BEFORE the loop starts
        // and before it is declared running. The pipe holds the two lines
        // until the UCI loop reads them, so they are the first commands it
        // sees. They used to go in after a 100 ms sleep, and a "go" landing
        // inside that window made the engine's network check exit() the app.
        [self configureNNUENetworks];

        self->_engineGeneration += 1;
        NSUInteger generation = self->_engineGeneration;
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        self->_engineDone = done;
        self->_shouldStopPolling = NO;
        self->_isEngineRunning = YES;

        // Start the engine in background
        __weak typeof(self) weakSelf = self;
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
            RCTLogInfo(@"[Stockfish] Starting engine main loop...");
            stockfish_main();
            RCTLogInfo(@"[Stockfish] Engine main loop ended");
            typeof(self) strongSelf = weakSelf;
            if (strongSelf && strongSelf->_engineGeneration == generation) {
                strongSelf->_isEngineRunning = NO;
            }
            dispatch_semaphore_signal(done);
        });

        // Start polling for output
        [self startOutputPolling];

        self->_isStarting = NO;
        dispatch_async(dispatch_get_main_queue(), ^{
            RCTLogInfo(@"[Stockfish] Engine initialized successfully");
            resolve(@YES);
        });
    });
}

RCT_EXPORT_METHOD(sendCommand:(NSString *)command
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    if (!_isEngineRunning) {
        reject(@"NOT_RUNNING", @"Engine is not running", nil);
        return;
    }

    dispatch_async(_engineQueue, ^{
        const char *cmd = [command UTF8String];
        int result = stockfish_stdin_write(cmd);

        dispatch_async(dispatch_get_main_queue(), ^{
            if (result > 0) {
                resolve(@YES);
            } else {
                reject(@"WRITE_ERROR", @"Failed to write command", nil);
            }
        });
    });
}

RCT_EXPORT_METHOD(shutdownEngine:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    if (!_isEngineRunning) {
        resolve(@YES);
        return;
    }

    dispatch_async(_engineQueue, ^{
        [self stopEngineAndWait];

        dispatch_async(dispatch_get_main_queue(), ^{
            RCTLogInfo(@"[Stockfish] Engine shut down");
            resolve(@YES);
        });
    });
}

RCT_EXPORT_METHOD(isEngineAvailable:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    resolve(@YES);
}

#pragma mark - Output Polling

- (void)startOutputPolling {
    // Weak: this loop runs for as long as the engine does, and a strong self
    // in it kept a torn-down module (and its engine) alive forever.
    __weak typeof(self) weakSelf = self;
    dispatch_async(_pollingQueue, ^{
        RCTLogInfo(@"[Stockfish] Starting output polling...");

        while (true) {
            @autoreleasepool {
                typeof(self) strongSelf = weakSelf;
                if (!strongSelf || strongSelf->_shouldStopPolling || !strongSelf->_isEngineRunning) {
                    break;
                }

                const char *output = stockfish_stdout_read();

                if (output != NULL && strlen(output) > 0) {
                    NSString *outputStr = [NSString stringWithUTF8String:output];
                    [strongSelf processOutput:outputStr];
                }

                // Small sleep to prevent CPU spinning
                [NSThread sleepForTimeInterval:0.01];
            }
        }

        RCTLogInfo(@"[Stockfish] Output polling stopped");
    });
}

- (void)processOutput:(NSString *)output {
    if (!_hasListeners) return;

    // Split by newlines and process each line
    NSArray *lines = [output componentsSeparatedByString:@"\n"];

    for (NSString *line in lines) {
        NSString *trimmedLine = [line stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];

        if (trimmedLine.length == 0) continue;

        // Emit raw output
        [self sendEventWithName:@"stockfish-output" body:trimmedLine];

        // Surface NNUE load failures reported by the engine's network verify step.
        if ([trimmedLine containsString:@"was not loaded successfully"] ||
            ([trimmedLine containsString:@"ERROR"] && [trimmedLine containsString:@"network file"])) {
            [self sendEventWithName:@"stockfish-error"
                               body:@{@"code": @"NNUE_LOAD_FAILED",
                                      @"message": trimmedLine}];
        }

        // Stockfish 19 rejects a malformed FEN, an illegal move in "position ...
        // moves" or a bad "go" argument with this line. Upstream follows it with
        // exit(1); the STOCKFISH_EMBEDDED patch in cpp/stockfish/uci.cpp keeps the
        // loop alive instead, which means the command simply produced nothing —
        // no bestmove is coming. Callers waiting on one need to hear about it.
        if ([trimmedLine containsString:@"CRITICAL ERROR:"]) {
            [self sendEventWithName:@"stockfish-error"
                               body:@{@"code": @"ENGINE_CRITICAL_ERROR",
                                      @"message": trimmedLine}];
        }

        // Parse and emit analyzed output
        NSDictionary *parsed = [self parseUCIOutput:trimmedLine];
        if (parsed) {
            [self sendEventWithName:@"stockfish-analyzed-output" body:parsed];
        }
    }
}

- (NSDictionary *)parseUCIOutput:(NSString *)line {
    // Parse bestmove
    if ([line hasPrefix:@"bestmove"]) {
        NSArray *parts = [line componentsSeparatedByString:@" "];
        NSMutableDictionary *result = [@{@"type": @"bestmove"} mutableCopy];

        if (parts.count > 1) {
            result[@"move"] = parts[1];
        }

        // Check for ponder
        NSUInteger ponderIndex = [parts indexOfObject:@"ponder"];
        if (ponderIndex != NSNotFound && parts.count > ponderIndex + 1) {
            result[@"ponder"] = parts[ponderIndex + 1];
        }

        return result;
    }

    // Parse info line
    if ([line hasPrefix:@"info"]) {
        // "info depth N currmove e2e4 currmovenumber K" is search progress,
        // not an evaluation. Parsed, it became a depth-only update with no
        // multipv that displaced PV 1 from the JS buffer.
        if ([line containsString:@" currmove "]) return nil;
        NSMutableDictionary *result = [@{@"type": @"info"} mutableCopy];
        NSArray *parts = [line componentsSeparatedByString:@" "];

        for (NSUInteger i = 0; i < parts.count; i++) {
            NSString *part = parts[i];

            if ([part isEqualToString:@"depth"] && i + 1 < parts.count) {
                result[@"depth"] = @([parts[i + 1] integerValue]);
            }
            else if ([part isEqualToString:@"multipv"] && i + 1 < parts.count) {
                result[@"multipv"] = @([parts[i + 1] integerValue]);
            }
            else if ([part isEqualToString:@"score"]) {
                if (i + 2 < parts.count) {
                    if ([parts[i + 1] isEqualToString:@"cp"]) {
                        // Centipawn score - convert to pawns
                        CGFloat score = [parts[i + 2] doubleValue] / 100.0;
                        result[@"score"] = @(score);
                    }
                    else if ([parts[i + 1] isEqualToString:@"mate"]) {
                        result[@"mate"] = @([parts[i + 2] integerValue]);
                    }
                }
            }
            else if ([part isEqualToString:@"pv"]) {
                // Extract the PV line (all moves after 'pv')
                NSMutableArray *pvMoves = [NSMutableArray new];
                for (NSUInteger j = i + 1; j < parts.count; j++) {
                    [pvMoves addObject:parts[j]];
                }
                result[@"line"] = [pvMoves componentsJoinedByString:@" "];

                // First move of PV is the best move
                if (pvMoves.count > 0) {
                    result[@"bestMove"] = pvMoves[0];
                }
                break;
            }
        }

        // Only return if we have meaningful data
        if (result[@"depth"] || result[@"score"] || result[@"mate"] || result[@"bestMove"]) {
            return result;
        }
    }

    return nil;
}

@end

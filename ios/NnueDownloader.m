// NnueDownloader.m

#import "NnueDownloader.h"
#import <CommonCrypto/CommonDigest.h>
#import <Network/Network.h>

static NSString *const kNnueNet = @"nn-1a298aa575a0.nnue";
static NSString *const kVerifiedLengthPrefix = @"dawikk.nnue.verifiedLength.";
static NSString *const kErrorDomain = @"dawikk-stockfish.nnue";

// Networks earlier versions of this app downloaded. Once the engine stops
// asking for them they are 113 MB of dead weight in Application Support, and
// nothing else would ever remove them. See -cleanupRetiredNetworks.
static NSArray<NSString *> *RetiredNets(void) {
  return @[ @"nn-c288c895ea92.nnue", @"nn-37f18f62d772.nnue" ];
}

// Only used to size the progress bar before the first Content-Length arrives.
// This is the exact size of nn-1a298aa575a0.nnue, so the bar is accurate from
// the first frame.
static const long long kApproxNetBytes = 98511183LL;

// Same source order as Stockfish's own scripts/net.sh. Each entry is a prefix
// the filename is appended to; JS may override it with its own CDN.
static NSArray<NSString *> *DefaultSources(void) {
  return @[ @"https://tests.stockfishchess.org/api/nn/",
            @"https://github.com/official-stockfish/networks/raw/master/" ];
}

// Is the only path out of here one the user pays for by the megabyte?
//
// The session would refuse such a transfer on its own, but as a generic
// "data not allowed" after the task has started, and Low Data Mode does not
// even produce that. Asking first gives the exact reason — the sheet's second
// choice, "Wi-Fi or mobile data", exists for this answer — and makes the two
// platforms behave the same, since Android checks before it starts too.
static BOOL NnueNetworkIsExpensive(void) {
  if (@available(iOS 12.0, *)) {
    __block BOOL expensive = NO;
    __block BOOL answered = NO;
    dispatch_semaphore_t settled = dispatch_semaphore_create(0);

    nw_path_monitor_t monitor = nw_path_monitor_create();
    nw_path_monitor_set_queue(monitor, dispatch_get_global_queue(QOS_CLASS_UTILITY, 0));
    nw_path_monitor_set_update_handler(monitor, ^(nw_path_t path) {
      if (answered) return;
      answered = YES;
      BOOL constrained = NO;
      if (@available(iOS 13.0, *)) constrained = nw_path_is_constrained(path);
      expensive = nw_path_is_expensive(path) || constrained;
      dispatch_semaphore_signal(settled);
    });
    nw_path_monitor_start(monitor);

    // The first update arrives immediately; the timeout only guards against a
    // monitor that never answers, in which case the transfer goes ahead and
    // the session's own rules apply.
    dispatch_semaphore_wait(settled, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC));
    nw_path_monitor_cancel(monitor);
    return expensive;
  }
  return NO;
}

@interface NnueDownloader () <NSURLSessionDataDelegate>
@end

@implementation NnueDownloader {
  NSURLSession *_session;
  // The transfer in flight, so cancel can stop it without invalidating the
  // session under a dataTaskWithRequest: that is about to run.
  NSURLSessionDataTask *_currentTask;
  NSFileHandle *_handle;
  NSString *_currentName;
  NSInteger _currentIndex;
  NSInteger _currentCount;
  long long _bytesWritten;
  long long _bytesTotal;
  long long _resumeOffset;
  // What the server said the body would be, so a transfer that stops short is
  // told apart from a file that is genuinely corrupt.
  long long _declaredTotal;
  // A 416 answer: the .part already holds the whole file.
  BOOL _completeFromRange;
  NSTimeInterval _lastEmit;
  NnueProgressBlock _progress;
  NSError *_taskError;
  dispatch_semaphore_t _done;
  BOOL _cancelled;
  // Resolved once: every path helper calls -directory, so -status alone used to
  // do a dozen stats, mkdir checks and backup-flag reads.
  NSString *_directoryPath;
  // -cleanupRetiredNetworks has run in this process.
  BOOL _cleanedRetired;
}

+ (NSString *)netName { return kNnueNet; }
+ (NSArray<NSString *> *)nets { return @[ kNnueNet ]; }
+ (long long)approxTotalBytes { return kApproxNetBytes; }

+ (long long)approxBytesForName:(NSString *)name {
  return [name isEqualToString:kNnueNet] ? kApproxNetBytes : 0;
}

#pragma mark - Locations

- (NSString *)directory {
  if (_directoryPath) return _directoryPath;

  NSURL *support = [[[NSFileManager defaultManager] URLsForDirectory:NSApplicationSupportDirectory
                                                           inDomains:NSUserDomainMask] firstObject];
  NSURL *dir = [support URLByAppendingPathComponent:@"nnue" isDirectory:YES];

  NSFileManager *fm = [NSFileManager defaultManager];
  if (![fm fileExistsAtPath:dir.path]) {
    [fm createDirectoryAtURL:dir withIntermediateDirectories:YES attributes:nil error:nil];
  }
  // 94 MB of re-downloadable content must not go into the user's iCloud
  // backup — Apple rejects apps that put it there.
  NSNumber *excluded = nil;
  [dir getResourceValue:&excluded forKey:NSURLIsExcludedFromBackupKey error:nil];
  if (!excluded.boolValue) {
    [dir setResourceValue:@YES forKey:NSURLIsExcludedFromBackupKey error:nil];
  }

  _directoryPath = dir.path;
  return _directoryPath;
}

- (NSString *)pathForName:(NSString *)name {
  return [[self directory] stringByAppendingPathComponent:name];
}

- (NSString *)partPathForName:(NSString *)name {
  return [[self pathForName:name] stringByAppendingPathExtension:@"part"];
}

- (long long)lengthOfFileAtPath:(NSString *)path {
  NSDictionary *attrs = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil];
  return attrs ? (long long)[attrs fileSize] : 0;
}

#pragma mark - Validation

// The filename embeds the first 12 hex chars of the file's sha256.
- (NSString *)expectedPrefixForName:(NSString *)name {
  NSString *prefix = [name hasPrefix:@"nn-"] ? [name substringFromIndex:3] : name;
  return [prefix stringByReplacingOccurrencesOfString:@".nnue" withString:@""];
}

- (NSString *)sha256PrefixOfFileAtPath:(NSString *)path {
  NSInputStream *stream = [NSInputStream inputStreamWithFileAtPath:path];
  if (!stream) return nil;
  [stream open];

  CC_SHA256_CTX ctx;
  CC_SHA256_Init(&ctx);

  const NSUInteger bufferSize = 1 << 16;
  uint8_t *buffer = malloc(bufferSize);
  if (!buffer) { [stream close]; return nil; }

  NSInteger read;
  while ((read = [stream read:buffer maxLength:bufferSize]) > 0) {
    CC_SHA256_Update(&ctx, buffer, (CC_LONG)read);
  }
  free(buffer);
  [stream close];
  if (read < 0) return nil;

  unsigned char digest[CC_SHA256_DIGEST_LENGTH];
  CC_SHA256_Final(digest, &ctx);

  NSMutableString *hex = [NSMutableString stringWithCapacity:24];
  for (int i = 0; i < 6; i++) [hex appendFormat:@"%02x", digest[i]];
  return hex;
}

- (NSString *)verifiedLengthKeyForName:(NSString *)name {
  return [kVerifiedLengthPrefix stringByAppendingString:name];
}

- (BOOL)isReadyForName:(NSString *)name {
  NSString *path = [self pathForName:name];
  if (![[NSFileManager defaultManager] fileExistsAtPath:path]) return NO;
  long long verified = [[NSUserDefaults standardUserDefaults]
      integerForKey:[self verifiedLengthKeyForName:name]];
  return verified > 0 && [self lengthOfFileAtPath:path] == verified;
}

- (BOOL)isReady {
  [self cleanupRetiredNetworks];
  for (NSString *name in [NnueDownloader nets]) {
    if (![self isReadyForName:name]) return NO;
  }
  return YES;
}

/**
 * Deletes the networks a previous Stockfish asked for, with their partial
 * transfers and recorded verified lengths. Upgrading from the Stockfish 18
 * build leaves 113 MB in Application Support that the engine will never open
 * again. Runs once per process, and only touches names this build does not use.
 */
- (void)cleanupRetiredNetworks {
  if (_cleanedRetired) return;
  _cleanedRetired = YES;

  NSFileManager *fm = [NSFileManager defaultManager];
  NSArray<NSString *> *current = [NnueDownloader nets];
  long long freed = 0;

  for (NSString *name in RetiredNets()) {
    if ([current containsObject:name]) continue;  // guards a net being reinstated
    for (NSString *path in @[ [self pathForName:name], [self partPathForName:name] ]) {
      if (![fm fileExistsAtPath:path]) continue;
      long long size = [self lengthOfFileAtPath:path];
      NSError *error = nil;
      if ([fm removeItemAtPath:path error:&error]) {
        freed += size;
      } else {
        NSLog(@"[NnueDownloader] Could not delete retired network %@: %@", path, error);
      }
    }
    [[NSUserDefaults standardUserDefaults]
        removeObjectForKey:[self verifiedLengthKeyForName:name]];
  }

  if (freed > 0) {
    NSLog(@"[NnueDownloader] Reclaimed %lld MB from networks retired in Stockfish 19",
          freed / 1048576);
  }
}

- (BOOL)verifyName:(NSString *)name {
  NSString *path = [self pathForName:name];
  if ([self lengthOfFileAtPath:path] < 1024) return NO;

  NSString *actual = [self sha256PrefixOfFileAtPath:path];
  BOOL ok = actual && [actual isEqualToString:[self expectedPrefixForName:name]];
  NSString *key = [self verifiedLengthKeyForName:name];
  if (ok) {
    [[NSUserDefaults standardUserDefaults] setInteger:(NSInteger)[self lengthOfFileAtPath:path]
                                               forKey:key];
  } else {
    [[NSUserDefaults standardUserDefaults] removeObjectForKey:key];
  }
  return ok;
}

#pragma mark - Status

- (NSDictionary *)status {
  NSMutableArray *files = [NSMutableArray array];
  long long onDisk = 0;

  for (NSString *name in [NnueDownloader nets]) {
    long long bytes = [self lengthOfFileAtPath:[self pathForName:name]];
    long long partial = [self lengthOfFileAtPath:[self partPathForName:name]];
    onDisk += bytes + partial;
    [files addObject:@{
      @"name": name,
      @"path": [self pathForName:name],
      @"ready": @([self isReadyForName:name]),
      @"bytes": @(bytes),
      @"partialBytes": @(partial),
      @"approxBytes": @([NnueDownloader approxBytesForName:name])
    }];
  }

  NSDictionary *fsAttrs = [[NSFileManager defaultManager]
      attributesOfFileSystemForPath:[self directory] error:nil];

  return @{
    @"ready": @([self isReady]),
    @"directory": [self directory],
    @"files": files,
    @"approxTotalBytes": @([NnueDownloader approxTotalBytes]),
    @"bytesOnDisk": @(onDisk),
    @"freeBytes": fsAttrs[NSFileSystemFreeSize] ?: @(0)
  };
}

- (BOOL)deleteNetworks {
  BOOL ok = YES;
  NSFileManager *fm = [NSFileManager defaultManager];
  for (NSString *name in [NnueDownloader nets]) {
    for (NSString *path in @[ [self pathForName:name], [self partPathForName:name] ]) {
      if ([fm fileExistsAtPath:path] && ![fm removeItemAtPath:path error:nil]) ok = NO;
    }
    [[NSUserDefaults standardUserDefaults]
        removeObjectForKey:[self verifiedLengthKeyForName:name]];
  }
  return ok;
}

#pragma mark - Downloading

- (void)cancel {
  // Only the task is cancelled, never the session: cancel runs on the RN
  // method queue while downloadWithSources: runs on its own, and creating a
  // task on an invalidated session throws (NSGenericException) rather than
  // returning nil — a crash in the gap between the cancelled check and
  // dataTaskWithRequest:. The task's completion still unblocks fetchURL:, and
  // the loop sees _cancelled on its next check. downloadWithSources: owns the
  // session and invalidates it on its own way out.
  _cancelled = YES;
  [_currentTask cancel];
}

- (void)finishSession {
  [_session invalidateAndCancel];
  _session = nil;
}

- (NSError *)errorWithCode:(NSString *)code message:(NSString *)message {
  return [NSError errorWithDomain:kErrorDomain
                             code:1
                         userInfo:@{ @"code": code, NSLocalizedDescriptionKey: message }];
}

- (BOOL)downloadWithSources:(NSArray<NSString *> *)sources
               allowMetered:(BOOL)allowMetered
                   progress:(NnueProgressBlock)progress
                      error:(NSError **)error {
  _cancelled = NO;
  _progress = progress;

  NSMutableArray<NSString *> *missing = [NSMutableArray array];
  for (NSString *name in [NnueDownloader nets]) {
    if (![self isReadyForName:name]) [missing addObject:name];
  }
  if (missing.count == 0) return YES;

  long long needed = 0;
  for (NSString *name in missing) {
    needed += [NnueDownloader approxBytesForName:name] - [self lengthOfFileAtPath:[self partPathForName:name]];
  }
  NSDictionary *fsAttrs = [[NSFileManager defaultManager]
      attributesOfFileSystemForPath:[self directory] error:nil];
  long long free = [fsAttrs[NSFileSystemFreeSize] longLongValue];
  if (free > 0 && free < needed + 16LL * 1024 * 1024) {
    if (error) {
      *error = [self errorWithCode:@"NNUE_NO_SPACE"
                           message:[NSString stringWithFormat:
                               @"Not enough free storage for the engine files (about %lld MB needed).",
                               needed / 1048576]];
    }
    return NO;
  }

  if (!allowMetered && NnueNetworkIsExpensive()) {
    if (error) {
      *error = [self errorWithCode:@"NNUE_METERED_NETWORK"
                           message:@"The engine files are large; download was restricted to Wi-Fi."];
    }
    return NO;
  }

  NSURLSessionConfiguration *config = [NSURLSessionConfiguration defaultSessionConfiguration];
  config.timeoutIntervalForRequest = 30;
  config.timeoutIntervalForResource = 60 * 60;
  // NO on purpose. Parking the task until connectivity appears sounds kind, but
  // with no path at all it is an hour of a progress bar at 0% that cannot even
  // be retried, because the transfer is still held. Failing now gives the user
  // the sheet, an explanation and a button — and the .part means retrying costs
  // nothing.
  config.waitsForConnectivity = NO;
  config.allowsCellularAccess = allowMetered;
  if (@available(iOS 13.0, *)) {
    // "Expensive" is cellular/hotspot, "constrained" is Low Data Mode: a
    // 94 MB transfer has no business on either unless the user said so.
    config.allowsExpensiveNetworkAccess = allowMetered;
    config.allowsConstrainedNetworkAccess = allowMetered;
  }
  _session = [NSURLSession sessionWithConfiguration:config
                                           delegate:self
                                      delegateQueue:nil];

  NSArray<NSString *> *urlPrefixes = sources.count > 0 ? sources : DefaultSources();
  NSError *lastError = nil;

  for (NSInteger i = 0; i < (NSInteger)missing.count; i++) {
    NSString *name = missing[(NSUInteger)i];
    BOOL done = NO;

    for (NSString *prefix in urlPrefixes) {
      if (_cancelled) {
        if (error) *error = [self errorWithCode:@"NNUE_CANCELLED" message:@"Download cancelled"];
        [self finishSession];
        return NO;
      }

      NSString *urlString = [prefix hasSuffix:@"/"]
          ? [prefix stringByAppendingString:name]
          : [NSString stringWithFormat:@"%@/%@", prefix, name];

      NSError *fetchError = nil;
      if (![self fetchURL:urlString name:name index:i count:(NSInteger)missing.count error:&fetchError]) {
        lastError = fetchError;
        // A cancel or a refused metered connection is the answer, not one
        // source failing: neither gets better by trying the next mirror.
        if ([fetchError.userInfo[@"code"] isEqualToString:@"NNUE_CANCELLED"] ||
            [fetchError.userInfo[@"code"] isEqualToString:@"NNUE_METERED_NETWORK"]) {
          if (error) *error = fetchError;
          [self finishSession];
          return NO;
        }
        continue;
      }

      NSString *partPath = [self partPathForName:name];
      NSString *actual = [self sha256PrefixOfFileAtPath:partPath];
      if (!actual || ![actual isEqualToString:[self expectedPrefixForName:name]]) {
        [[NSFileManager defaultManager] removeItemAtPath:partPath error:nil];
        lastError = [self errorWithCode:@"NNUE_CHECKSUM_FAILED"
                                message:[NSString stringWithFormat:@"Checksum mismatch for %@", name]];
        continue;
      }

      NSString *finalPath = [self pathForName:name];
      NSFileManager *fm = [NSFileManager defaultManager];
      if ([fm fileExistsAtPath:finalPath]) [fm removeItemAtPath:finalPath error:nil];
      NSError *moveError = nil;
      if (![fm moveItemAtPath:partPath toPath:finalPath error:&moveError]) {
        lastError = [self errorWithCode:@"NNUE_DOWNLOAD_FAILED"
                                message:[NSString stringWithFormat:@"Could not move %@ into place", name]];
        continue;
      }

      long long length = [self lengthOfFileAtPath:finalPath];
      [[NSUserDefaults standardUserDefaults] setInteger:(NSInteger)length
                                                 forKey:[self verifiedLengthKeyForName:name]];
      if (progress) progress(name, i, (NSInteger)missing.count, length, length);
      done = YES;
      break;
    }

    if (!done) {
      if (error) {
        // Keep the reason: every source serving a corrupted file is a different
        // problem from no source answering, and the sheet says so.
        NSString *code = lastError.userInfo[@"code"] ?: @"NNUE_DOWNLOAD_FAILED";
        *error = [self errorWithCode:code
                             message:[NSString stringWithFormat:@"Could not download %@ (%@)",
                                 name, lastError.localizedDescription ?: @"no source responded"]];
      }
      [self finishSession];
      return NO;
    }
  }

  [_session finishTasksAndInvalidate];
  _session = nil;
  return YES;
}

- (BOOL)fetchURL:(NSString *)urlString
            name:(NSString *)name
           index:(NSInteger)index
           count:(NSInteger)count
           error:(NSError **)error {
  NSURL *url = [NSURL URLWithString:urlString];
  if (!url) {
    if (error) *error = [self errorWithCode:@"NNUE_DOWNLOAD_FAILED" message:@"Invalid source URL"];
    return NO;
  }

  NSString *partPath = [self partPathForName:name];
  long long offset = [self lengthOfFileAtPath:partPath];

  NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
  [request setValue:@"dawikk-stockfish" forHTTPHeaderField:@"User-Agent"];
  [request setValue:@"identity" forHTTPHeaderField:@"Accept-Encoding"];
  if (offset > 0) {
    [request setValue:[NSString stringWithFormat:@"bytes=%lld-", offset] forHTTPHeaderField:@"Range"];
  }

  if (![[NSFileManager defaultManager] fileExistsAtPath:partPath]) {
    [[NSFileManager defaultManager] createFileAtPath:partPath contents:nil attributes:nil];
    offset = 0;
  }
  _handle = [NSFileHandle fileHandleForWritingAtPath:partPath];
  [_handle seekToEndOfFile];

  _currentName = name;
  _currentIndex = index;
  _currentCount = count;
  _resumeOffset = offset;
  _bytesWritten = offset;
  _bytesTotal = [NnueDownloader approxBytesForName:name];
  _declaredTotal = -1;
  _completeFromRange = NO;
  _lastEmit = 0;
  _taskError = nil;
  _done = dispatch_semaphore_create(0);

  NSURLSessionDataTask *task = [_session dataTaskWithRequest:request];
  if (!task) {
    [_handle closeFile];
    _handle = nil;
    if (error) *error = [self errorWithCode:@"NNUE_CANCELLED" message:@"Download cancelled"];
    return NO;
  }
  _currentTask = task;
  if (_cancelled) {
    // cancel() landed between the check at the top and the task existing.
    [task cancel];
  }
  [task resume];
  dispatch_semaphore_wait(_done, DISPATCH_TIME_FOREVER);
  _currentTask = nil;

  [_handle closeFile];
  _handle = nil;

  if (_completeFromRange) return YES;

  if (_taskError) {
    if (error) *error = _taskError;
    return NO;
  }

  // A body that stopped short is an interrupted transfer, not a corrupt file.
  // Saying so here matters: the caller deletes the .part on a checksum
  // mismatch, and doing that to 100 MB of good bytes would throw away exactly
  // what "it resumes where it stopped" promises.
  if (_declaredTotal > 0 && _bytesWritten < _declaredTotal) {
    if (error) {
      *error = [self errorWithCode:@"NNUE_DOWNLOAD_FAILED"
                           message:[NSString stringWithFormat:@"Incomplete body for %@: %lld of %lld bytes",
                               name, _bytesWritten, _declaredTotal]];
    }
    return NO;
  }
  return YES;
}

#pragma mark - NSURLSessionDataDelegate

- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask
didReceiveResponse:(NSURLResponse *)response
 completionHandler:(void (^)(NSURLSessionResponseDisposition))completionHandler {
  NSHTTPURLResponse *http = (NSHTTPURLResponse *)response;
  NSInteger status = [http respondsToSelector:@selector(statusCode)] ? http.statusCode : 200;

  // 416: the range starts past the end of the file, which means the .part
  // already holds every byte — the app was killed between the last chunk and
  // the rename. There is nothing left to fetch; the checksum decides whether
  // it is a good file or a corrupt one.
  if (status == 416 && _resumeOffset > 0) {
    _completeFromRange = YES;
    _taskError = nil;
    completionHandler(NSURLSessionResponseCancel);
    return;
  }

  if (status != 200 && status != 206) {
    _taskError = [self errorWithCode:@"NNUE_DOWNLOAD_FAILED"
                             message:[NSString stringWithFormat:@"HTTP %ld", (long)status]];
    completionHandler(NSURLSessionResponseCancel);
    return;
  }

  // The server ignored the Range header (or the partial file is stale): start
  // over rather than splicing a full body onto a partial one.
  if (_resumeOffset > 0 && status == 200) {
    [_handle truncateFileAtOffset:0];
    _bytesWritten = 0;
    _resumeOffset = 0;
  }

  long long expected = response.expectedContentLength;
  if (expected > 0) {
    _bytesTotal = _resumeOffset + expected;
    _declaredTotal = _bytesTotal;
  }

  completionHandler(NSURLSessionResponseAllow);
}

- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveData:(NSData *)data {
  if (_cancelled) {
    [dataTask cancel];
    return;
  }
  @try {
    [_handle writeData:data];
  } @catch (NSException *exception) {
    _taskError = [self errorWithCode:@"NNUE_NO_SPACE" message:exception.reason ?: @"Write failed"];
    [dataTask cancel];
    return;
  }
  _bytesWritten += (long long)data.length;

  // ~20 events/second at most: the bridge, not the socket, is what a
  // per-chunk emit would saturate.
  NSTimeInterval now = [NSDate timeIntervalSinceReferenceDate];
  if (_progress && now - _lastEmit > 0.05) {
    _lastEmit = now;
    _progress(_currentName, _currentIndex, _currentCount, _bytesWritten, _bytesTotal);
  }
}

- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task
didCompleteWithError:(NSError *)error {
  // The 416 path cancels its own task on purpose; that cancellation is the
  // answer, not a failure.
  if (error && !_taskError && !_completeFromRange) {
    if (_cancelled) {
      _taskError = [self errorWithCode:@"NNUE_CANCELLED" message:@"Download cancelled"];
    } else if (error.code == NSURLErrorDataNotAllowed ||
               error.userInfo[NSURLErrorNetworkUnavailableReasonKey] != nil) {
      _taskError = [self errorWithCode:@"NNUE_METERED_NETWORK"
                               message:@"The engine files are large; download was restricted to Wi-Fi."];
    } else {
      _taskError = [self errorWithCode:@"NNUE_DOWNLOAD_FAILED"
                               message:error.localizedDescription ?: @"Download failed"];
    }
  }
  if (_done) dispatch_semaphore_signal(_done);
}

@end

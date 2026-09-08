// NnueDownloader.h
// Post-install download of the Stockfish NNUE network.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^NnueProgressBlock)(NSString *name,
                                  NSInteger index,
                                  NSInteger count,
                                  long long bytesWritten,
                                  long long bytesTotal);

/**
 * The network is ~94 MB — bundling it into the IPA is most of the App Store
 * download (and all of it over cellular, where Apple's over-the-air limit
 * bites). It is content, not code, so it is fetched on first use into
 * Application Support/nnue, which is excluded from iCloud backup.
 *
 * Stockfish 19 retired the second (small) network 16.1 introduced, so what used
 * to be two files totalling ~113 MB is one file of 93.9 MB. +nets is still an
 * array: the download, resume, progress and verification code is written
 * against a set of files, and a later architecture change may add one back.
 *
 * The download is resumable (HTTP Range against a .part file) and verified
 * against the sha256 prefix embedded in the filename — the same rule
 * Stockfish's own scripts/net.sh uses — before the file is moved into place.
 */
@interface NnueDownloader : NSObject

/// Filename; must match cpp/stockfish/evaluate.h (EvalFileDefaultName).
@property (class, nonatomic, readonly) NSString *netName;
@property (class, nonatomic, readonly) NSArray<NSString *> *nets;
@property (class, nonatomic, readonly) long long approxTotalBytes;

/// Absolute directory the network lives in (created on demand).
- (NSString *)directory;
- (NSString *)pathForName:(NSString *)name;

/// Cheap check used on every engine start (presence + verified length).
- (BOOL)isReadyForName:(NSString *)name;
- (BOOL)isReady;

/// Full sha256 re-check of what is on disk. Slow.
- (BOOL)verifyName:(NSString *)name;

- (NSDictionary *)status;
- (BOOL)deleteNetworks;

- (void)cancel;

/// Blocking; run it off the main thread.
- (BOOL)downloadWithSources:(nullable NSArray<NSString *> *)sources
               allowMetered:(BOOL)allowMetered
                   progress:(nullable NnueProgressBlock)progress
                      error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END

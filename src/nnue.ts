// src/nnue.ts
// Post-install download of the Stockfish NNUE network.
//
// The network is ~94 MB. Shipping it inside the app would be most of the
// Play/App Store download, so the binary carries only the engine and the network
// is fetched on first use into a per-app directory that is kept out of the device
// backup. Everything below is a thin, typed wrapper around the native downloader
// (NnueDownloader.kt / NnueDownloader.m), which does the resumable transfer and
// the sha256 verification.
//
// Stockfish 19 retired the second (small) network that 16.1 introduced, so
// `files` now holds one entry. It stays an array: everything here — resume,
// weighted progress, verification — is written against a set of files, and a
// later architecture change may add one back.

import { StockfishModule, StockfishEventEmitter, isNativeModuleAvailable } from './native';

export interface NnueFileStatus {
  /** e.g. "nn-1a298aa575a0.nnue" */
  name: string;
  /** absolute path the engine will be pointed at */
  path: string;
  /** present and verified */
  ready: boolean;
  bytes: number;
  /** bytes of a partial (.part) transfer waiting to be resumed */
  partialBytes: number;
  approxBytes: number;
}

export interface NnueStatus {
  /** every network present and verified — the engine can start */
  ready: boolean;
  directory: string;
  files: NnueFileStatus[];
  approxTotalBytes: number;
  bytesOnDisk: number;
  freeBytes: number;
}

export interface NnueProgress {
  name: string;
  /** index of the file being fetched, within the files still missing */
  index: number;
  count: number;
  bytesWritten: number;
  bytesTotal: number;
  /** 0..1 for the current file */
  progress: number;
  /** 0..1 across everything this download has to fetch */
  totalProgress: number;
  /**
   * Bytes across the WHOLE download, not the file in flight — the pair that
   * belongs next to totalProgress. `bytesWritten`/`bytesTotal` above are the
   * current file's own numbers, so rendering them beside the overall
   * percentage reads as "97% — 1 MB / 4 MB".
   */
  totalBytesWritten: number;
  totalBytesTotal: number;
}

export interface NnueDownloadOptions {
  /**
   * URL prefixes tried in order; the filename is appended to each. Defaults to
   * the official Stockfish sources. Point this at your own CDN in production —
   * every install hitting tests.stockfishchess.org for 94 MB is not a good
   * neighbour, and a CDN gives you Range support and predictable throughput.
   */
  sources?: string[];
  /** Allow cellular / metered / Low Data Mode. Default false (Wi-Fi only). */
  allowMetered?: boolean;
  onProgress?: (progress: NnueProgress) => void;
}

/**
 * Error codes rejected by download():
 *   NNUE_METERED_NETWORK  - only a metered connection is available and
 *                           allowMetered was false
 *   NNUE_NO_SPACE         - not enough free storage
 *   NNUE_CHECKSUM_FAILED  - every source served a corrupted file
 *   NNUE_DOWNLOAD_FAILED  - no source responded / transfer failed
 *   NNUE_CANCELLED        - cancel() was called
 *   NNUE_DOWNLOAD_BUSY    - a download is already running
 */
export type NnueErrorCode =
  | 'NNUE_METERED_NETWORK'
  | 'NNUE_NO_SPACE'
  | 'NNUE_CHECKSUM_FAILED'
  | 'NNUE_DOWNLOAD_FAILED'
  | 'NNUE_CANCELLED'
  | 'NNUE_DOWNLOAD_BUSY';

// Reading a property off the module resolves the linking-error Proxy, so an app
// built without the native module has to stop at the availability check.
const constants: Record<string, any> = isNativeModuleAvailable
  ? (typeof StockfishModule.getConstants === 'function'
      ? StockfishModule.getConstants()
      : StockfishModule)
  : {};

class NnueNetworks {
  /** Directory the network is stored in (excluded from backup). */
  readonly directory: string = constants.nnueDirectory || '';
  /** Filename of the network this engine build asks for. */
  readonly name: string = constants.nnueName || '';
  /** Rough total, for a size hint in the UI before the download starts. */
  readonly approxTotalBytes: number = constants.nnueApproxTotalBytes || 0;

  private pending: Promise<NnueStatus> | null = null;

  async getStatus(): Promise<NnueStatus> {
    return StockfishModule.getNnueStatus();
  }

  async isReady(): Promise<boolean> {
    try {
      const status = await this.getStatus();
      return !!status?.ready;
    } catch {
      return false;
    }
  }

  /** True while a download started through this wrapper is still running. */
  get isDownloading(): boolean {
    return this.pending !== null;
  }

  /**
   * Downloads whatever is missing and resolves with the resulting status.
   * Concurrent calls share one transfer, so two screens asking at once do not
   * fight over the same file.
   */
  download(options: NnueDownloadOptions = {}): Promise<NnueStatus> {
    const { onProgress } = options;

    if (this.pending) {
      // Attach the caller's progress callback to the transfer already running.
      const detach = onProgress ? this.addProgressListener(onProgress) : null;
      return this.pending.finally(() => detach?.());
    }

    const run = async (): Promise<NnueStatus> => {
      const before = await this.getStatus();
      if (before.ready) return before;

      // Weight the per-file progress by size, so a set of unequal files does not
      // make the bar jump when one hands over to the next. The weights are what
      // is LEFT to fetch, so the bytes already on disk from an interrupted run
      // have to come off the current file's count too — otherwise a resumed
      // transfer opens at "48% — 26 MB / 54 MB" before a single new byte
      // arrives.
      const missing = before.files.filter((file) => !file.ready);
      const resumedFrom = missing.map((file) => file.partialBytes);
      const weights = missing.map((file) => Math.max(file.approxBytes - file.partialBytes, 1));
      const weightTotal = weights.reduce((sum, weight) => sum + weight, 0);

      const detach = onProgress
        ? this.addProgressListener((progress) => {
            const done = weights.slice(0, progress.index).reduce((sum, weight) => sum + weight, 0);
            const fresh = Math.max(progress.bytesWritten - (resumedFrom[progress.index] || 0), 0);
            const current = Math.min(fresh, weights[progress.index] || 0);
            const written = done + current;
            onProgress({
              ...progress,
              totalProgress: weightTotal > 0 ? written / weightTotal : progress.progress,
              totalBytesWritten: written,
              totalBytesTotal: weightTotal,
            });
          })
        : null;

      try {
        return await StockfishModule.downloadNnueNetworks({
          sources: options.sources || [],
          allowMetered: options.allowMetered === true,
        });
      } finally {
        detach?.();
      }
    };

    this.pending = run().finally(() => {
      this.pending = null;
    });
    return this.pending;
  }

  /** Aborts a running download. The partial file is kept and resumed later. */
  async cancel(): Promise<boolean> {
    try {
      return await StockfishModule.cancelNnueDownload();
    } catch {
      return false;
    }
  }

  /** Deletes the network (and any partial transfer). Engine must be stopped. */
  async remove(): Promise<boolean> {
    return StockfishModule.deleteNnueNetworks();
  }

  /** Full sha256 re-check of what is on disk. Slow — reads ~94 MB. */
  async verify(): Promise<boolean> {
    return StockfishModule.verifyNnueNetworks();
  }

  addProgressListener(listener: (progress: NnueProgress) => void): () => void {
    const subscription = StockfishEventEmitter.addListener(
      'stockfish-nnue-progress',
      (progress: NnueProgress) => {
        // Without the per-file weights (download() adds them) the best this can
        // do is count files, so the byte pair stays the current file's own.
        listener({
          ...progress,
          totalProgress:
            progress.count > 0
              ? (progress.index + progress.progress) / progress.count
              : progress.progress,
          totalBytesWritten: progress.bytesWritten,
          totalBytesTotal: progress.bytesTotal,
        });
      }
    );
    return () => subscription.remove();
  }

  addReadyListener(listener: (status: NnueStatus) => void): () => void {
    const subscription = StockfishEventEmitter.addListener('stockfish-nnue-ready', listener);
    return () => subscription.remove();
  }
}

export default new NnueNetworks();
export { NnueNetworks };

# dawikk-stockfish

Stockfish **19** for React Native — iOS and Android, running in-process, driven over UCI.

The engine is compiled from the Stockfish 19 sources vendored in `cpp/stockfish/`.

**The NNUE network is not in this repository, and it is not in your app binary
either.** It is a single ~94 MB file — most of a store download, and content
rather than code — so nothing here carries it: no `.nnue` file is checked in
(`.gitignore` blocks them), the npm package does not ship one, and the engine is
built with `NNUE_EMBEDDING_OFF` so it is not linked in with `incbin` the way
upstream does it. Instead the library downloads the network on first use into a
per-app directory kept out of device backups, verifies it by sha256, and points
the engine at it through the `EvalFile` UCI option. Until that download has
finished the engine cannot start: `init()` resolves `false` and an
`NNUE_MISSING` error reaches every error listener.

## Features

- Stockfish 19 (NNUE), full UCI support
- iOS (arm64 device + simulator) and Android (`arm64-v8a`, `armeabi-v7a`, `x86_64`)
- Resumable, checksum-verified post-install download of the NNUE network, with
  weighted progress and a Wi-Fi-only default
- Event-based API: raw messages, parsed analysis (MultiPV), bestmove, errors
- Configurable throttling so engine output never floods the JS thread
- The embedded build does not `exit()` on a bad command (see
  [Stockfish 19 notes](#stockfish-19-notes))

## Installation

```sh
npm install dawikk-stockfish
# or
yarn add dawikk-stockfish
```

### iOS

```sh
cd ios && pod install
```

Deployment target is iOS 15.1. The pod compiles the whole engine, so the first
build is slow; later builds are cached.

### Android

Nothing to configure — the library ships its own `CMakeLists.txt` and builds
`libstockfish.so` for the ABIs listed above. Requires NDK r23+ and
`minSdkVersion` 29.

This is not usable in Expo Go: it contains native code, so you need a development
build (`expo prebuild` + `expo run:android` / `expo run:ios`) or a bare project.

## Quick start

```javascript
import Stockfish, { NnueNetworks } from 'dawikk-stockfish';

// 1. Is there a native engine for this device at all?
if (!(await Stockfish.isEngineAvailable())) return;

// 2. The NNUE network is downloaded after install — do this once.
if (!(await Stockfish.areNetworksReady())) {
  await Stockfish.ensureNetworks({
    allowMetered: false,                 // Wi-Fi only (default)
    onProgress: (p) => console.log(Math.round(p.totalProgress * 100) + '%'),
  });
}

// 3. Start the engine and listen.
await Stockfish.init();

const off = Stockfish.addAnalysisListener((data) => {
  console.log(data.depth, data.evaluations, data.bestMoves);
});
const offBest = Stockfish.addBestMoveListener((data) => {
  console.log('bestmove', data.move);
});

await Stockfish.analyzePosition(
  'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  { depth: 20, multiPv: 3 }
);

// 4. Clean up.
off();
offBest();
await Stockfish.shutdown();
```

## NNUE network

The network (`nn-1a298aa575a0.nnue`, 93.9 MiB) is downloaded on demand. Stockfish 19
retired the small second network that 16.1 introduced, so there is one file now —
the API still speaks in terms of a *list* of files, because resume, weighted
progress and verification are all written against a set.

```javascript
import { NnueNetworks } from 'dawikk-stockfish';

NnueNetworks.name;              // "nn-1a298aa575a0.nnue"
NnueNetworks.directory;         // absolute, excluded from backup
NnueNetworks.approxTotalBytes;  // 98511183

await NnueNetworks.getStatus(); // NnueStatus (per-file ready/bytes/partialBytes, freeBytes)
await NnueNetworks.isReady();
NnueNetworks.isDownloading;     // true while a download started here is running
await NnueNetworks.download({ sources, allowMetered, onProgress });
await NnueNetworks.cancel();    // partial .part file is kept and resumed later
await NnueNetworks.remove();    // engine must be stopped first
await NnueNetworks.verify();    // full sha256 re-check (~94 MB read, slow)

const off = NnueNetworks.addProgressListener((p) => {/* NnueProgress */});
const offReady = NnueNetworks.addReadyListener((s) => {/* NnueStatus */});
```

`sources` is a list of URL prefixes tried in order; the filename is appended to
each. It defaults to Stockfish's own sources:

```
https://tests.stockfishchess.org/api/nn/
https://github.com/official-stockfish/networks/raw/master/
```

**Point this at your own CDN in production.** Every install pulling 94 MB from
`tests.stockfishchess.org` is not a good neighbour, and a CDN gives you Range
support and predictable throughput.

`download()` rejects with one of: `NNUE_METERED_NETWORK`, `NNUE_NO_SPACE`,
`NNUE_CHECKSUM_FAILED`, `NNUE_DOWNLOAD_FAILED`, `NNUE_CANCELLED`,
`NNUE_DOWNLOAD_BUSY`.

Networks retired in Stockfish 19 (`nn-c288c895ea92.nnue`, `nn-37f18f62d772.nnue`,
113 MB together) are deleted on the first run after an upgrade, from both the
current and the legacy storage location.

## API

### Engine

| Method | Description |
|---|---|
| `isEngineAvailable()` | `false` when there is no native engine library for this device's ABI. The engine can never start there — hide analysis rather than suggest a reinstall. |
| `areNetworksReady()` | Network present and verified. |
| `ensureNetworks(options?)` | Downloads whatever is missing; concurrent callers share one transfer. |
| `init()` | Starts the engine. Concurrent calls join the first one's promise. |
| `sendCommand(command)` | Raw UCI command. Initializes the engine if needed. |
| `shutdown()` | Stops the engine and frees resources. |
| `analyzePosition(fen, options)` | `uci` → `isready` → `ucinewgame` → `position fen` → `go`. Options: `depth`, `multiPv`, `movetime`, `nodes`. |
| `stopAnalysis()` | Sends `stop`. |
| `getComputerMove(fen, movetime?, depth?)` | Convenience wrapper; the move arrives on the bestmove listener. |
| `setConfig(config)` | Throttling and which events are emitted. Callable at any time. |
| `destroy()` | Shuts down and drops every listener. |

### Listeners

Every `add*Listener` returns its own unsubscribe function.

```javascript
Stockfish.addMessageListener((message: string) => {});
Stockfish.addAnalysisListener((data: AnalysisData) => {});
Stockfish.addBestMoveListener((data: BestMoveData) => {});
Stockfish.addErrorListener((error: StockfishError) => {});
```

`addErrorListener` replays the most recent error to a listener that subscribes
after it fired, so a failure during `init()` is not lost to a late subscriber.

For callers that would rather hold on to the function than to the unsubscriber,
`removeMessageListener`, `removeAnalysisListener`, `removeBestMoveListener` and
`removeErrorListener` take the original listener and do the same thing.

Underneath, these are the native events the module emits — useful if you attach
to `StockfishEventEmitter` (also exported) directly:

| Event | Payload |
|---|---|
| `stockfish-output` | `string` — one raw line of engine output |
| `stockfish-analyzed-output` | `AnalysisData` or `BestMoveData` |
| `stockfish-error` | `StockfishError` |
| `stockfish-nnue-progress` | `NnueProgress` |
| `stockfish-nnue-ready` | `NnueStatus` |

Error codes:

| Code | Meaning |
|---|---|
| `NNUE_MISSING` | Network not downloaded yet. Recoverable: `ensureNetworks()` then `init()`. |
| `NNUE_LOAD_FAILED` | A file exists but the engine could not load it. |
| `ENGINE_UNAVAILABLE` | No native library for this ABI. Not recoverable by the user. |
| `ENGINE_CRITICAL_ERROR` | Stockfish rejected the last command (bad FEN, illegal move in `position ... moves`, bad `go` argument). The engine stays up, but that command produced nothing — no `bestmove` is coming for it. After a rejected `position` the engine is reset to the start position, so the next `go` needs a fresh `position` first. |

### Configuration

```javascript
Stockfish.setConfig({
  throttling: {
    analysisInterval: 100,  // ms between analysis emissions
    messageInterval: 100,   // ms between raw message emissions
  },
  events: {
    emitMessage: true,
    emitAnalysis: true,
    emitBestMove: true,
  },
});
```

Turning off what you do not consume is the cheapest optimization here: for a game
against the computer keep only `emitBestMove`; for analysis keep only
`emitAnalysis`.

### Types

Everything below is exported from the package entry point.

```typescript
interface AnalysisOptions {
  depth?: number;      // default 20
  multiPv?: number;    // default 1
  movetime?: number;   // ms
  nodes?: number;
}

interface AnalysisData {
  type: 'info' | 'bestmove';
  depth?: number;
  score?: number;      // centipawns
  mate?: number;       // mate in N, when the line is forced
  bestMove?: string;
  line?: string;
  move?: string;

  // MultiPV: one entry per line, index-aligned across all four arrays.
  // `evaluations` holds either a centipawn number as a string, or `M<n>` for a
  // mate — exactly one entry per PV, so it never desyncs from `bestMoves`.
  bestMoves?: string[];
  evaluations?: string[];
  lines?: string[];
  depths?: number[];
}

interface BestMoveData {
  type: 'bestmove';
  move: string;        // '' for "bestmove (none)" — mate or stalemate
  ponder?: string;
}

interface StockfishError {
  code: 'NNUE_MISSING' | 'NNUE_LOAD_FAILED' | 'ENGINE_UNAVAILABLE'
      | 'ENGINE_CRITICAL_ERROR' | string;
  message: string;
}

interface StockfishConfig {
  throttling: { analysisInterval: number; messageInterval: number };
  events: { emitMessage: boolean; emitAnalysis: boolean; emitBestMove: boolean };
}

interface NnueFileStatus {
  name: string;          // "nn-1a298aa575a0.nnue"
  path: string;          // absolute path the engine is pointed at
  ready: boolean;        // present and verified
  bytes: number;
  partialBytes: number;  // bytes of a .part transfer waiting to be resumed
  approxBytes: number;
}

interface NnueStatus {
  ready: boolean;        // every network present and verified
  directory: string;
  files: NnueFileStatus[];
  approxTotalBytes: number;
  bytesOnDisk: number;
  freeBytes: number;
}

interface NnueProgress {
  name: string;
  index: number;              // file being fetched, among those still missing
  count: number;
  bytesWritten: number;       // current file
  bytesTotal: number;         // current file
  progress: number;           // 0..1, current file
  totalProgress: number;      // 0..1, whole download
  totalBytesWritten: number;  // whole download
  totalBytesTotal: number;    // whole download
}

interface NnueDownloadOptions {
  sources?: string[];
  allowMetered?: boolean;     // default false — Wi-Fi only
  onProgress?: (progress: NnueProgress) => void;
}
```

`bytesWritten`/`bytesTotal` are the file in flight; `totalBytesWritten`/
`totalBytesTotal` are the whole download and are the pair that belongs next to
`totalProgress`. Rendering the first pair beside the overall percentage reads as
"97% — 1 MB / 4 MB".

### Multiple instances

```javascript
import { Stockfish } from 'dawikk-stockfish'; // the class, not the default instance

const analysis = new Stockfish({ events: { emitMessage: false, emitAnalysis: true, emitBestMove: false } });
const game     = new Stockfish({ events: { emitMessage: false, emitAnalysis: false, emitBestMove: true } });
```

Both talk to the same native engine — instances differ in listeners and
throttling, not in engine state.

## Stockfish 19 notes

The vendored tree in `cpp/stockfish/` is upstream `sf_19` with two deliberate,
clearly marked local patches, both consequences of the engine sharing the app's
process instead of running as its own:

1. **`uci.{h,cpp}` — `terminate_on_critical_error()` no longer calls `std::exit(1)`**
   under `-DSTOCKFISH_EMBEDDED`. Upstream ends the process on a malformed FEN, an
   illegal move in `position ... moves`, or a bad `go` argument; here that would
   take the whole app down. The command is now reported and ignored, a rejected
   `go` is dropped rather than continued with half-parsed limits, and a rejected
   `position` resets the board to the start position (`Position::set()` clears
   before it validates, so the rejected position was left half-built). The native
   module also watches the output stream for `info string CRITICAL ERROR:` and
   raises `ENGINE_CRITICAL_ERROR`.
2. **`shm.h` — `USE_UNIX_SHM` is off on iOS.** It is enabled for `__APPLE__`
   upstream, but the sandbox makes the `/tmp` `mkdir` fail on every start, and
   cross-process shared memory buys a single-process embedded engine nothing.

Other build details worth knowing if you fork this:

- `-fconstexpr-steps=500000000` is **required** on Clang. Stockfish 19 builds its
  attack tables at compile time and blows past the default step budget;
  `attacks.cpp` fails without it. It is set in both `android/CMakeLists.txt` and
  the podspec.
- `NNUE_EMBEDDING_OFF` is what keeps the network out of the binary.
- `cpp/stockfish/universal/` is intentionally not vendored (runtime CPU dispatch
  binaries), and `main.cpp` is kept for fidelity with upstream but excluded from
  the build.
- `EvalFileSmall` no longer exists as a UCI option in Stockfish 19.

## License

**GPL-3.0.** This library includes the Stockfish sources, which are licensed
under the GNU General Public License version 3, so the library as a whole is
distributed under the same terms. The complete corresponding source — engine,
local patches and bridge — is this repository.

Stockfish: <https://github.com/official-stockfish/Stockfish> ·
<https://stockfishchess.org/>

// src/index.tsx
// TypeScript interface for Stockfish 19 (NNUE)
// Compatible with dawikk-stockfish API

import { StockfishModule, StockfishEventEmitter } from './native';
import NnueNetworks, {
  NnueDownloadOptions,
  NnueProgress,
  NnueStatus,
} from './nnue';

// Type definitions
export interface AnalysisOptions {
  depth?: number;
  multiPv?: number;
  movetime?: number;
  nodes?: number;
}

export interface AnalysisData {
  type: 'info' | 'bestmove';
  depth?: number;
  score?: number;
  mate?: number;
  bestMove?: string;
  line?: string;
  move?: string;
  evaluations?: string[];
  bestMoves?: string[];
  lines?: string[];
  depths?: number[];
  fen?: string;
  [key: string]: any;
}

export interface BestMoveData {
  type: 'bestmove';
  move: string;
  ponder?: string;
}

export interface StockfishError {
  // 'NNUE_MISSING'      - the network has not been downloaded yet (it is
  //                       fetched after install, not bundled). Recoverable:
  //                       call NnueNetworks.download() and init() again.
  // 'NNUE_LOAD_FAILED'  - a network file exists but could not be loaded
  // 'ENGINE_UNAVAILABLE'- no native engine library for this device's ABI, so
  //                       the engine can never start here. Not recoverable by
  //                       the user; callers should hide analysis rather than
  //                       suggest a reinstall.
  // 'ENGINE_CRITICAL_ERROR'
  //                     - Stockfish 19 rejected the last command: a malformed
  //                       FEN, an illegal move in "position ... moves", or a bad
  //                       "go" argument. The engine stays up (upstream would
  //                       exit(1); the embedded build does not), but that
  //                       command produced nothing — no bestmove is coming for
  //                       it, and after a rejected "position" the engine is
  //                       reset to the start position, so the next "go" needs a
  //                       fresh "position" first. This is a caller bug, not a
  //                       user-facing fault: log it and let the analysis
  //                       timeout do its work, rather than alerting about the
  //                       network file.
  code:
    | 'NNUE_MISSING'
    | 'NNUE_LOAD_FAILED'
    | 'ENGINE_UNAVAILABLE'
    | 'ENGINE_CRITICAL_ERROR'
    | string;
  message: string;
}

export interface StockfishConfig {
  throttling: {
    analysisInterval: number;
    messageInterval: number;
  };
  events: {
    emitMessage: boolean;
    emitAnalysis: boolean;
    emitBestMove: boolean;
  };
}

type MessageListener = (message: string) => void;
type AnalysisListener = (data: AnalysisData) => void;
type BestMoveListener = (data: BestMoveData) => void;
type ErrorListener = (error: StockfishError) => void;

const DEFAULT_CONFIG: StockfishConfig = {
  throttling: {
    analysisInterval: 100,
    messageInterval: 100,
  },
  events: {
    emitMessage: true,
    emitAnalysis: true,
    emitBestMove: true,
  }
};

class Stockfish {
  engineInitialized: boolean;
  private listeners: MessageListener[];
  private analysisListeners: AnalysisListener[];
  private bestMoveListeners: BestMoveListener[];
  private errorListeners: ErrorListener[];
  private lastError: StockfishError | null = null;
  // One start at a time. Two callers racing into init() used to start two
  // native engines; the second now joins the first's promise.
  private initPromise: Promise<boolean> | null = null;
  private outputSubscription: any;
  private analysisSubscription: any;
  private errorSubscription: any;

  private config: StockfishConfig;
  private messageBuffer: string[] = [];
  private analysisBuffer: Map<number, AnalysisData> = new Map();
  private lastBestMove: BestMoveData | null = null;
  private messageThrottleTimer: ReturnType<typeof setTimeout> | null = null;
  private analysisThrottleTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(config?: Partial<StockfishConfig>) {
    this.engineInitialized = false;
    this.listeners = [];
    this.analysisListeners = [];
    this.bestMoveListeners = [];
    this.errorListeners = [];

    this.config = {
      ...DEFAULT_CONFIG,
      throttling: {
        ...DEFAULT_CONFIG.throttling,
        ...(config?.throttling || {})
      },
      events: {
        ...DEFAULT_CONFIG.events,
        ...(config?.events || {})
      }
    };

    this.init = this.init.bind(this);
    this.sendCommand = this.sendCommand.bind(this);
    this.shutdown = this.shutdown.bind(this);
    this.addMessageListener = this.addMessageListener.bind(this);
    this.addAnalysisListener = this.addAnalysisListener.bind(this);
    this.addBestMoveListener = this.addBestMoveListener.bind(this);
    this.addErrorListener = this.addErrorListener.bind(this);
    this.removeMessageListener = this.removeMessageListener.bind(this);
    this.removeAnalysisListener = this.removeAnalysisListener.bind(this);
    this.removeBestMoveListener = this.removeBestMoveListener.bind(this);
    this.removeErrorListener = this.removeErrorListener.bind(this);
    this.handleOutput = this.handleOutput.bind(this);
    this.handleAnalysisOutput = this.handleAnalysisOutput.bind(this);
    this.handleError = this.handleError.bind(this);
    this.emitThrottledMessages = this.emitThrottledMessages.bind(this);
    this.emitThrottledAnalysis = this.emitThrottledAnalysis.bind(this);
    this.setConfig = this.setConfig.bind(this);

    this.outputSubscription = StockfishEventEmitter.addListener(
      'stockfish-output',
      this.handleOutput
    );

    this.analysisSubscription = StockfishEventEmitter.addListener(
      'stockfish-analyzed-output',
      this.handleAnalysisOutput
    );

    this.errorSubscription = StockfishEventEmitter.addListener(
      'stockfish-error',
      this.handleError
    );
  }

  setConfig(config: Partial<StockfishConfig>): void {
    this.config = {
      ...this.config,
      throttling: {
        ...this.config.throttling,
        ...(config.throttling || {})
      },
      events: {
        ...this.config.events,
        ...(config.events || {})
      }
    };
  }

  /**
   * Are the NNUE networks on the device? They are downloaded after install, so
   * this is false on a fresh install until ensureNetworks() has run.
   */
  async areNetworksReady(): Promise<boolean> {
    return NnueNetworks.isReady();
  }

  /**
   * Downloads the NNUE networks if they are missing. Safe to call repeatedly —
   * concurrent callers share one transfer.
   */
  async ensureNetworks(options?: NnueDownloadOptions): Promise<NnueStatus> {
    return NnueNetworks.download(options);
  }

  async isEngineAvailable(): Promise<boolean> {
    try {
      return await StockfishModule.isEngineAvailable();
    } catch (error) {
      console.error('Failed to check engine availability:', error);
      return false;
    }
  }

  async init(): Promise<boolean> {
    if (this.engineInitialized) {
      return true;
    }
    if (this.initPromise) {
      return this.initPromise;
    }

    this.initPromise = (async () => {
      try {
        await StockfishModule.initEngine();
        this.engineInitialized = true;
        // A replayed error belongs to the failed start, not to this one. Left
        // set, every later error listener was handed NNUE_MISSING after the
        // networks had long been downloaded, and the app answered it with a
        // pointless engine restart.
        this.lastError = null;
        return true;
      } catch (error) {
        console.error('Failed to initialize Stockfish engine:', error);
        return false;
      } finally {
        this.initPromise = null;
      }
    })();
    return this.initPromise;
  }

  async sendCommand(command: string): Promise<boolean> {
    if (!this.engineInitialized) {
      await this.init();
    }

    try {
      return await StockfishModule.sendCommand(command);
    } catch (error) {
      console.error('Failed to send command to Stockfish:', error);
      return false;
    }
  }

  async shutdown(): Promise<boolean> {
    if (this.messageThrottleTimer) {
      clearTimeout(this.messageThrottleTimer);
      this.messageThrottleTimer = null;
    }

    if (this.analysisThrottleTimer) {
      clearTimeout(this.analysisThrottleTimer);
      this.analysisThrottleTimer = null;
    }

    if (!this.engineInitialized) {
      return true;
    }

    try {
      await StockfishModule.shutdownEngine();
      this.engineInitialized = false;
      return true;
    } catch (error) {
      console.error('Failed to shutdown Stockfish engine:', error);
      return false;
    }
  }

  private emitThrottledMessages(): void {
    if (this.messageBuffer.length === 0 || !this.config.events.emitMessage) {
      this.messageThrottleTimer = null;
      return;
    }

    const latestMessage = this.messageBuffer[this.messageBuffer.length - 1];
    this.listeners.forEach(listener => listener(latestMessage));
    this.messageBuffer = [];

    this.messageThrottleTimer = setTimeout(
      this.emitThrottledMessages,
      this.config.throttling.messageInterval
    );
  }

  private emitThrottledAnalysis(): void {
    if (this.analysisBuffer.size === 0 ||
        (!this.config.events.emitAnalysis && !this.config.events.emitBestMove)) {
      this.analysisThrottleTimer = null;
      return;
    }

    if (this.config.events.emitAnalysis) {
      const analysisData: AnalysisData = {
        type: 'info',
        bestMoves: [],
        evaluations: [],
        lines: [],
        depths: []
      };

      this.analysisBuffer.forEach((data, pvNumber) => {
        if (data.bestMove) analysisData.bestMoves?.push(data.bestMove);
        // Exactly one entry per PV, in the `M<n>` form the JS consumers parse —
        // pushing both a cp score and a mate would desync evaluations[] from
        // bestMoves[]/lines[].
        if (data.mate !== undefined) analysisData.evaluations?.push(`M${data.mate}`);
        else if (data.score !== undefined) analysisData.evaluations?.push(data.score.toString());
        if (data.line) analysisData.lines?.push(data.line);
        if (data.depth) analysisData.depths?.push(data.depth);

        if (this.analysisBuffer.size === 1) {
          analysisData.bestMove = data.bestMove;
          analysisData.score = data.score;
          analysisData.mate = data.mate;
          analysisData.line = data.line;
          analysisData.depth = data.depth;
        }
      });

      this.analysisListeners.forEach(listener => listener(analysisData));
    }

    this.analysisBuffer.clear();

    this.analysisThrottleTimer = setTimeout(
      this.emitThrottledAnalysis,
      this.config.throttling.analysisInterval
    );
  }

  handleOutput(message: string): void {
    if (!this.config.events.emitMessage) return;

    this.messageBuffer.push(message);

    if (this.messageThrottleTimer === null) {
      this.messageThrottleTimer = setTimeout(
        this.emitThrottledMessages,
        this.config.throttling.messageInterval
      );
    }
  }

  handleAnalysisOutput(data: AnalysisData | BestMoveData): void {
    if (data.type === 'bestmove') {
      // "bestmove (none)" is the engine's answer for a mated or stalemated
      // position. Nothing downstream wants the literal string as a move.
      if ((data as BestMoveData).move === '(none)') {
        data = { ...(data as BestMoveData), move: '' };
      }
      this.lastBestMove = data as BestMoveData;

      // IMPORTANT: Emit any buffered analysis data BEFORE clearing
      // This ensures we don't lose analysis data when bestmove arrives quickly
      if (this.analysisBuffer.size > 0 && this.config.events.emitAnalysis) {
        const analysisData: AnalysisData = {
          type: 'info',
          bestMoves: [],
          evaluations: [],
          lines: [],
          depths: []
        };

        this.analysisBuffer.forEach((bufferedData, pvNumber) => {
          if (bufferedData.bestMove) analysisData.bestMoves?.push(bufferedData.bestMove);
          if (bufferedData.mate !== undefined) analysisData.evaluations?.push(`M${bufferedData.mate}`);
          else if (bufferedData.score !== undefined) analysisData.evaluations?.push(bufferedData.score.toString());
          if (bufferedData.line) analysisData.lines?.push(bufferedData.line);
          if (bufferedData.depth) analysisData.depths?.push(bufferedData.depth);

          if (this.analysisBuffer.size === 1) {
            analysisData.bestMove = bufferedData.bestMove;
            analysisData.score = bufferedData.score;
            analysisData.mate = bufferedData.mate;
            analysisData.line = bufferedData.line;
            analysisData.depth = bufferedData.depth;
          }
        });

        // Emit the final analysis data before bestmove
        this.analysisListeners.forEach(listener => listener(analysisData));
      }

      if (this.config.events.emitBestMove) {
        this.bestMoveListeners.forEach(listener =>
          listener(data as BestMoveData));
      }

      this.analysisBuffer.clear();

      if (this.analysisThrottleTimer) {
        clearTimeout(this.analysisThrottleTimer);
        this.analysisThrottleTimer = null;
      }
    } else if (data.type === 'info') {
      const multiPv = (data as any).multipv || 1;
      // A line without a pv (a "currmove" progress line, or a bare depth)
      // must not replace a buffered line that has one: it carried no multipv,
      // so it landed on PV 1 and wiped the best line out of the next emit.
      const info = data as AnalysisData;
      if (!info.bestMove && !info.line && this.analysisBuffer.has(multiPv)) {
        return;
      }
      this.analysisBuffer.set(multiPv, info);

      if (this.analysisThrottleTimer === null) {
        this.analysisThrottleTimer = setTimeout(
          this.emitThrottledAnalysis,
          this.config.throttling.analysisInterval
        );
      }
    }
  }

  handleError(error: StockfishError): void {
    // Remember the latest error so listeners that subscribe after it fired
    // (e.g. a "network missing" error raised during init) still receive it.
    this.lastError = error;
    this.errorListeners.forEach(listener => listener(error));
  }

  addMessageListener(listener: MessageListener): () => void {
    this.listeners.push(listener);
    return () => this.removeMessageListener(listener);
  }

  addErrorListener(listener: ErrorListener): () => void {
    this.errorListeners.push(listener);
    if (this.lastError) {
      listener(this.lastError);
    }
    return () => this.removeErrorListener(listener);
  }

  removeErrorListener(listener: ErrorListener): void {
    const index = this.errorListeners.indexOf(listener);
    if (index !== -1) {
      this.errorListeners.splice(index, 1);
    }
  }

  addAnalysisListener(listener: AnalysisListener): () => void {
    this.analysisListeners.push(listener);
    return () => this.removeAnalysisListener(listener);
  }

  addBestMoveListener(listener: BestMoveListener): () => void {
    this.bestMoveListeners.push(listener);
    return () => this.removeBestMoveListener(listener);
  }

  removeMessageListener(listener: MessageListener): void {
    const index = this.listeners.indexOf(listener);
    if (index !== -1) {
      this.listeners.splice(index, 1);
    }
  }

  removeAnalysisListener(listener: AnalysisListener): void {
    const index = this.analysisListeners.indexOf(listener);
    if (index !== -1) {
      this.analysisListeners.splice(index, 1);
    }
  }

  removeBestMoveListener(listener: BestMoveListener): void {
    const index = this.bestMoveListeners.indexOf(listener);
    if (index !== -1) {
      this.bestMoveListeners.splice(index, 1);
    }
  }

  async analyzePosition(fen: string, options: AnalysisOptions = {}): Promise<void> {
    const {
      depth = 20,
      multiPv = 1,
      movetime,
      nodes
    } = options;

    await this.sendCommand('uci');
    await this.sendCommand('isready');
    await this.sendCommand('ucinewgame');
    await this.sendCommand(`position fen ${fen}`);

    let goCommand = `go depth ${depth} multipv ${multiPv}`;
    if (movetime) goCommand += ` movetime ${movetime}`;
    if (nodes) goCommand += ` nodes ${nodes}`;

    await this.sendCommand(goCommand);
  }

  async stopAnalysis(): Promise<void> {
    await this.sendCommand('stop');
  }

  async getComputerMove(fen: string, movetime: number = 1000, depth: number = 15): Promise<void> {
    await this.sendCommand('uci');
    await this.sendCommand('isready');
    await this.sendCommand(`position fen ${fen}`);
    await this.sendCommand(`go movetime ${movetime} depth ${depth}`);
  }

  destroy(): void {
    if (this.messageThrottleTimer) {
      clearTimeout(this.messageThrottleTimer);
      this.messageThrottleTimer = null;
    }

    if (this.analysisThrottleTimer) {
      clearTimeout(this.analysisThrottleTimer);
      this.analysisThrottleTimer = null;
    }

    this.shutdown().catch(console.error);
    this.outputSubscription.remove();
    this.analysisSubscription.remove();
    this.errorSubscription.remove();
    this.listeners = [];
    this.analysisListeners = [];
    this.bestMoveListeners = [];
    this.errorListeners = [];
  }
}

export default new Stockfish();
export { Stockfish, StockfishEventEmitter, NnueNetworks };
export type { NnueDownloadOptions, NnueProgress, NnueStatus };

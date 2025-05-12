import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

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

// Configuration for event throttling and filtering
export interface StockfishConfig {
  // Throttling intervals (in ms)
  throttling: {
    analysisInterval: number;  // Time between analysis event emissions
    messageInterval: number;   // Time between message event emissions
  };
  // Event emission control
  events: {
    emitMessage: boolean;      // Whether to emit raw message events
    emitAnalysis: boolean;     // Whether to emit analysis events
    emitBestMove: boolean;     // Whether to emit bestMove events
  };
}

type MessageListener = (message: string) => void;
type AnalysisListener = (data: AnalysisData) => void;
type BestMoveListener = (data: BestMoveData) => void;

// Default configuration
const DEFAULT_CONFIG: StockfishConfig = {
  throttling: {
    analysisInterval: 100,   // Default: 100ms between analysis events
    messageInterval: 100,    // Default: 100ms between message events
  },
  events: {
    emitMessage: true,
    emitAnalysis: true,
    emitBestMove: true,
  }
};

// Linking error handling
const LINKING_ERROR =
  `The package 'dawikk-stock' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

// Get the native module
const StockfishModule = NativeModules.RNStockfishModule
  ? NativeModules.RNStockfishModule
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

// Create event emitter
export const StockfishEventEmitter = new NativeEventEmitter(StockfishModule);

class Stockfish {
  // Class properties
  engineInitialized: boolean;
  private listeners: MessageListener[];
  private analysisListeners: AnalysisListener[];
  private bestMoveListeners: BestMoveListener[];
  private outputSubscription: any;
  private analysisSubscription: any;
  
  // Throttling properties
  private config: StockfishConfig;
  private messageBuffer: string[] = [];
  private analysisBuffer: Map<number, AnalysisData> = new Map(); // MultiPV handling with Map
  private lastBestMove: BestMoveData | null = null;
  private messageThrottleTimer: ReturnType<typeof setTimeout> | null = null;
  private analysisThrottleTimer: ReturnType<typeof setTimeout> | null = null;
  
  constructor(config?: Partial<StockfishConfig>) {
    this.engineInitialized = false;
    this.listeners = [];
    this.analysisListeners = [];
    this.bestMoveListeners = [];
    
    // Merge provided config with defaults
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
    
    // Bind methods
    this.init = this.init.bind(this);
    this.sendCommand = this.sendCommand.bind(this);
    this.shutdown = this.shutdown.bind(this);
    this.addMessageListener = this.addMessageListener.bind(this);
    this.addAnalysisListener = this.addAnalysisListener.bind(this);
    this.addBestMoveListener = this.addBestMoveListener.bind(this);
    this.removeMessageListener = this.removeMessageListener.bind(this);
    this.removeAnalysisListener = this.removeAnalysisListener.bind(this);
    this.removeBestMoveListener = this.removeBestMoveListener.bind(this);
    this.handleOutput = this.handleOutput.bind(this);
    this.handleAnalysisOutput = this.handleAnalysisOutput.bind(this);
    this.emitThrottledMessages = this.emitThrottledMessages.bind(this);
    this.emitThrottledAnalysis = this.emitThrottledAnalysis.bind(this);
    this.setConfig = this.setConfig.bind(this);
    
    // Set up event subscriptions
    this.outputSubscription = StockfishEventEmitter.addListener(
      'stockfish-output',
      this.handleOutput
    );
    
    this.analysisSubscription = StockfishEventEmitter.addListener(
      'stockfish-analyzed-output',
      this.handleAnalysisOutput
    );
  }
  
  /**
   * Updates the Stockfish configuration.
   * @param config Partial configuration to update
   */
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
   * Initializes the Stockfish engine.
   * @returns Promise resolved as true if initialization succeeded.
   */
  async init(): Promise<boolean> {
    if (this.engineInitialized) {
      return true;
    }
    
    try {
      await StockfishModule.initEngine();
      this.engineInitialized = true;
      return true;
    } catch (error) {
      console.error('Failed to initialize Stockfish engine:', error);
      return false;
    }
  }
  
  /**
   * Sends a UCI command to the Stockfish engine.
   * @param command UCI command to send.
   * @returns Promise resolved as true if the command was sent.
   */
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
  
  /**
   * Shuts down the Stockfish engine.
   * @returns Promise resolved as true if shutdown succeeded.
   */
  async shutdown(): Promise<boolean> {
    if (!this.engineInitialized) {
      return true;
    }
    
    // Clear any pending throttle timers
    if (this.messageThrottleTimer) {
      clearTimeout(this.messageThrottleTimer);
      this.messageThrottleTimer = null;
    }
    
    if (this.analysisThrottleTimer) {
      clearTimeout(this.analysisThrottleTimer);
      this.analysisThrottleTimer = null;
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
  
  /**
   * Emits throttled message events based on configuration.
   */
  private emitThrottledMessages(): void {
    if (this.messageBuffer.length === 0 || !this.config.events.emitMessage) {
      this.messageThrottleTimer = null;
      return;
    }
    
    // Only emit the latest message (or all if needed)
    const latestMessage = this.messageBuffer[this.messageBuffer.length - 1];
    this.listeners.forEach(listener => listener(latestMessage));
    
    // Clear buffer after emitting
    this.messageBuffer = [];
    
    // Schedule next emission if needed
    this.messageThrottleTimer = setTimeout(
      this.emitThrottledMessages,
      this.config.throttling.messageInterval
    );
  }
  
  /**
   * Emits throttled analysis events based on configuration.
   * Handles MultiPV by emitting latest result for each PV.
   */
  private emitThrottledAnalysis(): void {
    if (this.analysisBuffer.size === 0 || 
        (!this.config.events.emitAnalysis && !this.config.events.emitBestMove)) {
      this.analysisThrottleTimer = null;
      return;
    }
    
    // For analysis data, we want to emit all MultiPV values we've collected
    if (this.config.events.emitAnalysis) {
      const analysisData: AnalysisData = {
        type: 'info',
        bestMoves: [],
        evaluations: [],
        lines: [],
        depths: []
      };
      
      // Convert MultiPV Map to arrays in the analysis data
      this.analysisBuffer.forEach((data, pvNumber) => {
        if (data.bestMove) analysisData.bestMoves?.push(data.bestMove);
        if (data.score !== undefined) analysisData.evaluations?.push(data.score.toString());
        if (data.mate !== undefined) analysisData.evaluations?.push(`mate ${data.mate}`);
        if (data.line) analysisData.lines?.push(data.line);
        if (data.depth) analysisData.depths?.push(data.depth);
        
        // For single PV, also include individual fields
        if (this.analysisBuffer.size === 1) {
          analysisData.bestMove = data.bestMove;
          analysisData.score = data.score;
          analysisData.mate = data.mate;
          analysisData.line = data.line;
          analysisData.depth = data.depth;
        }
      });
      
      // Emit consolidated analysis data
      this.analysisListeners.forEach(listener => listener(analysisData));
    }
    
    // Clear analysis buffer after emitting
    this.analysisBuffer.clear();
    
    // Schedule next emission if needed
    this.analysisThrottleTimer = setTimeout(
      this.emitThrottledAnalysis,
      this.config.throttling.analysisInterval
    );
  }
  
  /**
   * Handles output messages from the engine.
   * @param message Message from the Stockfish engine.
   */
  handleOutput(message: string): void {
    if (!this.config.events.emitMessage) return;
    
    // Add message to buffer
    this.messageBuffer.push(message);
    
    // Start throttle timer if not running
    if (this.messageThrottleTimer === null) {
      this.messageThrottleTimer = setTimeout(
        this.emitThrottledMessages,
        this.config.throttling.messageInterval
      );
    }
  }
  
  /**
   * Handles analyzed output data from the engine.
   * @param data Analyzed data from the Stockfish engine.
   */
  handleAnalysisOutput(data: AnalysisData | BestMoveData): void {
    if (data.type === 'bestmove') {
      // Store latest bestmove
      this.lastBestMove = data as BestMoveData;
      
      // Immediately emit bestMove events if configured
      if (this.config.events.emitBestMove) {
        this.bestMoveListeners.forEach(listener => 
          listener(data as BestMoveData));
      }
      
      // Clear analysis buffer when bestmove arrives
      this.analysisBuffer.clear();
      
      // Cancel any pending analysis emissions
      if (this.analysisThrottleTimer) {
        clearTimeout(this.analysisThrottleTimer);
        this.analysisThrottleTimer = null;
      }
    } else if (data.type === 'info') {
      // Extract multipv number (default to 1 if not present)
      const multiPv = (data as any).multipv || 1;
      
      // Update analysis buffer with latest data for this PV
      this.analysisBuffer.set(multiPv, data as AnalysisData);
      
      // Start throttle timer if not running
      if (this.analysisThrottleTimer === null) {
        this.analysisThrottleTimer = setTimeout(
          this.emitThrottledAnalysis,
          this.config.throttling.analysisInterval
        );
      }
    }
  }
  
  /**
   * Adds a message listener.
   * @param listener Function to call for each message.
   * @returns Function to remove the listener.
   */
  addMessageListener(listener: MessageListener): () => void {
    this.listeners.push(listener);
    return () => this.removeMessageListener(listener);
  }
  
  /**
   * Adds an analysis listener.
   * @param listener Function to call for each analysis result.
   * @returns Function to remove the listener.
   */
  addAnalysisListener(listener: AnalysisListener): () => void {
    this.analysisListeners.push(listener);
    return () => this.removeAnalysisListener(listener);
  }
  
  /**
   * Adds a bestmove listener.
   * @param listener Function to call for each bestmove.
   * @returns Function to remove the listener.
   */
  addBestMoveListener(listener: BestMoveListener): () => void {
    this.bestMoveListeners.push(listener);
    return () => this.removeBestMoveListener(listener);
  }
  
  /**
   * Removes a message listener.
   * @param listener Listener to remove.
   */
  removeMessageListener(listener: MessageListener): void {
    const index = this.listeners.indexOf(listener);
    if (index !== -1) {
      this.listeners.splice(index, 1);
    }
  }
  
  /**
   * Removes an analysis listener.
   * @param listener Listener to remove.
   */
  removeAnalysisListener(listener: AnalysisListener): void {
    const index = this.analysisListeners.indexOf(listener);
    if (index !== -1) {
      this.analysisListeners.splice(index, 1);
    }
  }
  
  /**
   * Removes a bestmove listener.
   * @param listener Listener to remove.
   */
  removeBestMoveListener(listener: BestMoveListener): void {
    const index = this.bestMoveListeners.indexOf(listener);
    if (index !== -1) {
      this.bestMoveListeners.splice(index, 1);
    }
  }
  
  /**
   * Helper method to set position and start analysis.
   * @param fen FEN notation of position to analyze.
   * @param options Analysis options.
   */
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
  
  /**
   * Helper method to stop ongoing analysis.
   */
  async stopAnalysis(): Promise<void> {
    await this.sendCommand('stop');
  }
  
  /**
   * Helper method to get computer move in a game.
   * @param fen FEN notation of current position.
   * @param movetime Time in milliseconds for the move (default 1000ms).
   * @param depth Analysis depth (default 15).
   */
  async getComputerMove(fen: string, movetime: number = 1000, depth: number = 15): Promise<void> {
    await this.sendCommand('uci');
    await this.sendCommand('isready');
    await this.sendCommand(`position fen ${fen}`);
    await this.sendCommand(`go movetime ${movetime} depth ${depth}`);
  }
  
  /**
   * Cleans up resources when done with the library.
   */
  destroy(): void {
    // Clear any pending throttle timers
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
    this.listeners = [];
    this.analysisListeners = [];
    this.bestMoveListeners = [];
  }
}

// Export a single instance, but allow custom configuration
export default new Stockfish();

// Also export the class for users who want to create custom instances
export { Stockfish };
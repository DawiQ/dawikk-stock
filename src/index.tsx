import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

// Definicje typów
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

type MessageListener = (message: string) => void;
type AnalysisListener = (data: AnalysisData) => void;
type BestMoveListener = (data: BestMoveData) => void;

// Obsługa błędu linkowania
const LINKING_ERROR =
  `The package 'react-native-stockfish' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

// Pobranie modułu natywnego
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

// Utworzenie emitera zdarzeń
export const StockfishEventEmitter = new NativeEventEmitter(StockfishModule);

class Stockfish {
  // Właściwości klasy
  engineInitialized: boolean;
  private listeners: MessageListener[];
  private analysisListeners: AnalysisListener[];
  private bestMoveListeners: BestMoveListener[];
  private outputSubscription: any;
  private analysisSubscription: any;
  
  constructor() {
    this.engineInitialized = false;
    this.listeners = [];
    this.analysisListeners = [];
    this.bestMoveListeners = [];
    
    // Powiązanie metod
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
    
    // Konfiguracja subskrypcji zdarzeń
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
   * Inicjalizuje silnik Stockfish.
   * @returns Promise rozwiązywany jako true jeśli inicjalizacja powiodła się.
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
   * Wysyła komendę UCI do silnika Stockfish.
   * @param command Komenda UCI do wysłania.
   * @returns Promise rozwiązywany jako true jeśli komenda została wysłana.
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
   * Zamyka silnik Stockfish.
   * @returns Promise rozwiązywany jako true jeśli zamknięcie powiodło się.
   */
  async shutdown(): Promise<boolean> {
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
  
  /**
   * Obsługuje wiadomości wyjściowe z silnika.
   * @param message Wiadomość z silnika Stockfish.
   */
  handleOutput(message: string): void {
    this.listeners.forEach(listener => listener(message));
  }
  
  /**
   * Obsługuje przeanalizowane dane wyjściowe z silnika.
   * @param data Przeanalizowane dane z silnika Stockfish.
   */
  handleAnalysisOutput(data: AnalysisData | BestMoveData): void {
    // Wysyłamy dane do wszystkich słuchaczy analizy
    this.analysisListeners.forEach(listener => listener(data as AnalysisData));
    
    // Dodatkowo wysyłamy zdarzenia bestmove do dedykowanych słuchaczy
    if (data.type === 'bestmove') {
      this.bestMoveListeners.forEach(listener => 
        listener(data as BestMoveData));
    }
  }
  
  /**
   * Dodaje nasłuchiwacz wiadomości.
   * @param listener Funkcja do wywołania dla każdej wiadomości.
   * @returns Funkcja usuwająca nasłuchiwacz.
   */
  addMessageListener(listener: MessageListener): () => void {
    this.listeners.push(listener);
    return () => this.removeMessageListener(listener);
  }
  
  /**
   * Dodaje nasłuchiwacz analizy.
   * @param listener Funkcja do wywołania dla każdego rezultatu analizy.
   * @returns Funkcja usuwająca nasłuchiwacz.
   */
  addAnalysisListener(listener: AnalysisListener): () => void {
    this.analysisListeners.push(listener);
    return () => this.removeAnalysisListener(listener);
  }
  
  /**
   * Dodaje nasłuchiwacz ruchów komputera.
   * @param listener Funkcja do wywołania dla każdego ruchu komputera.
   * @returns Funkcja usuwająca nasłuchiwacz.
   */
  addBestMoveListener(listener: BestMoveListener): () => void {
    this.bestMoveListeners.push(listener);
    return () => this.removeBestMoveListener(listener);
  }
  
  /**
   * Usuwa nasłuchiwacz wiadomości.
   * @param listener Nasłuchiwacz do usunięcia.
   */
  removeMessageListener(listener: MessageListener): void {
    const index = this.listeners.indexOf(listener);
    if (index !== -1) {
      this.listeners.splice(index, 1);
    }
  }
  
  /**
   * Usuwa nasłuchiwacz analizy.
   * @param listener Nasłuchiwacz do usunięcia.
   */
  removeAnalysisListener(listener: AnalysisListener): void {
    const index = this.analysisListeners.indexOf(listener);
    if (index !== -1) {
      this.analysisListeners.splice(index, 1);
    }
  }
  
  /**
   * Usuwa nasłuchiwacz ruchów komputera.
   * @param listener Nasłuchiwacz do usunięcia.
   */
  removeBestMoveListener(listener: BestMoveListener): void {
    const index = this.bestMoveListeners.indexOf(listener);
    if (index !== -1) {
      this.bestMoveListeners.splice(index, 1);
    }
  }
  
  /**
   * Metoda pomocnicza do ustawienia pozycji i rozpoczęcia analizy.
   * @param fen Notacja FEN pozycji do analizy.
   * @param options Opcje analizy.
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
   * Metoda pomocnicza do zatrzymania trwającej analizy.
   */
  async stopAnalysis(): Promise<void> {
    await this.sendCommand('stop');
  }
  
  /**
   * Metoda pomocnicza do uzyskania ruchu komputera w grze.
   * @param fen Notacja FEN aktualnej pozycji.
   * @param movetime Czas w milisekundach na ruch (domyślnie 1000ms).
   * @param depth Głębokość analizy (domyślnie 15).
   */
  async getComputerMove(fen: string, movetime: number = 1000, depth: number = 15): Promise<void> {
    await this.sendCommand('uci');
    await this.sendCommand('isready');
    await this.sendCommand(`position fen ${fen}`);
    await this.sendCommand(`go movetime ${movetime} depth ${depth}`);
  }
  
  /**
   * Czyści zasoby po zakończeniu korzystania z biblioteki.
   */
  destroy(): void {
    this.shutdown().catch(console.error);
    this.outputSubscription.remove();
    this.analysisSubscription.remove();
    this.listeners = [];
    this.analysisListeners = [];
    this.bestMoveListeners = [];
  }
}

// Eksportowanie pojedynczej instancji
export default new Stockfish();
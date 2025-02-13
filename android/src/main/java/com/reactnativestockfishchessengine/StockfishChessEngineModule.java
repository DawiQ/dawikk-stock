package com.reactnativestockfishchessengine;

import androidx.annotation.NonNull;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.Arguments;
import java.util.*;

@ReactModule(name = StockfishChessEngineModule.NAME)
public class StockfishChessEngineModule extends ReactContextBaseJavaModule {
  public static final String NAME = "StockfishChessEngine";
  protected Thread engineLineReader;
  protected Thread mainLoopThread;
  protected ReactApplicationContext reactContext;

  private int lastDepth = 0;
  private int lastDepthPV2 = 0;
  private boolean isWhiteToMove = true;
  private Map<Integer, PVInfo> pvLines = new HashMap<>();
  
  private static final int INITIAL_BUFFER_CAPACITY = 1024;
  private static final int READ_TIMEOUT_MS = 800; // Zmniejszony timeout
  private StringBuilder outputBuffer;
  private String currentFen = "";


  private Timer batchTimer = null;
  private static final int BATCH_DELAY = 800; // ms
  private List<WritableMap> eventBatch = new ArrayList<>();

  private static class PVInfo {
    String evaluation;
    String bestMove;
    String line;
    int depth;
    
    PVInfo(String evaluation, String bestMove, String line, int depth) {
      this.evaluation = evaluation;
      this.bestMove = bestMove;
      this.line = line;
      this.depth = depth;
    }
  }

  public StockfishChessEngineModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
    this.outputBuffer = new StringBuilder(INITIAL_BUFFER_CAPACITY);
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  protected void loopReadingEngineOutput() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        String output = readStdOut();
        if (output != null && !output.isEmpty()) {
          outputBuffer.append(output);
          
          // Przetwarzaj kompletne linie
          processCompleteLines();
        }
        
        // Krótsze czekanie między odczytami
        Thread.sleep(READ_TIMEOUT_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  private void processCompleteLines() {
    int newlineIndex;
    List<WritableMap> currentBatch = new ArrayList<>();
    
    while ((newlineIndex = outputBuffer.indexOf("\n")) != -1) {
        String line = outputBuffer.substring(0, newlineIndex).trim();
        outputBuffer.delete(0, newlineIndex + 1);
        
        if (!line.isEmpty()) {
            WritableMap result = processEngineOutput(line);
            if (result != null && result.hasKey("type")) {
                // Jeśli to bestmove, wyślij natychmiast wszystko co mamy
                if ("bestmove".equals(result.getString("type"))) {
                    if (!currentBatch.isEmpty()) {
                        sendBatchedEvent(currentBatch);
                    }
                    sendEvent("stockfish-analyzed-output", result);
                    currentBatch.clear();
                } else {
                    currentBatch.add(result);
                }
            }
        }
    }
    
    // Jeśli mamy jakieś eventy w batchu, zaplanuj ich wysłanie
    if (!currentBatch.isEmpty()) {
        scheduleBatchedEvent(currentBatch);
    }
  }

  private void scheduleBatchedEvent(List<WritableMap> events) {
    // Anuluj poprzedni timer jeśli istnieje
    if (batchTimer != null) {
        batchTimer.cancel();
    }
    
    // Stwórz nowy timer
    batchTimer = new Timer();
    batchTimer.schedule(new TimerTask() {
        @Override
        public void run() {
            reactContext.runOnUiQueueThread(() -> {
                sendBatchedEvent(events);
            });
        }
    }, BATCH_DELAY);
  }

  private void sendBatchedEvent(List<WritableMap> events) {
    if (events.isEmpty()) return;
    
    // Weź tylko ostatni event z każdej głębokości
    Map<Integer, WritableMap> latestByDepth = new HashMap<>();
    for (WritableMap event : events) {
        if ("info".equals(event.getString("type"))) {
            int depth = event.getInt("depth");
            latestByDepth.put(depth, event);
        }
    }
    
    // Weź event z największą głębokością
    WritableMap latestEvent = latestByDepth.values().stream()
        .max((a, b) -> Integer.compare(a.getInt("depth"), b.getInt("depth")))
        .orElse(events.get(events.size() - 1));
    
    sendEvent("stockfish-analyzed-output", latestEvent);
  }

  private WritableMap processEngineOutput(String line) {
    // Ignoruj linie z "currmove" aby zmniejszyć ilość eventów
    if (line.contains("currmove")) {
        return null;
    }

    WritableMap result = Arguments.createMap();

    // Priorytetowe przetwarzanie "bestmove"
    if (line.startsWith("bestmove")) {
        String[] parts = line.split(" ");
        if (parts.length > 1) {
            result.putString("type", "bestmove");
            result.putString("move", parts[1]);
            // Wyczyść zapisane linie PV przy nowym ruchu
            pvLines.clear();
            lastDepth = 0;
            result.putString("fen", currentFen);
            return result;
        }
    }

    if (line.contains("info") && line.contains("score") && line.contains("pv")) {
        String[] parts = line.split(" ");
        
        int scoreIndex = -1, depthIndex = -1, pvIndex = -1, multipvIndex = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("score")) scoreIndex = i;
            if (parts[i].equals("depth")) depthIndex = i;
            if (parts[i].equals("pv")) pvIndex = i;
            if (parts[i].equals("multipv")) multipvIndex = i;
        }

        // Zmiana: pozwalamy na brak multipv dla kompatybilności
        if (scoreIndex != -1 && depthIndex != -1 && pvIndex != -1) {
            // Sprawdź czy pierwsza część PV (bestmove) jest legalna dla aktualnego FEN
            String[] pvMoves = parts[pvIndex + 1].split(" ");
            if (pvMoves.length > 0) {
                StringBuilder potentialPositionCmd = new StringBuilder("position fen ");
                potentialPositionCmd.append(currentFen);
                potentialPositionCmd.append(" moves ");
                potentialPositionCmd.append(pvMoves[0]);
                
                // Jeśli nie możemy zweryfikować ruchu dla aktualnego FEN, pomijamy ten output
                if (!isMoveLegalForCurrentFen(pvMoves[0])) {
                    return null;
                }
            }

            String scoreType = parts[scoreIndex + 1];
            String scoreValue = parts[scoreIndex + 2];
            
            int depth = Integer.parseInt(parts[depthIndex + 1]);
            int pvNumber = multipvIndex != -1 ? Integer.parseInt(parts[multipvIndex + 1]) : 1;
            
            lastDepth = depth;
            lastDepthPV2 = depth;

            String evaluation = "";
            if (scoreType.equals("cp")) {
                double score = Double.parseDouble(scoreValue) / 100.0;
                evaluation = String.format("%.2f", isWhiteToMove ? score : -score);
                evaluation = evaluation.replace(',', '.');
            } else { // mate
                evaluation = (scoreValue.startsWith("-") ? "-M" : "M") + Math.abs(Integer.parseInt(scoreValue));
                if (!isWhiteToMove) {
                    evaluation = evaluation.startsWith("-") ? evaluation.substring(1) : "-" + evaluation;
                }
            }
            
            StringBuilder pvBuilder = new StringBuilder();
            for (int i = pvIndex + 1; i < parts.length; i++) {
                pvBuilder.append(parts[i]).append(" ");
            }
            String pv = pvBuilder.toString().trim();
            String bestMove = pv.split(" ")[0];

            // Aktualizuj lub dodaj nową linię PV
            pvLines.put(pvNumber, new PVInfo(evaluation, bestMove, pv, depth));
            
            // Przygotuj tablice dla wszystkich aktualnych linii
            WritableArray evaluations = Arguments.createArray();
            WritableArray bestMoves = Arguments.createArray();
            WritableArray lines = Arguments.createArray();
            WritableArray depths = Arguments.createArray();
            
            // Sortuj linie według numeru PV
            List<Map.Entry<Integer, PVInfo>> sortedEntries = new ArrayList<>(pvLines.entrySet());
            Collections.sort(sortedEntries, (a, b) -> a.getKey() - b.getKey());
            
            // Dodaj wszystkie linie do tablic
            for (Map.Entry<Integer, PVInfo> entry : sortedEntries) {
                PVInfo info = entry.getValue();
                evaluations.pushString(info.evaluation);
                bestMoves.pushString(info.bestMove);
                lines.pushString(info.line);
                depths.pushInt(info.depth);
            }

            result.putString("type", "info");
            result.putArray("evaluations", evaluations);
            result.putArray("bestMoves", bestMoves);
            result.putArray("lines", lines);
            result.putArray("depths", depths);
            result.putInt("depth", lastDepth);
            result.putString("fen", currentFen);
            return result;
        }
    }

    return null;
}

// Pomocnicza metoda do sprawdzania legalności ruchu
private boolean isMoveLegalForCurrentFen(String move) {
    try {
        // Sprawdź czy ruch pochodzi od właściwej strony
        String[] fenParts = currentFen.split(" ");
        if (fenParts.length < 2) return false;
        
        boolean isWhiteMove = fenParts[1].equals("w");
        
        // Jeśli aktualny FEN wskazuje na ruch białych, ale otrzymujemy analizę dla czarnych (lub odwrotnie)
        // wtedy ignorujemy tę analizę
        if (isWhiteMove != isWhiteToMove) {
            return false;
        }

        // Możesz dodać tutaj dodatkową walidację ruchu jeśli potrzebna
        return true;
    } catch (Exception e) {
        return false;
    }
}

  private void sendEvent(String eventName, WritableMap params) {
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
      .emit(eventName, params);
  }

  static {
    try {
      System.loadLibrary("stockfish");
    } catch (Exception ignored) {
      System.out.println("Failed to load stockfish");
    }
  }

  @ReactMethod
  public void mainLoop(Promise promise) {
    init();
    engineLineReader = new Thread(new Runnable() {
      public void run() {
        loopReadingEngineOutput();
      }
    });
    engineLineReader.start();
    mainLoopThread = new Thread(new Runnable() {
      public void run() {
        main();
      }
    });
    mainLoopThread.start();
    promise.resolve(null);
  }

  @ReactMethod
  public void shutdownStockfish(Promise promise) {
    writeStdIn("quit");

    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {}

    if (mainLoopThread != null) {
      mainLoopThread.interrupt();
    }
    
    if (engineLineReader != null) {
      engineLineReader.interrupt();
    }

    pvLines.clear();
    promise.resolve(null);
  }

  @ReactMethod
  public void sendCommand(String command, Promise promise) {
    if (command.startsWith("position fen")) {
        String fen = command.substring("position fen".length()).trim();
        // Wyciągnij sam FEN bez dodatkowych komend
        if (fen.contains("moves")) {
            currentFen = fen.substring(0, fen.indexOf("moves")).trim();
        } else {
            currentFen = fen;
        }
        
        String[] fenParts = fen.split(" ");
        lastDepth = 0;
        lastDepthPV2 = 0;
        if (fenParts.length > 1) {
            isWhiteToMove = fenParts[1].equals("w");
        }
        pvLines.clear();
        outputBuffer.setLength(0);
    }
    
    writeStdIn(command);
    promise.resolve(null);
  }

  private void clearStdOut() {
    while (readStdOut() != null) {}
  }

  public static native void init();
  public static native void main();
  protected static native String readStdOut();
  protected static native void writeStdIn(String command);
}
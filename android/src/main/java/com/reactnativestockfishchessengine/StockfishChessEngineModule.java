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
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  protected void loopReadingEngineOutput() {
    String previous = "";
    int timeoutMs = 100;
    
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        break;
      }

      String tmp = readStdOut();
      if (tmp != null) {
        String nextContent = previous + tmp;
        if (nextContent.endsWith("\n")) {
          WritableMap result = processEngineOutput(nextContent.trim());
          if (result != null && result.hasKey("type")) {
            sendEvent("stockfish-analyzed-output", result);
          }
          previous = "";
        } else {
          previous = nextContent;
        }
      }

      try {
        Thread.sleep(timeoutMs);
      } catch (InterruptedException e) {
        break;
      }
    }
  }

  private WritableMap processEngineOutput(String line) {
    if (line.contains("currmove")) {
      return null;
    }

    WritableMap result = Arguments.createMap();

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
        String scoreType = parts[scoreIndex + 1];
        String scoreValue = parts[scoreIndex + 2];
        
        int depth = Integer.parseInt(parts[depthIndex + 1]);
        int pvNumber = multipvIndex != -1 ? Integer.parseInt(parts[multipvIndex + 1]) : 1;
        
        // Update lastDepth only if the current PV1's depth is greater
        if (pvNumber == 1 && depth > lastDepth) {
            lastDepth = depth;
        }
        
        if (pvNumber == 2 && depth > lastDepthPV2) {
            lastDepthPV2 = depth;
        }

        if (pvNumber == 1 && lastDepth >= 0 && lastDepth % 4 != 0) {
          return null;
        }

        if (pvNumber == 2 && lastDepthPV2 >= 0 && lastDepthPV2 % 4 != 0) {
          return null;
        }

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
        result.putInt("depth", lastDepth); // Dodaj aktualną głębokość
        
        return result;
      }
    } else if (line.startsWith("bestmove")) {
      pvLines.clear();
      lastDepth = 0; // Reset głębokości przy nowym ruchu
      String[] parts = line.split(" ");
      if (parts.length > 1) {
        result.putString("type", "bestmove");
        result.putString("move", parts[1]);
        return result;
      }
    }

    return null;
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
      String[] fenParts = fen.split(" ");
      lastDepth = 0;
      lastDepthPV2 = 0;
      if (fenParts.length > 1) {
        isWhiteToMove = fenParts[1].equals("w");
      }
      pvLines.clear();
    } else if (command.equals("stop")) {
      pvLines.clear();
      clearStdOut();
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
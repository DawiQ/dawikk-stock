package com.reactnativestockfishchessengine;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

@ReactModule(name = StockfishChessEngineModule.NAME)
public class StockfishChessEngineModule extends ReactContextBaseJavaModule {

  public static final String NAME = "StockfishChessEngine";

  protected Thread engineLineReader;
  protected Thread mainLoopThread;
  protected ReactApplicationContext reactContext;

  private int lastDepth = 0;
  private String lastEvaluation = "";
  private String lastBestMove = "";
  private String lastPv = "";
  private boolean isWhiteToMove = true;

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
    int timeoutMs = 30;
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        break;
      }

      String tmp = readStdOut();
      if (tmp != null) {
        String nextContent = previous + tmp;
        if (nextContent.endsWith("\n")) {
          processAndSendEngineOutput(nextContent.trim());
          previous = "";
        }
        else {
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

  private void processAndSendEngineOutput(String line) {
    if (line.contains("currmove")) {
      return; // Ignoruj linie zawierające "currmove"
    }

    WritableMap result = Arguments.createMap();

    if (line.contains("info") && line.contains("score") && line.contains("pv")) {
      result = processInfoLine(line);
    } else if (line.startsWith("bestmove")) {
      result = processBestMoveLine(line);
    }

    if (result.hasKey("type")) {
      sendEvent("stockfish-analyzed-output", result);
    }
  }

  private WritableMap processInfoLine(String line) {
    WritableMap result = Arguments.createMap();
    String[] parts = line.split(" ");
    
    int scoreIndex = -1, depthIndex = -1, pvIndex = -1;
    for (int i = 0; i < parts.length; i++) {
      if (parts[i].equals("score")) scoreIndex = i;
      if (parts[i].equals("depth")) depthIndex = i;
      if (parts[i].equals("pv")) pvIndex = i;
    }

    if (scoreIndex != -1 && depthIndex != -1 && pvIndex != -1) {
      String scoreType = parts[scoreIndex + 1];
      String scoreValue = parts[scoreIndex + 2];
      int depth = Integer.parseInt(parts[depthIndex + 1]);
      
      if (depth >= lastDepth) {
        lastDepth = depth;
        
        // Dostosuj ocenę w zależności od koloru strony na ruchu
        if (scoreType.equals("cp")) {
          double score = Double.parseDouble(scoreValue) / 100;
          lastEvaluation = String.valueOf(isWhiteToMove ? score : -score);
        } else { // mate
          lastEvaluation = (scoreValue.startsWith("-") ? "-M" : "M") + Math.abs(Integer.parseInt(scoreValue));
          if (!isWhiteToMove) {
            lastEvaluation = lastEvaluation.startsWith("-") ? lastEvaluation.substring(1) : "-" + lastEvaluation;
          }
        }
        
        // Zbierz całą linię ruchów
        StringBuilder pvBuilder = new StringBuilder();
        for (int i = pvIndex + 1; i < parts.length; i++) {
          pvBuilder.append(parts[i]).append(" ");
        }
        lastPv = pvBuilder.toString().trim();
        lastBestMove = lastPv.split(" ")[0]; // Pierwszy ruch z PV

        result.putString("type", "info");
        result.putString("evaluation", lastEvaluation);
        result.putString("bestMove", lastBestMove);
        result.putString("pv", lastPv);
        result.putInt("depth", lastDepth);
        result.putBoolean("isWhiteToMove", isWhiteToMove);
        return result;
      }
    }

    return Arguments.createMap(); // Zwróć pusty WritableMap jeśli głębokość nie jest większa
  }

  private WritableMap processBestMoveLine(String line) {
    WritableMap result = Arguments.createMap();
    String[] parts = line.split(" ");
    
    if (parts.length > 1) {
      result.putString("type", "bestmove");
      result.putString("move", parts[1]);
    }

    return result;
  }

  private void sendEvent(String eventName, WritableMap params) {
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
      .emit(eventName, params);
  }

  static {
    try {
      // Used to load the 'native-lib' library on application startup.
      System.loadLibrary("stockfish");
    } catch (Exception ignored) {
      System.out.println("Failed to load stockfish");
    }
  }

  @ReactMethod
  public void mainLoop(Promise promise) {
    init();
    engineLineReader =
      new Thread(
        new Runnable() {
          public void run() {
            loopReadingEngineOutput();
          }
        }
      );
    engineLineReader.start();
    mainLoopThread = 
      new Thread(
        new Runnable() {
          public void run() {
            main();
          }
        }
      );
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

    promise.resolve(null);
  }

  @ReactMethod
  public void sendCommand(String command, Promise promise) {
    lastDepth = 0;
    if (command.startsWith("position fen")) {
      String fen = command.substring("position fen".length()).trim();
      String[] fenParts = fen.split(" ");
      if (fenParts.length > 1) {
        isWhiteToMove = fenParts[1].equals("w");
      }
    }
    writeStdIn(command);
    promise.resolve(null);
  }

  public static native void init();
  public static native void main();
  protected static native String readStdOut();
  protected static native void writeStdIn(String command);
}
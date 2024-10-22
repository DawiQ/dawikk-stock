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
      
      WritableMap latestInfo = null;

      String tmp = readStdOut();
      if (tmp != null) {
        String nextContent = previous + tmp;
        if (nextContent.endsWith("\n")) {
          WritableMap result = processEngineOutput(nextContent.trim());
          if ((lastDepth == 0 || lastDepth % 4 == 0) && result != null && result.hasKey("type")) {
            if (result.getString("type").equals("info")) {
              latestInfo = result;
            } else {
              sendEvent("stockfish-analyzed-output", result);
            }
          }
          previous = "";
        }
        else {
          previous = nextContent;
        }
      }

      if (latestInfo != null) {
        sendEvent("stockfish-analyzed-output", latestInfo);
        latestInfo = null;
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

          result.putString("type", "info");
          result.putString("evaluation", evaluation);
          result.putString("bestMove", bestMove);
          result.putString("bestLine", pv);
          result.putInt("depth", depth);
          return result;
        }
      }
    } else if (line.startsWith("bestmove")) {
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
    
    if (command.equals("stop")) {
      clearStdOut();
    }
    
    promise.resolve(null);
  }

  private void clearStdOut() {
    while (readStdOut() != null) {
    }
  }

  public static native void init();
  public static native void main();
  protected static native String readStdOut();
  protected static native void writeStdIn(String command);
}
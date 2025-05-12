package com.dawikk.stockfish;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ReactModule(name = "RNStockfishModule")
public class RNStockfishModule extends ReactContextBaseJavaModule {
    private static final String TAG = "RNStockfishModule";
    private static final String EVENT_STOCKFISH_OUTPUT = "stockfish-output";
    private static final String EVENT_STOCKFISH_ANALYZED_OUTPUT = "stockfish-analyzed-output";

    private final ReactApplicationContext reactContext;
    private boolean engineRunning = false;
    private boolean listenerRunning = false;
    private Thread engineThread;
    private Thread listenerThread;

    // Load native library
    static {
        System.loadLibrary("stockfish-lib");
    }

    // Native methods
    private native int nativeInit();
    private native int nativeMain();
    private native String nativeReadOutput();
    private native boolean nativeSendCommand(String command);

    public RNStockfishModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @NonNull
    @Override
    public String getName() {
        return "RNStockfishModule";
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put("STOCKFISH_OUTPUT", EVENT_STOCKFISH_OUTPUT);
        constants.put("STOCKFISH_ANALYZED_OUTPUT", EVENT_STOCKFISH_ANALYZED_OUTPUT);
        return constants;
    }

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable Object params) {
        if (reactContext.hasActiveReactInstance()) {
            reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(eventName, params);
        }
    }

    @ReactMethod
    public void initEngine(Promise promise) {
        if (engineRunning) {
            promise.resolve(true);
            return;
        }

        try {
            // Copy NNUE files from assets to app's files directory
            if (!NNUEHelper.copyNNUEFilesFromAssets(reactContext)) {
                Log.w(TAG, "Failed to copy some NNUE files from assets");
                // Continue anyway as the files might already exist
            }
            
            // Initialize the Stockfish engine
            int result = nativeInit();
            if (result != 0) {
                promise.reject("INIT_ERROR", "Failed to initialize Stockfish engine");
                return;
            }

            // Start the engine thread
            engineThread = new Thread(() -> {
                try {
                    nativeMain();
                } catch (Exception e) {
                    Log.e(TAG, "Error in engine thread", e);
                }
            });
            engineThread.start();
            engineRunning = true;

            // Start the listener thread
            listenerRunning = true;
            listenerThread = new Thread(() -> {
                try {
                    while (listenerRunning) {
                        String output = nativeReadOutput();
                        if (output != null && !output.isEmpty()) {
                            processEngineOutput(output);
                        }
                        // Small delay to avoid high CPU usage
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Listener thread interrupted", e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in listener thread", e);
                }
            });
            listenerThread.start();

            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("INIT_ERROR", "Failed to initialize Stockfish engine: " + e.getMessage());
        }
    }

    @ReactMethod
    public void sendCommand(String command, Promise promise) {
        if (!engineRunning) {
            promise.reject("ENGINE_NOT_RUNNING", "Stockfish engine is not running");
            return;
        }

        try {
            boolean success = nativeSendCommand(command);
            if (success) {
                promise.resolve(true);
            } else {
                promise.reject("COMMAND_FAILED", "Failed to send command to Stockfish");
            }
        } catch (Exception e) {
            promise.reject("COMMAND_ERROR", "Error sending command: " + e.getMessage());
        }
    }

    @ReactMethod
    public void shutdownEngine(Promise promise) {
        if (!engineRunning) {
            promise.resolve(true);
            return;
        }

        try {
            // Send the quit command to Stockfish
            nativeSendCommand("quit");

            // Stop the listener thread
            listenerRunning = false;
            if (listenerThread != null) {
                try {
                    listenerThread.join(1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for listener thread to stop", e);
                }
            }

            // Wait for engine thread to finish
            if (engineThread != null) {
                try {
                    engineThread.join(1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for engine thread to stop", e);
                }
            }

            engineRunning = false;
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("SHUTDOWN_ERROR", "Error shutting down engine: " + e.getMessage());
        }
    }

    private void processEngineOutput(String output) {
        if (output == null || output.isEmpty()) return;

        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;

            // Send raw output to JavaScript
            sendEvent(reactContext, EVENT_STOCKFISH_OUTPUT, line);

            // Process analysis output (info depth, bestmove, etc.)
            if (line.startsWith("info") && line.contains("score") && line.contains("pv")) {
                // Parse and send analyzed output
                sendAnalyzedOutput(line);
            } else if (line.startsWith("bestmove")) {
                // Parse and send bestmove output as structured data
                sendBestMoveOutput(line);
            }
        }
    }

    private void sendBestMoveOutput(String line) {
        WritableMap result = Arguments.createMap();
        result.putString("type", "bestmove");

        // Format: "bestmove e7e6 ponder c2c3"
        String[] parts = line.split(" ");
        if (parts.length >= 2) {
            result.putString("move", parts[1]);

            // Extract ponder move if available
            if (parts.length >= 4 && parts[2].equals("ponder")) {
                result.putString("ponder", parts[3]);
            }
        }

        sendEvent(reactContext, EVENT_STOCKFISH_ANALYZED_OUTPUT, result);
    }

    private void sendAnalyzedOutput(String line) {
        WritableMap result = Arguments.createMap();
        result.putString("type", "info");

        // Extract multipv number if available
        Pattern multipvPattern = Pattern.compile("multipv (\\d+)");
        Matcher multipvMatcher = multipvPattern.matcher(line);
        if (multipvMatcher.find()) {
            result.putInt("multipv", Integer.parseInt(multipvMatcher.group(1)));
        } else {
            // Default to PV 1 if not specified
            result.putInt("multipv", 1);
        }

        // Extract depth
        Pattern depthPattern = Pattern.compile("depth (\\d+)");
        Matcher depthMatcher = depthPattern.matcher(line);
        if (depthMatcher.find()) {
            result.putInt("depth", Integer.parseInt(depthMatcher.group(1)));
        }

        // Extract score
        Pattern scorePattern = Pattern.compile("score (cp|mate) (-?\\d+)");
        Matcher scoreMatcher = scorePattern.matcher(line);
        if (scoreMatcher.find()) {
            String scoreType = scoreMatcher.group(1);
            String scoreValue = scoreMatcher.group(2);

            if ("cp".equals(scoreType)) {
                float score = Float.parseFloat(scoreValue) / 100.0f;
                result.putDouble("score", score);
            } else { // mate
                result.putInt("mate", Integer.parseInt(scoreValue));
            }
        }

        // Extract best move (first move in pv)
        Pattern pvPattern = Pattern.compile("pv ([a-h][1-8][a-h][1-8][qrbnk]?)");
        Matcher pvMatcher = pvPattern.matcher(line);
        if (pvMatcher.find()) {
            result.putString("bestMove", pvMatcher.group(1));
        }

        // Extract full pv line
        int pvIndex = line.indexOf("pv ");
        if (pvIndex != -1) {
            result.putString("line", line.substring(pvIndex + 3));
        }

        sendEvent(reactContext, EVENT_STOCKFISH_ANALYZED_OUTPUT, result);
    }

    // Method to get app's files directory for JNI
    private String getFilesDir() {
        return reactContext.getFilesDir().getAbsolutePath();
    }

    // Properly handle module invalidation
    @Override
    public void invalidate() {
        if (engineRunning) {
            try {
                // Send the quit command to Stockfish
                nativeSendCommand("quit");

                // Stop the listener thread
                listenerRunning = false;
                if (listenerThread != null) {
                    try {
                        listenerThread.join(1000);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Interrupted while waiting for listener thread to stop", e);
                    }
                }

                // Wait for engine thread to finish
                if (engineThread != null) {
                    try {
                        engineThread.join(1000);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Interrupted while waiting for engine thread to stop", e);
                    }
                }

                engineRunning = false;
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down engine during invalidate", e);
            }
        }
        super.invalidate();
    }
}
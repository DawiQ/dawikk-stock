package com.dawikk.stockfish

import android.os.Build
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.concurrent.atomic.AtomicBoolean

class RNStockfishModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  companion object {
    private const val TAG = "RNStockfishModule"

    // Stockfish 19 NNUE network filename (must match cpp/stockfish/evaluate.h).
    // Stockfish 19 retired the second (small) network, so there is one file and
    // one UCI option now.
    private const val NNUE_NET = NnueDownloader.NNUE_NET

    // Whether libstockfish.so actually loaded on this device.
    //
    // This used to be a bare System.loadLibrary() in the initializer, which made
    // any load failure an UnsatisfiedLinkError thrown from <clinit>. That is
    // raised while React Native is building its TurboModule list, so it did not
    // just disable the engine — it aborted ReactInstance creation and the whole
    // app failed to start. A device that cannot run the engine should lose the
    // engine, not the application.
    @JvmStatic
    var isLibraryLoaded: Boolean = false
      private set

    init {
      isLibraryLoaded = try {
        System.loadLibrary("stockfish")
        true
      } catch (e: UnsatisfiedLinkError) {
        // Most likely this device was served an ABI split that has no
        // libstockfish.so — see abiFilters in android/build.gradle.
        Log.e(TAG, "libstockfish.so is unavailable on this device (${Build.SUPPORTED_ABIS.joinToString()})", e)
        false
      } catch (e: Throwable) {
        Log.e(TAG, "Unexpected failure loading libstockfish.so", e)
        false
      }
    }
  }

  // Guard for every entry point that would otherwise call into JNI. Calling an
  // external fun without the library raises UnsatisfiedLinkError again, so each
  // ReactMethod has to check first.
  private fun rejectIfUnavailable(promise: Promise): Boolean {
    if (isLibraryLoaded) return false
    promise.reject(
      "ENGINE_UNAVAILABLE",
      "The Stockfish native library is not available for this device's architecture."
    )
    return true
  }

  private external fun nativeInit(): Int
  private external fun nativeMain()
  private external fun nativeReadStdout(): String?
  private external fun nativeWriteStdin(data: String): Int

  private val isEngineRunning = AtomicBoolean(false)
  // Raised for the whole of a start, so a second initEngine cannot slip in
  // between "not running yet" and "running" and start a second engine on the
  // same stdin.
  private val isStarting = AtomicBoolean(false)
  private val shouldStopPolling = AtomicBoolean(false)
  private var engineThread: Thread? = null
  private var pollingThread: Thread? = null

  // The networks are downloaded after install rather than bundled — see
  // NnueDownloader.
  private val nnue = NnueDownloader(reactContext)
  private var downloadThread: Thread? = null

  override fun getName(): String = "RNStockfishModule"

  // Constants are read while React Native builds its module list, so anything
  // that throws here does not disable the engine — it aborts ReactInstance
  // creation and the app fails to launch. That is the same failure the library
  // load above is wrapped for, and directory() touches the filesystem.
  override fun getConstants(): MutableMap<String, Any> {
    val directory = try {
      nnue.directory().absolutePath
    } catch (e: Throwable) {
      Log.e(TAG, "Could not resolve the NNUE directory", e)
      ""
    }

    return hashMapOf(
      "nnueDirectory" to directory,
      "nnueName" to NNUE_NET,
      "nnueApproxTotalBytes" to NnueDownloader.APPROX_BYTES.values.sum(),
      "engineAvailable" to isLibraryLoaded
    )
  }

  private fun emit(eventName: String, body: Any?) {
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit(eventName, body)
  }

  private fun emitError(code: String, message: String) {
    val map: WritableMap = Arguments.createMap()
    map.putString("code", code)
    map.putString("message", message)
    emit("stockfish-error", map)
  }

  // ---------------------------------------------------------------------------
  // NNUE NETWORKS
  // ---------------------------------------------------------------------------

  private fun statusMap(): WritableMap {
    val status = nnue.status()
    val map = Arguments.createMap()
    map.putBoolean("ready", status["ready"] as Boolean)
    map.putString("directory", status["directory"] as String)
    map.putDouble("approxTotalBytes", (status["approxTotalBytes"] as Long).toDouble())
    map.putDouble("bytesOnDisk", (status["bytesOnDisk"] as Long).toDouble())
    map.putDouble("freeBytes", (status["freeBytes"] as Long).toDouble())

    val files = Arguments.createArray()
    @Suppress("UNCHECKED_CAST")
    for (entry in status["files"] as List<Map<String, Any>>) {
      val file = Arguments.createMap()
      file.putString("name", entry["name"] as String)
      file.putString("path", entry["path"] as String)
      file.putBoolean("ready", entry["ready"] as Boolean)
      file.putDouble("bytes", (entry["bytes"] as Long).toDouble())
      file.putDouble("partialBytes", (entry["partialBytes"] as Long).toDouble())
      file.putDouble("approxBytes", (entry["approxBytes"] as Long).toDouble())
      files.pushMap(file)
    }
    map.putArray("files", files)
    return map
  }

  @ReactMethod
  fun getNnueStatus(promise: Promise) {
    // Off the bridge thread: the first status call after an update deletes the
    // networks Stockfish 18 used and may re-hash 94 MB to record a checksum,
    // which takes a second or two. Every screen that shows the engine's state
    // asks for this on mount, so that second belongs to a thread of its own
    // rather than to the queue every other native call shares.
    Thread {
      try {
        promise.resolve(statusMap())
      } catch (e: Exception) {
        promise.reject("NNUE_STATUS_FAILED", e.message, e)
      }
    }.start()
  }

  /**
   * Downloads the networks that are missing. `options`:
   *   sources      — array of URL prefixes to try in order (defaults to the
   *                  official Stockfish sources)
   *   allowMetered — download over a metered connection (default false)
   * Progress is emitted as "stockfish-nnue-progress".
   */
  @ReactMethod
  fun downloadNnueNetworks(options: ReadableMap?, promise: Promise) {
    if (downloadThread?.isAlive == true) {
      promise.reject("NNUE_DOWNLOAD_BUSY", "A network download is already running")
      return
    }

    val sources = mutableListOf<String>()
    options?.getArray("sources")?.let { array ->
      for (i in 0 until array.size()) array.getString(i)?.let { sources.add(it) }
    }
    val allowMetered = options?.hasKey("allowMetered") == true && options.getBoolean("allowMetered")

    downloadThread = Thread {
      try {
        nnue.download(sources, allowMetered) { name, index, count, written, total ->
          val progress = Arguments.createMap()
          progress.putString("name", name)
          progress.putInt("index", index)
          progress.putInt("count", count)
          progress.putDouble("bytesWritten", written.toDouble())
          progress.putDouble("bytesTotal", total.toDouble())
          progress.putDouble("progress", if (total > 0) written.toDouble() / total.toDouble() else 0.0)
          emit("stockfish-nnue-progress", progress)
        }
        emit("stockfish-nnue-ready", statusMap())
        promise.resolve(statusMap())
      } catch (e: NnueDownloader.NnueException) {
        Log.e(TAG, "NNUE download failed: ${e.code} ${e.message}")
        if (e.code != "NNUE_CANCELLED") emitError(e.code, e.message ?: "Download failed")
        promise.reject(e.code, e.message, e)
      } catch (e: Exception) {
        Log.e(TAG, "NNUE download failed", e)
        emitError("NNUE_DOWNLOAD_FAILED", e.message ?: "Download failed")
        promise.reject("NNUE_DOWNLOAD_FAILED", e.message, e)
      }
    }.also { it.start() }
  }

  @ReactMethod
  fun cancelNnueDownload(promise: Promise) {
    nnue.cancel()
    promise.resolve(true)
  }

  @ReactMethod
  fun deleteNnueNetworks(promise: Promise) {
    if (isEngineRunning.get()) {
      promise.reject("ENGINE_RUNNING", "Shut the engine down before deleting its networks")
      return
    }
    // Cancelling only sets a flag the download thread reads between chunks.
    // Deleting on the spot let a net that finished in that window rename its
    // .part back over the file the user had just asked to be gone — and write
    // its verified length back with it. So wait for the thread to actually
    // stop, then delete.
    nnue.cancel()
    downloadThread?.let { thread ->
      try {
        thread.join(5000)
      } catch (e: InterruptedException) {
        Log.w(TAG, "Interrupted while waiting for the download to stop", e)
      }
    }
    promise.resolve(nnue.delete())
  }

  /** Full sha256 re-check of what is on disk. Slow (~94 MB read). */
  @ReactMethod
  fun verifyNnueNetworks(promise: Promise) {
    Thread {
      // map, not all: `all` stops at the first bad net, leaving any other one
      // carrying whatever verified length it had before this call.
      val ok = NnueDownloader.NETS.map { nnue.verify(it) }.all { it }
      promise.resolve(ok)
    }.start()
  }

  // ---------------------------------------------------------------------------
  // ENGINE LIFECYCLE
  // ---------------------------------------------------------------------------

  @ReactMethod
  fun initEngine(promise: Promise) {
    if (!isLibraryLoaded) {
      emitError(
        "ENGINE_UNAVAILABLE",
        "The chess engine is not available on this device."
      )
      rejectIfUnavailable(promise)
      return
    }
    if (isEngineRunning.get()) {
      promise.resolve(true)
      return
    }
    if (!isStarting.compareAndSet(false, true)) {
      promise.reject("ENGINE_STARTING", "Engine is already starting")
      return
    }

    Thread {
      try {
        // Refuse to start without the NNUE networks: a missing net would make
        // Stockfish abort the whole process on the first "go" command. They are
        // downloaded after install, so "missing" here means "not fetched yet" —
        // the JS side turns this code into the download prompt.
        if (!nnue.isReady()) {
          emitError("NNUE_MISSING", "Stockfish neural network files have not been downloaded yet.")
          promise.reject("NNUE_MISSING", "Stockfish neural network files have not been downloaded yet")
          return@Thread
        }

        // A previous engine that is still winding down must be gone before a
        // new one takes over stdin/stdout, or the two would share one pipe.
        engineThread?.let { old -> if (old.isAlive) old.join(2000) }

        if (nativeInit() != 0) {
          promise.reject("INIT_ERROR", "Failed to initialize Stockfish pipes")
          return@Thread
        }

        // Point the engine at the downloaded network BEFORE it is declared
        // running. The pipe holds this line until the UCI loop reads it, so it
        // is the first command the engine sees — ahead of anything a caller that
        // saw "running" sends. It used to go in after a 100 ms sleep, and a "go"
        // that landed inside that window made the engine's network verification
        // exit() the whole app.
        nativeWriteStdin("setoption name EvalFile value ${nnue.fileFor(NNUE_NET).absolutePath}")

        shouldStopPolling.set(false)
        isEngineRunning.set(true)

        engineThread = Thread {
          nativeMain()
          // Only this engine's own thread may declare it stopped. A stale
          // thread outliving a restart used to switch off the new engine.
          if (engineThread === Thread.currentThread()) {
            isEngineRunning.set(false)
          }
        }.also { it.start() }

        startOutputPolling()

        promise.resolve(true)
      } finally {
        isStarting.set(false)
      }
    }.start()
  }

  @ReactMethod
  fun sendCommand(command: String, promise: Promise) {
    if (rejectIfUnavailable(promise)) return
    if (!isEngineRunning.get()) {
      promise.reject("NOT_RUNNING", "Engine is not running")
      return
    }
    val result = nativeWriteStdin(command)
    if (result > 0) {
      promise.resolve(true)
    } else {
      promise.reject("WRITE_ERROR", "Failed to write command")
    }
  }

  @ReactMethod
  fun shutdownEngine(promise: Promise) {
    // Nothing was ever started, so this is a success, not an error.
    if (!isLibraryLoaded) {
      promise.resolve(true)
      return
    }
    if (!isEngineRunning.get()) {
      promise.resolve(true)
      return
    }
    Thread {
      stopEngine()
      promise.resolve(true)
    }.start()
  }

  /**
   * Ask the engine to quit and wait for it. "quit" is only read when the UCI
   * loop is back at its prompt, and loading a 100 MB network or clearing the
   * hash keeps it away for longer than the 200 ms this used to wait — after
   * which a restart handed the old loop the new engine's stdin.
   */
  private fun stopEngine() {
    shouldStopPolling.set(true)
    nativeWriteStdin("quit")
    engineThread?.let { t -> if (t.isAlive) t.join(3000) }
    pollingThread?.let { t -> if (t.isAlive) t.join(500) }
    isEngineRunning.set(false)
  }

  /**
   * The React instance is going away (reload, teardown). Without this the
   * engine, its polling thread and the loaded networks outlive the JS that
   * owned them, and the next instance starts a second engine beside them.
   */
  override fun invalidate() {
    if (isLibraryLoaded && isEngineRunning.get()) {
      try {
        stopEngine()
      } catch (e: Exception) {
        Log.w(TAG, "Engine teardown on invalidate failed: ${e.message}")
      }
    }
    super.invalidate()
  }

  @ReactMethod
  fun isEngineAvailable(promise: Promise) {
    // Report the truth: callers use this to decide whether to offer analysis.
    promise.resolve(isLibraryLoaded)
  }

  private fun startOutputPolling() {
    pollingThread = Thread {
      while (!shouldStopPolling.get() && isEngineRunning.get()) {
        val output = nativeReadStdout()
        if (!output.isNullOrEmpty()) {
          processOutput(output)
        }
        Thread.sleep(10)
      }
    }.also { it.start() }
  }

  private fun processOutput(output: String) {
    for (rawLine in output.split("\n")) {
      val line = rawLine.trim()
      if (line.isEmpty()) continue

      emit("stockfish-output", line)

      // Surface NNUE load failures reported by the engine's network verify step.
      if (line.contains("was not loaded successfully") ||
        (line.contains("ERROR") && line.contains("network file"))
      ) {
        emitError("NNUE_LOAD_FAILED", line)
      }

      // Stockfish 19 rejects a malformed FEN, an illegal move in "position ...
      // moves" or a bad "go" argument with this line. Upstream follows it with
      // exit(1); the STOCKFISH_EMBEDDED patch in cpp/stockfish/uci.cpp keeps the
      // loop alive instead, which means the command simply produced nothing —
      // no bestmove is coming. Callers waiting on one need to hear about it.
      if (line.contains("CRITICAL ERROR:")) {
        emitError("ENGINE_CRITICAL_ERROR", line)
      }

      val parsed = parseUciOutput(line)
      if (parsed != null) {
        emit("stockfish-analyzed-output", parsed)
      }
    }
  }

  private fun parseUciOutput(line: String): WritableMap? {
    if (line.startsWith("bestmove")) {
      val parts = line.split(" ")
      val result = Arguments.createMap()
      result.putString("type", "bestmove")
      if (parts.size > 1) result.putString("move", parts[1])
      val ponderIndex = parts.indexOf("ponder")
      if (ponderIndex != -1 && parts.size > ponderIndex + 1) {
        result.putString("ponder", parts[ponderIndex + 1])
      }
      return result
    }

    if (line.startsWith("info")) {
      // "info depth N currmove e2e4 currmovenumber K" is search progress, not
      // an evaluation. Parsed, it became a depth-only update with no multipv
      // that displaced PV 1 from the JS buffer once a search passed 10M nodes.
      if (line.contains(" currmove ")) return null
      val result = Arguments.createMap()
      result.putString("type", "info")
      val parts = line.split(" ")
      var hasData = false
      var i = 0
      while (i < parts.size) {
        when (parts[i]) {
          "depth" -> if (i + 1 < parts.size) {
            result.putInt("depth", parts[i + 1].toIntOrNull() ?: 0); hasData = true
          }
          "multipv" -> if (i + 1 < parts.size) {
            result.putInt("multipv", parts[i + 1].toIntOrNull() ?: 1)
          }
          "score" -> if (i + 2 < parts.size) {
            when (parts[i + 1]) {
              "cp" -> {
                result.putDouble("score", (parts[i + 2].toDoubleOrNull() ?: 0.0) / 100.0); hasData = true
              }
              "mate" -> {
                result.putInt("mate", parts[i + 2].toIntOrNull() ?: 0); hasData = true
              }
            }
          }
          "pv" -> {
            val pvMoves = parts.subList(i + 1, parts.size)
            result.putString("line", pvMoves.joinToString(" "))
            if (pvMoves.isNotEmpty()) {
              result.putString("bestMove", pvMoves[0]); hasData = true
            }
            return if (hasData) result else null
          }
        }
        i++
      }
      return if (hasData) result else null
    }

    return null
  }

  // Required by NativeEventEmitter on the JS side.
  @ReactMethod
  fun addListener(eventName: String) {
  }

  @ReactMethod
  fun removeListeners(count: Int) {
  }
}

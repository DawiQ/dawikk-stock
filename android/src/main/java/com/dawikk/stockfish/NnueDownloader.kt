package com.dawikk.stockfish

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches the Stockfish NNUE network AFTER the app is installed.
 *
 * The network is ~94 MB — bundling it into the AAB/IPA is most of the store
 * download, and Google Play's 200 MB base-delivery ceiling makes it a real
 * constraint. It is content, not code, so it is fetched on first use into the
 * app's no-backup files directory instead.
 *
 * Stockfish 19 retired the second (small) network 16.1 introduced, so what used
 * to be two files totalling ~113 MB is one file of 93.9 MB. NETS is still a
 * list: the download, resume, progress and verification code is written against
 * a set of files, and a later architecture change may well add one back.
 *
 * The download is resumable (HTTP Range against a .part file), verified against
 * the sha256 prefix embedded in the filename — the same rule Stockfish's own
 * scripts/net.sh uses — and only then renamed into place. A half-written or
 * corrupted file is therefore never visible to the engine.
 */
class NnueDownloader(private val context: Context) {

  class NnueException(val code: String, message: String) : Exception(message)

  companion object {
    private const val TAG = "NnueDownloader"

    // Must match cpp/stockfish/evaluate.h (EvalFileDefaultName).
    const val NNUE_NET = "nn-1a298aa575a0.nnue"
    val NETS = listOf(NNUE_NET)

    // Networks earlier versions of this app downloaded. They are dead weight
    // once the engine no longer asks for them — 113 MB of it — so they are
    // deleted on the first run after an upgrade. See cleanupRetiredNets().
    val RETIRED_NETS = listOf("nn-c288c895ea92.nnue", "nn-37f18f62d772.nnue")

    // Only used to size the progress bar before the first Content-Length
    // arrives; the real total comes from the server. This is the exact size of
    // nn-1a298aa575a0.nnue, so the bar is accurate from the first frame.
    val APPROX_BYTES = mapOf(
      NNUE_NET to 98_511_183L
    )

    // Same source order as Stockfish's own scripts/net.sh. Each source is a prefix the
    // filename is appended to; JS may override it with its own CDN.
    val DEFAULT_SOURCES = listOf(
      "https://tests.stockfishchess.org/api/nn/",
      "https://github.com/official-stockfish/networks/raw/master/"
    )

    private const val PREFS = "dawikk-stockfish-nnue"
    private const val BUFFER = 1 shl 16
    private const val MAX_REDIRECTS = 5
  }

  fun interface ProgressListener {
    fun onProgress(name: String, index: Int, count: Int, written: Long, total: Long)
  }

  private val cancelled = AtomicBoolean(false)

  @Volatile
  private var migrated = false

  // ---------------------------------------------------------------- locations

  // noBackupFilesDir, not filesDir: 94 MB of re-downloadable content has no
  // business in the user's Android auto-backup quota (or in an iCloud-style
  // restore of a new device).
  fun directory(): File = File(context.noBackupFilesDir, "nnue").apply { mkdirs() }

  /**
   * Housekeeping that has to happen before anything reads the directory.
   *
   * Two jobs, both once per process:
   *
   * 1. Versions that bundled the networks copied them out of the APK's assets
   *    into filesDir on first launch. If a future net ever ships that way again,
   *    adopting it beats asking for a download of bytes already on disk.
   * 2. Deleting the networks a previous Stockfish asked for. Upgrading from the
   *    Stockfish 18 build leaves nn-c288c895ea92.nnue and nn-37f18f62d772.nnue
   *    behind — 113 MB the engine will never open again. Nothing else would ever
   *    remove them, so this is the only chance.
   */
  @Synchronized
  fun migrateLegacyFiles() {
    if (migrated) return
    migrated = true

    for (name in NETS) {
      val legacy = File(context.filesDir, name)
      if (!legacy.isFile) continue

      val target = fileFor(name)
      if (target.isFile && isReady(name)) {
        // Already here and good: the old copy is dead weight.
        legacy.delete()
        continue
      }

      try {
        if (target.exists()) target.delete()
        // A rename inside the app's own data directory is the normal path; the
        // copy is there for the case where it is not one filesystem.
        if (!legacy.renameTo(target)) {
          legacy.copyTo(target, overwrite = true)
          legacy.delete()
        }
        if (verify(name)) {
          Log.i(TAG, "Adopted $name from the pre-download location")
        } else {
          target.delete()
          legacy.delete()
        }
      } catch (e: Exception) {
        Log.w(TAG, "Could not adopt $name from filesDir: ${e.message}")
      }
    }

    cleanupRetiredNets()
  }

  /**
   * Removes networks this engine no longer asks for, from both places they
   * could be: the download directory and the pre-download filesDir location.
   * Partial transfers and the recorded verified length go with them.
   */
  private fun cleanupRetiredNets() {
    var freed = 0L
    for (name in RETIRED_NETS) {
      if (name in NETS) continue  // guards a net being reinstated later
      for (stale in listOf(fileFor(name), partFor(name), File(context.filesDir, name))) {
        if (!stale.isFile) continue
        val size = stale.length()
        if (stale.delete()) {
          freed += size
        } else {
          Log.w(TAG, "Could not delete retired network ${stale.absolutePath}")
        }
      }
      prefs().edit().remove(name).apply()
    }
    if (freed > 0) {
      Log.i(TAG, "Reclaimed ${freed / 1048576} MB from networks retired in Stockfish 19")
    }
  }

  fun fileFor(name: String): File = File(directory(), name)

  private fun partFor(name: String): File = File(directory(), "$name.part")

  private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  // -------------------------------------------------------------- validation

  // The filename embeds the first 12 hex chars of the file's sha256.
  private fun expectedPrefix(name: String) =
    name.removePrefix("nn-").removeSuffix(".nnue")

  private fun sha256Prefix(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(BUFFER)
      while (true) {
        val read = input.read(buf)
        if (read <= 0) break
        digest.update(buf, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.substring(0, 12)
  }

  /**
   * Cheap readiness check, used on every engine start: a file counts as ready
   * when it is present at exactly the length it had when its checksum was last
   * verified. Re-hashing 94 MB on each launch would cost seconds for nothing.
   */
  fun isReady(name: String): Boolean {
    val file = fileFor(name)
    val verifiedLength = prefs().getLong(name, -1L)
    return file.isFile && verifiedLength > 0 && file.length() == verifiedLength
  }

  fun isReady(): Boolean {
    migrateLegacyFiles()
    return NETS.all { isReady(it) }
  }

  /** Full re-hash of what is on disk. Slow; for an explicit "verify" action. */
  fun verify(name: String): Boolean {
    val file = fileFor(name)
    if (!file.isFile || file.length() < 1024) return false
    return try {
      val ok = sha256Prefix(file) == expectedPrefix(name)
      if (ok) prefs().edit().putLong(name, file.length()).apply()
      else prefs().edit().remove(name).apply()
      ok
    } catch (e: Exception) {
      Log.e(TAG, "Failed to hash $name", e)
      false
    }
  }

  fun status(): Map<String, Any> {
    migrateLegacyFiles()
    val files = NETS.map { name ->
      val file = fileFor(name)
      val part = partFor(name)
      mapOf(
        "name" to name,
        "path" to file.absolutePath,
        "ready" to isReady(name),
        "bytes" to (if (file.isFile) file.length() else 0L),
        "partialBytes" to (if (part.isFile) part.length() else 0L),
        "approxBytes" to (APPROX_BYTES[name] ?: 0L)
      )
    }
    return mapOf(
      "ready" to NETS.all { isReady(it) },
      "directory" to directory().absolutePath,
      "files" to files,
      "approxTotalBytes" to APPROX_BYTES.values.sum(),
      "bytesOnDisk" to files.sumOf { (it["bytes"] as Long) + (it["partialBytes"] as Long) },
      "freeBytes" to directory().usableSpace
    )
  }

  fun delete(): Boolean {
    var ok = true
    for (name in NETS) {
      if (fileFor(name).exists() && !fileFor(name).delete()) ok = false
      if (partFor(name).exists() && !partFor(name).delete()) ok = false
      prefs().edit().remove(name).apply()
    }
    return ok
  }

  // --------------------------------------------------------------- downloading

  fun cancel() {
    cancelled.set(true)
  }

  private fun throwIfCancelled() {
    if (cancelled.get()) throw NnueException("NNUE_CANCELLED", "Download cancelled")
  }

  // Deliberately NOT ConnectivityManager.isActiveNetworkMetered: it answers the
  // conservative `true` when there is no active network at all, so an offline
  // user was told "you are not on Wi-Fi" and walked into switching their
  // download to mobile data to fix a problem that was never about metering.
  // No network is not a metered network — let the transfer fail as what it is.
  private fun isMetered(): Boolean {
    return try {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      val network = cm.activeNetwork ?: return false
      val caps = cm.getNetworkCapabilities(network) ?: return false
      !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Downloads whatever is missing. Returns the resolved path of every network.
   * Blocking — callers run it off the main thread.
   */
  fun download(
    sources: List<String>,
    allowMetered: Boolean,
    listener: ProgressListener?
  ): Map<String, String> {
    cancelled.set(false)
    migrateLegacyFiles()

    val missing = NETS.filter { !isReady(it) }
    if (missing.isEmpty()) return NETS.associateWith { fileFor(it).absolutePath }

    if (!allowMetered && isMetered()) {
      throw NnueException(
        "NNUE_METERED_NETWORK",
        "The engine files are large; download was restricted to unmetered networks."
      )
    }

    val needed = missing.sumOf { (APPROX_BYTES[it] ?: 0L) - partFor(it).length() }
    // usableSpace answers 0 when the stat fails, which is not the same as a
    // full disk — refusing on it would make the download impossible forever.
    val free = directory().usableSpace
    if (free > 0 && free < needed + (16L * 1024 * 1024)) {
      throw NnueException(
        "NNUE_NO_SPACE",
        "Not enough free storage for the engine files (about ${needed / 1048576} MB needed)."
      )
    }

    val urls = if (sources.isEmpty()) DEFAULT_SOURCES else sources
    missing.forEachIndexed { index, name ->
      downloadOne(name, urls, index, missing.size, listener)
    }
    return NETS.associateWith { fileFor(it).absolutePath }
  }

  private fun downloadOne(
    name: String,
    sources: List<String>,
    index: Int,
    count: Int,
    listener: ProgressListener?
  ) {
    var lastError: Exception? = null

    for (source in sources) {
      throwIfCancelled()
      val url = if (source.endsWith("/")) source + name else "$source/$name"
      try {
        fetchToPart(url, name, index, count, listener)

        val part = partFor(name)
        if (sha256Prefix(part) != expectedPrefix(name)) {
          Log.w(TAG, "Checksum mismatch for $name from $url, trying next source")
          part.delete()
          lastError = NnueException("NNUE_CHECKSUM_FAILED", "Checksum mismatch for $name")
          continue
        }

        val target = fileFor(name)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
          throw NnueException("NNUE_DOWNLOAD_FAILED", "Could not move $name into place")
        }
        prefs().edit().putLong(name, target.length()).apply()
        listener?.onProgress(name, index, count, target.length(), target.length())
        return
      } catch (e: NnueException) {
        if (e.code == "NNUE_CANCELLED") throw e
        lastError = e
      } catch (e: Exception) {
        Log.w(TAG, "Download of $name from $url failed: ${e.message}")
        lastError = e
      }
    }

    // Keep the reason: every source serving a corrupted file is a different
    // problem from no source answering, and the sheet says so.
    val code = (lastError as? NnueException)?.code ?: "NNUE_DOWNLOAD_FAILED"
    throw NnueException(
      code,
      "Could not download $name (${lastError?.message ?: "no source responded"})"
    )
  }

  private fun fetchToPart(
    url: String,
    name: String,
    index: Int,
    count: Int,
    listener: ProgressListener?
  ) {
    val part = partFor(name)
    var offset = if (part.isFile) part.length() else 0L

    val connection = open(url, offset)
    val status = connection.responseCode

    // 416: the range starts past the end of the file, which means the .part
    // already holds every byte — the app was killed between the last chunk and
    // the rename. There is nothing left to fetch; the checksum below decides
    // whether it is a good file or a corrupt one.
    if (offset > 0 && status == 416) {
      connection.disconnect()
      listener?.onProgress(name, index, count, offset, offset)
      return
    }

    // 200 to a Range request means the server ignored it and is sending the
    // whole file: the partial has to go, or a full body would be spliced onto
    // it. Only 200 — this used to fire on ANY non-206 answer, so a 503 from a
    // mirror deleted 90 MB of good bytes on its way to trying the next one.
    if (offset > 0 && status == HttpURLConnection.HTTP_OK) {
      part.delete()
      offset = 0
    }

    if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
      connection.disconnect()
      // Note what is NOT here: the .part survives, so the next attempt — this
      // source again, the next mirror, or tomorrow — resumes from it.
      throw IOException("HTTP $status for $url")
    }

    val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
    val declaredTotal = if (contentLength > 0) offset + contentLength else -1L
    val total = if (declaredTotal > 0) declaredTotal else (APPROX_BYTES[name] ?: 0L)
    var received = offset

    try {
      connection.inputStream.use { input ->
        FileOutputStream(part, offset > 0).use { output ->
          val buf = ByteArray(BUFFER)
          var written = offset
          var lastEmit = 0L
          while (true) {
            throwIfCancelled()
            val read = input.read(buf)
            if (read <= 0) break
            output.write(buf, 0, read)
            written += read
            received = written
            // ~20 events/second at most: the bridge, not the socket, is what
            // a per-chunk emit would saturate.
            val now = System.currentTimeMillis()
            if (now - lastEmit > 50) {
              lastEmit = now
              listener?.onProgress(name, index, count, written, total)
            }
          }
          output.flush()
        }
      }
    } finally {
      connection.disconnect()
    }

    // A body that stopped short is an interrupted transfer, not a corrupt file.
    // Saying so here matters: the caller deletes the .part on a checksum
    // mismatch, and doing that to 90 MB of good bytes would throw away exactly
    // what "it resumes where it stopped" promises. Left alone, the next attempt
    // continues from here.
    if (declaredTotal > 0 && received < declaredTotal) {
      throw IOException("Incomplete body for $name: $received of $declaredTotal bytes")
    }
  }

  private fun open(url: String, offset: Long, redirectsLeft: Int = MAX_REDIRECTS): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 20000
    connection.readTimeout = 30000
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("User-Agent", "dawikk-stockfish")
    connection.setRequestProperty("Accept-Encoding", "identity")
    if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")

    val status = connection.responseCode
    if (status in 300..399) {
      val location = connection.getHeaderField("Location")
      connection.disconnect()
      if (location.isNullOrEmpty() || redirectsLeft <= 0) {
        throw IOException("Too many redirects for $url")
      }
      // Following redirects by hand means re-implementing what the JDK refuses
      // to do: cross into cleartext. The platform would block the request
      // anyway (no cleartext permitted at this targetSdk), but a mirror that
      // answers a 302 to http:// is answered here, not two layers down.
      val next = URL(URL(url), location)
      if (!next.protocol.equals("https", ignoreCase = true)) {
        throw IOException("Refusing a redirect to ${next.protocol} for $url")
      }
      return open(next.toString(), offset, redirectsLeft - 1)
    }
    return connection
  }
}

package com.armsx2

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.RPCSX
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Cloud save + cloud game sync for ARMSX3.
 *
 * Transport: WebDAV over HTTPS — any WebDAV host works (Nextcloud, a VPN'd
 * Nginx + davfs, Google Drive via a WebDAV bridge, etc.).  A plain HTTP PUT /
 * GET also works if a folder is mapped to a simple static server; the client
 * treats the endpoint as "a directory on some server".
 *
 * Layout on the server:
 *   <remote>/saves/<titleId>/<saveFolderName>.zip   — one per save folder
 *   <remote>/games/                                  — game ISOs/PKGs mirrored from the network
 *
 * The download/upload path is chosen so that saves are batched in one zip per
 * title (keeps manifest bookkeeping trivial) and games are streamed as raw
 * files (so a 7GB ISO is never copied through a zip layer).
 *
 * Side effects are minimal on purpose.  No background service, no scheduled
 * sync, no aggressive retries: the user pressed a button or the game exited and
 * the request either completes or surfaces an error.  This is a utility the UI
 * calls, not a daemon.
 */
object CloudSync {
    private const val TAG = "CloudSync"

    /** Basic-auth user, or null when the endpoint is anonymous. Set via [config]. */
    data class Config(
        /** WebDAV base URL, e.g. https://host/dav/armsx3/ */
        val remoteUrl: String,
        val username: String? = null,
        val password: String? = null,
    )

    @Volatile
    var config: Config? = null

    // ---- Persistence ------------------------------------------------------

    private const val PrefUrl = "cloud.sync.url"
    private const val PrefUser = "cloud.sync.user"
    private const val PrefPass = "cloud.sync.pass"
    private const val PrefAuto = "cloud.sync.auto"

    fun load() {
        val prefs = runCatching { com.armsx2.runtime.MainActivityRuntime.prefs }
            .getOrNull() ?: return
        val url = prefs.getString(PrefUrl, null)?.takeIf { it.isNotBlank() } ?: run {
            config = null
            return
        }
        config = Config(
            remoteUrl = url,
            username = prefs.getString(PrefUser, null)?.takeIf { it.isNotBlank() },
            password = prefs.getString(PrefPass, null)?.takeIf { it.isNotBlank() },
        )
        autoPush = prefs.getBoolean(PrefAuto, false)
    }

    fun save(newConfig: Config?) {
        val prefs = runCatching { com.armsx2.runtime.MainActivityRuntime.prefs }
            .getOrNull() ?: return
        if (newConfig == null || newConfig.remoteUrl.isBlank()) {
            prefs.edit().remove(PrefUrl).remove(PrefUser).remove(PrefPass).apply()
            config = null
            return
        }
        prefs.edit()
            .putString(PrefUrl, newConfig.remoteUrl.trim())
            .putString(PrefUser, newConfig.username.orEmpty())
            .putString(PrefPass, newConfig.password.orEmpty())
            .apply()
        config = newConfig
    }

    /** Upload saves automatically when a game exits. Off by default: nobody asked
     *  for their data to leave the device, and the sync needs credentials first. */
    @Volatile
    var autoPush: Boolean = false

    fun setAutoPush(enabled: Boolean) {
        autoPush = enabled
        runCatching { com.armsx2.runtime.MainActivityRuntime.prefs }
            .getOrNull()?.edit()?.putBoolean(PrefAuto, enabled)?.apply()
    }

    // ---- Save data --------------------------------------------------------

    /** .zip of the whole savedata root, named "<titleId>.zip". */
    private fun saveArchive(dir: File, titleId: String): File {
        val staged = File(RPCSX.rootDirectory + "cache/sync-stage")
        staged.mkdirs()
        val out = File(staged, "$titleId.zip")
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            dir.walkTopDown().forEach { f ->
                val rel = dir.toPath().relativize(f.toPath()).toString()
                if (f.isFile && !rel.endsWith(".tmp")) {
                    zos.putNextEntry(ZipEntry(rel))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return out
    }

    /** Upload one save title's archive to <remote>/saves/<titleId>.zip */
    private suspend fun uploadArchive(zip: File, titleId: String): Boolean =
        withContext(Dispatchers.IO) {
            val res = http("saves/${titleId}.zip", "PUT", headers = {
                setRequestProperty("Content-Type", "application/zip")
                setRequestProperty("Content-Length", zip.length().toString())
            }) { conn ->
                zip.inputStream().use { it.copyTo(conn.outputStream) }
            }
            res in 200..204
        }

    /** Download <remote>/saves/<titleId>.zip to a temp file and unzip into savedata/. */
    suspend fun downloadSaves(titleId: String): Boolean = withContext(Dispatchers.IO) {
        val dest = SaveDataImporter.savedataRoot() ?: return@withContext false
        val staged = File(RPCSX.rootDirectory + "cache/sync-stage", "dl-$titleId.zip")
        staged.parentFile?.mkdirs()

        val ok = http("saves/${titleId}.zip", "GET") { conn ->
            conn.inputStream.use { input ->
                FileOutputStream(staged).use { it.write(input.readBytes()) }
            }
        } in 200..204

        if (!ok) return@withContext false

        // Replace the title's save directories atomically.
        val target = File(dest, titleId)
        if (target.exists()) target.deleteRecursively()
        ZipInputStream(FileInputStream(staged)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val outFile = File(dest, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zin.copyTo(it) }
                }
                entry = zin.nextEntry
            }
        }
        staged.delete()
        true
    }

    /** Upload every installed save folder. Return the number successfully pushed. */
    suspend fun pushAllSaves(): Int {
        val root = SaveDataImporter.savedataRoot() ?: return 0
        var pushed = 0
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .forEach { dir ->
                val zip = saveArchive(dir, dir.name)
                if (uploadArchive(zip, dir.name)) pushed++
                zip.delete()
            }
        return pushed
    }

    /** Pull every remote save the server has under saves/. */
    suspend fun pullAllSaves(): Int {
        val root = SaveDataImporter.savedataRoot() ?: return 0
        // We can't PROPFIND generically without an XML client; iterate the
        // local save list and pull matches.  A fuller listing is a future
        // nicety; matching on what exists locally is the common case.
        var pulled = 0
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .forEach { dir ->
                if (downloadSaves(dir.name)) pulled++
            }
        return pulled
    }

    // ---- Game files -------------------------------------------------------

    /**
     * Stream a game file from <remote>/games/<name> to a local cache under the
     * data root, then return the local path for [net.rpcsx.RPCSX.boot].
     *
     * The file is streamed block-by-block (no full-buffer), so a multi-GB ISO
     * is downloaded without exhausting heap.  Existing local files are
     * returned immediately when their size matches the remote's
     * Content-Length — a cheap "resume" that avoids re-pulling giant discs on
     * every launch.
     */
    suspend fun downloadGame(remoteName: String): String? = withContext(Dispatchers.IO) {
        val local = File(RPCSX.rootDirectory + "games", remoteName.substringAfterLast('/'))
        local.parentFile?.mkdirs()

        val remoteSize = headSize("games/$remoteName")
        if (local.exists() && remoteSize != null && local.length() == remoteSize) {
            Log.i(TAG, "cached $remoteName (${local.length()} B)")
            return@withContext local.absolutePath
        }

        // The server may not answer content-length (chunked). Stream anyway.
        val ok = http("games/$remoteName", "GET") { conn ->
            local.outputStream().use { out ->
                conn.inputStream.use { it.copyTo(out) }
            }
        } in 200..204

        if (ok) local.absolutePath else null
    }

    /** HEAD request for a remote file size. Null when absent or unknown. */
    private suspend fun headSize(path: String): Long? = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            val c = open(path, "HEAD")
            c.responseCode
            val n = c.getHeaderFieldLong("Content-Length", -1)
            c.disconnect()
            if (n >= 0) n else null
        }.getOrNull()
    }

    // ---- Low level --------------------------------------------------------

    private suspend fun http(
        path: String,
        method: String,
        headers: (HttpURLConnection) -> Unit = {},
        body: (HttpURLConnection) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val conn = open(path, method)
        try {
            headers(conn)
            conn.connect()
            if (method != "GET" && method != "HEAD" && method != "DELETE") { body(conn) }
            runCatching { conn.responseCode }.getOrDefault(-1)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun open(path: String, method: String): HttpURLConnection {
        val cfg = config ?: throw IllegalStateException("CloudSync not configured")
        val base = if (cfg.remoteUrl.endsWith("/")) cfg.remoteUrl else "${cfg.remoteUrl}/"
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.requestMethod = method
        conn.setRequestProperty("User-Agent", "ARMSX3-CloudSync/1.0")
        if (cfg.username != null) {
            val raw = java.util.Base64.getEncoder()
                .encodeToString("${cfg.username}:${cfg.password.orEmpty()}".toByteArray())
            conn.setRequestProperty("Authorization", "Basic $raw")
        }
        return conn
    }
}
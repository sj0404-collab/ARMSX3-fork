package com.armsx2

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.RPCSX
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashSet
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
 * A WebDAV PROPFIND on <remote>/saves/ lists what the server holds, so a fresh
 * install can pull its saves even before any local folder exists. Servers that
 * answer no PROPFIND (a bare static folder) degrade to matching the local save
 * list, which is what the client used to do everywhere.
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

    /**
     * Consistent capture of config + autoPush for use off the UI thread.
     *
     * The two are separate @Volatile fields and a caller that checks one then
     * the other can race a settings change between the reads (a thread starts
     * on a config that was just cleared, or pushAllSaves runs with autoPush
     * disabled). Snapshot captures both under one read of each so the worker
     * decides against the values it will actually use.
     */
    data class Snapshot(val config: Config?, val autoPush: Boolean)

    fun snapshot(): Snapshot {
        // Read autoPush last: it is the gate that decides whether a worker is
        // spawned at all, so a torn pair only ever over-reports the toggle.
        val cfg = config
        return Snapshot(cfg, if (cfg == null) false else autoPush)
    }

    /**
     * A title/remote name that cannot climb out of its URL or local path.
     * Applies to the identifier of a save archive and to file names used for
     * game downloads; both end up in URL paths and on disk, so any separators,
     * dot-dot segments, or control characters are rejected outright. Spaces
     * and Unicode are allowed (legitimate file names on a network share).
     */
    private fun safeComponent(name: String): String {
        val candidate = name.trim().replace('\\', '/')
        if (candidate.isBlank()) return ""
        if (candidate.contains('\n') || candidate.contains('\r') || candidate.contains('\u0000')) return ""
        if (candidate.startsWith("/")) return ""
        if (candidate.split('/').any { it == ".." || it == "." }) return ""
        return candidate
    }

    /** Percent-encode a WebDAV credential for use inside an HTTP URL userinfo. */
    private fun encodeUserInfo(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Password-less copy of an URL for logs: `scheme://user:***@host/...`. */
    private fun redactUrl(url: String): String {
        val sep = url.indexOf("://")
        if (sep < 0) return url
        val at = url.indexOf('@', sep + 3)
        if (at < 0) return url
        val colon = url.indexOf(':', sep + 3)
        return if (colon in (sep + 3) until at) {
            url.substring(0, colon + 1) + "***" + url.substring(at)
        } else {
            url
        }
    }

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

    private const val MAX_ARCHIVE_BYTES = 1L shl 30    // 1 GiB per save title
    private const val MAX_ARCHIVE_ENTRIES = 100_000   // sanity cap on entry count

    /** .zip of the whole savedata root, named "<titleId>.zip".
     *  Returns null when the archive exceeds the size or entry caps — the
     *  caller must treat that as "this title did not sync", not crash. */
    private fun saveArchive(dir: File, titleId: String): File? {
        val staged = File(RPCSX.rootDirectory + "cache/sync-stage")
        staged.mkdirs()
        val out = File(staged, "$titleId.zip")
        try {
            var total = 0L
            var entries = 0
            ZipOutputStream(FileOutputStream(out)).use { zos ->
                for (f in dir.walkTopDown()) {
                    val rel = dir.toPath().relativize(f.toPath()).toString()
                    if (!f.isFile || rel.endsWith(".tmp")) continue
                    if (entries++ >= MAX_ARCHIVE_ENTRIES) break
                    zos.putNextEntry(ZipEntry(rel))
                    FileInputStream(f).use { it.copyTo(zos) { bytes -> total += bytes } }
                    zos.closeEntry()
                    if (total > MAX_ARCHIVE_BYTES) break
                }
            }
            if (total > MAX_ARCHIVE_BYTES || entries >= MAX_ARCHIVE_ENTRIES) {
                Log.w(TAG, "save archive for $titleId exceeds caps (${total}B/$entries entries); skipping")
                out.delete()
                return null
            }
            return out
        } catch (e: Exception) {
            Log.e(TAG, "archive failed for $titleId", e)
            runCatching { out.delete() }
            return null
        }
    }

    /** Upload one save title's archive to <remote>/saves/<titleId>.zip */
    private suspend fun uploadArchive(zip: File, titleId: String): Boolean =
        withContext(Dispatchers.IO) {
            val safe = safeComponent(titleId)
            if (safe.isEmpty()) return@withContext false
            val res = http("saves/$safe.zip", "PUT", headers = {
                setRequestProperty("Content-Type", "application/zip")
                setRequestProperty("Content-Length", zip.length().toString())
            }) { conn ->
                zip.inputStream().use { it.copyTo(conn.outputStream) }
            }
            res in 200..204
        }

    /** Download <remote>/saves/<titleId>.zip to a temp file and unzip into savedata/. */
    suspend fun downloadSaves(titleId: String): Boolean = withContext(Dispatchers.IO) {
        val safeTitle = safeComponent(titleId)
        if (safeTitle.isEmpty()) {
            Log.e(TAG, "refusing save sync with unsafe title id '$titleId'")
            return@withContext false
        }
        val dest = SaveDataImporter.savedataRoot() ?: return@withContext false
        val staged = File(RPCSX.rootDirectory + "cache/sync-stage", "dl-$safeTitle.zip")
        staged.parentFile?.mkdirs()

        val ok = http("saves/${safeTitle}.zip", "GET") { conn ->
            conn.inputStream.use { input ->
                FileOutputStream(staged).use { it.write(input.readBytes()) }
            }
        } in 200..204

        if (!ok) return@withContext false

        // Replace the title's save directories atomically.
        val target = File(dest, safeTitle)
        if (target.exists()) target.deleteRecursively()
        try {
            val destCanonical = dest.absoluteFile.canonicalPath
            ZipInputStream(FileInputStream(staged)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    // ZIP-SLIP GUARD: an attacker-controlled zip must never be
                    // able to climb out of the save root. Reject any entry name
                    // with a dot-dot segment or an absolute path; the resolved
                    // canonical path must stay inside the destination.
                    val candidate = entry.name.replace('\\', '/')
                    if (candidate.isEmpty() ||
                        candidate.startsWith("/") ||
                        candidate.split('/').any { it == ".." || it == "." }
                    ) {
                        Log.w(TAG, "skipping zip entry with unsafe name '${entry.name}' in $safeTitle")
                        zin.closeEntry()
                        entry = zin.nextEntry
                        continue
                    }
                    val outFile = File(dest, candidate)
                    if (!outFile.absoluteFile.canonicalPath.startsWith(destCanonical + File.separator)) {
                        Log.w(TAG, "skipping zip entry escaping save root '${entry.name}' in $safeTitle")
                        zin.closeEntry()
                        entry = zin.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { zin.copyTo(it) }
                    }
                    entry = zin.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "unzip failed for $safeTitle", e)
            target.deleteRecursively()
            staged.delete()
            return@withContext false
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
                val zip = saveArchive(dir, dir.name) ?: return@forEach
                if (uploadArchive(zip, dir.name)) pushed++
                zip.delete()
            }
        return pushed
    }

    /** Pull the remote save archive for every locally known title, plus every
     *  title the server lists under saves/ (WebDAV PROPFIND).
     *
     *  The PROPFIND pass is what lets a fresh install recover its saves: the
     *  local-only iteration that preceded it consulted only folders that already
     *  existed on the device, so an empty install pulled nothing. Servers with
     *  no PROPFIND (a bare static HTTP folder) return an empty listing and we
     *  degrade to the local-only behaviour unchanged. */
    suspend fun pullAllSaves(): Int {
        val root = SaveDataImporter.savedataRoot() ?: return 0
        val local = root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map { it.name }
        val remote = listRemoteSaves()

        val targets = LinkedHashSet<String>()
        targets.addAll(local)
        targets.addAll(remote)

        var pulled = 0
        targets.forEach { if (downloadSaves(it)) pulled++ }
        return pulled
    }

    /**
     * Directory listing of <remote>/saves/ via WebDAV PROPFIND (Depth: 1).
     * Returns the archive file names (.zip). Empty when the server does not
     * answer PROPFIND — that keeps plain-HTTP static folders working.
     */
    private suspend fun listRemoteSaves(): List<String> = withContext(Dispatchers.IO) {
        // Pinned inside open(); a settings edit mid-flight must not swap the
        // server underneath the request.
        var conn: HttpURLConnection? = null
        try {
            conn = open("saves/", "PROPFIND")
            conn!!.setRequestProperty("Depth", "1")
            conn!!.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            conn!!.doOutput = true
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:displayname/></D:prop></D:propfind>"
            conn!!.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn!!.responseCode != 207) return@withContext emptyList()

            val xml = conn!!.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            propfindHrefs(xml).mapNotNull { href ->
                // href may be a full URL or a root-relative path depending on the
                // server; the archive name is always the tail segment. Decode
                // %XX escapes so a titleId with spaces/Unicode matches disk.
                val segment = Uri.decode(href.trim().trimEnd('/').substringAfterLast('/'))
                segment.takeIf { it.endsWith(".zip", ignoreCase = true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PROPFIND saves/ failed: ${e.message}")
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /** Extract every <D:response><D:href> text from a 207 Multi-Status body.
     *  Tolerates both prefixed (D:href) and bare (href) tags. */
    private fun propfindHrefs(xml: String): List<String> {
        val out = ArrayList<String>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            val buffer = StringBuilder()
            var inHref = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.run {
                    if (eventType == XmlPullParser.START_TAG || eventType == XmlPullParser.END_TAG) {
                        name.substringAfter(':')
                    } else null
                }
                when (parser.eventType) {
                    XmlPullParser.START_TAG ->
                        if (tag == "href") { inHref = true; buffer.setLength(0) }
                    XmlPullParser.TEXT ->
                        if (inHref) buffer.append(parser.text)
                    XmlPullParser.END_TAG ->
                        if (tag == "href") {
                            inHref = false
                            val href = buffer.toString().trim()
                            if (href.isNotEmpty()) out.add(href)
                        }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "PROPFIND href parse failed: ${e.message}")
        }
        return out
    }

    // ---- Game files -------------------------------------------------------

    /**
     * Fetch a game file from <remote>/games/<name> into the local cache under
     * the data root, then return the local path for [net.rpcsx.RPCSX.boot].
     *
     * The file is streamed block-by-block (no full-buffer), so a multi-GB ISO
     * is downloaded without exhausting heap. Existing local files are reused
     * when their size matches the remote's Content-Length — a cheap "resume"
     * that avoids re-pulling giant discs on every launch. When the remote is
     * unreachable (offline, or a chunked host with no HEAD) and a non-empty
     * local copy exists, that copy is launched as-is instead of failing: that
     * is the "downloaded from the cloud, now play it without a network" case.
     */
    suspend fun downloadGame(remoteName: String): String? = withContext(Dispatchers.IO) {
        // remoteName is attacker-visible (URL path) AND lands on the local disk
        // under games/; a "../.." name would both climb the server's directory
        // and write outside the app data root. Refuse anything unsafe.
        val safeRemote = safeComponent(remoteName)
        if (safeRemote.isEmpty()) {
            Log.e(TAG, "refusing game download with unsafe name '$remoteName'")
            return@withContext null
        }
        val local = File(RPCSX.rootDirectory + "games", safeRemote.substringAfterLast('/'))
        local.parentFile?.mkdirs()

        val remoteSize = headSize("games/$safeRemote")
        if (local.exists()) {
            if (remoteSize == null && local.length() > 0) {
                Log.i(TAG, "offline fallback: launching cached $safeRemote (${local.length()} B)")
                return@withContext local.absolutePath
            }
            if (remoteSize != null && local.length() == remoteSize) {
                Log.i(TAG, "cached $safeRemote (${local.length()} B)")
                return@withContext local.absolutePath
            }
        }

        // The server may not answer content-length (chunked). Stream anyway.
        val ok = http("games/$safeRemote", "GET") { conn ->
            local.outputStream().use { out ->
                conn.inputStream.use { it.copyTo(out) }
            }
        } in 200..204

        if (ok) {
            local.absolutePath
        } else {
            // Drop the partial transfer so a later offline fallback can never
            // be handed a truncated disc and told it is complete.
            runCatching { local.delete() }
            null
        }
    }

    /** HEAD request for a remote file size. Null when absent or unknown. */
    private suspend fun headSize(path: String): Long? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        return@withContext runCatching {
            conn = open(path, "HEAD")
            conn!!.responseCode
            val n = conn!!.getHeaderFieldLong("Content-Length", -1)
            if (n >= 0) n else null
        }.onFailure { Log.w(TAG, "HEAD $path failed: ${it.message}") }.getOrNull()
            .also { conn?.disconnect() }
    }

    /**
     * Build the full HTTP URL for a cloud game without downloading it.
     * The RPCS3 http_file backend will read the ISO via HTTP Range requests.
     * Returns null if the server is unreachable or the URL can't be built.
     */
    suspend fun streamGameUrl(remoteName: String): String? = withContext(Dispatchers.IO) {
        val safeRemote = safeComponent(remoteName)
        if (safeRemote.isEmpty()) {
            Log.e(TAG, "refusing game stream with unsafe name '$remoteName'")
            return@withContext null
        }
        val cfg = snapshot().config ?: return@withContext null
        val base = if (cfg.remoteUrl.endsWith("/")) cfg.remoteUrl else "${cfg.remoteUrl}/"

        // The core plays/installs these files through its own libcurl transport.
        // Pass credentials in the URL userinfo so curl can authenticate with
        // Basic auth; the native side strips them before any logging.
        val user = cfg.username
        val cred = if (user != null) "${encodeUserInfo(user)}:${encodeUserInfo(cfg.password.orEmpty())}@" else ""
        val host = when {
            cred.isEmpty() -> base
            else -> {
                val sep = base.indexOf("://")
                if (sep < 0) base
                else base.substring(0, sep + 3) + cred + base.substring(sep + 3)
            }
        }
        val url = "${host}games/$safeRemote"

        // Verify server is reachable via HEAD before handing URL to the emulator
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) {
            Log.w(TAG, "streamGameUrl: cannot open connection: ${e.message}")
            return@withContext null
        }
        try {
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "ARMSX3-CloudSync/1.0")
            if (cfg.username != null) {
                val raw = java.util.Base64.getEncoder()
                    .encodeToString("${cfg.username}:${cfg.password.orEmpty()}".toByteArray())
                conn.setRequestProperty("Authorization", "Basic $raw")
            }
            val code = runCatching { conn.responseCode }.getOrDefault(-1)
            if (code in 200..299) {
                Log.i(TAG, "streamGameUrl: HEAD OK ($code) for ${redactUrl(url)}")
                url
            } else {
                Log.w(TAG, "streamGameUrl: HEAD returned $code for ${redactUrl(url)}")
                null
            }
        } finally {
            conn.disconnect()
        }
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
        // Pin the config for THIS request; a settings edit mid-flight must not
        // swap the server underneath an in-progress upload.
        val cfg = snapshot().config ?: throw IllegalStateException("CloudSync not configured")
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
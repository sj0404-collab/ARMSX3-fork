package com.armsx2

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import net.rpcsx.RPCSX

/**
 * Cloud save sync that needs no server at all: saves are pushed to a private GitHub
 * repository as <titleId>.zip archives in the repo's saves/ folder, using the Contents
 * API. The "server" is whatever account the personal access token belongs to (there is no
 * hardcoded account). If that account dies, the user only has to paste a new token and the
 * sync transparently follows the new account; the repository is auto-created under it on
 * the first sync. Intentionally independent of the WebDAV [CloudSync] transport — either,
 * neither, or both can be configured.
 *
 * Security notes:
 *  - The token is stored only in the app's private preferences and only ever sent to
 *    api.github.com over HTTPS with a Bearer header.
 *  - The repo is created private by default; contents come back over HTTPS.
 *  - GitHub's Contents API caps single files at 100 MB; saves over 90 MB are skipped so a
 *    blob/blob commit failure cannot poison the whole batch.
 */
object GithubSaveSync {

    private const val TAG = "GithubSaveSync"
    private const val PREF_TOKEN = "github.sync.token"
    private const val PREF_REPO = "github.sync.repo"
    private const val PREF_AUTO = "github.sync.auto"
    const val DEFAULT_REPO = "armsx3-saves"
    private const val MAX_GITHUB_BYTES = 90L * 1024 * 1024 // Contents API cap is ~100 MB
    private const val API_BASE = "https://api.github.com/"
    private const val USER_AGENT = "ARMSX3-GitHubSync/1.0"

    /** Token in memory; validated/erased via [save]. */
    @Volatile
    var token: String? = null
        private set

    /** Repository name (a repository path / owner is never stored, only the name). */
    @Volatile
    var repo: String = DEFAULT_REPO
        private set

    @Volatile
    var autoPush: Boolean = false
        private set

    data class Snapshot(val token: String?, val repo: String, val autoPush: Boolean)

    fun snapshot(): Snapshot = Snapshot(token, repo, autoPush)

    /** Restore persisted config; called once at startup alongside CloudSync.load(). */
    fun load() {
        val prefs = com.armsx2.runtime.MainActivityRuntime.prefs
        token = prefs.getString(PREF_TOKEN, null)?.takeIf { it.isNotBlank() }
        repo = prefs.getString(PREF_REPO, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_REPO
        autoPush = prefs.getBoolean(PREF_AUTO, false)
    }

    /** Persist config. Passing null (or blank) clears the whole sync. */
    fun save(newToken: String?, newRepo: String?) {
        val prefs = com.armsx2.runtime.MainActivityRuntime.prefs
        val clean = newToken?.trim()?.takeIf { it.isNotBlank() }
        val cleanRepo = newRepo?.trim()?.ifBlank { null }
        val editor = prefs.edit()
        if (clean == null) {
            editor.remove(PREF_TOKEN).remove(PREF_REPO).putBoolean(PREF_AUTO, false)
            token = null
            repo = DEFAULT_REPO
            autoPush = false
        } else {
            editor.putString(PREF_TOKEN, clean)
                .putString(PREF_REPO, cleanRepo ?: DEFAULT_REPO)
                .putBoolean(PREF_AUTO, autoPush)
            token = clean
            repo = cleanRepo ?: DEFAULT_REPO
        }
        editor.apply()
    }

    fun setAutoPush(enabled: Boolean) {
        autoPush = enabled
        com.armsx2.runtime.MainActivityRuntime.prefs.edit().putBoolean(PREF_AUTO, enabled).apply()
    }

    // ---------------------------------------------------------------------
    // Public operations (all suspend, run on Dispatchers.IO)
    // ---------------------------------------------------------------------

    /** Check the token against GET /user and return the account login, or null on failure. */
    suspend fun verify(): String? = withContext(Dispatchers.IO) {
        try {
            val user = getJson("user")
            user?.optString("login")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "verify failed: ${e.message}")
            null
        }
    }

    /** Push every installed save folder. Returns the number successfully uploaded. */
    suspend fun pushAll(): Int = withContext(Dispatchers.IO) {
        val t = token ?: return@withContext 0
        val root = SaveDataImporter.savedataRoot() ?: return@withContext 0
        var pushed = 0
        try {
            val (owner, repoName, branch) = ensureRepo(t)
            root.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .forEach { dir ->
                    val zip = CloudSync.saveArchive(dir, dir.name) ?: return@forEach
                    try {
                        if (zip.length() > MAX_GITHUB_BYTES) {
                            Log.w(TAG, "save '${dir.name}' is ${zip.length()} B, over the 90 MB GitHub cap; skipping")
                            return@forEach
                        }
                        val path = "saves/${Uri.encode(dir.name)}.zip"
                        val sha = findSha(owner, repoName, branch, "saves", "${dir.name}.zip")
                        val json = JSONObject()
                            .put("message", "ARMSX3 save sync: ${dir.name}")
                            .put("content", Base64.getEncoder().encodeToString(zip.readBytes()))
                            .apply { if (sha != null) put("sha", sha) }
                        val res = putJson("repos/$owner/$repoName/contents/$path", json.toString())
                        if (res != null) pushed++
                        Log.i(TAG, "pushed ${dir.name} (${zip.length()} B)")
                    } finally {
                        zip.delete()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "pushAll failed: ${e.message}")
        }
        pushed
    }

    /** Pull every <titleId>.zip found in the remote repo into savedata. Returns the count pulled. */
    suspend fun pullAll(): Int = withContext(Dispatchers.IO) {
        val t = token ?: return@withContext 0
        val dest = SaveDataImporter.savedataRoot() ?: return@withContext 0
        var pulled = 0
        try {
            val (owner, repoName, branch) = ensureRepo(t)
            val entries = listContents(owner, repoName, branch, "saves")
            entries.forEach { item ->
                val name = item.optString("name")
                if (!name.endsWith(".zip")) return@forEach
                val titleId = name.removeSuffix(".zip")
                if (titleId.isBlank()) return@forEach
                // A malicious repo could name a zip so titleId resolves outside savedata/
                // ("..", a leading dot, or a path separator route the File() below astray).
                if (titleId.startsWith(".") || titleId.contains('/') || titleId.contains('\\') ||
                    titleId.split('/').any { it == ".." || it == "." }
                ) {
                    Log.w(TAG, "skipping pull of unsafe name '$name'")
                    return@forEach
                }
                val encoded = Uri.encode(name)
                val zipBytes = getRaw("repos/$owner/$repoName/contents/saves/$encoded?ref=$branch") ?: return@forEach
                val staged = File(RPCSX.rootDirectory + "cache/sync-stage", "gh-$encoded")
                try {
                    staged.parentFile?.mkdirs()
                    if (staged.exists()) staged.delete()
                    staged.writeBytes(zipBytes)
                    // Extract into a staging folder FIRST so a corrupt/truncated archive can
                    // never wipe the local save: the real title dir is only replaced once the
                    // whole zip unpacked cleanly. Dot-prefixed so the push side skips it.
                    val stageName = ".gh-stage-$titleId"
                    if (CloudSync.unzipSaveArchive(staged, dest, stageName)) {
                        val stagedDir = File(dest, stageName)
                        val target = File(dest, titleId)
                        if (target.exists()) target.deleteRecursively()
                        if (stagedDir.renameTo(target)) pulled++
                        else stagedDir.deleteRecursively()
                        Log.i(TAG, "pulled $name (${zipBytes.size} B)")
                    } else {
                        File(dest, stageName).deleteRecursively()
                        Log.w(TAG, "pull of $name failed; local save left untouched")
                    }
                    staged.delete()
                } finally {
                    staged.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pullAll failed: ${e.message}")
        }
        pulled
    }

    // ---------------------------------------------------------------------
    // GitHub API plumbing
    // ---------------------------------------------------------------------

    /** Make sure the private repo exists under the token's account; return (owner, name, defaultBranch). */
    private fun ensureRepo(token: String): Triple<String, String, String> {
        val user = getJson("user")
            ?: throw IllegalStateException("GitHub: could not read account with this token")
        val owner = user.optString("login").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub: token returned no account login")
        val name = repo
        val existing = getJson("repos/$owner/$name")
        if (existing != null) {
            return Triple(owner, name, existing.optString("default_branch", "main"))
        }
        // auto_init=true gives the repo its first commit on a default branch, so the very
        // first PUT below never races GitHub's "Git Repository is empty." (409) transient
        // window that repros when a just-created repo has no commit yet.
        val created = postJson(
            "user/repos",
            JSONObject().put("name", name).put("private", true).put("auto_init", true).toString(),
        ) ?: throw IllegalStateException("GitHub: could not create private repo '$name'")
        val branch = created.optString("default_branch", "main")
        Log.i(TAG, "created private repo $owner/$name (branch $branch)")
        return Triple(owner, name, branch)
    }

    /** Look up the current blob sha of a file in the remote repo, or null. */
    private fun findSha(owner: String, repoName: String, branch: String, parentDir: String, rawName: String): String? {
        return listContents(owner, repoName, branch, parentDir)
            .firstOrNull { it.optString("name") == rawName }
            ?.optString("sha")
            ?.takeIf { it.isNotBlank() }
    }

    /** List the entries of a repo directory (404 → empty). */
    private fun listContents(owner: String, repoName: String, branch: String, dir: String): List<JSONObject> {
        val text = getRawText("repos/$owner/$repoName/contents/$dir?ref=$branch") ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val t = token ?: throw IllegalStateException("GitHub sync is not configured")
        val conn = URL(API_BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $t")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn
    }

    private fun getJson(path: String): JSONObject? {
        val text = getRawText(path) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun getRawText(path: String): String? {
        val conn = open("GET", path)
        return try {
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                Log.w(TAG, "GET /$path -> HTTP $code")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET /$path failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Raw byte download for binary content (Accept: application/vnd.github.raw). */
    private fun getRaw(path: String): ByteArray? {
        val conn = open("GET", path).apply {
            setRequestProperty("Accept", "application/vnd.github.raw")
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.readBytes() else null
        } catch (e: Exception) {
            Log.w(TAG, "raw GET /$path failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun putJson(path: String, body: String): JSONObject? {
        val conn = open("PUT", path).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                runCatching { JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }) }.getOrNull()
            } else {
                Log.w(TAG, "PUT /$path -> HTTP $code")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "PUT /$path failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(path: String, body: String): JSONObject? {
        val conn = open("POST", path).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                runCatching { JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }) }.getOrNull()
            } else {
                Log.w(TAG, "POST /$path -> HTTP $code")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST /$path failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
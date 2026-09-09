package net.rpcsx

import android.content.Context
import android.content.res.Resources.NotFoundException
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File


enum class FirmwareStatus {
    None,
    Installed,
    Compiled
}

@Serializable
private data class FirmwareInfo(val version: String?, val status: FirmwareStatus, val externalDir: String? = null)

class FirmwareRepository {
    companion object {
        val progressChannel: MutableState<Long?> = mutableStateOf(null)
        val version: MutableState<String?> = mutableStateOf(null)
        val status: MutableState<FirmwareStatus> = mutableStateOf(FirmwareStatus.None)
        val externalFirmwareDir: MutableState<String?> = mutableStateOf(null)

        /** Name of the SharedPreferences key backing [externalFirmwareDir]. */
        private const val PREF_KEY_FW_DIR = "firmware_external_dir"

        fun save() {
            try {
                File(RPCSX.rootDirectory + "fw.json").writeText(
                    Json.encodeToString(
                        FirmwareInfo(version.value, status.value, externalFirmwareDir.value)
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun load() {
            // No fw.json is the normal first-run state, not an error. Reading it blind
            // threw FileNotFoundException and printStackTrace() put the whole trace in
            // the diagnostic log, where it reads as a crash -- it was reported as one.
            val file = File(RPCSX.rootDirectory + "fw.json")
            if (!file.isFile) return
            try {
                val info = Json.decodeFromString<FirmwareInfo>(file.readText())
                status.value = info.status
                version.value = info.version
                externalFirmwareDir.value = info.externalDir
            } catch (_: NotFoundException) {
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @Keep
        @JvmStatic
        fun onFirmwareInstalled(version: String?) {
            updateStatus(version, FirmwareStatus.Installed)
        }

        @Keep
        @JvmStatic
        fun onFirmwareCompiled(version: String?) {
            updateStatus(version, FirmwareStatus.Compiled)
        }

        fun updateStatus(version: String?, status: FirmwareStatus) {
            synchronized(Companion.version) {
                Companion.version.value = version
                Companion.status.value = status
                save()
            }
        }

        /**
         * Point the firmware root at an external POSIX directory. The C++ core
         * resolves dev_flash relative to this (via g_cfg_vfs / $(EmulatorDir)),
         * so once set, installed games, the VSH, and all firmware modules live
         * under [dir] instead of the app-private tree.  The move is not
         * performed automatically: the caller must copy existing files, and
         * ideally the existing app-private dev_flash is left untouched as a
         * fallback until the copy is verified.
         *
         * Calling with null reverts to the default (app-private root).
         *
         * Persisted in fw.json so it survives restarts.  If the directory
         * cannot be written to at boot, the core logs a warning and falls
         * back to its own default.
         */
        fun setExternalFirmwareDir(dir: String?) {
            externalFirmwareDir.value = dir
            save()
        }

        /**
         * Verify the external directory is writable and contains at least one
         * firmware file (dev_flash/vsh/module/vsh.self) before accepting it.
         */
        fun validateExternalDir(dir: String): Boolean {
            val root = if (dir.endsWith("/")) dir else "$dir/"
            return try {
                val vshSelf = File(root, "dev_flash/vsh/module/vsh.self")
                // Check writability by attempting a zero-byte create.
                val probe = File(root, ".fw_probe")
                val writable = try {
                    probe.createNewFile()
                    probe.delete()
                    true
                } catch (_: Exception) { false }
                writable && vshSelf.exists()
            } catch (_: SecurityException) {
                false
            }
        }

        /**
         * Load firmware from the external directory into the core's dev_flash
         * mount. Called after initialize() when an external dir is set.
         */
        fun applyExternalDirToCore() {
            val dir = externalFirmwareDir.value ?: return
            RPCSX.instance.setFirmwareDir(dir)
        }
    }
}

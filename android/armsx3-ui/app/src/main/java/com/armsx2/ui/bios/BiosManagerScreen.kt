package com.armsx2.ui.bios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.FileBrowserDialog
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.canBrowse
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.FirmwareRepository
import net.rpcsx.FirmwareStatus
import net.rpcsx.ProgressRepository
import net.rpcsx.RPCSX

/**
 * PS3 firmware, replacing ARMSX2's PS2 BIOS manager.
 *
 * The old screen listed .bin BIOS dumps, let you choose a per-game one, and
 * imported .mec/.nvm companions. None of that maps: PS3 firmware is a single
 * PS3UPDAT.PUP that gets INSTALLED into dev_flash rather than selected. There is
 * one system firmware, so there is nothing to choose between and nothing to
 * override per game -- hence one status card and one action.
 *
 * It also reported "No valid PS2 BIOS files found in that folder" and never
 * showed the firmware you had already installed, because it was reading a BIOS
 * directory that does not exist here instead of asking FirmwareRepository.
 *
 * `game` is accepted and ignored so the existing navigation route still compiles.
 */
@Composable
fun BiosManagerScreen(onBack: () -> Unit, game: com.armsx2.GameInfo? = null) {
    val context = LocalContext.current
    val version by FirmwareRepository.version
    val status by FirmwareRepository.status
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    val externalDir by FirmwareRepository.externalFirmwareDir

    // Re-read fw.json on entry. Firmware may have been installed by the setup
    // wizard in this same session, and the repository is otherwise only loaded
    // once at startup -- which is why this screen used to look empty right after
    // a successful install.
    LaunchedEffect(Unit) { runCatching { FirmwareRepository.load() } }

    // Install takes a URI, not a File.
    //
    // The core is handed a file descriptor either way, and openAssetFileDescriptor resolves a
    // content:// URI exactly as happily as a file://. Routing both paths through one function is
    // what lets the SAF picker below share it -- the in-app browser cannot reach a MicroSD on
    // Android 11 and later, because storageRoots() enumerates /storage by POSIX and a removable
    // volume is not listable that way. Reported on an Odin 3 Max: the picker showed internal
    // storage only. The package installer already had this second route; firmware never did.
    val installFirmware: (android.net.Uri) -> Unit = { uri ->
        busy = true
        message = null
        MainActivityRuntime.invoke {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val id = ProgressRepository.create(context, "Installing firmware")
                    context.contentResolver
                        .openAssetFileDescriptor(uri, "r")
                        .use { afd ->
                            val fd = afd?.parcelFileDescriptor?.fd
                                ?: return@runCatching false
                            RPCSX.instance.installFw(fd, id)
                        }
                }.getOrDefault(false)
            }
            busy = false
            if (!ok) message = I18n.get("bios.firmware.failed")
        }
    }

    // */* rather than a PUP MIME type: Android has no type for a PS3 firmware update, and every
    // provider reports something different for it -- octet-stream, nothing at all, or the type of
    // whatever extension it guesses. A filtered picker would grey the file out on some devices.
    val safPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(installFirmware) }

    // Directory tree picker for external firmware location.
    val dirPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Resolve the SAF tree URI to a POSIX path.
            val posixPath = runCatching {
                com.armsx2.runtime.MainActivityRuntime.resolveTreeUriToPosix(uri.toString())
            }.getOrNull()
            if (posixPath != null) {
                // Refuse directories that are not writable or hold no firmware:
                // pointing dev_flash at an empty folder is a silent brick — every
                // VSH/module load fails later with no clear cause.
                if (net.rpcsx.FirmwareRepository.validateExternalDir(posixPath)) {
                    net.rpcsx.FirmwareRepository.setExternalFirmwareDir(posixPath)
                    message = "External firmware directory set: $posixPath"
                } else {
                    message = I18n.get("bios.firmware.dirInvalid")
                }
            } else {
                message = I18n.get("bios.firmware.failed")
            }
            showFolderPicker = false
        }
    }

    if (showBrowser) {
        FileBrowserDialog(
            title = str("setup.bios.selectTitle"),
            extensions = setOf("pup"),
            onPick = { file ->
                showBrowser = false
                installFirmware(android.net.Uri.fromFile(file))
            },
            onDismiss = { showBrowser = false },
        )
    }

    // ArmsBackdrop supplies the themed surface AND the content colours. Without
    // it this screen drew on the raw window background, so the headline rendered
    // near-black on a dark backdrop and was unreadable.
    ArmsBackdrop {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            str("setup.page.bios.title"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (status == FirmwareStatus.None) str("bios.firmware.notInstalled")
                    else str("setup.status.installed"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Version comes from the installed dev_flash, so this reflects the
                // firmware actually in use rather than the last file picked.
                version?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    str("setup.step.bios.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (busy) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(
                    str("setup.firmware.installing"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            OutlinedButton(
                onClick = {
                    // In-app browser when storage is visible; otherwise say why
                    // rather than opening an empty list.
                    if (canBrowse()) showBrowser = true
                    else message = I18n.get("bios.firmware.needsStorage")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .controllerFocusable(
                        "firmware.install",
                        RoundedCornerShape(13.dp),
                        onConfirm = {
                            if (canBrowse()) showBrowser = true
                            else message = I18n.get("bios.firmware.needsStorage")
                        },
                    ),
            ) {
                Text(
                    if (status == FirmwareStatus.None) str("setup.bios.selectTitle")
                    else str("bios.firmware.reinstall"),
                )
            }

            // Reaches storage the in-app browser cannot open by path: USB-OTG, and MicroSD on
            // devices that only expose it through SAF. Always offered, not just when canBrowse()
            // fails -- a device can have all-files access AND still hide its card from a POSIX
            // walk of /storage, which is exactly the case that was reported.
            OutlinedButton(
                onClick = { safPicker.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .controllerFocusable(
                        "firmware.install.external",
                        RoundedCornerShape(13.dp),
                        onConfirm = { safPicker.launch(arrayOf("*/*")) },
                    ),
            ) {
                Text(str("packages.select.external"))
            }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .controllerFocusable("firmware.back", RoundedCornerShape(13.dp), onConfirm = onBack),
        ) {
            Text(str("action.back"))
        }

        // External firmware directory: lets the user point the emulator at a
        // POSIX directory containing dev_flash (installed firmware files) rather
        // than keeping them inside the app's data root.  Useful when sharing
        // one firmware install between multiple emulators, when the app-private
        // tree is near its quota, or when the firmware lives on removable
        // storage that can be swapped between sessions.
        if (status != FirmwareStatus.None) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "External Firmware Directory",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    val dirText = externalDir?.takeIf { it.isNotBlank() }
                        ?: "Using internal storage (default)"
                    Text(
                        dirText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Select a folder containing dev_flash/. Firmware files will be read from " +
                            "there instead of the app's internal storage.  The app must be " +
                            "restarted for the change to take effect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = { dirPicker.launch(null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .controllerFocusable(
                                    "firmware.external.dir",
                                    RoundedCornerShape(13.dp),
                                    onConfirm = { dirPicker.launch(null) },
                                ),
                        ) {
                            Text(if (externalDir != null) "Change" else "Select Folder")
                        }
                        if (externalDir != null) {
                            OutlinedButton(
                                onClick = {
                                    net.rpcsx.FirmwareRepository.setExternalFirmwareDir(null)
                                    message = "Reverted to internal firmware"
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .controllerFocusable(
                                        "firmware.external.reset",
                                        RoundedCornerShape(13.dp),
                                        onConfirm = {
                                            net.rpcsx.FirmwareRepository.setExternalFirmwareDir(null)
                                            message = "Reverted to internal firmware"
                                        },
                                    ),
                            ) {
                                Text("Reset")
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

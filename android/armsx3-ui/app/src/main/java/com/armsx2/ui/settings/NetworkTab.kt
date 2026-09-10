package com.armsx2.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.config.Settings
import com.armsx2.config.Dev9HostMapping
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.ui.Colors
import com.armsx2.ui.InGameOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

/**
 * DEV9 networking/HDD settings brought over from OG ARMSX2's SettingsActivity.
 *
 * Android's useful backend is PCSX2's socket backend. PCAP options are kept
 * visible for parity/debugging, but normal users should leave the API on
 * Sockets and the adapter on Auto. DEV9 is initialized at VM boot, so these
 * settings are persisted immediately and take effect on the next game/BIOS
 * launch.
 */
@Composable
fun NetworkTab(state: MutableState<Settings>) {
    val s = state.value
    val scroll = settingsScrollState()
    ControllerAutoScroll(scroll)
    fun apply(updated: Settings) = InGameOverlay.saveSettings(updated)

    // The PS2 networking that used to live here -- DEV9 Ethernet, the HDD image,
    // per-game DNS host lists and Local Link (PS2 System Link over UDP) -- was
    // all emulating a PS2 expansion bay. The PS3 has none of it: RPCS3 models
    // networking at the PSN/RPCN level instead, which is these four controls.
    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleRow(
                str("net.internet.label"),
                s.ps3.netEnabled,
                description = str("net.internet.description"),
        ) { apply(s.copy(ps3 = s.ps3.copy(netEnabled = it))) }
        SettingsDivider()
        // Three states, not two. This was a toggle that could only reach Disconnected and
        // Simulated, so RPCN -- the one that actually connects, and whose client has been
        // compiled into the core all along -- had no way of being selected.
        SegmentedGridRow(
                label = str("net.psn.label"),
                options = listOf(
                        str("net.psn.off"),
                        str("net.psn.simulated"),
                        str("net.psn.rpcn"),
                ),
                selectedIndex = s.ps3.psnStatus.coerceIn(0, 2),
                columns = 3,
                description = str("net.psn.description"),
                onChange = { apply(s.copy(ps3 = s.ps3.copy(psnStatus = it))) },
        )
        // The account lives behind RPCN, so only offer it once RPCN is the choice --
        // otherwise it invites people to set up an account the emulator will not use.
        if (s.ps3.psnStatus == 2) {
            SettingsDivider()
            RpcnAccountSection()
            SettingsDivider()
            RpcnFriendsSection()
        }
        SettingsDivider()
        ToggleRow(
                str("net.upnp.label"),
                s.ps3.upnpEnabled,
                description = str("net.upnp.description"),
        ) { apply(s.copy(ps3 = s.ps3.copy(upnpEnabled = it))) }
        SettingsDivider()
        // DNS and the redirect list come first because they are the two that answer "how do
        // I reach a custom game server" -- RPCN covers Sony's side only, and a publisher's
        // own backend was never part of it.
        EditableTextRow(
                controllerId = "net.dns",
                label = str("net.dns.label"),
                value = s.ps3.dnsAddress,
                description = str("net.dns.description"),
                placeholder = "8.8.8.8",
        ) { apply(s.copy(ps3 = s.ps3.copy(dnsAddress = it.ifBlank { "8.8.8.8" }))) }
        SettingsDivider()
        EditableTextRow(
                controllerId = "net.swap",
                label = str("net.swap.label"),
                value = s.ps3.ipSwapList,
                description = str("net.swap.description"),
        ) { apply(s.copy(ps3 = s.ps3.copy(ipSwapList = it))) }
        SettingsDivider()
        EditableTextRow(
                controllerId = "net.ip",
                label = str("net.ip.label"),
                value = s.ps3.ipAddress,
                description = str("net.ip.description"),
                placeholder = "0.0.0.0",
        ) { apply(s.copy(ps3 = s.ps3.copy(ipAddress = it.ifBlank { "0.0.0.0" }))) }
        SettingsDivider()
        EditableTextRow(
                controllerId = "net.bind",
                label = str("net.bind.label"),
                value = s.ps3.bindAddress,
                description = str("net.bind.description"),
                placeholder = "0.0.0.0",
        ) { apply(s.copy(ps3 = s.ps3.copy(bindAddress = it.ifBlank { "0.0.0.0" }))) }
        SettingsDivider()
        EditableTextRow(
                controllerId = "net.country",
                label = str("net.country.label"),
                value = s.ps3.psnCountry,
                description = str("net.country.description"),
                placeholder = "us",
        ) { apply(s.copy(ps3 = s.ps3.copy(psnCountry = it.ifBlank { "us" }.lowercase().take(2)))) }
        SettingsDivider()
        ToggleRow(
                str("net.mac.label"),
                s.ps3.deriveMacFromPsid,
                description = str("net.mac.description"),
        ) { apply(s.copy(ps3 = s.ps3.copy(deriveMacFromPsid = it))) }
        SettingsDivider()
        ToggleRow(
                str("net.clans.label"),
                s.ps3.clansEnabled,
                description = str("net.clans.description"),
        ) { apply(s.copy(ps3 = s.ps3.copy(clansEnabled = it))) }
        SettingsDivider()
        // Emulate USB Keyboard. Previously reachable ONLY from the in-game pause menu, which made
        // the "On-Screen Keyboard (toggle)" hotkey's own message a dead end: it says to turn this
        // on in Network settings, and there was nothing here to turn on. Same field, so the two
        // rows stay in sync.
        ToggleRow(
                str("network.emulateUsbKeyboard"),
                s.usbKeyboard,
                description = str("net.usbKeyboard.description"),
        ) { apply(s.copy(usbKeyboard = it)) }
        SettingsDivider()
        CloudSection()
    }
}

/**
 * Cloud save + cloud game mirroring (see [com.armsx2.CloudSync]).
 *
 * Saves sync over WebDAV (or any plain HTTP host that keeps a file tree) —
 * <remote>/saves/<titleId>.zip — and games stream from <remote>/games/. Save data is
 * pushed on demand and, when the toggle is on, automatically when a game exits.
 *
 * The transport is plain HTTPS + Basic auth in the URL, so it also works on a
 * bare static server or a sync folder; no SDK is required on the other end.
 */
private const val CloudGlyph = "☁"
private const val CloudUrlKey = "cloud.tab.url"
private const val CloudUserKey = "cloud.tab.user"
private const val CloudPassKey = "cloud.tab.pass"

/** Free WebDAV hosts prefilled by one tap in [CloudSection]. */
private data class CloudProviderPreset(
    val url: String,
    val labelKey: String,
    val hintKey: String,
)

private val CLOUD_PROVIDERS = listOf(
    CloudProviderPreset(
        url = "https://webdav.pcloud.com/",
        labelKey = "cloud.provider.pcloud",
        hintKey = "cloud.provider.pcloud.hint",
    ),
    CloudProviderPreset(
        url = "https://app.koofr.net/dav/",
        labelKey = "cloud.provider.koofr",
        hintKey = "cloud.provider.koofr.hint",
    ),
    CloudProviderPreset(
        url = "https://your-nextcloud.example/remote.php/dav/files/username/",
        labelKey = "cloud.provider.nextcloud",
        hintKey = "cloud.provider.nextcloud.hint",
    ),
)

@Composable
private fun CloudSection() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // The whole section is driven off CloudSync.config, which is volatile and may
    // change underneath us; these mirrors keep the text fields sane while editing.
    var url by remember { mutableStateOf(com.armsx2.CloudSync.config?.remoteUrl ?: "") }
    var user by remember { mutableStateOf(com.armsx2.CloudSync.config?.username.orEmpty()) }
    var pass by remember { mutableStateOf(com.armsx2.CloudSync.config?.password.orEmpty()) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(CloudGlyph, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                str("cloud.section.title"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            str("cloud.section.description"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )

        // One-tap free WebDAV hosts: most users do not own a server, and a preset that fills
        // the address turns "set up WebDAV" into "create a free account and paste your login".
        // Credentials are never guessed — only the URL template is prefilled.
        Text(
            str("cloud.providers.label"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in CLOUD_PROVIDERS) {
                // Resolve the strings here (composition context); onClick is not composable.
                val presetLabel = str(p.labelKey)
                val presetHint = str(p.hintKey)
                OutlinedButton(
                    onClick = { url = p.url; status = presetHint },
                    modifier = Modifier
                        .weight(1f)
                        .controllerFocusable(
                            "cloud.provider.${p.labelKey}",
                            onConfirm = { url = p.url },
                        ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    Text(
                        presetLabel,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            str("cloud.providers.hint"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )

        CloudEditRow(
            controllerId = "cloud.url",
            label = str("cloud.url.label"),
            description = str("cloud.url.description"),
            placeholder = "https://nextcloud.example.com/remote.php/dav/armsx3/",
            value = url,
            fieldLabel = str("cloud.url.fieldLabel"),
            onChange = { url = it },
        )
        if (url.isNotBlank()) {
            CloudEditRow(
                controllerId = "cloud.user",
                label = str("cloud.user.label"),
                description = str("cloud.user.description"),
                value = user,
                fieldLabel = str("cloud.user.fieldLabel"),
                onChange = { user = it },
            )
            CloudEditRow(
                controllerId = "cloud.pass",
                label = str("cloud.pass.label"),
                description = str("cloud.pass.description"),
                value = pass,
                fieldLabel = str("cloud.pass.fieldLabel"),
                onChange = { pass = it },
            )
        }

        // Saving writes credentials to app-private SharedPreferences. Show that the config
        // changed rather than hiding the secret behind a save that never happens.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val save = {
                val updated = if (url.isBlank()) null
                else com.armsx2.CloudSync.Config(
                    remoteUrl = url,
                    username = user.trim().takeIf { it.isNotBlank() },
                    password = pass.takeIf { it.isNotBlank() },
                )
                com.armsx2.CloudSync.save(updated)
                status = if (updated == null) str("cloud.status.disabled") else str("cloud.status.saved")
            }
            OutlinedButton(
                onClick = save,
                modifier = Modifier.weight(1f).controllerFocusable("cloud.save", onConfirm = save),
            ) { Text(str("cloud.save")) }
            if (com.armsx2.CloudSync.config != null) {
                val clear = {
                    com.armsx2.CloudSync.save(null)
                    url = ""; user = ""; pass = ""
                    status = str("cloud.status.disabled")
                }
                OutlinedButton(
                    onClick = clear,
                    modifier = Modifier.weight(1f).controllerFocusable("cloud.clear", onConfirm = clear),
                ) { Text(str("action.reset")) }
            }
        }

        if (com.armsx2.CloudSync.config != null) {
            SettingsDivider()
            ToggleRow(
                label = str("cloud.autopush"),
                value = com.armsx2.CloudSync.autoPush,
                description = str("cloud.autopush.description"),
                onChange = { com.armsx2.CloudSync.setAutoPush(it) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val push = {
                    if (!busy) scope.launch {
                        busy = true
                        status = str("cloud.working")
                        val n = withContext(Dispatchers.IO) { com.armsx2.CloudSync.pushAllSaves() }
                        status = I18n.get("cloud.pushed").format(n)
                        busy = false
                    }
                }
                OutlinedButton(
                    onClick = push,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).controllerFocusable("cloud.push", onConfirm = push),
                ) { Text(str("cloud.push")) }
                val pull = {
                    if (!busy) scope.launch {
                        busy = true
                        status = str("cloud.working")
                        val n = withContext(Dispatchers.IO) { com.armsx2.CloudSync.pullAllSaves() }
                        status = I18n.get("cloud.pulled").format(n)
                        busy = false
                    }
                }
                OutlinedButton(
                    onClick = pull,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).controllerFocusable("cloud.pull", onConfirm = pull),
                ) { Text(str("cloud.pull")) }
            }

            // Cloud game streaming: download a disc under games/ and launch it.
            CloudGameRow(busy = busy) { m ->
                busy = m
            }
        }

        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("✓")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

/** A name entry row mirroring [LocalLinkRow], but text-only and custom-labelled
 *  (LocalLinkRow carries a Generate action and the shared rows hardcode "Address"). */
@Composable
private fun CloudEditRow(
    controllerId: String,
    label: String,
    description: String,
    value: String,
    fieldLabel: String,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    val edit = {
        com.armsx2.ui.home.LibraryKeyboard.open(value, onChange, fieldLabel)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable(onClick = edit)
            .controllerFocusable(controllerId, onConfirm = edit)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                value.ifEmpty { placeholder.ifEmpty { "\u2014" } },
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Download-and-play a single game file from the cloud ("cloud games"). */
@Composable
private fun CloudGameRow(busy: Boolean, setBusy: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            str("cloud.games.title"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            str("cloud.games.description"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CloudEditRow(
            controllerId = "cloud.game",
            label = str("cloud.games.file"),
            description = str("cloud.games.file.description"),
            value = name,
            fieldLabel = str("cloud.games.file.fieldLabel"),
            placeholder = "BLUS30475/BLUS30475.iso",
            onChange = { name = it },
        )
        if (name.isNotBlank()) {
            val launch = {
                if (!busy) scope.launch {
                    setBusy(true)
                    status = str("cloud.download.working")
                    val local = withContext(Dispatchers.IO) { com.armsx2.CloudSync.downloadGame(name) }
                    setBusy(false)
                    if (local != null) {
                        status = str("cloud.download.done")
                        com.armsx2.runtime.MainActivityRuntime.launchGame(local)
                    } else {
                        status = str("cloud.download.failed")
                    }
                }
            }
            OutlinedButton(
                onClick = launch,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().controllerFocusable("cloud.game.launch", onConfirm = launch),
            ) { Text(str("cloud.games.launch")) }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** This device's own LAN IPv4 addresses, so a host can read one out to the guests instead of being
 *  told to go hunting in Android's settings. Hotspot interfaces are included on purpose — a hotspot
 *  is the most reliable way to get two handhelds onto one network. */
private fun enumerateLocalIPv4(): List<String> {
    val out = linkedSetOf<String>()
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        for (iface in interfaces.toList()) {
            val usable = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!usable) continue
            for (addr in iface.inetAddresses.toList()) {
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress)
                    addr.hostAddress?.let { out.add(it) }
            }
        }
    }
    return out.toList()
}

/** A fresh 8-character room code. Seeded automatically when LAN mode is first selected, because an
 *  empty code silently disables DEV9 (see the Network mode onChange for the full failure chain).
 *  Uppercase alphanumerics only, matching what the native side normalises to. */
private fun generateRoomCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I/O/0/1 — these get read aloud
    return (1..8).map { alphabet[kotlin.random.Random.nextInt(alphabet.length)] }.joinToString("")
}

/** A stable guest peer id in 2..65533, derived from ANDROID_ID so it differs per device and never
 *  needs to be chosen by hand. 1 is reserved for the host by the wire protocol. */
private fun derivePeerId(context: android.content.Context): Int {
    val seed = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )
    }.getOrNull().orEmpty().ifEmpty { android.os.Build.FINGERPRINT }
    // 65532 slots starting at 2; abs() on the hash, guarding Int.MIN_VALUE.
    val h = seed.hashCode()
    val positive = if (h == Int.MIN_VALUE) 0 else if (h < 0) -h else h
    return 2 + (positive % 65532)
}

/** A tappable label+subtitle row that fires an Intent. Used for the Wi-Fi shortcut (the devices have
 *  to be on one network before any of this works, and that is the step people miss) and for the
 *  supported-games list. Registers with the pad-nav registry — without that the whole Local Link
 *  section was unreachable on a controller, since only the shared ToggleRow/SegmentedRow widgets
 *  self-register and every custom row here was skipped. */
@Composable
private fun ActionRow(
    controllerId: String,
    label: String,
    description: String,
    context: android.content.Context,
    intent: () -> android.content.Intent,
) {
    val fire = {
        // runCatching: no ACTION_VIEW handler (no browser) or a blocked settings intent must not
        // take the settings screen down with it.
        runCatching {
            context.startActivity(intent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Unit
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable(onClick = fire)
            .controllerFocusable(controllerId, onConfirm = fire)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

/** Wikipedia's LAN-games section: the authoritative answer to "which games can I actually use this
 *  with?", which is the first thing anyone asks. Kept as a link rather than a baked-in list so it
 *  can't go stale in our strings. */
private const val LAN_GAMES_URL =
    "https://en.wikipedia.org/wiki/List_of_PlayStation_2_online_games#LAN_Games"

/** A non-editable value row (host address, local peer id) — same shape as the editable rows so the
 *  section reads consistently, but with no tap target, because these are computed, not chosen. */
@Composable
private fun ReadOnlyRow(label: String, value: String, description: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                value,
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * A Local Link field: same look as [EditableTextRow], but it shows a description under the label
 * and does not assume the value is an IP address. [EditableTextRow] prefills AND displays
 * "0.0.0.0" for an empty value and hardcodes the edit dialog's field label to "Address" — correct
 * for the DNS/gateway rows it serves, wrong for a room code, a port or a peer id. Kept separate
 * rather than adding switches to that one, which has a dozen existing call sites.
 *
 * onChange receives the trimmed text; callers do their own validation/coercion.
 */
@Composable
private fun LocalLinkRow(
    controllerId: String,
    label: String,
    value: String,
    description: String,
    fieldLabel: String,
    /** When supplied, adds a Generate action (button + D-pad Right) that fills the field. Used for
     *  the room code, which has validity rules a person shouldn't have to remember. */
    onGenerate: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    // Text entry goes through LibraryKeyboard, NOT an AlertDialog. A Compose dialog takes its own
    // focused window and swallows gamepad keys, so a pad user could open it and then be stuck with
    // no way to type or dismiss. LibraryKeyboard is D-pad navigable by design, and it also honours
    // the "Use system keyboard" preference for touch users.
    val edit = {
        com.armsx2.ui.home.LibraryKeyboard.open(value, onChange, fieldLabel)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable(onClick = edit)
            .controllerFocusable(
                controllerId,
                onConfirm = edit,
                // D-pad Right regenerates, matching how other rows use left/right to adjust.
                onRight = onGenerate,
            )
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                value.ifEmpty { str("network.localLink.notSet") },
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onGenerate != null) {
                // One-tap valid code, without opening the editor. Its own clickable consumes the
                // tap, so it does not also open the row's edit dialog.
                TextButton(onClick = onGenerate) { Text(str("network.localLink.generate")) }
            }
        }
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun EditableTextRow(
    controllerId: String,
    label: String,
    value: String,
    description: String,
    /** Shown greyed when the value is empty; also what the editor starts from. */
    placeholder: String = "",
    // Last, so callers can pass it as a trailing lambda like every other row here.
    onChange: (String) -> Unit,
) {
    var editing by remember(label) { mutableStateOf(false) }
    var draft by remember(label, value) { mutableStateOf(value.ifEmpty { placeholder }) }
    val open = { draft = value.ifEmpty { placeholder }; editing = true }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        label = { Text(str("net.address")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange(draft.trim())
                    editing = false
                }) { Text(str("action.save")) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(str("action.cancel")) }
            },
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable(onClick = open)
            // Without this the row is invisible to a controller: only the shared
            // ToggleRow/SegmentedRow widgets self-register with the pad-nav registry.
            .controllerFocusable(controllerId, onConfirm = open)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                value.ifEmpty { placeholder.ifEmpty { "\u2014" } },
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun DeviceChooser(
    selected: String,
    adapters: List<String>,
    onChange: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(str("network.ethernetDevice"), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        adapters.forEach { adapter ->
            val active = adapter == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clickable { onChange(adapter) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    adapter,
                    color = if (active) Colors.pasx2_blue else Color(0xFFCCCCCC),
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (active) {
                    Text(str("network.selected"), color = Colors.pasx2_blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HddFileRow(fileName: String, onChange: (String) -> Unit, onReset: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(fileName) { mutableStateOf(fileName) }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(str("network.hddImage.title")) },
            text = {
                Column {
                    Text(
                        str("network.hddImage.dialogHint"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        label = { Text(str("network.hddImage.fieldLabel")) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange(draft.trim())
                    editing = false
                }) { Text(str("action.save")) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(str("action.cancel")) }
            },
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable { draft = fileName; editing = true }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(str("network.hddImage.title"), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(fileName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                str("action.reset"),
                color = Colors.pasx2_blue,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onReset() }.padding(start = 8.dp),
            )
        }
    }
}

private fun enumerateAdapters(): List<String> {
    val out = linkedSetOf("Auto")
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        interfaces.toList()
            .filter { iface ->
                runCatching {
                    iface.isUp && !iface.isLoopback && !iface.isVirtual
                }.getOrDefault(false)
            }
            .mapTo(out) { it.name }
    }
    return out.toList()
}

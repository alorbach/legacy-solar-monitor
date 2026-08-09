package com.alorbach.solarmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.importing.FtpImportClient
import com.alorbach.solarmonitor.data.importing.ImportManager
import com.alorbach.solarmonitor.data.importing.ImportRequest
import com.alorbach.solarmonitor.data.importing.LegacySbfspotImporters
import com.alorbach.solarmonitor.data.importing.RemoteBrowseHelpers
import com.alorbach.solarmonitor.data.importing.RemoteEntry
import com.alorbach.solarmonitor.data.importing.SftpImportClient
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RemoteImportProtocol {
    FTP,
    SFTP,
}

private enum class WizardStep {
    Protocol,
    Device,
    Connection,
    Browse,
    Confirm,
}

@Composable
fun RemoteImportWizard(
    devices: List<DeviceProfileEntity>,
    importers: LegacySbfspotImporters,
    importManager: ImportManager,
    initialDeviceId: Long?,
    onDismiss: () -> Unit,
    onImportSucceeded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    var step by rememberSaveable { mutableStateOf(WizardStep.Protocol.name) }
    val currentStep = WizardStep.valueOf(step)

    var protocol by rememberSaveable { mutableStateOf(RemoteImportProtocol.SFTP.name) }
    val selectedProtocol = RemoteImportProtocol.valueOf(protocol)

    var selectedDeviceId by rememberSaveable { mutableStateOf(initialDeviceId) }
    var clearBeforeImport by rememberSaveable { mutableStateOf(false) }

    var host by rememberSaveable { mutableStateOf("") }
    var portText by rememberSaveable {
        mutableStateOf(SftpImportClient.DEFAULT_PORT.toString())
    }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    var currentPath by rememberSaveable { mutableStateOf("/") }
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIsDirectory by rememberSaveable { mutableStateOf(false) }
    var selectedCsvCount by rememberSaveable { mutableStateOf(0) }
    var manualPath by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }

    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }

    // Password is not saveable; after process death fall back to Connection so the user re-enters it.
    LaunchedEffect(Unit) {
        val restored = WizardStep.valueOf(step)
        if (password.isBlank() && restored.ordinal >= WizardStep.Browse.ordinal) {
            step = WizardStep.Connection.name
            errorMessage = null
        }
    }

    fun defaultPortFor(proto: RemoteImportProtocol): Int =
        if (proto == RemoteImportProtocol.FTP) FtpImportClient.DEFAULT_PORT else SftpImportClient.DEFAULT_PORT

    fun parsedPortOrNull(): Int? {
        val trimmed = portText.trim()
        if (trimmed.isEmpty()) return defaultPortFor(selectedProtocol)
        return trimmed.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    fun parsedPort(): Int =
        parsedPortOrNull() ?: defaultPortFor(selectedProtocol)

    fun resolvedSelection(): Pair<String, Boolean>? {
        val path = selectedPath ?: manualPath.trim().takeIf { it.isNotBlank() } ?: return null
        val asDirectory = when {
            selectedPath != null -> selectedIsDirectory
            else -> RemoteBrowseHelpers.looksLikeDirectory(path)
        }
        return path to asDirectory
    }

    fun canGoNext(): Boolean = when (currentStep) {
        WizardStep.Protocol -> true
        WizardStep.Device -> selectedDeviceId != null
        WizardStep.Connection ->
            host.isNotBlank() && username.isNotBlank() && parsedPortOrNull() != null && !busy
        WizardStep.Browse -> {
            val selection = resolvedSelection()
            selection != null && !busy && (
                selection.second ||
                    RemoteBrowseHelpers.isImportableFile(RemoteBrowseHelpers.fileName(selection.first))
                )
        }
        WizardStep.Confirm -> {
            if (busy) {
                false
            } else if (selectedIsDirectory || RemoteBrowseHelpers.looksLikeDirectory(selectedPath ?: manualPath)) {
                selectedCsvCount in 1..RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES
            } else {
                true
            }
        }
    }

    suspend fun listDirectory(path: String): Result<List<RemoteEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            when (selectedProtocol) {
                RemoteImportProtocol.FTP -> importers.listFtp(
                    host = host.trim(),
                    port = parsedPort(),
                    username = username.trim(),
                    password = password,
                    path = path,
                )
                RemoteImportProtocol.SFTP -> importers.listSftp(
                    host = host.trim(),
                    port = parsedPort(),
                    username = username.trim(),
                    password = password,
                    path = path,
                )
            }
        }
    }

    suspend fun countCsvUnder(path: String): Int = withContext(Dispatchers.IO) {
        when (selectedProtocol) {
            RemoteImportProtocol.FTP -> importers.listCsvRecursiveFtp(
                host = host.trim(),
                port = parsedPort(),
                username = username.trim(),
                password = password,
                path = path,
            ).size
            RemoteImportProtocol.SFTP -> importers.listCsvRecursiveSftp(
                host = host.trim(),
                port = parsedPort(),
                username = username.trim(),
                password = password,
                path = path,
            ).size
        }
    }

    fun connectAndBrowse() {
        scope.launch {
            busy = true
            errorMessage = null
            val homePath = withContext(Dispatchers.IO) {
                runCatching {
                    when (selectedProtocol) {
                        RemoteImportProtocol.FTP -> importers.homeDirectoryFtp(
                            host = host.trim(),
                            port = parsedPort(),
                            username = username.trim(),
                            password = password,
                        )
                        RemoteImportProtocol.SFTP -> importers.homeDirectorySftp(
                            host = host.trim(),
                            port = parsedPort(),
                            username = username.trim(),
                            password = password,
                        )
                    }
                }.getOrDefault("/")
            }
            var startPath = RemoteBrowseHelpers.normalizeDirectory(homePath)
            var result = listDirectory(startPath)
            if (result.isFailure && startPath != "/") {
                startPath = "/"
                result = listDirectory(startPath)
            }
            busy = false
            result.fold(
                onSuccess = {
                    entries = it
                    currentPath = startPath
                    selectedPath = null
                    selectedIsDirectory = false
                    selectedCsvCount = 0
                    step = WizardStep.Browse.name
                },
                onFailure = { errorMessage = it.message ?: "Connection failed" },
            )
        }
    }

    fun openDirectory(path: String) {
        scope.launch {
            busy = true
            errorMessage = null
            val result = listDirectory(path)
            busy = false
            result.fold(
                onSuccess = {
                    entries = it
                    currentPath = RemoteBrowseHelpers.normalizeDirectory(path)
                    selectedPath = null
                    selectedIsDirectory = false
                    selectedCsvCount = 0
                },
                onFailure = { errorMessage = it.message ?: "List failed" },
            )
        }
    }

    fun selectDirectory(path: String) {
        scope.launch {
            busy = true
            errorMessage = null
            val normalized = RemoteBrowseHelpers.normalizeDirectory(path)
            runCatching { countCsvUnder(normalized) }
                .fold(
                    onSuccess = { count ->
                        if (count == 0) {
                            errorMessage = "No CSV files found under $normalized"
                        } else {
                            selectedPath = normalized
                            selectedIsDirectory = true
                            selectedCsvCount = count
                            manualPath = normalized
                            errorMessage = null
                        }
                    },
                    onFailure = { errorMessage = it.message ?: "List failed" },
                )
            busy = false
        }
    }

    fun goBack() {
        errorMessage = null
        step = when (currentStep) {
            WizardStep.Protocol -> {
                onDismiss()
                return
            }
            WizardStep.Device -> WizardStep.Protocol.name
            WizardStep.Connection -> WizardStep.Device.name
            WizardStep.Browse -> WizardStep.Connection.name
            WizardStep.Confirm -> WizardStep.Browse.name
        }
    }

    fun goNext() {
        errorMessage = null
        when (currentStep) {
            WizardStep.Protocol -> step = WizardStep.Device.name
            WizardStep.Device -> {
                portText = defaultPortFor(selectedProtocol).toString()
                step = WizardStep.Connection.name
            }
            WizardStep.Connection -> connectAndBrowse()
            WizardStep.Browse -> {
                val selection = resolvedSelection() ?: return
                val (path, asDirectory) = selection
                if (asDirectory) {
                    val normalized = RemoteBrowseHelpers.normalizeDirectory(path)
                    // Reuse the count from "Select this folder" to avoid a second full remote walk.
                    if (selectedIsDirectory && selectedPath == normalized && selectedCsvCount > 0) {
                        manualPath = normalized
                        step = WizardStep.Confirm.name
                        return
                    }
                    scope.launch {
                        busy = true
                        errorMessage = null
                        val result = runCatching { countCsvUnder(normalized) }
                        busy = false
                        result.fold(
                            onSuccess = { count ->
                                if (count == 0) {
                                    errorMessage = "No CSV files found under $normalized"
                                } else {
                                    selectedPath = normalized
                                    selectedIsDirectory = true
                                    selectedCsvCount = count
                                    manualPath = normalized
                                    step = WizardStep.Confirm.name
                                }
                            },
                            onFailure = { errorMessage = it.message ?: "List failed" },
                        )
                    }
                } else {
                    selectedPath = path
                    selectedIsDirectory = false
                    selectedCsvCount = 1
                    manualPath = path
                    step = WizardStep.Confirm.name
                }
            }
            WizardStep.Confirm -> {
                val deviceId = selectedDeviceId ?: return
                val path = selectedPath ?: manualPath.trim()
                if (path.isBlank()) return
                val asDirectory = selectedIsDirectory || RemoteBrowseHelpers.looksLikeDirectory(path)
                if (asDirectory && selectedCsvCount > RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES) {
                    errorMessage = "Folder import exceeds ${RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES} CSV files " +
                        "(found $selectedCsvCount). Choose a smaller subfolder."
                    return
                }
                scope.launch {
                    busy = true
                    errorMessage = null
                    progressCurrent = 0
                    progressTotal = 0
                    val request = when (selectedProtocol) {
                        RemoteImportProtocol.FTP -> ImportRequest.FtpRequest(
                            deviceId = deviceId,
                            host = host.trim(),
                            port = parsedPort(),
                            username = username.trim(),
                            password = password,
                            path = path,
                            directory = asDirectory,
                            clearBeforeImport = clearBeforeImport,
                            sourceLabel = if (asDirectory) "FTP folder $host:$path" else "FTP $host:$path",
                        )
                        RemoteImportProtocol.SFTP -> ImportRequest.SftpRequest(
                            deviceId = deviceId,
                            host = host.trim(),
                            port = parsedPort(),
                            username = username.trim(),
                            password = password,
                            path = path,
                            directory = asDirectory,
                            clearBeforeImport = clearBeforeImport,
                            sourceLabel = if (asDirectory) "SFTP folder $host:$path" else "SFTP $host:$path",
                        )
                    }
                    val result = importManager.run(request) { current, total ->
                        // Throttle Main updates: avoid one coroutine per file on ~25k imports.
                        if (current == 1 || current == total || current % 25 == 0) {
                            scope.launch(Dispatchers.Main.immediate) {
                                progressCurrent = current
                                progressTotal = total
                            }
                        }
                    }
                    busy = false
                    progressCurrent = 0
                    progressTotal = 0
                    result.fold(
                        onSuccess = {
                            onImportSucceeded()
                            onDismiss()
                        },
                        onFailure = { errorMessage = it.message ?: "Import failed" },
                    )
                }
            }
        }
    }

    // Full-screen Dialog so the footer is not clipped by the app NavigationBar /
    // gesture inset. decorFitsSystemWindows=false + explicit safeDrawing padding.
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !busy,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
            color = colors.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.remote_import_wizard_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(
                                    R.string.remote_import_step_of,
                                    currentStep.ordinal + 1,
                                    WizardStep.entries.size,
                                ),
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        when (currentStep) {
                            WizardStep.Protocol -> ProtocolStep(
                                selected = selectedProtocol,
                                onSelect = {
                                    protocol = it.name
                                    portText = defaultPortFor(it).toString()
                                },
                            )
                            WizardStep.Device -> DeviceStep(
                                devices = devices,
                                selectedDeviceId = selectedDeviceId,
                                onSelect = { selectedDeviceId = it },
                            )
                            WizardStep.Connection -> ConnectionStep(
                                protocol = selectedProtocol,
                                host = host,
                                onHostChange = { host = it },
                                portText = portText,
                                onPortChange = { portText = it.filter(Char::isDigit).take(5) },
                                username = username,
                                onUsernameChange = { username = it },
                                password = password,
                                onPasswordChange = { password = it },
                                showPassword = showPassword,
                                onTogglePassword = { showPassword = !showPassword },
                            )
                            WizardStep.Browse -> BrowseStep(
                                currentPath = currentPath,
                                entries = entries,
                                selectedPath = selectedPath,
                                selectedIsDirectory = selectedIsDirectory,
                                selectedCsvCount = selectedCsvCount,
                                manualPath = manualPath,
                                busy = busy,
                                onManualPathChange = {
                                    manualPath = it
                                    selectedPath = null
                                    selectedIsDirectory = false
                                    selectedCsvCount = 0
                                },
                                onOpenParent = {
                                    RemoteBrowseHelpers.parentPath(currentPath)?.let(::openDirectory)
                                },
                                onOpenDirectory = { openDirectory(it.path) },
                                onSelectFile = {
                                    selectedPath = it.path
                                    selectedIsDirectory = false
                                    selectedCsvCount = 1
                                    manualPath = it.path
                                },
                                onSelectDirectory = { selectDirectory(it) },
                            )
                            WizardStep.Confirm -> ConfirmStep(
                                protocol = selectedProtocol,
                                deviceName = devices.firstOrNull { it.id == selectedDeviceId }?.name.orEmpty(),
                                host = host.trim(),
                                port = parsedPort(),
                                username = username.trim(),
                                path = selectedPath ?: manualPath.trim(),
                                isDirectory = selectedIsDirectory,
                                csvCount = selectedCsvCount,
                                clearBeforeImport = clearBeforeImport,
                                onClearBeforeImportChange = { clearBeforeImport = it },
                                enabled = !busy,
                            )
                        }
                    }

                    if (progressTotal > 0) {
                        Spacer(Modifier.height(10.dp))
                        val fraction = (progressCurrent.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.remote_import_progress, progressCurrent, progressTotal),
                            color = colors.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    errorMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = ::goBack,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (currentStep == WizardStep.Protocol) {
                                stringResource(R.string.cancel)
                            } else {
                                stringResource(R.string.remote_import_back)
                            },
                        )
                    }
                    Button(
                        onClick = ::goNext,
                        enabled = canGoNext(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(18.dp).height(18.dp),
                                strokeWidth = 2.dp,
                                color = colors.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when (currentStep) {
                                WizardStep.Connection -> stringResource(R.string.remote_import_connect)
                                WizardStep.Confirm -> stringResource(R.string.remote_import_start)
                                else -> stringResource(R.string.remote_import_next)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolStep(
    selected: RemoteImportProtocol,
    onSelect: (RemoteImportProtocol) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.remote_import_choose_protocol),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ProtocolChip(
            label = stringResource(R.string.remote_import_protocol_sftp),
            selected = selected == RemoteImportProtocol.SFTP,
            onClick = { onSelect(RemoteImportProtocol.SFTP) },
        )
        ProtocolChip(
            label = stringResource(R.string.remote_import_protocol_ftp),
            selected = selected == RemoteImportProtocol.FTP,
            onClick = { onSelect(RemoteImportProtocol.FTP) },
        )
        if (selected == RemoteImportProtocol.FTP) {
            Text(
                stringResource(R.string.remote_import_ftp_insecure_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (selected == RemoteImportProtocol.SFTP) {
            Text(
                stringResource(R.string.remote_import_sftp_hostkey_warning),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProtocolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primary else colors.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        color = if (selected) colors.onPrimary else colors.onSurface,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun DeviceStep(
    devices: List<DeviceProfileEntity>,
    selectedDeviceId: Long?,
    onSelect: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.remote_import_choose_device),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.remote_import_choose_device_body),
            color = colors.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(devices, key = { it.id }) { device ->
                val selected = selectedDeviceId == device.id
                Text(
                    text = device.name,
                    modifier = Modifier
                        .background(
                            if (selected) colors.primary else colors.surfaceVariant,
                            RoundedCornerShape(16.dp),
                        )
                        .clickable { onSelect(device.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (selected) colors.onPrimary else colors.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ConnectionStep(
    protocol: RemoteImportProtocol,
    host: String,
    onHostChange: (String) -> Unit,
    portText: String,
    onPortChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(
                if (protocol == RemoteImportProtocol.FTP) {
                    R.string.remote_import_connection_ftp
                } else {
                    R.string.remote_import_connection_sftp
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_import_host)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = portText,
            onValueChange = onPortChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_import_port)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_import_username)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_import_password)) },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = onTogglePassword) {
                    Text(stringResource(if (showPassword) R.string.hide else R.string.show))
                }
            },
        )
    }
}

@Composable
private fun BrowseStep(
    currentPath: String,
    entries: List<RemoteEntry>,
    selectedPath: String?,
    selectedIsDirectory: Boolean,
    selectedCsvCount: Int,
    manualPath: String,
    busy: Boolean,
    onManualPathChange: (String) -> Unit,
    onOpenParent: () -> Unit,
    onOpenDirectory: (RemoteEntry) -> Unit,
    onSelectFile: (RemoteEntry) -> Unit,
    onSelectDirectory: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.remote_import_browse_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(currentPath, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (RemoteBrowseHelpers.parentPath(currentPath) != null) {
                OutlinedButton(onClick = onOpenParent, enabled = !busy) {
                    Text(stringResource(R.string.remote_import_parent_folder))
                }
            }
            OutlinedButton(
                onClick = { onSelectDirectory(currentPath) },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.remote_import_select_folder))
            }
        }
        if (selectedIsDirectory && selectedPath != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.remote_import_folder_selected, selectedPath, selectedCsvCount),
                color = colors.primary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (entries.isEmpty() && !busy) {
                item {
                    Text(
                        stringResource(R.string.remote_import_empty_folder),
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            items(entries, key = { it.path }) { entry ->
                val selected = selectedPath == entry.path
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) colors.primary.copy(alpha = 0.18f) else colors.surfaceVariant,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable(enabled = !busy) {
                            if (entry.isDirectory) onOpenDirectory(entry) else onSelectFile(entry)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                        contentDescription = null,
                        tint = if (selected) colors.primary else colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.name, fontWeight = FontWeight.Medium)
                        if (entry.isDirectory) {
                            Text(
                                stringResource(R.string.remote_import_folder_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        } else {
                            entry.size?.let {
                                Text(
                                    stringResource(R.string.remote_import_file_size, it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (entry.isDirectory) {
                        TextButton(
                            onClick = { onSelectDirectory(entry.path) },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.remote_import_select))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = manualPath,
            onValueChange = onManualPathChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_import_manual_path)) },
            singleLine = true,
            enabled = !busy,
        )
    }
}

@Composable
private fun ConfirmStep(
    protocol: RemoteImportProtocol,
    deviceName: String,
    host: String,
    port: Int,
    username: String,
    path: String,
    isDirectory: Boolean,
    csvCount: Int,
    clearBeforeImport: Boolean,
    onClearBeforeImportChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.remote_import_confirm_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ConfirmRow(stringResource(R.string.remote_import_protocol), protocol.name)
        ConfirmRow(stringResource(R.string.remote_import_target_device), deviceName)
        ConfirmRow(stringResource(R.string.remote_import_host), "$host:$port")
        ConfirmRow(stringResource(R.string.remote_import_username), username)
        ConfirmRow(
            if (isDirectory) stringResource(R.string.remote_import_folder) else stringResource(R.string.remote_import_path),
            path,
        )
        if (isDirectory) {
            ConfirmRow(stringResource(R.string.remote_import_csv_count), csvCount.toString())
            if (csvCount > RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES) {
                Text(
                    stringResource(
                        R.string.remote_import_folder_limit,
                        RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES,
                        csvCount,
                    ),
                    color = colors.error,
                )
            }
        }
        Text(
            stringResource(
                if (isDirectory) R.string.remote_import_confirm_folder_body else R.string.remote_import_confirm_body,
            ),
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = clearBeforeImport,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onClearBeforeImportChange,
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = clearBeforeImport,
                onCheckedChange = null,
                enabled = enabled,
            )
            Column(modifier = Modifier.padding(start = 4.dp, top = 12.dp)) {
                Text(
                    stringResource(R.string.remote_import_clear_before),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.remote_import_clear_before_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}


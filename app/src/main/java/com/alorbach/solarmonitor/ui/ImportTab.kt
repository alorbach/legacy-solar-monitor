package com.alorbach.solarmonitor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.alorbach.solarmonitor.BuildConfig
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.AppContainer
import com.alorbach.solarmonitor.data.cloud.BackupTrigger
import com.alorbach.solarmonitor.data.cloud.CloudBackupPolicy
import com.alorbach.solarmonitor.data.importing.ImportRequest
import com.alorbach.solarmonitor.data.importing.canReplay
import com.alorbach.solarmonitor.data.importing.groupImportJobs
import com.alorbach.solarmonitor.data.importing.replayConfig
import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.DeviceTransport
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportJobStatus
import com.alorbach.solarmonitor.data.model.ImportSourceType
import com.alorbach.solarmonitor.data.model.PortfolioSummary
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import com.alorbach.solarmonitor.data.settings.AppSettings
import com.alorbach.solarmonitor.device.BluetoothDeviceDescriptor
import com.alorbach.solarmonitor.domain.YieldFormatting
import com.alorbach.solarmonitor.i18n.LocaleController
import com.alorbach.solarmonitor.work.ScheduledImportWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun ImportTab(
    modifier: Modifier,
    devices: List<DeviceProfileEntity>,
    importJobs: List<ImportJobEntity>,
    container: AppContainer,
    onDataChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedDeviceId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(devices) {
        if (selectedDeviceId == null || devices.none { it.id == selectedDeviceId }) {
            selectedDeviceId = devices.firstOrNull()?.id
        }
    }
    var importUrl by rememberSaveable { mutableStateOf("") }
    val importProgress by container.importManager.progress.collectAsStateWithLifecycle()
    val importRunning = importProgress.running
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importSuccess by remember { mutableStateOf(false) }
    var showRemoteWizard by rememberSaveable { mutableStateOf(false) }
    var showClearImportJobsConfirm by remember { mutableStateOf(false) }
    var deleteJobConfirm by remember { mutableStateOf<ImportJobEntity?>(null) }
    var expandedGroupKeys by remember { mutableStateOf(setOf<String>()) }
    var rerunJob by remember { mutableStateOf<ImportJobEntity?>(null) }
    var rerunUsername by remember { mutableStateOf("") }
    var rerunPassword by remember { mutableStateOf("") }
    var rerunPort by remember { mutableStateOf("") }
    var rerunUrl by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val importFailedMessage = stringResource(R.string.import_failed)
    val context = androidx.compose.ui.platform.LocalContext.current
    var scheduleHours by rememberSaveable { mutableStateOf("6") }
    var showBatteryPrompt by remember { mutableStateOf(false) }
    val alreadyRunningMessage = stringResource(R.string.import_already_running)

    LaunchedEffect(importProgress.generation) {
        if (importProgress.generation == 0L || importProgress.running) return@LaunchedEffect
        importProgress.lastMessage?.let { importMessage = it }
        importProgress.lastSuccess?.let { success ->
            importSuccess = success
            onDataChanged()
        }
    }

    fun launchForegroundImport(request: ImportRequest, overwriteCopyPath: String? = null) {
        importMessage = null
        if (request is ImportRequest.FileRequest) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    request.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        if (!container.importManager.startForegroundImport(context, request, overwriteCopyPath)) {
            importSuccess = false
            importMessage = alreadyRunningMessage
        }
    }

    fun startRerun(
        job: ImportJobEntity,
        usernameOverride: String? = null,
        passwordOverride: String? = null,
        portOverride: Int? = null,
        urlOverride: String? = null,
    ) {
        scope.launch {
            importMessage = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    container.importManager.replayRequest(
                        job = job,
                        usernameOverride = usernameOverride,
                        passwordOverride = passwordOverride,
                        portOverride = portOverride,
                        urlOverride = urlOverride,
                    )
                }
            }
            result.fold(
                onSuccess = { launchForegroundImport(it, job.preservedCopyPath) },
                onFailure = {
                    importSuccess = false
                    importMessage = it.message ?: importFailedMessage
                },
            )
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val deviceId = selectedDeviceId ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        launchForegroundImport(
            ImportRequest.FileRequest(
                deviceId = deviceId,
                uri = uri,
                sourceLabel = uri.lastPathSegment ?: context.getString(R.string.import_source_local_file),
            )
        )
    }

    val jobGroups = remember(importJobs) { groupImportJobs(importJobs) }

    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.import_sources), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.import_sources_body))
                    if (devices.isEmpty()) {
                        Text(stringResource(R.string.import_add_device_first), color = colors.onSurfaceVariant)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(devices, key = { it.id }) { device ->
                                DeviceChip(
                                    label = device.name,
                                    selected = selectedDeviceId == device.id,
                                    onClick = { selectedDeviceId = device.id },
                                )
                            }
                        }
                        Button(
                            enabled = selectedDeviceId != null && !importRunning,
                            onClick = {
                                filePicker.launch(
                                    arrayOf(
                                        "text/*",
                                        "application/zip",
                                        "application/octet-stream",
                                        "*/*",
                                    )
                                )
                            },
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = stringResource(R.string.import_from_file))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.import_from_file))
                        }
                        Button(
                            enabled = !importRunning,
                            onClick = { showRemoteWizard = true },
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = stringResource(R.string.import_from_remote))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.import_from_remote))
                        }
                        OutlinedTextField(
                            value = importUrl,
                            onValueChange = { importUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.import_url)) },
                            supportingText = { Text(stringResource(R.string.import_url_hint)) },
                            singleLine = true,
                        )
                        Button(
                            enabled = selectedDeviceId != null && importUrl.isNotBlank() && !importRunning,
                            onClick = {
                                val deviceId = selectedDeviceId ?: return@Button
                                launchForegroundImport(
                                    ImportRequest.UrlRequest(
                                        deviceId = deviceId,
                                        url = importUrl.trim(),
                                    )
                                )
                            },
                        ) {
                            if (importRunning) {
                                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.import_running))
                            } else {
                                Icon(Icons.Rounded.CloudUpload, contentDescription = stringResource(R.string.import_from_url))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.import_from_url))
                            }
                        }
                        importMessage?.let {
                            Text(it, color = if (importSuccess) colors.primary else colors.error)
                        }
                        if (importRunning) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                                Text(
                                    if (importProgress.total > 0) {
                                        stringResource(
                                            R.string.import_notification_progress,
                                            importProgress.current,
                                            importProgress.total,
                                        )
                                    } else {
                                        stringResource(R.string.import_in_progress)
                                    },
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (importJobs.isEmpty()) {
            item {
                EmptyStateCard(
                    stringResource(R.string.no_imports_yet),
                    stringResource(R.string.no_imports_body),
                )
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = scheduleHours,
                        onValueChange = { scheduleHours = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.schedule_import_for_jobs)) },
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = { showClearImportJobsConfirm = true },
                        enabled = !importRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.clear_import_jobs))
                    }
                }
            }
        }
        items(jobGroups, key = { it.key }) { group ->
            val job = group.latest
            val historyExpanded = group.key in expandedGroupKeys
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceVariant),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            group.sourceLabel,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (job.canReplay() && devices.any { it.id == job.deviceId }) {
                            IconButton(
                                enabled = !importRunning,
                                onClick = {
                                    val config = job.replayConfig() ?: return@IconButton
                                    rerunJob = job
                                    rerunUsername = config.username.orEmpty()
                                    rerunPassword = ""
                                    rerunPort = config.port?.toString().orEmpty()
                                    rerunUrl = config.url.orEmpty()
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.rerun_import_job),
                                )
                            }
                        }
                        IconButton(
                            enabled = !importRunning,
                            onClick = { deleteJobConfirm = job },
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.delete_import_job),
                            )
                        }
                    }
                    Text(
                        stringResource(
                            R.string.import_job_status,
                            importSourceTypeLabel(job.sourceType),
                            stringResource(
                                when (job.status) {
                                    ImportJobStatus.PENDING -> R.string.import_status_pending
                                    ImportJobStatus.RUNNING -> R.string.import_status_running
                                    ImportJobStatus.SUCCEEDED -> R.string.import_status_succeeded
                                    ImportJobStatus.FAILED -> R.string.import_status_failed
                                },
                            ),
                        ),
                    )
                    job.message?.let { Text(it) }
                    job.preservedCopyPath?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (job.canReplay()) {
                        OutlinedButton(
                            enabled = !importRunning,
                            onClick = {
                                val hours = scheduleHours.toLongOrNull()?.coerceIn(1L, 168L) ?: 6L
                                if (ScheduledImportWorker.enqueueJob(context, job, hours)) {
                                    importSuccess = true
                                    importMessage = context.getString(
                                        R.string.schedule_import_saved,
                                        hours.toInt(),
                                    )
                                    if (!context.isBatteryUnrestricted()) {
                                        showBatteryPrompt = true
                                    }
                                } else {
                                    importSuccess = false
                                    importMessage = context.getString(R.string.schedule_import_failed)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.schedule_import))
                        }
                    }
                    if (group.history.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                expandedGroupKeys = if (historyExpanded) {
                                    expandedGroupKeys - group.key
                                } else {
                                    expandedGroupKeys + group.key
                                }
                            },
                        ) {
                            Icon(
                                if (historyExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (historyExpanded) {
                                    stringResource(R.string.import_hide_history)
                                } else {
                                    stringResource(R.string.import_show_history, group.history.size)
                                },
                            )
                        }
                    }
                    if (historyExpanded) {
                        group.history.forEach { past ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        formatEpochSeconds(
                                            past.completedAtEpochSeconds ?: past.createdAtEpochSeconds,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        enabled = !importRunning,
                                        onClick = { deleteJobConfirm = past },
                                    ) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = stringResource(R.string.delete_import_job),
                                        )
                                    }
                                }
                                Text(
                                    stringResource(
                                        R.string.import_job_status,
                                        importSourceTypeLabel(past.sourceType),
                                        stringResource(
                                            when (past.status) {
                                                ImportJobStatus.PENDING -> R.string.import_status_pending
                                                ImportJobStatus.RUNNING -> R.string.import_status_running
                                                ImportJobStatus.SUCCEEDED -> R.string.import_status_succeeded
                                                ImportJobStatus.FAILED -> R.string.import_status_failed
                                            },
                                        ),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                past.message?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        if (showBatteryPrompt) {
            BatteryUnrestrictedPromptDialog(onDismiss = { showBatteryPrompt = false })
        }

        deleteJobConfirm?.let { job ->
            AlertDialog(
                onDismissRequest = { deleteJobConfirm = null },
                title = { Text(stringResource(R.string.delete_import_job_title)) },
                text = { Text(stringResource(R.string.delete_import_job_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val id = job.id
                            deleteJobConfirm = null
                            scope.launch {
                                container.repository.deleteImportJob(id)
                                container.cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.delete_import_job_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteJobConfirm = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        if (showClearImportJobsConfirm) {
            AlertDialog(
                onDismissRequest = { showClearImportJobsConfirm = false },
                title = { Text(stringResource(R.string.clear_import_jobs_title)) },
                text = { Text(stringResource(R.string.clear_import_jobs_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                container.repository.deleteAllImportJobs()
                                container.cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
                                showClearImportJobsConfirm = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.clear_import_jobs_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearImportJobsConfirm = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        rerunJob?.let { job ->
            val config = job.replayConfig()
            val isUrlKind = config?.kind == "URL"
            val showCredentialFields = config?.kind != null && !isUrlKind
            // EncryptedSharedPreferences init/read is slow — never do it during composition.
            var hasStoredSecret by remember(job.id, job.passwordCredentialId) {
                mutableStateOf<Boolean?>(null)
            }
            LaunchedEffect(job.id, job.passwordCredentialId) {
                val credentialId = job.passwordCredentialId
                hasStoredSecret = if (credentialId.isNullOrBlank()) {
                    false
                } else {
                    withContext(Dispatchers.IO) {
                        container.credentialStore.getSecret(credentialId) != null
                    }
                }
            }
            val secretReady = hasStoredSecret != null
            val hasSecret = hasStoredSecret == true
            val showUrlField = isUrlKind && (config.url.isNullOrBlank() || !hasSecret)
            val canConfirmRerun = when {
                !secretReady -> false
                showUrlField && rerunUrl.isBlank() && !hasSecret -> false
                else -> true
            }
            AlertDialog(
                onDismissRequest = { if (!importRunning) rerunJob = null },
                title = { Text(stringResource(R.string.rerun_import_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            when {
                                showCredentialFields ->
                                    stringResource(R.string.rerun_import_credentials_body)
                                showUrlField ->
                                    stringResource(R.string.rerun_import_url_body)
                                else ->
                                    stringResource(R.string.rerun_import_body)
                            },
                        )
                        Text(job.sourceLabel, fontWeight = FontWeight.SemiBold)
                        if (showUrlField) {
                            OutlinedTextField(
                                value = rerunUrl,
                                onValueChange = { rerunUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(
                                        if (hasSecret) {
                                            stringResource(R.string.rerun_import_url_optional)
                                        } else {
                                            stringResource(R.string.rerun_import_url)
                                        },
                                    )
                                },
                                singleLine = true,
                            )
                        }
                        if (showCredentialFields) {
                            OutlinedTextField(
                                value = rerunUsername,
                                onValueChange = { rerunUsername = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.rerun_import_username)) },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = rerunPassword,
                                onValueChange = { rerunPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(
                                        if (hasSecret) {
                                            stringResource(R.string.rerun_import_password_optional)
                                        } else {
                                            stringResource(R.string.rerun_import_password)
                                        },
                                    )
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            OutlinedTextField(
                                value = rerunPort,
                                onValueChange = { rerunPort = it.filter { ch -> ch.isDigit() }.take(5) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.rerun_import_port)) },
                                singleLine = true,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !importRunning && canConfirmRerun,
                        onClick = {
                            val target = job
                            rerunJob = null
                            when {
                                showCredentialFields ->
                                    startRerun(
                                        target,
                                        usernameOverride = rerunUsername.trim(),
                                        // Blank + stored secret → reuse; blank without → "".
                                        passwordOverride = if (hasSecret && rerunPassword.isEmpty()) {
                                            null
                                        } else {
                                            rerunPassword
                                        },
                                        portOverride = rerunPort.toIntOrNull()?.takeIf { it in 1..65535 },
                                    )
                                showUrlField ->
                                    startRerun(
                                        target,
                                        urlOverride = rerunUrl.trim().takeIf { it.isNotEmpty() },
                                    )
                                else -> startRerun(target)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.rerun_import_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !importRunning,
                        onClick = { rerunJob = null },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        if (showRemoteWizard) {
            RemoteImportWizard(
                devices = devices,
                importers = container.importers,
                importManager = container.importManager,
                initialDeviceId = selectedDeviceId,
                onDismiss = { showRemoteWizard = false },
            )
        }
    }
}

@Composable
private fun importSourceTypeLabel(type: ImportSourceType): String = stringResource(
    when (type) {
        ImportSourceType.FILE -> R.string.import_type_file
        ImportSourceType.ZIP -> R.string.import_type_zip
        ImportSourceType.FTP -> R.string.import_type_ftp
        ImportSourceType.SFTP -> R.string.import_type_sftp
        ImportSourceType.URL -> R.string.import_type_url
        ImportSourceType.SQLITE_DB -> R.string.import_type_sqlite
    },
)

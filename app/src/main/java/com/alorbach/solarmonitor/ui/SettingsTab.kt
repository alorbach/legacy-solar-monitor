package com.alorbach.solarmonitor.ui

import android.app.Activity
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
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import com.alorbach.solarmonitor.data.importing.replayConfig
import com.alorbach.solarmonitor.data.model.DailyPoint
import com.alorbach.solarmonitor.data.model.DeviceDashboardSummary
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.DeviceTransport
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportJobStatus
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
fun SettingsTab(
    modifier: Modifier,
    container: AppContainer,
    settings: AppSettings,
    devices: List<DeviceProfileEntity>,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var bucket by rememberSaveable { mutableStateOf(settings.gcsBucket) }
    var prefix by rememberSaveable { mutableStateOf(settings.gcsPrefix) }
    // The signed URL is a credential kept in encrypted storage; saved instance state is not
    // encrypted, so it is only held in memory and re-read from the store after process death.
    // Empty storage shows a path built from bucket/prefix; signature query is still required.
    var signedUrl by remember {
        mutableStateOf(
            CloudBackupPolicy.displaySignedUrlTemplate(
                settings.gcsSignedUrl,
                settings.gcsBucket,
                settings.gcsPrefix,
            ),
        )
    }
    var bucketDirty by rememberSaveable { mutableStateOf(false) }
    var prefixDirty by rememberSaveable { mutableStateOf(false) }
    var signedUrlDirty by remember { mutableStateOf(false) }
    var signedUrlPathLocked by remember { mutableStateOf(false) }
    var includeDatabase by rememberSaveable { mutableStateOf(settings.backupIncludeDatabase) }
    var includeImports by rememberSaveable { mutableStateOf(settings.backupIncludeImportCopies) }
    var includeDatabaseDirty by rememberSaveable { mutableStateOf(false) }
    var includeImportsDirty by rememberSaveable { mutableStateOf(false) }
    var showSignedUrl by rememberSaveable { mutableStateOf(false) }
    var pollSeconds by rememberSaveable { mutableStateOf(settings.livePollIntervalSeconds.toString()) }
    val colors = MaterialTheme.colorScheme
    val neverLabel = stringResource(R.string.backup_never)
    val backupRunning by produceState(initialValue = false, context) {
        val liveData = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(CloudBackupPolicy.UNIQUE_WORK_NAME)
        val observer = Observer<List<WorkInfo>> { infos ->
            value = infos.any { it.state == WorkInfo.State.RUNNING }
        }
        liveData.observeForever(observer)
        awaitDispose { liveData.removeObserver(observer) }
    }

    fun refreshAutoPath(nextBucket: String = bucket, nextPrefix: String = prefix) {
        if (signedUrlPathLocked) return
        signedUrl = CloudBackupPolicy.withAutoPath(signedUrl, nextBucket, nextPrefix)
        signedUrlDirty = true
    }

    fun normalizeStoredSignedUrl(raw: String, nextBucket: String, nextPrefix: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed == CloudBackupPolicy.DEFAULT_SIGNED_URL_TEMPLATE) return ""
        if (trimmed == CloudBackupPolicy.buildPathTemplate(nextBucket, nextPrefix)) return ""
        if (trimmed == CloudBackupPolicy.buildDatabaseObjectUrl(nextBucket, nextPrefix)) return ""
        return trimmed
    }

    val signedUrlCoversOnlyDatabase = CloudBackupPolicy.selectableBackupFilenames(
        signedUrl,
        listOf(CloudBackupPolicy.DATABASE_BACKUP_FILENAME, "example.csv"),
    ) == listOf(CloudBackupPolicy.DATABASE_BACKUP_FILENAME)

    LaunchedEffect(
        settings.gcsBucket,
        settings.gcsPrefix,
        settings.gcsSignedUrl,
        settings.backupIncludeDatabase,
        settings.backupIncludeImportCopies,
    ) {
        if (!bucketDirty) bucket = settings.gcsBucket
        if (!prefixDirty) prefix = settings.gcsPrefix
        if (!signedUrlDirty) {
            signedUrl = CloudBackupPolicy.displaySignedUrlTemplate(
                settings.gcsSignedUrl,
                if (bucketDirty) bucket else settings.gcsBucket,
                if (prefixDirty) prefix else settings.gcsPrefix,
            )
            signedUrlPathLocked = false
        }
        if (!includeDatabaseDirty) includeDatabase = settings.backupIncludeDatabase
        if (!includeImportsDirty) includeImports = settings.backupIncludeImportCopies
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            BatteryUnrestrictedCard()
        }
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.widget_device), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.widget_device_hint), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeviceChip(
                            label = stringResource(R.string.widget_device_first),
                            selected = settings.widgetDeviceId == null,
                            onClick = {
                                scope.launch {
                                    container.settingsStore.update { it.copy(widgetDeviceId = null) }
                                    com.alorbach.solarmonitor.widget.SolarWidgets.refreshAll(context)
                                }
                            },
                        )
                        devices.forEach { device ->
                            DeviceChip(
                                label = device.name,
                                selected = settings.widgetDeviceId == device.id,
                                onClick = {
                                    scope.launch {
                                        container.settingsStore.update { it.copy(widgetDeviceId = device.id) }
                                        com.alorbach.solarmonitor.widget.SolarWidgets.refreshAll(context)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.live_poll_interval), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.live_poll_interval_hint), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = pollSeconds,
                        onValueChange = { pollSeconds = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.live_poll_interval)) },
                        singleLine = true,
                    )
                    Button(onClick = {
                        val seconds = pollSeconds.toLongOrNull()?.coerceIn(15L, 3600L) ?: 60L
                        pollSeconds = seconds.toString()
                        scope.launch {
                            container.settingsStore.update { it.copy(livePollIntervalSeconds = seconds) }
                        }
                    }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "" to stringResource(R.string.language_system),
                            "de" to stringResource(R.string.language_german),
                            "en" to stringResource(R.string.language_english),
                        ).forEach { (tag, label) ->
                            DeviceChip(
                                label = label,
                                selected = settings.languageTag == tag,
                                onClick = {
                                    scope.launch {
                                        container.settingsStore.update { it.copy(languageTag = tag) }
                                        LocaleController.apply(context, tag)
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                            (context as? Activity)?.recreate()
                                        }
                                    }
                                },
                            )
                        }
                    }
                    Text(stringResource(R.string.language_restart_hint), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.tab_settings))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.cloud_backup), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    OutlinedTextField(
                        value = bucket,
                        onValueChange = {
                            bucket = it
                            bucketDirty = true
                            refreshAutoPath(nextBucket = it)
                        },
                        label = { Text(stringResource(R.string.gcs_bucket)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = prefix,
                        onValueChange = {
                            prefix = it
                            prefixDirty = true
                            refreshAutoPath(nextPrefix = it)
                        },
                        label = { Text(stringResource(R.string.gcs_prefix)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = signedUrl,
                        onValueChange = {
                            signedUrl = it
                            signedUrlDirty = true
                            signedUrlPathLocked = true
                        },
                        label = { Text(stringResource(R.string.signed_url_template)) },
                        supportingText = { Text(stringResource(R.string.signed_url_template_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        visualTransformation = if (showSignedUrl) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showSignedUrl = !showSignedUrl }) {
                                Icon(
                                    imageVector = if (showSignedUrl) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = stringResource(
                                        if (showSignedUrl) R.string.hide else R.string.show,
                                    ),
                                )
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.backup_include_database), modifier = Modifier.weight(1f))
                        Switch(
                            checked = includeDatabase,
                            onCheckedChange = {
                                includeDatabase = it
                                includeDatabaseDirty = true
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.backup_include_import_copies))
                            if (signedUrlCoversOnlyDatabase && CloudBackupPolicy.isUploadConfigured(signedUrl)) {
                                Text(
                                    stringResource(R.string.backup_import_copies_signed_url_hint),
                                    color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Switch(
                            checked = includeImports &&
                                !(signedUrlCoversOnlyDatabase && CloudBackupPolicy.isUploadConfigured(signedUrl)),
                            enabled = !(signedUrlCoversOnlyDatabase && CloudBackupPolicy.isUploadConfigured(signedUrl)),
                            onCheckedChange = {
                                includeImports = it
                                includeImportsDirty = true
                            },
                        )
                    }
                    Text(stringResource(R.string.backup_status), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (backupRunning) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.backup_running), color = colors.onSurfaceVariant)
                        }
                    }
                    Text(
                        stringResource(R.string.backup_restore_not_supported),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(
                            R.string.backup_last_attempt,
                            settings.backupLastAttemptEpochSeconds?.let(::formatEpochSeconds) ?: neverLabel,
                        ),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(
                            R.string.backup_last_success,
                            settings.backupLastSuccessEpochSeconds?.let(::formatEpochSeconds) ?: neverLabel,
                        ),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (settings.backupLastMessage.isNotBlank()) {
                        Text(
                            settings.backupLastMessage,
                            color = when (settings.backupLastOk) {
                                true -> colors.primary
                                false -> colors.error
                                null -> colors.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (!settings.cloudBackupEnabled) {
                        Text(
                            stringResource(R.string.backup_not_configured),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = {
                        scope.launch {
                            container.settingsStore.update { stored ->
                                val nextBucket = if (bucketDirty) bucket else stored.gcsBucket
                                val nextPrefix = if (prefixDirty) prefix else stored.gcsPrefix
                                val raw = if (signedUrlDirty) signedUrl.trim() else stored.gcsSignedUrl
                                val url = normalizeStoredSignedUrl(raw, nextBucket, nextPrefix)
                                stored.copy(
                                    cloudBackupEnabled = CloudBackupPolicy.isUploadConfigured(url),
                                    gcsBucket = nextBucket,
                                    gcsPrefix = nextPrefix,
                                    gcsSignedUrl = url,
                                    backupIncludeDatabase = if (includeDatabaseDirty) includeDatabase else stored.backupIncludeDatabase,
                                    backupIncludeImportCopies = if (includeImportsDirty) includeImports else stored.backupIncludeImportCopies,
                                )
                            }
                            bucketDirty = false
                            prefixDirty = false
                            signedUrlDirty = false
                            signedUrlPathLocked = false
                            includeDatabaseDirty = false
                            includeImportsDirty = false
                        }
                    }) {
                        Text(stringResource(R.string.save_backup_settings))
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                if (bucketDirty || prefixDirty || signedUrlDirty || includeDatabaseDirty || includeImportsDirty) {
                                    container.settingsStore.update { stored ->
                                        val nextBucket = if (bucketDirty) bucket else stored.gcsBucket
                                        val nextPrefix = if (prefixDirty) prefix else stored.gcsPrefix
                                        val raw = if (signedUrlDirty) signedUrl.trim() else stored.gcsSignedUrl
                                        val url = normalizeStoredSignedUrl(raw, nextBucket, nextPrefix)
                                        stored.copy(
                                            cloudBackupEnabled = CloudBackupPolicy.isUploadConfigured(url),
                                            gcsBucket = nextBucket,
                                            gcsPrefix = nextPrefix,
                                            gcsSignedUrl = url,
                                            backupIncludeDatabase = if (includeDatabaseDirty) includeDatabase else stored.backupIncludeDatabase,
                                            backupIncludeImportCopies = if (includeImportsDirty) includeImports else stored.backupIncludeImportCopies,
                                        )
                                    }
                                    bucketDirty = false
                                    prefixDirty = false
                                    signedUrlDirty = false
                                    signedUrlPathLocked = false
                                    includeDatabaseDirty = false
                                    includeImportsDirty = false
                                }
                                container.cloudBackupCoordinator.enqueue(BackupTrigger.Manual)
                            }
                        },
                        enabled = !backupRunning && (
                            settings.cloudBackupEnabled ||
                                CloudBackupPolicy.isUploadConfigured(signedUrl)
                            ),
                    ) {
                        Text(stringResource(R.string.backup_now))
                    }
                }
            }
        }
    }
}

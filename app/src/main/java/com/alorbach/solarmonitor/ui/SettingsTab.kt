package com.alorbach.solarmonitor.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.alorbach.solarmonitor.BuildConfig
import com.alorbach.solarmonitor.MainActivity
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.AppContainer
import com.alorbach.solarmonitor.data.cloud.BackupTrigger
import com.alorbach.solarmonitor.data.cloud.CloudBackupPolicy
import com.alorbach.solarmonitor.data.cloud.GoogleDriveSignInStart
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
import com.alorbach.solarmonitor.data.settings.ChartBarAccent
import com.alorbach.solarmonitor.device.BluetoothDeviceDescriptor
import com.alorbach.solarmonitor.domain.HomeWifiPolicy
import com.alorbach.solarmonitor.domain.LivePollWindow
import com.alorbach.solarmonitor.domain.YieldFormatting
import com.alorbach.solarmonitor.i18n.LocaleController
import com.alorbach.solarmonitor.service.LivePollScheduler
import com.alorbach.solarmonitor.work.ScheduledImportWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var includeDatabase by rememberSaveable { mutableStateOf(settings.backupIncludeDatabase) }
    var includeImports by rememberSaveable { mutableStateOf(settings.backupIncludeImportCopies) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var restoreRunning by remember { mutableStateOf(false) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }
    var restoreSuccess by remember { mutableStateOf(false) }
    var signInBusy by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    var pollSeconds by rememberSaveable { mutableStateOf(settings.livePollIntervalSeconds.toString()) }
    var pickerTarget by remember { mutableStateOf<PollWindowBound?>(null) }
    val colors = MaterialTheme.colorScheme
    val neverLabel = stringResource(R.string.backup_never)
    val signedIn = CloudBackupPolicy.isAccountConfigured(settings.googleAccountEmail)
    val googleClientReady = container.googleDriveAuth.isClientConfigured()
    val playServicesReady = container.googleDriveAuth.isPlayServicesAvailable()
    val backupRunning by produceState(initialValue = false, context) {
        val liveData = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(CloudBackupPolicy.UNIQUE_WORK_NAME)
        val observer = Observer<List<WorkInfo>> { infos ->
            value = infos.any { it.state == WorkInfo.State.RUNNING }
        }
        liveData.observeForever(observer)
        awaitDispose { liveData.removeObserver(observer) }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        scope.launch {
            signInBusy = true
            try {
                if (result.resultCode != Activity.RESULT_OK) {
                    signInError = context.getString(R.string.backup_sign_in_cancelled)
                    return@launch
                }
                val email = container.googleDriveAuth.completeSignIn(result.data)
                container.settingsStore.update {
                    it.copy(googleAccountEmail = email, driveFolderId = "")
                }
                signInError = null
            } catch (error: Throwable) {
                signInError = error.message ?: context.getString(R.string.backup_sign_in_failed)
            } finally {
                signInBusy = false
            }
        }
    }

    fun startGoogleSignIn() {
        scope.launch {
            signInBusy = true
            signInError = null
            var launchedUi = false
            try {
                when (val start = container.googleDriveAuth.beginSignIn(settings.googleAccountEmail)) {
                    is GoogleDriveSignInStart.Completed -> {
                        container.settingsStore.update {
                            it.copy(googleAccountEmail = start.email, driveFolderId = "")
                        }
                    }
                    is GoogleDriveSignInStart.NeedsUi -> {
                        launchedUi = true
                        signInLauncher.launch(
                            IntentSenderRequest.Builder(start.intentSender).build(),
                        )
                    }
                }
            } catch (error: Throwable) {
                signInError = error.message ?: context.getString(R.string.backup_sign_in_failed)
            } finally {
                if (!launchedUi) signInBusy = false
            }
        }
    }

    suspend fun persistBackupToggles() {
        container.settingsStore.update { stored ->
            stored.copy(
                backupIncludeDatabase = includeDatabase,
                backupIncludeImportCopies = includeImports,
            )
        }
    }

    LaunchedEffect(settings.backupIncludeDatabase, settings.backupIncludeImportCopies) {
        includeDatabase = settings.backupIncludeDatabase
        includeImports = settings.backupIncludeImportCopies
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_background),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = stringResource(R.string.app_name),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(R.string.about),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(R.string.app_name),
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.app_subtitle),
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.about_free),
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.about_not_affiliated),
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.about_author),
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.about_email),
                        color = colors.primary,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        ),
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(context.getString(R.string.about_email_uri)),
                                    ),
                                )
                            }
                        },
                    )
                    Text(
                        text = stringResource(R.string.about_privacy),
                        color = colors.primary,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        ),
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(context.getString(R.string.about_privacy_url)),
                                    ),
                                )
                            }
                        },
                    )
                    Text(
                        text = stringResource(R.string.about_github),
                        color = colors.primary,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        ),
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(context.getString(R.string.about_github_url)),
                                    ),
                                )
                            }
                        },
                    )
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
            HomeWifiSettingsCard(
                settings = settings,
                currentSsid = container.homeWifiChecker.currentSsid(),
                onUpdate = { transform ->
                    scope.launch {
                        container.settingsStore.update(transform)
                        LivePollScheduler.syncAfterSettingsChange(context)
                    }
                },
            )
        }
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.chart_bar_color),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.chart_bar_color_hint),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeviceChip(
                            label = stringResource(R.string.chart_bar_color_gold),
                            selected = settings.chartBarAccent == ChartBarAccent.GOLD,
                            onClick = {
                                scope.launch {
                                    container.settingsStore.update {
                                        it.copy(chartBarAccent = ChartBarAccent.GOLD)
                                    }
                                    com.alorbach.solarmonitor.widget.SolarWidgets.refreshAll(context)
                                }
                            },
                        )
                        DeviceChip(
                            label = stringResource(R.string.chart_bar_color_cyan),
                            selected = settings.chartBarAccent == ChartBarAccent.CYAN,
                            onClick = {
                                scope.launch {
                                    container.settingsStore.update {
                                        it.copy(chartBarAccent = ChartBarAccent.CYAN)
                                    }
                                    com.alorbach.solarmonitor.widget.SolarWidgets.refreshAll(context)
                                }
                            },
                        )
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
                    Text(stringResource(R.string.live_poll_window), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.live_poll_window_hint), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { pickerTarget = PollWindowBound.START },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "${stringResource(R.string.live_poll_window_start)} ${formatPollMinutes(settings.livePollWindowStartMinutes)}",
                            )
                        }
                        OutlinedButton(
                            onClick = { pickerTarget = PollWindowBound.END },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "${stringResource(R.string.live_poll_window_end)} ${formatPollMinutes(settings.livePollWindowEndMinutes)}",
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
                    Text(stringResource(R.string.inverter_warnings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.inverter_warnings_hint), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.inverter_warnings), modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.inverterWarningAlertsEnabled,
                            onCheckedChange = { enabled ->
                                val activity = context as? MainActivity
                                if (!enabled) {
                                    scope.launch {
                                        container.settingsStore.update {
                                            it.copy(inverterWarningAlertsEnabled = false)
                                        }
                                    }
                                } else if (activity == null) {
                                    scope.launch {
                                        container.settingsStore.update {
                                            it.copy(inverterWarningAlertsEnabled = true)
                                        }
                                    }
                                } else {
                                    activity.ensureNotificationPermissionForWarnings()
                                }
                            },
                        )
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
                    Text(
                        stringResource(R.string.backup_drive_folder_hint),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!googleClientReady) {
                        Text(
                            stringResource(R.string.backup_sign_in_not_built),
                            color = colors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (!playServicesReady) {
                        Text(
                            stringResource(R.string.backup_play_services_missing),
                            color = colors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (signedIn) {
                        Text(
                            stringResource(R.string.backup_signed_in_as, settings.googleAccountEmail),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    signInBusy = true
                                    try {
                                        container.googleDriveAuth.signOut()
                                        container.settingsStore.update {
                                            it.copy(googleAccountEmail = "", driveFolderId = "")
                                        }
                                        signInError = null
                                    } catch (error: Throwable) {
                                        signInError = error.message
                                            ?: context.getString(R.string.backup_sign_in_failed)
                                    } finally {
                                        signInBusy = false
                                    }
                                }
                            },
                            enabled = !signInBusy && !backupRunning && !restoreRunning,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_sign_out))
                        }
                    } else {
                        Button(
                            onClick = { startGoogleSignIn() },
                            enabled = googleClientReady && playServicesReady && !signInBusy,
                        ) {
                            Icon(Icons.Rounded.AccountCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_sign_in))
                        }
                    }
                    if (signInBusy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.widget_loading), color = colors.onSurfaceVariant)
                        }
                    }
                    signInError?.let { message ->
                        Text(message, color = colors.error, style = MaterialTheme.typography.bodySmall)
                    }
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
                                scope.launch { persistBackupToggles() }
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.backup_include_import_copies), modifier = Modifier.weight(1f))
                        Switch(
                            checked = includeImports,
                            onCheckedChange = {
                                includeImports = it
                                scope.launch { persistBackupToggles() }
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
                    if (restoreRunning) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.restore_running), color = colors.onSurfaceVariant)
                        }
                    }
                    restoreMessage?.let { message ->
                        Text(
                            message,
                            color = if (restoreSuccess) colors.primary else colors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
                    } else if (!signedIn) {
                        Text(
                            stringResource(R.string.backup_not_configured),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                persistBackupToggles()
                                container.cloudBackupCoordinator.enqueue(BackupTrigger.Manual)
                            }
                        },
                        enabled = !backupRunning && signedIn,
                    ) {
                        Text(stringResource(R.string.backup_now))
                    }
                    Button(
                        onClick = { showRestoreConfirm = true },
                        enabled = !restoreRunning && !backupRunning && signedIn,
                    ) {
                        Text(stringResource(R.string.restore_from_cloud))
                    }
                    if (showRestoreConfirm) {
                        AlertDialog(
                            onDismissRequest = { if (!restoreRunning) showRestoreConfirm = false },
                            title = { Text(stringResource(R.string.restore_confirm_title)) },
                            text = { Text(stringResource(R.string.restore_confirm_body)) },
                            confirmButton = {
                                TextButton(
                                    enabled = !restoreRunning,
                                    onClick = {
                                        restoreRunning = true
                                        restoreMessage = null
                                        scope.launch {
                                            try {
                                                persistBackupToggles()
                                                val result = container.cloudBackupRepository.runRestore {
                                                    container.importManager.holdForRestore()
                                                    context.startService(
                                                        com.alorbach.solarmonitor.service.ImportForegroundService.stopIntent(context),
                                                    )
                                                    com.alorbach.solarmonitor.work.ScheduledImportWorker.cancelAll(context, emptyList())
                                                    container.liveMonitoringRepository.stopAll()
                                                    context.startService(
                                                        com.alorbach.solarmonitor.service.LiveMonitorService.stopIntent(context),
                                                    )
                                                    var waited = 0
                                                    fun writersBusy(): Boolean =
                                                        container.importManager.isImportActive() ||
                                                            com.alorbach.solarmonitor.work.ScheduledImportWorker.isInFlight() ||
                                                            container.liveMonitoringRepository.hasInFlightWork()
                                                    while (writersBusy() && waited < 30_000) {
                                                        delay(50)
                                                        waited += 50
                                                    }
                                                    if (writersBusy()) {
                                                        error(context.getString(R.string.restore_import_busy))
                                                    }
                                                }
                                                restoreSuccess = result.success
                                                restoreMessage = result.message
                                                showRestoreConfirm = false
                                                if (result.shouldRestart) {
                                                    delay(400)
                                                    com.alorbach.solarmonitor.service.AppProcessRestarter.restart(context)
                                                }
                                            } catch (error: Throwable) {
                                                container.importManager.clearRestoreHold()
                                                restoreSuccess = false
                                                restoreMessage = error.message
                                                    ?: context.getString(R.string.restore_failed)
                                            } finally {
                                                restoreRunning = false
                                                showRestoreConfirm = false
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.restore_confirm))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    enabled = !restoreRunning,
                                    onClick = { showRestoreConfirm = false },
                                ) {
                                    Text(stringResource(R.string.cancel))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    pickerTarget?.let { bound ->
        PollWindowTimePickerDialog(
            bound = bound,
            startMinutes = settings.livePollWindowStartMinutes,
            endMinutes = settings.livePollWindowEndMinutes,
            onDismiss = { pickerTarget = null },
            onConfirm = { minutes ->
                pickerTarget = null
                scope.launch {
                    container.settingsStore.update { current ->
                        when (bound) {
                            PollWindowBound.START -> current.copy(
                                livePollWindowStartMinutes = LivePollWindow.normalizeMinutes(minutes),
                            )
                            PollWindowBound.END -> current.copy(
                                livePollWindowEndMinutes = LivePollWindow.normalizeMinutes(minutes),
                            )
                        }
                    }
                    LivePollScheduler.syncAfterSettingsChange(context)
                }
            },
        )
    }
}

private enum class PollWindowBound { START, END }

@Composable
private fun HomeWifiSettingsCard(
    settings: AppSettings,
    currentSsid: String?,
    onUpdate: (suspend (AppSettings) -> AppSettings) -> Unit,
) {
    var homeWifiSsid by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val unavailableLabel = stringResource(R.string.home_wifi_unavailable)

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.home_wifi_check),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.home_wifi_check_hint),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_wifi_check),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.homeWifiCheckEnabled,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(homeWifiCheckEnabled = enabled) }
                    },
                )
            }
            Text(
                stringResource(
                    R.string.home_wifi_current,
                    currentSsid ?: unavailableLabel,
                ),
                color = colors.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = homeWifiSsid,
                    onValueChange = { homeWifiSsid = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.home_wifi_ssid)) },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        val normalized = HomeWifiPolicy.normalizeSsid(homeWifiSsid)
                        if (normalized.isNotEmpty()) {
                            onUpdate {
                                it.copy(allowedHomeWifiSsids = it.allowedHomeWifiSsids + normalized)
                            }
                            homeWifiSsid = ""
                        }
                    },
                    enabled = HomeWifiPolicy.normalizeSsid(homeWifiSsid).isNotEmpty(),
                ) {
                    Text(stringResource(R.string.add))
                }
            }
            currentSsid?.let { current ->
                if (current !in settings.allowedHomeWifiSsids) {
                    OutlinedButton(
                        onClick = {
                            onUpdate {
                                it.copy(allowedHomeWifiSsids = it.allowedHomeWifiSsids + current)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.home_wifi_add_current))
                    }
                }
            }
            if (settings.allowedHomeWifiSsids.isEmpty()) {
                Text(
                    stringResource(R.string.home_wifi_empty),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                settings.allowedHomeWifiSsids.sorted().forEach { ssid ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ssid, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                onUpdate {
                                    it.copy(allowedHomeWifiSsids = it.allowedHomeWifiSsids - ssid)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                }
            }
        }
    }
}

private fun formatPollMinutes(minutes: Int): String {
    val normalized = LivePollWindow.normalizeMinutes(minutes)
    return "%02d:%02d".format(normalized / 60, normalized % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PollWindowTimePickerDialog(
    bound: PollWindowBound,
    startMinutes: Int,
    endMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initial = LivePollWindow.normalizeMinutes(
        if (bound == PollWindowBound.START) startMinutes else endMinutes,
    )
    val state = rememberTimePickerState(
        initialHour = initial / 60,
        initialMinute = initial % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (bound == PollWindowBound.START) {
                        R.string.live_poll_window_start
                    } else {
                        R.string.live_poll_window_end
                    },
                ),
            )
        },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

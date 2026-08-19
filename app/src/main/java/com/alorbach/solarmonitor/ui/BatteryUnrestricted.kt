package com.alorbach.solarmonitor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alorbach.solarmonitor.R

fun Context.isBatteryUnrestricted(): Boolean {
    val pm = getSystemService(PowerManager::class.java)
    return pm?.isIgnoringBatteryOptimizations(packageName) == true
}

fun Context.requestBatteryUnrestricted() {
    startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        },
    )
}

@Composable
fun rememberBatteryUnrestricted(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var unrestricted by remember { mutableStateOf(context.isBatteryUnrestricted()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted = context.isBatteryUnrestricted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return unrestricted
}

@Composable
fun BatteryUnrestrictedCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val unrestricted = rememberBatteryUnrestricted()
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.battery_unrestricted_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (unrestricted) {
                    stringResource(R.string.battery_unrestricted_ok)
                } else {
                    stringResource(R.string.battery_unrestricted_body)
                },
                color = colors.onSurfaceVariant,
            )
            if (!unrestricted) {
                Button(onClick = { context.requestBatteryUnrestricted() }) {
                    Text(stringResource(R.string.battery_unrestricted_action))
                }
            }
        }
    }
}

@Composable
fun BatteryUnrestrictedPromptDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.battery_unrestricted_title)) },
        text = { Text(stringResource(R.string.battery_unrestricted_needed_for_schedule)) },
        confirmButton = {
            TextButton(
                onClick = {
                    context.requestBatteryUnrestricted()
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.battery_unrestricted_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

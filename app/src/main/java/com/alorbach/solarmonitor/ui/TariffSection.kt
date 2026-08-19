package com.alorbach.solarmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.AppContainer
import com.alorbach.solarmonitor.data.model.TariffPeriodEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TariffSection(
    deviceId: Long,
    container: AppContainer,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val savedLabel = stringResource(R.string.tariff_saved)
    val invalidLabel = stringResource(R.string.tariff_invalid)
    var rows by remember { mutableStateOf(listOf<TariffDraft>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    LaunchedEffect(deviceId) {
        val existing = container.repository.getTariffs(deviceId)
        rows = if (existing.isEmpty()) {
            listOf(
                TariffDraft(
                    from = LocalDate.now().minusYears(15).toString(),
                    to = "",
                    price = "0.28",
                    currency = "EUR",
                ),
            )
        } else {
            existing.map {
                TariffDraft(
                    from = LocalDate.ofEpochDay(it.validFromEpochDay).toString(),
                    to = it.validToEpochDay?.let(LocalDate::ofEpochDay)?.toString().orEmpty(),
                    price = it.pricePerKwh.toString(),
                    currency = it.currency,
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.tariffs), fontWeight = FontWeight.SemiBold)
        if (rows.isEmpty()) {
            Text(stringResource(R.string.tariff_empty), color = colors.onSurfaceVariant)
        }
        rows.forEachIndexed { index, row ->
            OutlinedTextField(
                value = row.from,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tariff_from)) },
                trailingIcon = {
                    IconButton(onClick = { picking = index to true }) {
                        Icon(Icons.Rounded.DateRange, contentDescription = stringResource(R.string.tariff_from))
                    }
                },
                singleLine = true,
            )
            OutlinedTextField(
                value = row.to,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tariff_to)) },
                trailingIcon = {
                    IconButton(onClick = { picking = index to false }) {
                        Icon(Icons.Rounded.DateRange, contentDescription = stringResource(R.string.tariff_to))
                    }
                },
                singleLine = true,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = row.price,
                    onValueChange = { value -> rows = rows.toMutableList().also { it[index] = row.copy(price = value) } },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.tariff_price)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = row.currency,
                    onValueChange = { value -> rows = rows.toMutableList().also { it[index] = row.copy(currency = value) } },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.tariff_currency)) },
                    singleLine = true,
                )
                IconButton(onClick = { rows = rows.toMutableList().also { it.removeAt(index) } }) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.tariff_delete),
                        tint = colors.error,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                rows = rows + TariffDraft(
                    from = LocalDate.now().toString(),
                    to = "",
                    price = rows.lastOrNull()?.price ?: "0.28",
                    currency = rows.lastOrNull()?.currency ?: "EUR",
                )
            }) {
                Text(stringResource(R.string.tariff_add))
            }
            Button(onClick = {
                scope.launch {
                    val parsed = rows.map { it.toEntity(deviceId) }
                    if (parsed.any { it == null }) {
                        messageIsError = true
                        message = invalidLabel
                    } else {
                        container.repository.saveTariffs(deviceId, parsed.filterNotNull())
                        messageIsError = false
                        message = savedLabel
                        onSaved()
                    }
                }
            }) {
                Text(stringResource(R.string.tariff_save))
            }
        }
        message?.let {
            Text(it, color = if (messageIsError) colors.error else colors.primary)
        }
    }

    val picker = picking
    if (picker != null) {
        val (index, isFrom) = picker
        val current = rows.getOrNull(index)
        val initial = runCatching {
            val text = if (isFrom) current?.from.orEmpty() else current?.to.orEmpty()
            if (text.isBlank()) null else LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        key(index, isFrom, initial) {
            val state = rememberDatePickerState(initialSelectedDateMillis = initial)
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val millis = state.selectedDateMillis
                            if (millis != null && index in rows.indices) {
                                val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                                rows = rows.toMutableList().also { list ->
                                    list[index] = if (isFrom) list[index].copy(from = date) else list[index].copy(to = date)
                                }
                            } else if (!isFrom && index in rows.indices) {
                                rows = rows.toMutableList().also { it[index] = it[index].copy(to = "") }
                            }
                            picking = null
                        },
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    Row {
                        if (!isFrom) {
                            TextButton(
                                onClick = {
                                    if (index in rows.indices) {
                                        rows = rows.toMutableList().also { it[index] = it[index].copy(to = "") }
                                    }
                                    picking = null
                                },
                            ) {
                                Text(stringResource(R.string.tariff_open_ended))
                            }
                        }
                        TextButton(onClick = { picking = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                },
            ) {
                DatePicker(state = state)
            }
        }
    }
}

private data class TariffDraft(
    val from: String,
    val to: String,
    val price: String,
    val currency: String,
) {
    fun toEntity(deviceId: Long): TariffPeriodEntity? {
        val fromDay = runCatching { LocalDate.parse(from.trim()).toEpochDay() }.getOrNull() ?: return null
        val toTrimmed = to.trim()
        val toDay = if (toTrimmed.isEmpty()) {
            null
        } else {
            runCatching { LocalDate.parse(toTrimmed).toEpochDay() }.getOrNull() ?: return null
        }
        val priceValue = price.replace(',', '.').toDoubleOrNull() ?: return null
        return TariffPeriodEntity(
            deviceId = deviceId,
            validFromEpochDay = fromDay,
            validToEpochDay = toDay,
            pricePerKwh = priceValue,
            currency = currency.trim().ifBlank { "EUR" },
        )
    }
}

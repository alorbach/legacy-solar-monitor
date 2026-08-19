package com.alorbach.solarmonitor.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DeviceTransport {
    BLUETOOTH_LEGACY,
    SPEEDWIRE_FUTURE,
}

enum class LegacyBluetoothCompatibilityMode {
    SINGLE_INVERTER_LEGACY,
}

enum class ImportSourceType {
    FILE,
    ZIP,
    FTP,
    SFTP,
    URL,
    SQLITE_DB,
}

enum class ImportJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

@Entity(
    tableName = "device_profiles",
    indices = [Index(value = ["btMac"], unique = true)],
)
data class DeviceProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val serial: Long?,
    val model: String?,
    val transport: DeviceTransport = DeviceTransport.BLUETOOTH_LEGACY,
    val btMac: String?,
    val passwordRef: String?,
    val plantName: String?,
    val ownerName: String?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val timezone: String,
    val locale: String,
    val decimalPoint: String = "comma",
    val delimiter: String = "semicolon",
    val dateFormat: String = "dd.MM.yyyy HH:mm",
    val enabled: Boolean = true,
    val legacyCompatibilityMode: LegacyBluetoothCompatibilityMode = LegacyBluetoothCompatibilityMode.SINGLE_INVERTER_LEGACY,
    val lastSuccessfulSocketStrategy: String? = null,
    val lastDiagnostics: String? = null,
    val lastLiveReadAtEpochSeconds: Long? = null,
    val lastArchiveSyncAtEpochSeconds: Long? = null,
    val lastConnectionStatus: String? = null,
)

@Entity(
    tableName = "import_sources",
    foreignKeys = [
        ForeignKey(
            entity = DeviceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("deviceId")]
)
data class ImportSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val type: ImportSourceType,
    val uriOrPath: String,
    val username: String?,
    val passwordRef: String?,
    val lastImportedAtEpochSeconds: Long? = null,
)

@Entity(
    tableName = "tariff_periods",
    foreignKeys = [
        ForeignKey(
            entity = DeviceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("deviceId")]
)
data class TariffPeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val validFromEpochDay: Long,
    val validToEpochDay: Long?,
    val pricePerKwh: Double,
    val currency: String,
)

@Entity(
    tableName = "spot_samples",
    foreignKeys = [
        ForeignKey(
            entity = DeviceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["deviceId", "timestampEpochSeconds"], unique = true)]
)
data class SpotSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val timestampEpochSeconds: Long,
    val pdc1: Int? = null,
    val pdc2: Int? = null,
    val pac1: Int? = null,
    val pac2: Int? = null,
    val pac3: Int? = null,
    val totalPac: Int? = null,
    val eTodayWh: Long? = null,
    val eTotalWh: Long? = null,
    val frequencyHz: Double? = null,
    val temperatureC: Double? = null,
    val status: String? = null,
    val gridRelay: String? = null,
    val btSignalPercent: Double? = null,
    val sourceType: String = "spot",
)

@Entity(
    tableName = "day_aggregates",
    foreignKeys = [
        ForeignKey(
            entity = DeviceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["deviceId", "dateEpochDay"], unique = true)]
)
data class DayAggregateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val dateEpochDay: Long,
    val totalYieldWh: Long,
    val powerW: Int? = null,
    val sourceType: String = "day_csv",
)

@Entity(
    tableName = "month_aggregates",
    foreignKeys = [
        ForeignKey(
            entity = DeviceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["deviceId", "monthKey"], unique = true)]
)
data class MonthAggregateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val monthKey: String,
    val totalYieldWh: Long,
    val dayYieldWh: Long,
    val sourceType: String = "month_csv",
)

@Entity(
    tableName = "hour_aggregates",
    foreignKeys = [
        ForeignKey(
            entity = DeviceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["deviceId", "hourEpochSeconds"], unique = true)]
)
data class HourAggregateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val hourEpochSeconds: Long,
    val yieldWh: Long,
    val maxPowerW: Int? = null,
    val sourceType: String = "derived",
)

enum class StatsGranularity {
    HOUR,
    DAY,
    MONTH,
    YEAR,
}

data class StatsPoint(
    val label: String,
    val bucketKey: String,
    val yieldWh: Long,
    val peakPowerW: Int?,
    val earnings: Double,
    val eventCount: Int = 0,
)

/** Per-device yearly day yield projection (avoids loading every day row into memory). */
data class DeviceYearYieldRow(
    val deviceId: Long,
    val yearKey: String,
    val yieldWh: Long,
    val peakPowerW: Int?,
)

data class DayArchiveResult(
    val dayAggregates: List<DayAggregateEntity>,
    val spotSamples: List<SpotSampleEntity>,
)

sealed class SaveDeviceResult {
    data class Success(val deviceId: Long) : SaveDeviceResult()
    data class DuplicateMac(val existingDeviceId: Long, val mac: String) : SaveDeviceResult()
}

@Entity(
    tableName = "device_events",
    foreignKeys = [
        ForeignKey(
            entity = DeviceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["deviceId", "entryId"], unique = true), Index("deviceId"), Index("timestampEpochSeconds")]
)
data class DeviceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val entryId: Long,
    val timestampEpochSeconds: Long,
    val eventCode: Int,
    val eventType: String,
    val category: String,
    val eventGroup: String,
    val tag: String,
    val oldValue: String?,
    val newValue: String?,
    val userGroup: String?,
)

@Entity(tableName = "import_jobs")
data class ImportJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long?,
    val sourceLabel: String,
    val sourceType: ImportSourceType,
    val status: ImportJobStatus,
    val createdAtEpochSeconds: Long,
    val completedAtEpochSeconds: Long? = null,
    val message: String? = null,
    val preservedCopyPath: String? = null,
    /** JSON replay payload for FTP/SFTP/URL (no password). Null = not re-runnable. */
    val replayConfigJson: String? = null,
    /** EncryptedSharedPreferences id for the import password. */
    val passwordCredentialId: String? = null,
)

data class DeviceDashboardSummary(
    val deviceId: Long,
    val deviceName: String,
    val model: String?,
    val currentPowerW: Int?,
    val todayYieldWh: Long?,
    val monthYieldWh: Long?,
    val yearlyYieldWh: Long?,
    val estimatedEarnings: Double,
    val currency: String?,
    val status: String?,
    val lastUpdateEpochSeconds: Long?,
)

data class PortfolioSummary(
    val deviceCount: Int,
    val currentPowerW: Int?,
    val todayYieldWh: Long,
    val monthYieldWh: Long,
    val yearYieldWh: Long,
    val estimatedEarnings: Double,
    val currency: String?,
)

data class DailyPoint(
    val dateEpochDay: Long,
    val yieldWh: Long,
    val earnings: Double,
)

data class MonthlyPoint(
    val monthKey: String,
    val yieldWh: Long,
    val earnings: Double,
)

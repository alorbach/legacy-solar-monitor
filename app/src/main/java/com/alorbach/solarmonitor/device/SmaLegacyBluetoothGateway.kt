package com.alorbach.solarmonitor.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.alorbach.solarmonitor.BuildConfig
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.DayArchiveResult
import com.alorbach.solarmonitor.data.model.DeviceProfileEntity
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class BluetoothDeviceDescriptor(
    val name: String?,
    val address: String,
    val bonded: Boolean = false,
    val rssi: Short? = null,
)

data class SmaConnectionTestResult(
    val message: String,
    val signalPercent: Double? = null,
    val socketStrategy: String? = null,
    val diagnostics: String? = null,
)

data class SmaGatewayResult<T>(
    val value: T,
    val socketStrategy: String,
    val diagnostics: String,
    val inverterSerial: Long? = null,
)

internal data class SmaDayArchiveWindow(
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
)

internal fun smaDayArchiveWindow(date: LocalDate, zoneId: ZoneId): SmaDayArchiveWindow {
    val startOfDay = date.atStartOfDay(zoneId)
    val endOfDay = date.plusDays(1).atStartOfDay(zoneId)
    // The five-minute baseline before midnight lets the first sample produce a delta while the
    // end remains the inverter's last regular sample of the requested local day. Using the next
    // local midnight rather than +24h also handles DST transition days correctly.
    return SmaDayArchiveWindow(
        startEpochSeconds = startOfDay.toEpochSecond() - 300,
        endEpochSeconds = endOfDay.toEpochSecond() - 300,
    )
}

private class SessionTrace(
    private val mac: String?,
) {
    private val entries = mutableListOf<String>()

    fun record(message: String) {
        val line = buildString {
            append(Instant.now().toString())
            append(" | ")
            mac?.let {
                append(it)
                append(" | ")
            }
            append(message)
        }
        entries += line
        if (BuildConfig.DEBUG) {
            Log.d("SmaLegacyBt", line)
        }
    }

    fun render(): String = entries.joinToString("\n")
}

interface SmaLegacyBluetoothGateway {
    fun listBondedDevices(): List<BluetoothDeviceDescriptor>
    val discoveredDevices: StateFlow<List<BluetoothDeviceDescriptor>>
    val isDiscovering: StateFlow<Boolean>
    /** Starts classic discovery. Returns an error reason, or null when discovery was started. */
    fun startDiscovery(): String?
    fun stopDiscovery()
    fun release()
    /** Close the in-flight RFCOMM socket of [mac] so a blocked read/connect unblocks. */
    fun abortSession(mac: String?)
    /** Close every in-flight RFCOMM socket. Only for teardown of the whole gateway. */
    fun abortActiveSessions()
    suspend fun testConnection(device: DeviceProfileEntity?): Result<SmaGatewayResult<SmaConnectionTestResult>>
    suspend fun connectAndReadLive(device: DeviceProfileEntity?): Result<SmaGatewayResult<SpotSampleEntity>>
    suspend fun syncDayArchive(device: DeviceProfileEntity?, fromDate: LocalDate): Result<SmaGatewayResult<DayArchiveResult>>
    suspend fun syncMonthArchive(device: DeviceProfileEntity?, fromMonth: YearMonth): Result<SmaGatewayResult<List<MonthAggregateEntity>>>
}

class SmaLegacyBluetoothGatewayImpl(
    context: Context,
) : SmaLegacyBluetoothGateway {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private fun zoneFor(device: DeviceProfileEntity?): ZoneId =
        runCatching {
            ZoneId.of(device?.timezone?.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id)
        }.getOrDefault(ZoneId.systemDefault())
    private val _discoveredDevices = MutableStateFlow(emptyList<BluetoothDeviceDescriptor>())
    override val discoveredDevices: StateFlow<List<BluetoothDeviceDescriptor>> = _discoveredDevices.asStateFlow()
    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()
    private val sessionMutexes = mutableMapOf<String, Mutex>()
    private val sessionMutexGuard = Any()
    private var receiverRegistered = false
    /** In-flight RFCOMM sockets keyed by normalised MAC so aborts hit only the intended device. */
    private val activeSockets = ConcurrentHashMap<String, BluetoothSocket>()

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                    if (!hasBluetoothConnectPermission()) return
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val descriptor = BluetoothDeviceDescriptor(
                        name = runCatching { device.name }.getOrNull()
                            ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME),
                        address = device.address,
                        bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
                        rssi = rssi.takeUnless { it == Short.MIN_VALUE },
                    )
                    if (BuildConfig.DEBUG) {
                        Log.d("SmaLegacyBt", "discovery found ${descriptor.address} name=${descriptor.name} bonded=${descriptor.bonded}")
                    }
                    _discoveredDevices.value = mergeDescriptors(_discoveredDevices.value + descriptor)
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _isDiscovering.value = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _isDiscovering.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun listBondedDevices(): List<BluetoothDeviceDescriptor> {
        if (!hasBluetoothConnectPermission()) return emptyList()
        return adapter?.bondedDevices?.map {
            BluetoothDeviceDescriptor(name = it.name, address = it.address, bonded = true)
        } ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery(): String? {
        if (!hasBluetoothConnectPermission() || !hasBluetoothScanPermission()) {
            return "missing_permission"
        }
        if (!hasLocationPermission()) {
            return "location_precise_required"
        }
        if (adapter?.isEnabled != true) {
            return "bluetooth_disabled"
        }
        if (!isLocationEnabled()) {
            return "location_disabled"
        }
        registerReceiverIfNeeded()
        // Re-seed bonded devices but keep nearby hits from earlier rounds; inverters often
        // answer only some inquiry scans, so results must accumulate across scans.
        _discoveredDevices.value = mergeDescriptors(
            _discoveredDevices.value.filterNot { it.bonded } + listBondedDevices(),
        )
        adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
        val started = adapter?.startDiscovery() == true
        if (!started) {
            Log.w("SmaLegacyBt", "BluetoothAdapter.startDiscovery() returned false")
            return "scan_failed"
        }
        _isDiscovering.value = true
        return null
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        if (hasBluetoothScanPermission()) {
            adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
        }
        _isDiscovering.value = false
    }

    override fun release() {
        abortActiveSessions()
        stopDiscovery()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(discoveryReceiver) }
            receiverRegistered = false
        }
    }

    override fun abortSession(mac: String?) {
        val socket = mac?.let { activeSockets.remove(it.uppercase()) } ?: return
        runCatching { socket.close() }
    }

    override fun abortActiveSessions() {
        val sockets = activeSockets.values.toList()
        activeSockets.clear()
        sockets.forEach { socket -> runCatching { socket.close() } }
    }

    override suspend fun testConnection(device: DeviceProfileEntity?): Result<SmaGatewayResult<SmaConnectionTestResult>> = runCatching {
        withSession(device) { session, socketStrategy, trace ->
            val signal = session.readSignalStrength()
            SmaGatewayResult(
                value = SmaConnectionTestResult(
                    message = buildString {
                        append(appContext.getString(R.string.connection_ok))
                        signal?.let {
                            append(appContext.getString(R.string.connection_signal_suffix, it.roundToInt()))
                        }
                        if (session.inverterSerial > 0L) {
                            append(appContext.getString(R.string.connection_serial_suffix, session.inverterSerial))
                        }
                    },
                    signalPercent = signal,
                    socketStrategy = socketStrategy,
                    diagnostics = trace.render(),
                ),
                socketStrategy = socketStrategy,
                diagnostics = trace.render(),
                inverterSerial = session.inverterSerial.takeIf { it > 0L },
            )
        }
    }

    override suspend fun connectAndReadLive(device: DeviceProfileEntity?): Result<SmaGatewayResult<SpotSampleEntity>> = runCatching {
        withSession(device) { session, socketStrategy, trace ->
            val snapshot = session.readLiveSample(device!!.id)
            SmaGatewayResult(
                value = snapshot.copy(
                    status = snapshot.status ?: appContext.getString(R.string.live_read_ok),
                    sourceType = "bluetooth_live",
                ),
                socketStrategy = socketStrategy,
                diagnostics = trace.render(),
                inverterSerial = session.inverterSerial.takeIf { it > 0L },
            )
        }
    }

    override suspend fun syncDayArchive(
        device: DeviceProfileEntity?,
        fromDate: LocalDate,
    ): Result<SmaGatewayResult<DayArchiveResult>> = runCatching {
        withSession(device) { session, socketStrategy, trace ->
            val today = LocalDate.now(zoneFor(device))
            val dayAggregates = mutableListOf<DayAggregateEntity>()
            val spotSamples = mutableListOf<SpotSampleEntity>()
            generateSequence(fromDate) { current ->
                current.plusDays(1).takeIf { !it.isAfter(today) }
            }.forEach { day ->
                val parsed = session.readDayArchive(device!!.id, day) ?: return@forEach
                dayAggregates += parsed.dayAggregate
                spotSamples += parsed.spotSamples
            }
            SmaGatewayResult(
                value = DayArchiveResult(
                    dayAggregates = dayAggregates,
                    spotSamples = spotSamples,
                ),
                socketStrategy = socketStrategy,
                diagnostics = trace.render(),
                inverterSerial = session.inverterSerial.takeIf { it > 0L },
            )
        }
    }

    override suspend fun syncMonthArchive(
        device: DeviceProfileEntity?,
        fromMonth: YearMonth,
    ): Result<SmaGatewayResult<List<MonthAggregateEntity>>> = runCatching {
        withSession(device) { session, socketStrategy, trace ->
            val currentMonth = YearMonth.now(zoneFor(device))
            val items = generateSequence(fromMonth) { current ->
                current.plusMonths(1).takeIf { !it.isAfter(currentMonth) }
            }.mapNotNull { month ->
                session.readMonthArchive(device!!.id, month)
            }.toList()
            SmaGatewayResult(
                value = items,
                socketStrategy = socketStrategy,
                diagnostics = trace.render(),
                inverterSerial = session.inverterSerial.takeIf { it > 0L },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun <T> withSession(
        device: DeviceProfileEntity?,
        block: suspend (SmaBluetoothSession, String, SessionTrace) -> T,
    ): T = withContext(Dispatchers.IO) {
        requireNotNull(device) { "Device not found" }
        require(!device.btMac.isNullOrBlank()) { "Bluetooth MAC is missing" }
        require(!device.passwordRef.isNullOrBlank()) { "SMA Bluetooth PIN/password is missing" }
        require(hasBluetoothConnectPermission()) { "Bluetooth permission missing" }
        require(adapter?.isEnabled == true) { "Bluetooth is disabled" }

        val macKey = device.btMac!!.uppercase()
        val deviceMutex = synchronized(sessionMutexGuard) {
            sessionMutexes.getOrPut(macKey) { Mutex() }
        }

        deviceMutex.withLock {
            @SuppressLint("MissingPermission")
            val btDevice = requireNotNull(adapter?.getRemoteDevice(device.btMac)) {
                "Bluetooth adapter unavailable"
            }
            @SuppressLint("MissingPermission")
            val bondState = btDevice.bondState
            if (bondState == BluetoothDevice.BOND_NONE) {
                traceBondHint(device.btMac)
            }
            adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
            val trace = SessionTrace(device.btMac)
            trace.record(
                "session:start compatibility=${device.legacyCompatibilityMode} preferred=${device.lastSuccessfulSocketStrategy ?: "none"} bond=$bondState",
            )
            val strategies = rfcommStrategies(
                preferredStrategy = device.lastSuccessfulSocketStrategy,
                bonded = bondState == BluetoothDevice.BOND_BONDED,
            )
            var lastError: Throwable? = null
            for (strategy in strategies) {
                var socket: BluetoothSocket? = null
                var linkEstablished = false
                try {
                    if (BuildConfig.DEBUG) {
                        Log.d("SmaLegacyBt", "Trying socket strategy: ${strategy.label} for ${device.btMac}")
                    }
                    trace.record("socket:${strategy.label} open")
                    socket = strategy.open(btDevice)
                    activeSockets[macKey] = socket
                    withTimeout(CONNECT_TIMEOUT_MS) {
                        socket.connect()
                    }
                    delay(150)
                    linkEstablished = true
                    trace.record("socket:${strategy.label} connected")
                    return@withLock withTimeout(SESSION_TIMEOUT_MS) {
                        SmaBluetoothSession(socket, device, zoneFor(device), trace).use { session ->
                            block(session, strategy.label, trace)
                        }
                    }
                } catch (error: Throwable) {
                    runCatching { socket?.close() }
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    lastError = error
                    trace.record("socket:${strategy.label} failed ${error.message ?: error::class.java.simpleName}")
                    Log.w("SmaLegacyBt", "Socket strategy failed: ${strategy.label}", error)
                    // Another transport only helps while the RFCOMM link itself refuses to come
                    // up. Once the inverter talked to us, the failure is in the SMA protocol and
                    // would repeat on every remaining strategy.
                    if (linkEstablished) throw error
                } finally {
                    socket?.let { activeSockets.remove(macKey, it) }
                }
            }
            throw IllegalStateException(lastError?.message ?: "Bluetooth socket connection failed", lastError)
        }
    }

    @SuppressLint("MissingPermission")
    private fun rfcommStrategies(preferredStrategy: String?, bonded: Boolean): List<RfcommStrategy> {
        val insecure = listOf(
            RfcommStrategy("hidden_insecure_channel_1") { device ->
                val insecureMethod = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                insecureMethod.invoke(device, 1) as BluetoothSocket
            },
            RfcommStrategy("insecure_uuid") { device ->
                device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT_UUID)
            },
        )
        // Secure RFCOMM makes Android start pairing. Legacy SMA inverters are never bonded and
        // authenticate with the PIN instead, so offering these on an unbonded device only
        // produces a failed pairing prompt and a 15-30s connect timeout.
        val secure = if (bonded) {
            listOf(
                RfcommStrategy("hidden_secure_channel_1") { device ->
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, 1) as BluetoothSocket
                },
                RfcommStrategy("secure_uuid") { device ->
                    device.createRfcommSocketToServiceRecord(SERIAL_PORT_UUID)
                },
            )
        } else {
            emptyList()
        }
        return (insecure + secure).sortedBy { if (it.label == preferredStrategy) 0 else 1 }
    }

    private fun traceBondHint(mac: String?) {
        if (BuildConfig.DEBUG) {
            Log.i("SmaLegacyBt", "Device $mac is not bonded; pairing may be required for secure RFCOMM")
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission()
        }
    }

    /**
     * BLUETOOTH_SCAN is declared without neverForLocation, so Android only delivers ACTION_FOUND
     * for unpaired devices when *precise* location is granted. Approximate-only grants silently
     * yield bonded devices alone.
     */
    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun mergeDescriptors(items: List<BluetoothDeviceDescriptor>): List<BluetoothDeviceDescriptor> {
        return items
            .groupBy { it.address.uppercase() }
            .map { (_, group) ->
                group.reduce { acc, next ->
                    acc.copy(
                        name = next.name?.takeIf { it.isNotBlank() } ?: acc.name,
                        bonded = acc.bonded || next.bonded,
                        rssi = next.rssi ?: acc.rssi,
                    )
                }
            }
            .sortedWith(bluetoothDiscoveryComparator)
    }

    private fun isLocationEnabled(): Boolean {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            discoveryReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
    }

    private companion object {
        private val SERIAL_PORT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SESSION_TIMEOUT_MS = 180_000L

        /** Prefer SMA-named and unpaired nearby devices over already-bonded headphones/cars. */
        private val bluetoothDiscoveryComparator = compareByDescending<BluetoothDeviceDescriptor> {
            it.name?.contains("SMA", ignoreCase = true) == true
        }.thenByDescending {
            !it.bonded
        }.thenByDescending {
            !it.name.isNullOrBlank()
        }.thenByDescending {
            it.rssi ?: Short.MIN_VALUE
        }.thenBy {
            it.name ?: it.address
        }
    }
}

private data class RfcommStrategy(
    val label: String,
    val open: (BluetoothDevice) -> BluetoothSocket,
)

private class SmaBluetoothSession(
    private val socket: BluetoothSocket,
    private val device: DeviceProfileEntity,
    private val zoneId: ZoneId,
    private val trace: SessionTrace,
) {
    private val input: InputStream = socket.inputStream
    private val output = socket.outputStream
    private var packetId = 1
    private val appSusyId = 125
    private val appSerial = 900000000L + Random.nextLong(100000000L)
    private var localAddress = ByteArray(6)
    private var rootDeviceAddress = parseMacAddress(device.btMac!!)
    var inverterSerial: Long = 0
        private set
    private var inverterSusyId: Int = 0
    private val closed = AtomicBoolean(false)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    suspend fun <T> use(block: suspend (SmaBluetoothSession) -> T): T {
        var loggedIn = false
        // Handshake and login run inside the try so a failure there still shuts the watchdog
        // executor down instead of leaking its thread for every failed connection attempt.
        return try {
            trace.record("phase:opening-socket")
            trace.record("phase:initialize")
            initializeConnection()
            trace.record("phase:login")
            login()
            loggedIn = true
            block(this)
        } finally {
            if (loggedIn) {
                trace.record("phase:logoff")
                runCatching { logoff() }
            }
            closeQuietly()
        }
    }

    fun readSignalStrength(): Double? {
        trace.record("phase:reading-signal")
        sendSimpleCommand(rootDeviceAddress, 0x03) {
            writeEscapedByte(0x05)
            writeEscapedByte(0x00)
        }
        val packet = getPacket(rootDeviceAddress, 0x04)
        return packet.payload.getOrNull(22)?.toUByte()?.toInt()?.times(100.0)?.div(255.0)
    }

    fun readLiveSample(deviceId: Long): SpotSampleEntity {
        trace.record("phase:reading-live-data")
        val liveValues = mutableMapOf<Int, Long>()
        val statusValues = mutableMapOf<Int, Int>()

        queryData(0x54000200, 0x00260100, 0x002622FF).forEach { record ->
            when (record.lri) {
                Lri.METERING_TOT_WH_OUT -> liveValues[record.lri] = record.longValue
                Lri.METERING_DY_WH_OUT -> liveValues[record.lri] = record.longValue
            }
        }
        queryData(0x53800200, 0x00251E00, 0x00251EFF).forEach { record ->
            when (record.cls) {
                1 -> liveValues[Lri.DC_MS_WATT_MPP1] = record.intValue.toLong()
                2 -> liveValues[Lri.DC_MS_WATT_MPP2] = record.intValue.toLong()
            }
        }
        queryData(0x51000200, 0x00464000, 0x004642FF).forEach { record ->
            when (record.lri) {
                Lri.GRID_MS_W_A -> liveValues[Lri.GRID_MS_W_A] = record.intValue.toLong()
                Lri.GRID_MS_W_B -> liveValues[Lri.GRID_MS_W_B] = record.intValue.toLong()
                Lri.GRID_MS_W_C -> liveValues[Lri.GRID_MS_W_C] = record.intValue.toLong()
            }
        }
        queryData(0x51000200, 0x00263F00, 0x00263FFF).forEach { record ->
            if (record.lri == Lri.GRID_MS_TOT_W) {
                liveValues[Lri.GRID_MS_TOT_W] = record.intValue.toLong()
                if (record.timestampEpochSeconds > 0) {
                    liveValues[Lri.TIME_MARKER] = record.timestampEpochSeconds
                }
            }
        }
        queryData(0x51000200, 0x00465700, 0x004657FF).forEach { record ->
            if (record.lri == Lri.GRID_MS_HZ) liveValues[Lri.GRID_MS_HZ] = record.intValue.toLong()
        }
        queryData(0x52000200, 0x00237700, 0x002377FF).forEach { record ->
            if (record.lri == Lri.COOLSYS_TMP_NOM) liveValues[Lri.COOLSYS_TMP_NOM] = record.intValue.toLong()
        }
        queryData(0x51800200, 0x00214800, 0x002148FF).forEach { record ->
            if (record.lri == Lri.OPERATION_HEALTH) statusValues[Lri.OPERATION_HEALTH] = record.statusValue
        }
        queryData(0x51800200, 0x00416400, 0x004164FF).forEach { record ->
            if (record.lri == Lri.OPERATION_GRI_SW_STT) statusValues[Lri.OPERATION_GRI_SW_STT] = record.statusValue
        }

        val signal = readSignalStrength()
        return SpotSampleEntity(
            deviceId = deviceId,
            timestampEpochSeconds = liveValues[Lri.TIME_MARKER] ?: Instant.now().epochSecond,
            pdc1 = liveValues[Lri.DC_MS_WATT_MPP1]?.toInt(),
            pdc2 = liveValues[Lri.DC_MS_WATT_MPP2]?.toInt(),
            pac1 = liveValues[Lri.GRID_MS_W_A]?.toInt(),
            pac2 = liveValues[Lri.GRID_MS_W_B]?.toInt(),
            pac3 = liveValues[Lri.GRID_MS_W_C]?.toInt(),
            totalPac = liveValues[Lri.GRID_MS_TOT_W]?.toInt(),
            eTodayWh = liveValues[Lri.METERING_DY_WH_OUT],
            eTotalWh = liveValues[Lri.METERING_TOT_WH_OUT],
            frequencyHz = liveValues[Lri.GRID_MS_HZ]?.div(100.0),
            temperatureC = liveValues[Lri.COOLSYS_TMP_NOM]?.div(100.0),
            status = statusValues[Lri.OPERATION_HEALTH]?.let { SmaStatusLabels.encodeHealth(it) },
            gridRelay = statusValues[Lri.OPERATION_GRI_SW_STT]?.let { SmaStatusLabels.encodeRelay(it) },
            btSignalPercent = signal,
            sourceType = "bluetooth_live",
        )
    }

    data class DayArchiveParse(
        val dayAggregate: DayAggregateEntity,
        val spotSamples: List<SpotSampleEntity>,
    )

    fun readDayArchive(deviceId: Long, date: LocalDate): DayArchiveParse? {
        trace.record("phase:reading-day-archive $date")
        val window = smaDayArchiveWindow(date, zoneId)

        val packets = sendArchiveRequest(
            command = 0x70000200,
            startTime = window.startEpochSeconds,
            endTime = window.endEpochSeconds,
        )
        var previousTimestamp = 0L
        var previousTotalWh = 0L
        var firstTotalWh: Long? = null
        var lastTotalWh = 0L
        var maxPower = 0
        val spotSamples = mutableListOf<SpotSampleEntity>()

        for (packet in packets) {
            var offset = 41
            while (offset < packet.payload.size - 3) {
                val timestamp = uint32(packet.payload, offset)
                val totalWh = uint64(packet.payload, offset + 4)
                if (totalWh != U64_NAN && totalWh >= 0) {
                    if (firstTotalWh == null) firstTotalWh = totalWh
                    lastTotalWh = totalWh
                    var watts: Int? = null
                    if (previousTotalWh > 0L && timestamp > previousTimestamp) {
                        val deltaWh = totalWh - previousTotalWh
                        watts = if (deltaWh <= 0L) {
                            0
                        } else {
                            ((deltaWh * 3600L / (timestamp - previousTimestamp)).toInt()).coerceAtLeast(0)
                        }
                        maxPower = maxOf(maxPower, watts)
                    }
                    spotSamples += SpotSampleEntity(
                        deviceId = deviceId,
                        timestampEpochSeconds = timestamp,
                        totalPac = watts,
                        eTotalWh = totalWh,
                        status = "Archive",
                        sourceType = "bluetooth_day_archive",
                    )
                    previousTotalWh = totalWh
                    previousTimestamp = timestamp
                }
                offset += 12
            }
        }

        val dayYield = if (firstTotalWh != null && lastTotalWh >= firstTotalWh) {
            lastTotalWh - firstTotalWh
        } else {
            0L
        }

        // totalYieldWh is the yield of this day, never the lifetime meter reading: a day without
        // production is a real zero-yield record, not the inverter's cumulative total.
        return if (firstTotalWh != null) {
            DayArchiveParse(
                dayAggregate = DayAggregateEntity(
                    deviceId = deviceId,
                    dateEpochDay = date.toEpochDay(),
                    totalYieldWh = dayYield,
                    powerW = maxPower.takeIf { it > 0 },
                    sourceType = "bluetooth_day_archive",
                ),
                spotSamples = spotSamples,
            )
        } else {
            null
        }
    }

    fun readMonthArchive(deviceId: Long, month: YearMonth): MonthAggregateEntity? {
        trace.record("phase:reading-month-archive $month")
        val start = month.atDay(1).atTime(12, 0).atZone(zoneId).toEpochSecond()
        val requestStart = start - 86400 - 86400
        val requestEnd = start + (86400L * (month.lengthOfMonth() + 1))
        val packets = sendArchiveRequest(0x70200200, requestStart, requestEnd)

        var previousTotalWh = 0L
        var lastTotalWh = 0L
        var monthYieldWh = 0L

        for (packet in packets) {
            var offset = 41
            while (offset < packet.payload.size - 3) {
                val timestamp = uint32(packet.payload, offset)
                val datetime = Instant.ofEpochSecond(timestamp).atZone(zoneId).toLocalDate()
                val totalWh = uint64(packet.payload, offset + 4)
                if (totalWh != U64_NAN && totalWh >= 0) {
                    if (previousTotalWh > 0 && YearMonth.from(datetime) == month) {
                        val deltaWh = totalWh - previousTotalWh
                        if (deltaWh > 0L) {
                            monthYieldWh += deltaWh
                        }
                    }
                    previousTotalWh = totalWh
                    if (YearMonth.from(datetime) == month) {
                        lastTotalWh = totalWh
                    }
                }
                offset += 12
            }
        }

        return if (monthYieldWh > 0L || lastTotalWh > 0L) {
            MonthAggregateEntity(
                deviceId = deviceId,
                monthKey = month.toString(),
                totalYieldWh = lastTotalWh,
                dayYieldWh = monthYieldWh,
                sourceType = "bluetooth_month_archive",
            )
        } else {
            null
        }
    }

    private fun initializeConnection() {
        trace.record("phase:waiting-for-inverter")
        trace.record("init:wait-announcement")
        // A silence timeout keeps the socket alive, so the prompt below still has a link to talk on.
        val announcement = runCatching {
            getPacket(rootDeviceAddress, 0x02, silenceTimeoutMs = ANNOUNCE_TIMEOUT_MS)
        }.getOrElse {
            Log.w("SmaLegacyBt", "Passive announcement wait failed, prompting inverter", it)
            trace.record("phase:prompting-inverter")
            trace.record("init:announcement-fallback")
            val version = byteArrayOf(1, 0, 0, 0, 0, 0)
            // L1-only frame: no FCS trailer (matches SBFspot writePacketLength without writePacketTrailer)
            val initFrame = BluetoothFrameBuilder(localAddress, version, 0x0201).apply {
                writeEscapedByte('v'.code)
                writeEscapedByte('e'.code)
                writeEscapedByte('r'.code)
                writeEscapedByte(13)
                writeEscapedByte(10)
            }.build(includeTrailer = false)
            send(initFrame)
            getPacket(rootDeviceAddress, 0x02)
        }
        val netId = announcement.payload.getOrNull(22)?.toUByte()?.toInt()
            ?: throw IOException("SMA NetID not received")
        trace.record("init:netid=$netId")

        trace.record("phase:connecting-inverter")
        // L1-only connect frame: must not include FCS trailer
        val connectFrame = BluetoothFrameBuilder(localAddress, rootDeviceAddress, 0x02).apply {
            writeEscapedLong(0x00700400)
            writeEscapedByte(netId)
            writeEscapedLong(0)
            writeEscapedLong(1)
        }.build(includeTrailer = false)
        send(connectFrame)

        trace.record("init:wait-handshake")
        val handshake = getPacket(rootDeviceAddress, 0x05)
        trace.record("init:handshake command=${handshake.command} source=${formatBluetoothAddress(handshake.sourceAddress)}")
        require(handshake.payload.size > 31) { "Incomplete SMA handshake packet" }
        localAddress = handshake.payload.copyOfRange(26, 32)
        trace.record("init:local-bt=${formatBluetoothAddress(localAddress)}")

        trace.record("phase:reading-identity")
        val identityPacket = sendL2UntilValid(
            destinationAddress = UNKNOWN_ADDRESS,
            packetBuilder = { builder ->
                builder.beginL2Packet(
                    longWords = 0x09,
                    ctrl = 0xA0,
                    ctrl2 = 0,
                    dstSusyId = 0xFFFF,
                    dstSerial = 0xFFFFFFFFL,
                    appSusyId = appSusyId,
                    appSerial = appSerial,
                    packetId = packetId,
                )
                builder.writeEscapedLong(0x00000200)
                builder.writeEscapedLong(0)
                builder.writeEscapedLong(0)
            },
            expectedSender = rootDeviceAddress,
            waitCommand = 0x01,
        )

        require(validateChecksum(identityPacket.payload)) { "SMA identification checksum invalid" }
        inverterSusyId = uint16(identityPacket.payload, 55)
        inverterSerial = uint32(identityPacket.payload, 57)
        trace.record("init:identity susy=$inverterSusyId serial=$inverterSerial")
    }

    private fun login() {
        trace.record("phase:logging-in")
        val now = Instant.now().epochSecond
        val passwordBytes = encodePassword(device.passwordRef!!)
        trace.record("login:send packet=${packetId + 1}")
        val loginPacket = sendL2UntilValid(
            destinationAddress = UNKNOWN_ADDRESS,
            packetBuilder = { builder ->
                builder.beginL2Packet(
                    longWords = 0x0E,
                    ctrl = 0xA0,
                    ctrl2 = 0x0100,
                    dstSusyId = 0xFFFF,
                    dstSerial = 0xFFFFFFFFL,
                    appSusyId = appSusyId,
                    appSerial = appSerial,
                    packetId = packetId,
                )
                builder.writeEscapedLong(0xFFFD040C)
                builder.writeEscapedLong(0x07)
                builder.writeEscapedLong(0x00000384)
                builder.writeEscapedLong(now)
                builder.writeEscapedLong(0)
                passwordBytes.forEach { builder.writeEscapedByte(it.toInt() and 0xFF) }
            },
            expectedSender = UNKNOWN_ADDRESS,
            waitCommand = 0x01,
        )

        require(validateChecksum(loginPacket.payload)) { "SMA login checksum invalid" }
        val responsePacketId = uint16(loginPacket.payload, 27) and 0x7FFF
        val retcode = uint16(loginPacket.payload, 23)
        trace.record("login:response packet=$responsePacketId error=0x${retcode.toString(16)}")
        require(responsePacketId == packetId) { "Unexpected SMA login packet id" }
        require(uint32(loginPacket.payload, 41) == now) { "Unexpected SMA login timestamp" }
        when (retcode) {
            0 -> Unit
            0x0100 -> throw IOException("SMA login failed: wrong PIN/password")
            else -> throw IOException("SMA login failed: error 0x${retcode.toString(16)}")
        }
        inverterSusyId = uint16(loginPacket.payload, 15)
        inverterSerial = uint32(loginPacket.payload, 17)
    }

    private fun logoff() {
        incrementPacketIdUntilValid { candidate ->
            BluetoothFrameBuilder(localAddress, UNKNOWN_ADDRESS, 0x01).apply {
                beginL2Packet(
                    longWords = 0x08,
                    ctrl = 0xA0,
                    ctrl2 = 0x0300,
                    dstSusyId = 0xFFFF,
                    dstSerial = 0xFFFFFFFFL,
                    appSusyId = appSusyId,
                    appSerial = appSerial,
                    packetId = candidate,
                )
                writeEscapedLong(0xFFFD010E)
                writeEscapedLong(0xFFFFFFFFL)
            }.build(includeTrailer = true)
        }
    }

    private fun queryData(command: Long, first: Long, last: Long): List<SmaRecord> {
        // Address the specific inverter after login (SBFspot getInverterData), not broadcast
        val packet = sendL2UntilValid(
            destinationAddress = rootDeviceAddress,
            packetBuilder = { builder ->
                builder.beginL2Packet(
                    longWords = 0x09,
                    ctrl = 0xA0,
                    ctrl2 = 0,
                    dstSusyId = inverterSusyId,
                    dstSerial = inverterSerial,
                    appSusyId = appSusyId,
                    appSerial = appSerial,
                    packetId = packetId,
                )
                builder.writeEscapedLong(command)
                builder.writeEscapedLong(first)
                builder.writeEscapedLong(last)
            },
            expectedSender = rootDeviceAddress,
            waitCommand = 0x01,
        )

        require(validateChecksum(packet.payload)) { "SMA live data checksum invalid" }
        val status = uint16(packet.payload, 23)
        require(status == 0) { "SMA live data status error 0x${status.toString(16)}" }
        require((uint16(packet.payload, 27) and 0x7FFF) == packetId) { "Unexpected SMA data packet id" }
        val responseSusy = uint16(packet.payload, 15)
        val responseSerial = uint32(packet.payload, 17)
        require(responseSusy == inverterSusyId && responseSerial == inverterSerial) {
            "SMA live data identity mismatch"
        }
        return parseRecords(packet.payload)
    }

    private fun sendArchiveRequest(command: Long, startTime: Long, endTime: Long): List<ReceivedPacket> {
        val sentPacketId = incrementPacketIdUntilValid { packetIdCandidate ->
            BluetoothFrameBuilder(localAddress, rootDeviceAddress, 0x01).apply {
                beginL2Packet(
                    longWords = 0x09,
                    ctrl = 0xE0,
                    ctrl2 = 0,
                    dstSusyId = inverterSusyId,
                    dstSerial = inverterSerial,
                    appSusyId = appSusyId,
                    appSerial = appSerial,
                    packetId = packetIdCandidate,
                )
                writeEscapedLong(command)
                writeEscapedLong(startTime)
                writeEscapedLong(endTime)
            }.build(includeTrailer = true)
        }

        val packets = mutableListOf<ReceivedPacket>()
        var morePackets = true
        var mismatches = 0
        while (morePackets) {
            val packet = getPacket(rootDeviceAddress, 0x01)
            require(validateChecksum(packet.payload)) { "SMA archive checksum invalid" }
            val responsePacketId = uint16(packet.payload, 27) and 0x7FFF
            if (responsePacketId != sentPacketId) {
                mismatches += 1
                require(mismatches <= MAX_PACKET_MISMATCHES) { "Too many unexpected archive packet ids" }
                continue
            }
            packets += packet
            morePackets = packet.payload.getOrNull(25)?.toUByte()?.toInt()?.let { it > 0 } == true
        }
        return packets
    }

    private fun sendSimpleCommand(destAddress: ByteArray, command: Int, block: BluetoothFrameBuilder.() -> Unit) {
        // L1-only command: no FCS trailer
        val frame = BluetoothFrameBuilder(localAddress, destAddress, command).apply(block).build(includeTrailer = false)
        send(frame)
    }

    private fun sendL2UntilValid(
        destinationAddress: ByteArray,
        packetBuilder: (BluetoothFrameBuilder) -> Unit,
        expectedSender: ByteArray,
        waitCommand: Int,
    ): ReceivedPacket {
        var attempt = 0
        while (true) {
            attempt += 1
            incrementPacketIdUntilValid { candidate ->
                BluetoothFrameBuilder(localAddress, destinationAddress, 0x01).apply {
                    packetBuilder(this)
                }.build(includeTrailer = true)
            }
            try {
                return getPacket(expectedSender, waitCommand, silenceTimeoutMs = REQUEST_TIMEOUT_MS)
            } catch (silence: SmaSilentInverterException) {
                if (attempt >= MAX_REQUEST_ATTEMPTS) {
                    throw IOException(
                        "Inverter did not answer SMA command 0x${waitCommand.toString(16)} after $attempt attempts",
                        silence,
                    )
                }
                trace.record("request:resend command=0x${waitCommand.toString(16)} attempt=$attempt")
            }
        }
    }

    private fun incrementPacketIdUntilValid(build: (Int) -> ByteArray): Int {
        var built: ByteArray
        do {
            packetId += 1
            built = build(packetId)
        } while (!isTrailerValid(built))
        send(built)
        return packetId
    }

    private fun parseRecords(packet: ByteArray): List<SmaRecord> {
        val records = mutableListOf<SmaRecord>()
        // Derive record size from packet metadata (SBFspot getInverterData), not dataType heuristic
        val recordSize = computeRecordSize(packet)
        var index = 41
        while (index < packet.size - 3) {
            if (index + 8 > packet.size - 3) break
            val code = uint32(packet, index).toInt()
            val lri = code and 0x00FFFF00
            val cls = code and 0xFF
            val dataType = (code ushr 24) and 0xFF
            val timestamp = uint32(packet, index + 4)
            val size = recordSize.takeIf { it > 0 } ?: when {
                lri in LONG_TAGS -> 16
                dataType == 0x10 || dataType == 0x08 -> 40
                else -> 28
            }
            if (index + size > packet.size) break
            val intValue = if (size >= 12) {
                uint32(packet, index + 8).takeUnless { it == U32_NAN }?.toInt() ?: 0
            } else {
                0
            }
            val longValue = if (size == 16 || lri in LONG_TAGS) {
                uint64(packet, index + 8).takeUnless { it == U64_NAN } ?: 0L
            } else {
                0L
            }
            val statusValue = if (dataType == 0x08) {
                firstActiveStatus(packet, index + 8, size - 8)
            } else {
                0
            }
            records += SmaRecord(
                lri = lri,
                cls = cls,
                timestampEpochSeconds = timestamp,
                intValue = intValue,
                longValue = longValue,
                statusValue = statusValue,
            )
            index += size
        }
        return records
    }

    private fun computeRecordSize(packet: ByteArray): Int {
        if (packet.size < 41) return 0
        val longWords = packet[5].toUByte().toInt()
        val first = uint32(packet, 33)
        val last = uint32(packet, 37)
        val count = (last - first + 1).toInt()
        if (count <= 0 || longWords <= 9) return 0
        return 4 * (longWords - 9) / count
    }

    private fun firstActiveStatus(packet: ByteArray, start: Int, length: Int): Int {
        var offset = start
        val end = start + length
        while (offset + 3 < end) {
            val attribute = uint32(packet, offset).toInt() and 0x00FFFFFF
            val active = packet[offset + 3].toInt() and 0xFF
            if (attribute == 0xFFFFFE) break
            if (active == 1) return attribute
            offset += 4
        }
        return 0
    }

    private fun send(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /**
     * Read one logical SMA packet. L2 payloads may span multiple L1 frames; those are
     * reassembled into a single unescaped payload buffer (SBFspot getPacket behaviour).
     */
    private fun getPacket(
        expectedSender: ByteArray,
        waitCommand: Int,
        timeoutMs: Long = BT_TIMEOUT_MS,
        silenceTimeoutMs: Long? = null,
    ): ReceivedPacket {
        val reassembled = ArrayList<Byte>(256)
        var hasL2 = false
        var escapePending = false
        var attempts = 0
        while (attempts < MAX_PACKET_ATTEMPTS) {
            attempts += 1
            // Giving up is only safe before the first frame; afterwards the L2 payload is partially
            // consumed and a re-send would desynchronise the stream.
            val header = readExact(PK_HEADER_SIZE, timeoutMs, silenceTimeoutMs.takeIf { !hasL2 })
            val packetLength = uint16(header, 1)
            require(packetLength >= PK_HEADER_SIZE) { "Invalid SMA packet length" }
            val command = uint16(header, 16)
            val sourceAddress = header.copyOfRange(4, 10)
            val remaining = if (packetLength > PK_HEADER_SIZE) {
                readExact(packetLength - PK_HEADER_SIZE, timeoutMs)
            } else {
                ByteArray(0)
            }
            if (!isValidSender(expectedSender, sourceAddress)) {
                trace.record("packet:skip command=$command source=${formatBluetoothAddress(sourceAddress)}")
                continue
            }

            val isL2Frame = remaining.isNotEmpty() &&
                remaining[0] == 0x7E.toByte() &&
                remaining.size >= 5 &&
                uint32(remaining, 1) == 0x656003FFL

            if (isL2Frame || hasL2) {
                hasL2 = true
                escapePending = appendUnescaped(
                    target = reassembled,
                    bytes = remaining,
                    escapePending = escapePending,
                )
                // An L2 payload can span several L1 frames and only ends at its closing 0x7E flag
                // (a 0x7E inside the payload is escaped). Returning earlier would hand a partial
                // payload to the checksum and field offsets.
                if (escapePending || reassembled.size < 2 || reassembled.last() != L2_END_FLAG) {
                    trace.record("packet:l2-continue bytes=${reassembled.size}")
                    continue
                }
            }

            val payload = if (hasL2) {
                reassembled.toByteArray()
            } else {
                header + remaining
            }
            trace.record(
                "packet:recv command=$command source=${formatBluetoothAddress(sourceAddress)} bytes=${payload.size} l2=$hasL2",
            )
            if (waitCommand == 0xFF || command == waitCommand) {
                return ReceivedPacket(command = command, sourceAddress = sourceAddress, payload = payload)
            }
        }
        throw IOException("Timed out waiting for SMA command 0x${waitCommand.toString(16)}")
    }

    private fun readExact(count: Int, timeoutMs: Long = BT_TIMEOUT_MS, silenceTimeoutMs: Long? = null): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        if (silenceTimeoutMs != null) awaitInput(silenceTimeoutMs)
        val cancelWatchdog = scheduleReadWatchdog(timeoutMs)
        try {
            while (offset < count) {
                val read = input.read(buffer, offset, count - offset)
                if (read < 0) throw IOException("Bluetooth socket closed")
                offset += read
            }
        } finally {
            cancelWatchdog()
        }
        return buffer
    }

    /**
     * A blocking read can only be aborted by closing the socket, which kills the session. Polling
     * for buffered bytes instead lets us give up on a silent inverter with the link still intact.
     */
    private fun awaitInput(timeoutMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (input.available() <= 0) {
            if (closed.get()) throw IOException("Bluetooth session closed")
            if (System.nanoTime() >= deadline) {
                throw SmaSilentInverterException("No SMA response within ${timeoutMs}ms")
            }
            Thread.sleep(INPUT_POLL_INTERVAL_MS)
        }
    }

    private fun scheduleReadWatchdog(timeoutMs: Long): () -> Unit {
        val future = watchdog.schedule(
            {
                if (!closed.get()) {
                    trace.record("watchdog:closing-socket after ${timeoutMs}ms")
                    runCatching { socket.close() }
                }
            },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )
        return {
            future.cancel(false)
        }
    }

    private fun closeQuietly() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            watchdog.shutdownNow()
        }
    }

    private fun validateChecksum(packet: ByteArray): Boolean {
        if (packet.size < 4) return false
        var checksum = 0xFFFF
        for (index in 1 until packet.size - 3) {
            checksum = (checksum shr 8) xor FCS_TABLE[(checksum xor packet[index].toInt()) and 0xFF]
        }
        checksum = checksum xor 0xFFFF
        return uint16(packet, packet.size - 3) == checksum
    }

    /**
     * Appends one L1 frame to the L2 payload, resolving 0x7D escapes. A 0x7D can be the last byte
     * of a frame with the escaped value arriving in the next one, so the pending state is returned
     * and fed back into the following call.
     */
    private fun appendUnescaped(
        target: MutableList<Byte>,
        bytes: ByteArray,
        escapePending: Boolean,
    ): Boolean {
        var escapeNext = escapePending
        for (byte in bytes) {
            if (escapeNext) {
                target += (byte.toInt() xor 0x20).toByte()
                escapeNext = false
            } else if (byte.toInt() and 0xFF == 0x7D) {
                escapeNext = true
            } else {
                target += byte
            }
        }
        return escapeNext
    }

    private fun isValidSender(expected: ByteArray, actual: ByteArray): Boolean {
        return expected.indices.all { expected[it] == 0xFF.toByte() || expected[it] == actual[it] }
    }

    private fun isTrailerValid(packet: ByteArray): Boolean {
        if (packet.size < 3) return false
        val low = packet[packet.size - 3]
        val high = packet[packet.size - 2]
        return low != 0x7E.toByte() && high != 0x7E.toByte() && low != 0x7D.toByte() && high != 0x7D.toByte()
    }

    private fun encodePassword(password: String): ByteArray {
        val encoded = ByteArray(12) { 0x88.toByte() }
        password.take(12).forEachIndexed { index, char ->
            encoded[index] = (char.code + 0x88).toByte()
        }
        return encoded
    }

    private companion object {
        private const val PK_HEADER_SIZE = 18
        private const val U32_NAN = 0xFFFFFFFFL
        private const val U64_NAN = -1L
        private const val BT_TIMEOUT_MS = 10_000L
        private const val ANNOUNCE_TIMEOUT_MS = 5_000L
        private const val REQUEST_TIMEOUT_MS = 4_000L
        private const val MAX_REQUEST_ATTEMPTS = 3
        private const val INPUT_POLL_INTERVAL_MS = 20L
        private const val MAX_PACKET_ATTEMPTS = 32
        private const val MAX_PACKET_MISMATCHES = 16
        private const val L2_END_FLAG = 0x7E.toByte()
        private val UNKNOWN_ADDRESS = byteArrayOf(
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
        )

        private object Lri {
            const val TIME_MARKER = -1
            const val METERING_TOT_WH_OUT = 0x00260100
            const val METERING_DY_WH_OUT = 0x00262200
            const val GRID_MS_TOT_W = 0x00263F00
            const val COOLSYS_TMP_NOM = 0x00237700
            const val OPERATION_HEALTH = 0x00214800
            const val OPERATION_GRI_SW_STT = 0x00416400
            const val DC_MS_WATT_MPP1 = 0x00251E01
            const val DC_MS_WATT_MPP2 = 0x00251E02
            const val GRID_MS_W_A = 0x00464000
            const val GRID_MS_W_B = 0x00464100
            const val GRID_MS_W_C = 0x00464200
            const val GRID_MS_HZ = 0x00465700
        }

        private val LONG_TAGS = setOf(
            Lri.METERING_TOT_WH_OUT,
            Lri.METERING_DY_WH_OUT,
        )

        private val FCS_TABLE = intArrayOf(
            0x0000, 0x1189, 0x2312, 0x329b, 0x4624, 0x57ad, 0x6536, 0x74bf,
            0x8c48, 0x9dc1, 0xaf5a, 0xbed3, 0xca6c, 0xdbe5, 0xe97e, 0xf8f7,
            0x1081, 0x0108, 0x3393, 0x221a, 0x56a5, 0x472c, 0x75b7, 0x643e,
            0x9cc9, 0x8d40, 0xbfdb, 0xae52, 0xdaed, 0xcb64, 0xf9ff, 0xe876,
            0x2102, 0x308b, 0x0210, 0x1399, 0x6726, 0x76af, 0x4434, 0x55bd,
            0xad4a, 0xbcc3, 0x8e58, 0x9fd1, 0xeb6e, 0xfae7, 0xc87c, 0xd9f5,
            0x3183, 0x200a, 0x1291, 0x0318, 0x77a7, 0x662e, 0x54b5, 0x453c,
            0xbdcb, 0xac42, 0x9ed9, 0x8f50, 0xfbef, 0xea66, 0xd8fd, 0xc974,
            0x4204, 0x538d, 0x6116, 0x709f, 0x0420, 0x15a9, 0x2732, 0x36bb,
            0xce4c, 0xdfc5, 0xed5e, 0xfcd7, 0x8868, 0x99e1, 0xab7a, 0xbaf3,
            0x5285, 0x430c, 0x7197, 0x601e, 0x14a1, 0x0528, 0x37b3, 0x263a,
            0xdecd, 0xcf44, 0xfddf, 0xec56, 0x98e9, 0x8960, 0xbbfb, 0xaa72,
            0x6306, 0x728f, 0x4014, 0x519d, 0x2522, 0x34ab, 0x0630, 0x17b9,
            0xef4e, 0xfec7, 0xcc5c, 0xddd5, 0xa96a, 0xb8e3, 0x8a78, 0x9bf1,
            0x7387, 0x620e, 0x5095, 0x411c, 0x35a3, 0x242a, 0x16b1, 0x0738,
            0xffcf, 0xee46, 0xdcdd, 0xcd54, 0xb9eb, 0xa862, 0x9af9, 0x8b70,
            0x8408, 0x9581, 0xa71a, 0xb693, 0xc22c, 0xd3a5, 0xe13e, 0xf0b7,
            0x0840, 0x19c9, 0x2b52, 0x3adb, 0x4e64, 0x5fed, 0x6d76, 0x7cff,
            0x9489, 0x8500, 0xb79b, 0xa612, 0xd2ad, 0xc324, 0xf1bf, 0xe036,
            0x18c1, 0x0948, 0x3bd3, 0x2a5a, 0x5ee5, 0x4f6c, 0x7df7, 0x6c7e,
            0xa50a, 0xb483, 0x8618, 0x9791, 0xe32e, 0xf2a7, 0xc03c, 0xd1b5,
            0x2942, 0x38cb, 0x0a50, 0x1bd9, 0x6f66, 0x7eef, 0x4c74, 0x5dfd,
            0xb58b, 0xa402, 0x9699, 0x8710, 0xf3af, 0xe226, 0xd0bd, 0xc134,
            0x39c3, 0x284a, 0x1ad1, 0x0b58, 0x7fe7, 0x6e6e, 0x5cf5, 0x4d7c,
            0xc60c, 0xd785, 0xe51e, 0xf497, 0x8028, 0x91a1, 0xa33a, 0xb2b3,
            0x4a44, 0x5bcd, 0x6956, 0x78df, 0x0c60, 0x1de9, 0x2f72, 0x3efb,
            0xd68d, 0xc704, 0xf59f, 0xe416, 0x90a9, 0x8120, 0xb3bb, 0xa232,
            0x5ac5, 0x4b4c, 0x79d7, 0x685e, 0x1ce1, 0x0d68, 0x3ff3, 0x2e7a,
            0xe70e, 0xf687, 0xc41c, 0xd595, 0xa12a, 0xb0a3, 0x8238, 0x93b1,
            0x6b46, 0x7acf, 0x4854, 0x59dd, 0x2d62, 0x3ceb, 0x0e70, 0x1ff9,
            0xf78f, 0xe606, 0xd49d, 0xc514, 0xb1ab, 0xa022, 0x92b9, 0x8330,
            0x7bc7, 0x6a4e, 0x58d5, 0x495c, 0x3de3, 0x2c6a, 0x1ef1, 0x0f78,
        )
    }
}

private data class ReceivedPacket(
    val command: Int,
    val sourceAddress: ByteArray,
    val payload: ByteArray,
)

/**
 * Raised when the inverter stayed silent at a frame boundary. The socket is still usable, so the
 * caller may re-send its request instead of tearing the session down.
 */
private class SmaSilentInverterException(message: String) : IOException(message)

private data class SmaRecord(
    val lri: Int,
    val cls: Int,
    val timestampEpochSeconds: Long,
    val intValue: Int,
    val longValue: Long,
    val statusValue: Int,
)

private class BluetoothFrameBuilder(
    private val localAddress: ByteArray,
    private val destinationAddress: ByteArray,
    private val command: Int,
) {
    private val bytes = ArrayList<Int>(128)
    private var fcsChecksum = 0xFFFF

    init {
        bytes += 0x7E
        bytes += 0
        bytes += 0
        bytes += 0
        localAddress.forEach { bytes += (it.toInt() and 0xFF) }
        destinationAddress.forEach { bytes += (it.toInt() and 0xFF) }
        bytes += (command and 0xFF)
        bytes += ((command shr 8) and 0xFF)
    }

    fun beginL2Packet(
        longWords: Int,
        ctrl: Int,
        ctrl2: Int,
        dstSusyId: Int,
        dstSerial: Long,
        appSusyId: Int,
        appSerial: Long,
        packetId: Int,
    ) {
        writeRawByte(0x7E)
        writeEscapedLong(0x656003FF)
        writeEscapedByte(longWords)
        writeEscapedByte(ctrl)
        writeEscapedShort(dstSusyId)
        writeEscapedLong(dstSerial)
        writeEscapedShort(ctrl2)
        writeEscapedShort(appSusyId)
        writeEscapedLong(appSerial)
        writeEscapedShort(ctrl2)
        writeEscapedShort(0)
        writeEscapedShort(0)
        writeEscapedShort(packetId or 0x8000)
    }

    fun writeEscapedByte(value: Int) {
        fcsChecksum = (fcsChecksum shr 8) xor FCS_TABLE[(fcsChecksum xor value) and 0xFF]
        when (value) {
            0x7D, 0x7E, 0x11, 0x12, 0x13 -> {
                bytes += 0x7D
                bytes += (value xor 0x20)
            }

            else -> bytes += (value and 0xFF)
        }
    }

    fun writeEscapedShort(value: Int) {
        writeEscapedByte(value and 0xFF)
        writeEscapedByte((value shr 8) and 0xFF)
    }

    fun writeEscapedLong(value: Long) {
        writeEscapedByte((value and 0xFF).toInt())
        writeEscapedByte(((value shr 8) and 0xFF).toInt())
        writeEscapedByte(((value shr 16) and 0xFF).toInt())
        writeEscapedByte(((value shr 24) and 0xFF).toInt())
    }

    /**
     * @param includeTrailer L2 packets need FCS + 0x7E trailer. L1-only frames
     * (ver init, connect/netId, signal strength) must NOT include it — matching
     * SBFspot's writePacketLength without writePacketTrailer.
     */
    fun build(includeTrailer: Boolean = true): ByteArray {
        if (includeTrailer) {
            val checksum = fcsChecksum xor 0xFFFF
            bytes += (checksum and 0xFF)
            bytes += ((checksum shr 8) and 0xFF)
            bytes += 0x7E
        }
        bytes[1] = bytes.size and 0xFF
        bytes[2] = (bytes.size shr 8) and 0xFF
        bytes[3] = bytes[0] xor bytes[1] xor bytes[2]
        return bytes.map { it.toByte() }.toByteArray()
    }

    private fun writeRawByte(value: Int) {
        bytes += (value and 0xFF)
    }

    private companion object {
        private val FCS_TABLE = intArrayOf(
            0x0000, 0x1189, 0x2312, 0x329b, 0x4624, 0x57ad, 0x6536, 0x74bf,
            0x8c48, 0x9dc1, 0xaf5a, 0xbed3, 0xca6c, 0xdbe5, 0xe97e, 0xf8f7,
            0x1081, 0x0108, 0x3393, 0x221a, 0x56a5, 0x472c, 0x75b7, 0x643e,
            0x9cc9, 0x8d40, 0xbfdb, 0xae52, 0xdaed, 0xcb64, 0xf9ff, 0xe876,
            0x2102, 0x308b, 0x0210, 0x1399, 0x6726, 0x76af, 0x4434, 0x55bd,
            0xad4a, 0xbcc3, 0x8e58, 0x9fd1, 0xeb6e, 0xfae7, 0xc87c, 0xd9f5,
            0x3183, 0x200a, 0x1291, 0x0318, 0x77a7, 0x662e, 0x54b5, 0x453c,
            0xbdcb, 0xac42, 0x9ed9, 0x8f50, 0xfbef, 0xea66, 0xd8fd, 0xc974,
            0x4204, 0x538d, 0x6116, 0x709f, 0x0420, 0x15a9, 0x2732, 0x36bb,
            0xce4c, 0xdfc5, 0xed5e, 0xfcd7, 0x8868, 0x99e1, 0xab7a, 0xbaf3,
            0x5285, 0x430c, 0x7197, 0x601e, 0x14a1, 0x0528, 0x37b3, 0x263a,
            0xdecd, 0xcf44, 0xfddf, 0xec56, 0x98e9, 0x8960, 0xbbfb, 0xaa72,
            0x6306, 0x728f, 0x4014, 0x519d, 0x2522, 0x34ab, 0x0630, 0x17b9,
            0xef4e, 0xfec7, 0xcc5c, 0xddd5, 0xa96a, 0xb8e3, 0x8a78, 0x9bf1,
            0x7387, 0x620e, 0x5095, 0x411c, 0x35a3, 0x242a, 0x16b1, 0x0738,
            0xffcf, 0xee46, 0xdcdd, 0xcd54, 0xb9eb, 0xa862, 0x9af9, 0x8b70,
            0x8408, 0x9581, 0xa71a, 0xb693, 0xc22c, 0xd3a5, 0xe13e, 0xf0b7,
            0x0840, 0x19c9, 0x2b52, 0x3adb, 0x4e64, 0x5fed, 0x6d76, 0x7cff,
            0x9489, 0x8500, 0xb79b, 0xa612, 0xd2ad, 0xc324, 0xf1bf, 0xe036,
            0x18c1, 0x0948, 0x3bd3, 0x2a5a, 0x5ee5, 0x4f6c, 0x7df7, 0x6c7e,
            0xa50a, 0xb483, 0x8618, 0x9791, 0xe32e, 0xf2a7, 0xc03c, 0xd1b5,
            0x2942, 0x38cb, 0x0a50, 0x1bd9, 0x6f66, 0x7eef, 0x4c74, 0x5dfd,
            0xb58b, 0xa402, 0x9699, 0x8710, 0xf3af, 0xe226, 0xd0bd, 0xc134,
            0x39c3, 0x284a, 0x1ad1, 0x0b58, 0x7fe7, 0x6e6e, 0x5cf5, 0x4d7c,
            0xc60c, 0xd785, 0xe51e, 0xf497, 0x8028, 0x91a1, 0xa33a, 0xb2b3,
            0x4a44, 0x5bcd, 0x6956, 0x78df, 0x0c60, 0x1de9, 0x2f72, 0x3efb,
            0xd68d, 0xc704, 0xf59f, 0xe416, 0x90a9, 0x8120, 0xb3bb, 0xa232,
            0x5ac5, 0x4b4c, 0x79d7, 0x685e, 0x1ce1, 0x0d68, 0x3ff3, 0x2e7a,
            0xe70e, 0xf687, 0xc41c, 0xd595, 0xa12a, 0xb0a3, 0x8238, 0x93b1,
            0x6b46, 0x7acf, 0x4854, 0x59dd, 0x2d62, 0x3ceb, 0x0e70, 0x1ff9,
            0xf78f, 0xe606, 0xd49d, 0xc514, 0xb1ab, 0xa022, 0x92b9, 0x8330,
            0x7bc7, 0x6a4e, 0x58d5, 0x495c, 0x3de3, 0x2c6a, 0x1ef1, 0x0f78,
        )
    }
}

private fun parseMacAddress(mac: String): ByteArray {
    val parts = mac.split(':')
    require(parts.size == 6) { "Invalid Bluetooth MAC address" }
    return ByteArray(6) { index ->
        parts[5 - index].toInt(16).toByte()
    }
}

private fun formatBluetoothAddress(address: ByteArray): String =
    address.reversedArray().joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }

private fun uint16(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

private fun uint32(bytes: ByteArray, offset: Int): Long {
    return (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

private fun uint64(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 7 downTo 0) {
        value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
    }
    return value
}

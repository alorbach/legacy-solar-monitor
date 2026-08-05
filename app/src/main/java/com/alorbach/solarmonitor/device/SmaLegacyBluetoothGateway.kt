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
import android.util.Log
import android.os.Build
import androidx.core.content.ContextCompat
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
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
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
)

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
        Log.d("SmaLegacyBt", line)
    }

    fun render(): String = entries.joinToString("\n")
}

interface SmaLegacyBluetoothGateway {
    fun listBondedDevices(): List<BluetoothDeviceDescriptor>
    val discoveredDevices: StateFlow<List<BluetoothDeviceDescriptor>>
    val isDiscovering: StateFlow<Boolean>
    fun startDiscovery()
    fun stopDiscovery()
    suspend fun testConnection(device: DeviceProfileEntity?): Result<SmaGatewayResult<SmaConnectionTestResult>>
    suspend fun connectAndReadLive(device: DeviceProfileEntity?): Result<SmaGatewayResult<SpotSampleEntity>>
    suspend fun syncDayArchive(device: DeviceProfileEntity?, fromDate: LocalDate): Result<SmaGatewayResult<List<DayAggregateEntity>>>
    suspend fun syncMonthArchive(device: DeviceProfileEntity?, fromMonth: YearMonth): Result<SmaGatewayResult<List<MonthAggregateEntity>>>
}

class SmaLegacyBluetoothGatewayImpl(
    context: Context,
) : SmaLegacyBluetoothGateway {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val _discoveredDevices = MutableStateFlow(emptyList<BluetoothDeviceDescriptor>())
    override val discoveredDevices: StateFlow<List<BluetoothDeviceDescriptor>> = _discoveredDevices.asStateFlow()
    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()
    private val sessionMutexes = mutableMapOf<String, Mutex>()
    private val sessionMutexGuard = Any()
    private var receiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val descriptor = BluetoothDeviceDescriptor(
                        name = device.name,
                        address = device.address,
                        bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                        rssi = rssi.takeUnless { it == Short.MIN_VALUE },
                    )
                    _discoveredDevices.value = (_discoveredDevices.value + descriptor)
                        .distinctBy { it.address }
                        .sortedWith(compareByDescending<BluetoothDeviceDescriptor> { it.bonded }.thenBy { it.name ?: it.address })
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _isDiscovering.value = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _isDiscovering.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun listBondedDevices(): List<BluetoothDeviceDescriptor> {
        if (!hasBluetoothPermission()) return emptyList()
        return adapter?.bondedDevices?.map {
            BluetoothDeviceDescriptor(name = it.name, address = it.address, bonded = true)
        } ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery() {
        if (!hasBluetoothScanPermission()) return
        registerReceiverIfNeeded()
        _discoveredDevices.value = listBondedDevices()
        adapter?.takeIf { it.isEnabled }?.let {
            if (it.isDiscovering) it.cancelDiscovery()
            it.startDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        if (hasBluetoothScanPermission()) {
            adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
        }
        _isDiscovering.value = false
    }

    override suspend fun testConnection(device: DeviceProfileEntity?): Result<SmaGatewayResult<SmaConnectionTestResult>> = runCatching {
        withSession(device) { session, socketStrategy, trace ->
            val signal = session.readSignalStrength()
            SmaGatewayResult(
                value = SmaConnectionTestResult(
                    message = buildString {
                        append("Connection OK")
                        signal?.let { append(" | BT ${it.roundToInt()}%") }
                        append(" | SN ${session.inverterSerial}")
                    },
                    signalPercent = signal,
                    socketStrategy = socketStrategy,
                    diagnostics = trace.render(),
                ),
                socketStrategy = socketStrategy,
                diagnostics = trace.render(),
            )
        }
    }

    override suspend fun connectAndReadLive(device: DeviceProfileEntity?): Result<SmaGatewayResult<SpotSampleEntity>> = runCatching {
        withSession(device) { session, socketStrategy, trace ->
            val snapshot = session.readLiveSample(device!!.id)
            SmaGatewayResult(
                value = snapshot.copy(
                    status = snapshot.status ?: "Live read OK",
                    sourceType = "bluetooth_live",
                ),
                socketStrategy = socketStrategy,
                diagnostics = trace.render(),
            )
        }
    }

    override suspend fun syncDayArchive(
        device: DeviceProfileEntity?,
        fromDate: LocalDate,
    ): Result<SmaGatewayResult<List<DayAggregateEntity>>> = runCatching {
        withSession(device) { session, socketStrategy, trace ->
            val today = LocalDate.now(zoneId)
            val items = generateSequence(fromDate) { current ->
                current.plusDays(1).takeIf { !it.isAfter(today) }
            }.mapNotNull { day ->
                session.readDayArchive(device!!.id, day)
            }.toList()
            SmaGatewayResult(
                value = items,
                socketStrategy = socketStrategy,
                diagnostics = trace.render(),
            )
        }
    }

    override suspend fun syncMonthArchive(
        device: DeviceProfileEntity?,
        fromMonth: YearMonth,
    ): Result<SmaGatewayResult<List<MonthAggregateEntity>>> = runCatching {
        withSession(device) { session, socketStrategy, trace ->
            val currentMonth = YearMonth.now(zoneId)
            val items = generateSequence(fromMonth) { current ->
                current.plusMonths(1).takeIf { !it.isAfter(currentMonth) }
            }.mapNotNull { month ->
                session.readMonthArchive(device!!.id, month)
            }.toList()
            SmaGatewayResult(
                value = items,
                socketStrategy = socketStrategy,
                diagnostics = trace.render(),
            )
        }
    }

    private suspend fun <T> withSession(
        device: DeviceProfileEntity?,
        block: suspend (SmaBluetoothSession, String, SessionTrace) -> T,
    ): T = withContext(Dispatchers.IO) {
        requireNotNull(device) { "Device not found" }
        require(!device.btMac.isNullOrBlank()) { "Bluetooth MAC is missing" }
        require(!device.passwordRef.isNullOrBlank()) { "SMA Bluetooth PIN/password is missing" }
        require(hasBluetoothPermission()) { "Bluetooth permission missing" }

        val deviceMutex = synchronized(sessionMutexGuard) {
            sessionMutexes.getOrPut(device.btMac!!.uppercase()) { Mutex() }
        }

        deviceMutex.withLock {
            val btDevice = requireNotNull(adapter?.getRemoteDevice(device.btMac)) {
                "Bluetooth adapter unavailable"
            }
            adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
            val trace = SessionTrace(device.btMac)
            trace.record("session:start compatibility=${device.legacyCompatibilityMode} preferred=${device.lastSuccessfulSocketStrategy ?: "none"}")
            val sockets = openRfcommSockets(btDevice, device.lastSuccessfulSocketStrategy)
            var lastError: Throwable? = null
            for ((label, socket) in sockets) {
                try {
                    Log.d("SmaLegacyBt", "Trying socket strategy: $label for ${device.btMac}")
                    trace.record("socket:$label open")
                    socket.connect()
                    Thread.sleep(150)
                    trace.record("socket:$label connected")
                    return@withLock SmaBluetoothSession(socket, device, zoneId, trace).use { session ->
                        block(session, label, trace)
                    }
                } catch (error: Throwable) {
                    lastError = error
                    trace.record("socket:$label failed ${error.message ?: error::class.java.simpleName}")
                    Log.w("SmaLegacyBt", "Socket strategy failed: $label", error)
                } finally {
                    runCatching { socket.close() }
                }
            }
            throw IllegalStateException(lastError?.message ?: "Bluetooth socket connection failed", lastError)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRfcommSockets(
        device: BluetoothDevice,
        preferredStrategy: String?,
    ): List<Pair<String, BluetoothSocket>> {
        val sockets = mutableListOf<Pair<String, BluetoothSocket>>()
        runCatching {
            val insecureMethod = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            sockets += "hidden_insecure_channel_1" to (insecureMethod.invoke(device, 1) as BluetoothSocket)
        }
        runCatching {
            sockets += "insecure_uuid" to device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT_UUID)
        }
        runCatching {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            sockets += "hidden_secure_channel_1" to (method.invoke(device, 1) as BluetoothSocket)
        }
        runCatching {
            sockets += "secure_uuid" to device.createRfcommSocketToServiceRecord(SERIAL_PORT_UUID)
        }
        return sockets
            .distinctBy { it.first }
            .sortedBy { if (it.first == preferredStrategy) 0 else 1 }
    }

    private fun hasBluetoothPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(
            appContext,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val locationGranted =
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return bluetoothGranted && locationGranted
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        appContext.registerReceiver(discoveryReceiver, filter)
        receiverRegistered = true
    }

    private companion object {
        private val SERIAL_PORT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}

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

    suspend fun <T> use(block: suspend (SmaBluetoothSession) -> T): T {
        trace.record("phase:opening-socket")
        trace.record("phase:initialize")
        initializeConnection()
        trace.record("phase:login")
        login()
        return try {
            block(this)
        } finally {
            trace.record("phase:logoff")
            runCatching { logoff() }
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
            status = statusValues[Lri.OPERATION_HEALTH]?.let { "Health 0x${it.toString(16)}" } ?: "Live read OK",
            gridRelay = statusValues[Lri.OPERATION_GRI_SW_STT]?.let { "Relay 0x${it.toString(16)}" },
            btSignalPercent = signal,
            sourceType = "bluetooth_live",
        )
    }

    fun readDayArchive(deviceId: Long, date: LocalDate): DayAggregateEntity? {
        trace.record("phase:reading-day-archive $date")
        val startOfDay = date.atStartOfDay(zoneId).toEpochSecond()
        val requestStart = startOfDay - 300
        val requestEnd = startOfDay + 86100

        val packets = sendArchiveRequest(0x70000200, requestStart, requestEnd)
        var previousTimestamp = 0L
        var previousTotalWh = 0L
        var maxTotalWh = 0L
        var maxPower = 0

        for (packet in packets) {
            var offset = 41
            while (offset < packet.payload.size - 3) {
                val timestamp = uint32(packet.payload, offset)
                val totalWh = uint64(packet.payload, offset + 4)
                if (totalWh != U64_NAN && totalWh >= 0) {
                    maxTotalWh = maxOf(maxTotalWh, totalWh)
                    if (previousTotalWh > 0L && timestamp > previousTimestamp) {
                        val watts = ((totalWh - previousTotalWh) * 3600L / (timestamp - previousTimestamp)).toInt()
                        maxPower = maxOf(maxPower, watts)
                    }
                    previousTotalWh = totalWh
                    previousTimestamp = timestamp
                }
                offset += 12
            }
        }

        return if (maxTotalWh > 0L) {
            DayAggregateEntity(
                deviceId = deviceId,
                dateEpochDay = date.toEpochDay(),
                totalYieldWh = maxTotalWh,
                powerW = maxPower.takeIf { it > 0 },
                sourceType = "bluetooth_day_archive",
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
                        monthYieldWh += (totalWh - previousTotalWh)
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
        val announcement = runCatching { getPacket(rootDeviceAddress, 0x02) }.getOrElse {
            Log.w("SmaLegacyBt", "Passive announcement wait failed, prompting inverter", it)
            trace.record("phase:prompting-inverter")
            trace.record("init:announcement-fallback")
            val version = byteArrayOf(1, 0, 0, 0, 0, 0)
            val initFrame = BluetoothFrameBuilder(localAddress, version, 0x0201).apply {
                writeEscapedByte('v'.code)
                writeEscapedByte('e'.code)
                writeEscapedByte('r'.code)
                writeEscapedByte(13)
                writeEscapedByte(10)
            }.build()
            send(initFrame)
            getPacket(rootDeviceAddress, 0x02)
        }
        val netId = announcement.payload.getOrNull(22)?.toUByte()?.toInt()
            ?: throw IOException("SMA NetID not received")
        trace.record("init:netid=$netId")

        trace.record("phase:connecting-inverter")
        val connectFrame = BluetoothFrameBuilder(localAddress, rootDeviceAddress, 0x02).apply {
            writeEscapedLong(0x00700400)
            writeEscapedByte(netId)
            writeEscapedLong(0)
            writeEscapedLong(1)
        }.build()
        send(connectFrame)

        trace.record("init:wait-handshake")
        val handshake = getPacket(rootDeviceAddress, 0x05)
        trace.record("init:handshake command=${handshake.command} source=${formatBluetoothAddress(handshake.sourceAddress)}")
        require(handshake.payload.size > 31) { "Incomplete SMA handshake packet" }
        localAddress = handshake.payload.copyOfRange(26, 32)
        trace.record("init:local-bt=${formatBluetoothAddress(localAddress)}")

        trace.record("phase:reading-identity")
        val identityPacket = sendL2UntilValid(rootDeviceAddress = UNKNOWN_ADDRESS, packetBuilder = { builder ->
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
        }, expectedSender = rootDeviceAddress, waitCommand = 0x01)

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
        val loginPacket = sendL2UntilValid(rootDeviceAddress = UNKNOWN_ADDRESS, packetBuilder = { builder ->
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
        }, expectedSender = UNKNOWN_ADDRESS, waitCommand = 0x01)

        require(validateChecksum(loginPacket.payload)) { "SMA login checksum invalid" }
        val responsePacketId = uint16(loginPacket.payload, 27) and 0x7FFF
        trace.record("login:response packet=$responsePacketId error=${loginPacket.payload[24].toInt() and 0xFF}")
        require(responsePacketId == packetId) { "Unexpected SMA login packet id" }
        require(uint32(loginPacket.payload, 41) == now) { "Unexpected SMA login timestamp" }
        require(loginPacket.payload[24].toInt() == 0) { "SMA login failed. Check PIN/password." }
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
            }.build()
        }
    }

    private fun queryData(command: Long, first: Long, last: Long): List<SmaRecord> {
        val packet = sendL2UntilValid(rootDeviceAddress = UNKNOWN_ADDRESS, packetBuilder = { builder ->
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
            builder.writeEscapedLong(command)
            builder.writeEscapedLong(first)
            builder.writeEscapedLong(last)
        }, expectedSender = UNKNOWN_ADDRESS, waitCommand = 0x01)

        require(validateChecksum(packet.payload)) { "SMA live data checksum invalid" }
        require((uint16(packet.payload, 27) and 0x7FFF) == packetId) { "Unexpected SMA data packet id" }
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
            }.build()
        }

        val packets = mutableListOf<ReceivedPacket>()
        var morePackets = true
        while (morePackets) {
            val packet = getPacket(rootDeviceAddress, 0x01)
            require(validateChecksum(packet.payload)) { "SMA archive checksum invalid" }
            val responsePacketId = uint16(packet.payload, 27) and 0x7FFF
            if (responsePacketId != sentPacketId) {
                continue
            }
            packets += packet
            morePackets = packet.payload.getOrNull(25)?.toUByte()?.toInt()?.let { it > 0 } == true
        }
        return packets
    }

    private fun sendSimpleCommand(destAddress: ByteArray, command: Int, block: BluetoothFrameBuilder.() -> Unit) {
        val frame = BluetoothFrameBuilder(localAddress, destAddress, command).apply(block).build()
        send(frame)
    }

    private fun sendL2UntilValid(
        rootDeviceAddress: ByteArray,
        packetBuilder: (BluetoothFrameBuilder) -> Unit,
        expectedSender: ByteArray,
        waitCommand: Int,
    ): ReceivedPacket {
        incrementPacketIdUntilValid { candidate ->
            BluetoothFrameBuilder(localAddress, rootDeviceAddress, 0x01).apply {
                packetBuilder(this)
            }.build()
        }
        return getPacket(expectedSender, waitCommand)
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
        var index = 41
        while (index < packet.size - 3) {
            val code = uint32(packet, index).toInt()
            val lri = code and 0x00FFFF00
            val cls = code and 0xFF
            val dataType = (code ushr 24) and 0xFF
            val timestamp = uint32(packet, index + 4)
            val size = when {
                lri in LONG_TAGS -> 16
                dataType == 0x10 || dataType == 0x08 -> 40
                else -> 28
            }
            val intValue = if (size >= 12) {
                uint32(packet, index + 8).takeUnless { it == U32_NAN }?.toInt() ?: 0
            } else {
                0
            }
            val longValue = if (lri in LONG_TAGS) {
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

    private fun getPacket(expectedSender: ByteArray, waitCommand: Int): ReceivedPacket {
        while (true) {
            val header = readExact(PK_HEADER_SIZE)
            val packetLength = uint16(header, 1)
            require(packetLength >= PK_HEADER_SIZE) { "Invalid SMA packet length" }
            val command = uint16(header, 16)
            val sourceAddress = header.copyOfRange(4, 10)
            val remaining = if (packetLength > PK_HEADER_SIZE) readExact(packetLength - PK_HEADER_SIZE) else ByteArray(0)
            if (!isValidSender(expectedSender, sourceAddress)) {
                trace.record("packet:skip command=$command source=${formatBluetoothAddress(sourceAddress)}")
                continue
            }
            val payload = if (packetLength > PK_HEADER_SIZE &&
                remaining.isNotEmpty() &&
                remaining[0] == 0x7E.toByte() &&
                uint32(remaining, 1) == 0x656003FFL
            ) {
                unescapePayload(remaining)
            } else {
                header + remaining
            }
            trace.record("packet:recv command=$command source=${formatBluetoothAddress(sourceAddress)} bytes=${payload.size}")
            if (waitCommand == 0xFF || command == waitCommand) {
                return ReceivedPacket(command = command, sourceAddress = sourceAddress, payload = payload)
            }
        }
    }

    private fun readExact(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw IOException("Bluetooth socket closed")
            offset += read
        }
        return buffer
    }

    private fun validateChecksum(packet: ByteArray): Boolean {
        var checksum = 0xFFFF
        for (index in 1 until packet.size - 3) {
            checksum = (checksum shr 8) xor FCS_TABLE[(checksum xor packet[index].toInt()) and 0xFF]
        }
        checksum = checksum xor 0xFFFF
        return uint16(packet, packet.size - 3) == checksum
    }

    private fun unescapePayload(bytes: ByteArray): ByteArray {
        val payload = ArrayList<Byte>(bytes.size)
        var escapeNext = false
        for (byte in bytes) {
            if (payload.isEmpty()) {
                payload += byte
                continue
            }
            if (escapeNext) {
                payload += (byte.toInt() xor 0x20).toByte()
                escapeNext = false
            } else if (byte.toInt() == 0x7D) {
                escapeNext = true
            } else {
                payload += byte
            }
        }
        return payload.toByteArray()
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

    fun build(): ByteArray {
        val checksum = fcsChecksum xor 0xFFFF
        bytes += (checksum and 0xFF)
        bytes += ((checksum shr 8) and 0xFF)
        bytes += 0x7E
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

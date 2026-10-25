package app.goultra.transfer.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE control channel of the GO Ultra: commands to BE81 (write without response), replies on BE82 (notify).
 * B002 carries a status packet from the BLE controller every ~5 s while the camera is powered off.
 */
@SuppressLint("MissingPermission")
class CameraBle(private val context: Context, private val log: (String) -> Unit) {
    companion object {
        private fun uuid16(id: String) = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")
        val SERVICE_BE80: UUID = uuid16("be80")
        private val CHAR_BE81 = uuid16("be81")
        private val CHAR_BE82 = uuid16("be82")
        private val SERVICE_B000 = uuid16("b000")
        private val CHAR_B002 = uuid16("b002")
        private val CCCD = uuid16("2902")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val opLock = Mutex()
    private val frameLock = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<CamMessage>>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null
    @Volatile private var connected = CompletableDeferred<Boolean>()
    @Volatile private var sync = CompletableDeferred<Unit>()
    @Volatile private var wakeAck: CompletableDeferred<ByteArray>? = null
    @Volatile private var authResult = CompletableDeferred<Int>()
    @Volatile private var mtu = 23
    private var rxBuf = ByteArray(0)
    private var lastB002: String? = null
    private var seq = 0

    // ---------- scan / bond / connect ----------

    suspend fun scan(timeoutMs: Long = 15_000): ScanResult? {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: run { log("Bluetooth выключен"); return null }
        val found = CompletableDeferred<ScanResult>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(result)
            }

            override fun onScanFailed(errorCode: Int) {
                log("ошибка сканирования: $errorCode")
                found.completeExceptionally(IllegalStateException("scan failed: $errorCode"))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_BE80)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        log("поиск камеры (сервис BE80)…")
        scanner.startScan(filters, settings, callback)
        try {
            val r = withTimeoutOrNull(timeoutMs) { found.await() } ?: return null
            val manuf = manufacturerData(r.scanRecord?.bytes)
            val awake = manuf != null && manuf.size > 3 && manuf[3].toInt() == 1
            log("найдена ${r.device.address} «${r.scanRecord?.deviceName}» rssi=${r.rssi} " +
                "manuf=[${manuf?.hex()}] ${if (awake) "ВКЛ" else "ВЫКЛ"}, bond=${bondName(r.device.bondState)}")
            return r
        } finally {
            scanner.stopScan(callback)
        }
    }

    /**
     * Debug: every advertiser for [ms], unfiltered — to find out whether the Action Pod advertises on its own
     * (and with what) next to the camera module. Logs each address once: name, services, manufacturer data, bond.
     */
    suspend fun scanAll(ms: Long = 12_000) {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run { log("Bluetooth выключен"); return }
        val seen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                if (seen.putIfAbsent(r.device.address, true) != null) return
                val rec = r.scanRecord
                val uuids = rec?.serviceUuids?.joinToString { it.uuid.toString().substring(4, 8) } ?: ""
                val name = rec?.deviceName ?: ""
                // Only what can be ours: the camera service, a bonded device, or an Insta360-like name.
                if ("be80" !in uuids && r.device.bondState != BluetoothDevice.BOND_BONDED &&
                    !name.contains("GO", true) && !name.contains("insta", true) && !name.contains("pod", true)
                ) return
                log("СКАН ${r.device.address} «$name» rssi=${r.rssi} services=[$uuids] " +
                    "manuf=[${manufacturerData(rec?.bytes)?.hex()}] bond=${bondName(r.device.bondState)}")
            }
        }
        log("скан всех устройств ${ms / 1000} с…")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        try {
            kotlinx.coroutines.delay(ms)
        } finally {
            scanner.stopScan(callback)
        }
        log("скан окончен, устройств всего: ${seen.size}")
    }

    /** Raw manufacturer-specific AD structure (type 0xFF), company id included: 01 00 22 [on] [counter] 00. */
    private fun manufacturerData(record: ByteArray?): ByteArray? {
        if (record == null) return null
        var i = 0
        while (i < record.size) {
            val len = record[i].toInt() and 0xFF
            if (len == 0 || i + len >= record.size) break
            if (record[i + 1].toInt() and 0xFF == 0xFF) return record.copyOfRange(i + 2, i + 1 + len)
            i += len + 1
        }
        return null
    }

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "есть"
        BluetoothDevice.BOND_BONDING -> "в процессе"
        else -> "нет"
    }

    /** Bonds with the currently connected camera (needed to wake it from power-off). */
    suspend fun bond(): Boolean {
        val device = gatt?.device ?: run { log("нет BLE-подключения"); return false }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            log("уже сопряжено")
            return true
        }
        return ensureBonded(device)
    }

    private suspend fun ensureBonded(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val result = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (d?.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                log("сопряжение: ${bondName(state)}")
                if (state != BluetoothDevice.BOND_BONDING) result.complete(state)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            // Android usually shows this as a notification, not a dialog; it expires after ~30 s.
            log("сопряжение… ОТКРОЙТЕ ШТОРКУ и нажмите «Запрос на сопряжение» от камеры (30 с)")
            if (!device.createBond()) {
                log("createBond() отказал")
                return false
            }
            return withTimeoutOrNull(60_000) { result.await() } == BluetoothDevice.BOND_BONDED
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    val isBonded: Boolean get() = gatt?.device?.bondState == BluetoothDevice.BOND_BONDED

    @Volatile private var linkUp = false

    /** GATT link is up and the camera answered sync: commands can be sent. */
    val isReady: Boolean get() = gatt != null && linkUp && sync.isCompleted

    /**
     * Connects; if the camera is not bonded yet, starts bonding and calls [onBonding] (user must accept it).
     * Android often fails the first GATT setup with status 133 or a dropped link: up to 3 attempts.
     */
    suspend fun connect(device: BluetoothDevice, onBonding: () -> Unit = {}): Boolean {
        repeat(3) { attempt ->
            if (attempt > 0) {
                log("повторное подключение (${attempt + 1}/3)…")
                delay(800)
            }
            if (connectOnce(device, onBonding)) return true
            disconnect()
        }
        return false
    }

    private suspend fun connectOnce(device: BluetoothDevice, onBonding: () -> Unit): Boolean {
        disconnect()
        connected = CompletableDeferred()
        sync = CompletableDeferred()
        rxBuf = ByteArray(0)
        lastB002 = null
        log("подключение к ${device.address}…")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        if (withTimeoutOrNull(20_000) { connected.await() } != true) {
            log("не удалось подключиться")
            return false
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            onBonding()
            if (!ensureBonded(device)) log("сопряжение не удалось — выключенную камеру разбудить не получится")
        }
        val mtuStatus = gattOp { g.requestMtu(517) }
        log("MTU $mtu" + if (mtuStatus != BluetoothGatt.GATT_SUCCESS) " (запрос MTU не прошёл: $mtuStatus)" else "")
        if (gattOp(15_000) { g.discoverServices() } != BluetoothGatt.GATT_SUCCESS) {
            log("не удалось получить список сервисов")
            return false
        }
        val service = g.getService(SERVICE_BE80) ?: run { log("нет сервиса BE80"); return false }
        writeChar = service.getCharacteristic(CHAR_BE81)
        if (!enableNotify(service.getCharacteristic(CHAR_BE82))) return false // no replies without it
        enableNotify(g.getService(SERVICE_B000)?.getCharacteristic(CHAR_B002))
        if (!linkUp) {
            log("связь оборвалась во время настройки")
            return false
        }
        log("BLE подключено")
        return true
    }

    fun disconnect() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        writeChar = null
        linkUp = false
        val closed = IOException("BLE disconnected")
        pending.values.forEach { it.completeExceptionally(closed) }
        pending.clear()
    }

    // ---------- camera protocol ----------

    suspend fun waitSync(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { sync.await() } != null

    /** Wakes the camera if its main CPU is off. Returns true once the camera sends sync. */
    suspend fun wake(authId: String): Boolean {
        if (sync.isCompleted) {
            log("камера уже не спит")
            return true
        }
        val ack = CompletableDeferred<ByteArray>()
        wakeAck = ack
        write(BleFrame.wake(authId), "wake")
        val payload = withTimeoutOrNull(3_000) { ack.await() }
        log(if (payload == null) "нет ответа на wake" else "wake принят: ${payload.hex()}")
        val ok = waitSync(15_000)
        log(if (ok) "камера проснулась" else "камера не прислала sync")
        return ok
    }

    /** CHECK_AUTHORIZATION; if unauthorized the camera asks the user on its screen, result comes as 8209. */
    suspend fun authorize(authId: String, onPrompt: () -> Unit = {}): Boolean {
        authResult = CompletableDeferred()
        val r = request(Proto.CMD_CHECK_AUTHORIZATION, PbWriter().string(1, authId).toByteArray()) ?: return false
        if (r.code != 200) {
            log("CHECK_AUTHORIZATION: код ${r.code}")
            return false
        }
        // proto3 omits default values: an empty body means status 0 = AUTHORIZED.
        val status = r.body.pbFields().field(1)?.varint ?: 0L
        if (status == 0L) {
            log("авторизация: OK (id=$authId)")
            return true
        }
        log("не авторизовано (status=$status) — подтвердите подключение на экране камеры")
        onPrompt()
        val res = withTimeoutOrNull(60_000) { authResult.await() }
        log("результат авторизации: ${res ?: "нет ответа"}")
        return res == 0
    }

    suspend fun wifiCredentials(): WifiCreds? {
        val body = PbWriter().packed(1, listOf(Proto.OPTION_WIFI_INFO)).toByteArray()
        val r = request(Proto.CMD_GET_OPTIONS, body) ?: return null
        if (r.code != 200) {
            log("GET_OPTIONS: код ${r.code}")
            return null
        }
        val options = r.body.pbFields().field(2)?.data ?: return null
        val wifi = options.pbFields().field(36)?.data?.pbFields() ?: return null
        val ssid = wifi.field(1)?.string ?: return null
        val password = wifi.field(2)?.string ?: return null
        return WifiCreds(ssid, password, wifi.field(3)?.varint?.toInt() ?: 0)
    }

    /** [quiet]: don't log a successful exchange (status polling every second). */
    suspend fun request(
        code: Int,
        body: ByteArray = ByteArray(0),
        timeoutMs: Long = 5_000,
        quiet: Boolean = false,
    ): CamMessage? {
        val s = synchronized(this) { seq = seq % 254 + 1; seq }
        val d = CompletableDeferred<CamMessage>()
        pending[s] = d
        if (!write(BleFrame.command(code, s, body), if (quiet) null else "cmd $code seq=$s")) {
            pending.remove(s)
            return null
        }
        val r = try {
            withTimeoutOrNull(timeoutMs) { d.await() }
        } catch (e: IOException) {
            null // disconnected while waiting
        } finally {
            pending.remove(s)
        }
        if (r == null) log("нет ответа на команду $code")
        else if (!quiet || r.code != 200) log("<< ответ ${r.code} seq=${r.seq}: ${r.body.hex(24)}")
        return r
    }

    // ---------- GATT plumbing ----------

    /** Runs one GATT operation at a time and waits for its callback status (-1: not started, -2: timeout). */
    private suspend fun gattOp(timeoutMs: Long = 10_000, start: () -> Boolean): Int = opLock.withLock {
        val d = CompletableDeferred<Int>()
        pendingOp = d
        try {
            if (!start()) -1 else withTimeoutOrNull(timeoutMs) { d.await() } ?: -2
        } finally {
            pendingOp = null
        }
    }

    /** [what] == null: don't log the frame. */
    private suspend fun write(data: ByteArray, what: String?): Boolean {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null) {
            log("нет BLE-подключения")
            return false
        }
        if (what != null) log(">> $what: ${data.hex(24)}")
        // One write carries at most MTU-3 bytes; longer frames go in consecutive chunks,
        // and no other frame (e.g. a sync reply) may interleave with them.
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        frameLock.withLock {
            var offset = 0
            while (offset < data.size) {
                val chunk = data.copyOfRange(offset, minOf(data.size, offset + chunkSize))
                val status = gattOp {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            ch.value = chunk
                            g.writeCharacteristic(ch)
                        }
                    }
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("ошибка записи: $status")
                    return false
                }
                offset += chunk.size
            }
        }
        return true
    }

    private suspend fun enableNotify(ch: BluetoothGattCharacteristic?): Boolean {
        val g = gatt ?: return false
        if (ch == null) return false
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val status = gattOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    d.value = value
                    g.writeDescriptor(d)
                }
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) log("не удалось подписаться на ${ch.uuid}: $status")
        return status == BluetoothGatt.GATT_SUCCESS
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linkUp = true
                    connected.complete(true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("BLE отключено (status $status)")
                    linkUp = false
                    connected.complete(false)
                    pendingOp?.complete(-3)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this@CameraBle.mtu = mtu
            pendingOp?.complete(status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(ch.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onNotify(ch.uuid, ch.value ?: return)
        }
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        if (uuid == CHAR_B002) {
            val h = value.hex()
            if (h != lastB002) {
                lastB002 = h
                log("B002 (камера спит): ${value.hex(32)}")
            }
            return
        }
        if (uuid != CHAR_BE82) return
        rxBuf += value
        parseFrames()
    }

    private fun parseFrames() {
        while (rxBuf.size >= 5) {
            if (rxBuf[0] != 0xFF.toByte()) {
                log("<< не кадр: ${rxBuf.hex()}")
                rxBuf = ByteArray(0)
                return
            }
            val total = u16(rxBuf, 3) + 7
            if (rxBuf.size < total) return
            val pkt = rxBuf.copyOfRange(0, total)
            rxBuf = rxBuf.copyOfRange(total, rxBuf.size)
            if (Crc16.modbus(pkt, 0, total - 2) != u16(pkt, total - 2)) log("<< CRC не сошёлся: ${pkt.hex()}")
            val type = pkt[1].toInt() and 0xFF
            val subtype = pkt[2].toInt() and 0xFF
            val inner = pkt.copyOfRange(5, total - 2)
            when {
                subtype == 0x41 -> {
                    log("<< sync")
                    val done = sync
                    scope.launch {
                        write(BleFrame.syncReply(), "sync reply")
                        done.complete(Unit) // commands go out only after the reply
                    }
                }
                type == 0x0D -> wakeAck?.complete(inner)
                subtype == 0x40 -> Proto.parseMessage(inner, 4, seqBytes = 1)?.let(::route)
                    ?: log("<< непонятное сообщение: ${pkt.hex()}")
                else -> log("<< кадр: ${pkt.hex()}")
            }
        }
    }

    private fun route(msg: CamMessage) {
        if (msg.isResponse) {
            pending.remove(msg.seq)?.complete(msg)
            return
        }
        log("<< уведомление ${msg.code}: ${msg.body.hex(24)}")
        if (msg.code == Proto.NOTIFY_AUTHORIZATION_RESULT) {
            authResult.complete(msg.body.pbFields().field(1)?.varint?.toInt() ?: 0)
        }
    }
}

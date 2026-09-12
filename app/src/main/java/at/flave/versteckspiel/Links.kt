package at.flave.versteckspiel

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/** Gemeinsamer Port aller Geraete im WLAN. */
const val PORT = 45678

/** Kennzeichnet ein Paket mit Nutzdaten statt eines Befehls. */
private val BULK = byteArrayOf('V'.code.toByte(), 'S'.code.toByte(), 'A'.code.toByte())

/**
 * Weg 1: UDP-Broadcast im WLAN. Schnell und reicht durch die ganze Wohnung,
 * setzt aber voraus, dass alle Handys im selben Netz haengen.
 */
class UdpLink(
    private val ctx: Context,
    private val onMsg: (Msg, Int?, BluetoothDevice?) -> Unit,
    private val onBulk: (ByteArray) -> Unit = {},
) : Link {

    override val name = "WLAN"

    /** Ohne erreichbares Netz bringt der gebundene Socket nichts. */
    override val active: Boolean
        get() = bound && broadcastAddresses().isNotEmpty()

    @Volatile private var bound = false
    @Volatile private var running = false

    /**
     * Adressen der anderen Handys. Groessere Daten gehen gezielt dorthin statt
     * per Broadcast - WLAN-Broadcasts werden auf MAC-Ebene nicht wiederholt und
     * verlieren bei vielen Paketen zu viel.
     */
    private val peers = HashSet<InetAddress>()
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    override fun start() {
        if (running) return
        running = true
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = runCatching {
            wifi.createMulticastLock("versteckspiel").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()

        Thread {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PORT))
                }
                socket = s
                bound = true
                val buf = ByteArray(1600)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (p.length >= 3 && buf[0] == BULK[0] && buf[1] == BULK[1] && buf[2] == BULK[2]) {
                        onBulk(buf.copyOfRange(3, p.length))
                    } else {
                        val text = String(p.data, 0, p.length).trim()
                        Msg.fromText(text)?.let {
                            synchronized(peers) { peers.add(p.address) }
                            onMsg(it, null, null)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                bound = false
            }
        }.start()
    }

    override fun send(msg: Msg) {
        val s = socket ?: return
        val bytes = msg.toText().toByteArray()
        Thread {
            for (addr in broadcastAddresses()) {
                runCatching { s.send(DatagramPacket(bytes, bytes.size, addr, PORT)) }
            }
        }.start()
    }

    /** Grossere Nutzdaten gezielt an alle bekannten Handys schicken. */
    fun sendBulk(payload: ByteArray) {
        val s = socket ?: return
        val bytes = BULK + payload
        val targets = synchronized(peers) { peers.toList() }
        Thread {
            for (addr in targets) {
                runCatching { s.send(DatagramPacket(bytes, bytes.size, addr, PORT)) }
            }
        }.start()
    }

    override fun stop() {
        running = false; bound = false
        runCatching { socket?.close() }
        runCatching { lock?.release() }
        socket = null; lock = null
    }

    /** Alle Broadcast-Adressen der aktiven Interfaces (WLAN, Hotspot, ...). */
    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        return out
    }
}

/**
 * Weg 2: BLE-Advertising - Funken ohne Verbindung und ohne Kopplung.
 *
 * Jedes Handy funkt dauerhaft ein Anwesenheitspaket. Ein Befehl loest das
 * Anwesenheitspaket fuer kurze Zeit ab, danach geht es zurueck auf Anwesenheit.
 * Ein Scan-Filter auf die Hersteller-Kennung ist Pflicht, sonst liefert Android
 * bei ausgeschaltetem Bildschirm gar keine Treffer mehr.
 */
class BleLink(
    private val ctx: Context,
    private val onMsg: (Msg, Int?, BluetoothDevice?) -> Unit,
) : Link {

    companion object {
        /** 0xFFFF ist laut Bluetooth SIG fuer Tests und interne Zwecke frei. */
        private const val COMPANY = 0xFFFF
        /**
         * Wie lange ein Befehl in der Luft bleibt. Grosszuegig, weil Android das
         * Scannen bei ausgeschaltetem Bildschirm phasenweise aussetzt - ein kurz
         * gefunkter Befehl faellt sonst genau in so eine Pause.
         */
        private const val COMMAND_HOLD_MS = 3500L

        /** Laufzeitrechte, die BLE hier braucht. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            else
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasPermissions(ctx: Context) = requiredPermissions().all {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override val name = "Bluetooth"
    @Volatile override var active = false
        private set

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertising: AdvertiseCallback? = null

    /** Das Paket, auf das nach einem Befehl zurueckgefallen wird. */
    @Volatile private var idle: ByteArray? = null

    private val worker = HandlerThread("ble").apply { start() }
    private val handler = Handler(worker.looper)

    /** Setzt das Dauerpaket - muss vor [start] bekannt sein. */
    fun setPresence(msg: Msg) {
        idle = msg.toBytes()
        if (active) handler.post { advertise(idle) }
    }

    override fun start() {
        if (active) return
        if (!hasPermissions(ctx)) return
        val adapter = runCatching {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        }.getOrNull() ?: return
        if (!adapter.isEnabled) return

        advertiser = adapter.bluetoothLeAdvertiser ?: return
        scanner = adapter.bluetoothLeScanner ?: return
        active = true
        startScanning()
        handler.post { advertise(idle) }
    }

    private fun startScanning() {
        // Schon die Hardware soll nur unsere eigenen Pakete durchlassen.
        val filter = ScanFilter.Builder()
            .setManufacturerData(COMPANY, Msg.MAGIC, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        runCatching { scanner?.startScan(listOf(filter), settings, scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(COMPANY) ?: return
            // Signalstaerke und Geraet kommen hier gratis mit: die eine speist
            // den Naeherungspegel, das andere den Abruf von Aufnahmen.
            Msg.fromBytes(data)?.let { onMsg(it, result.rssi, result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            active = false
        }
    }

    override fun send(msg: Msg) {
        if (!active) return
        handler.post {
            advertise(msg.toBytes())
            // Befehle muessen eine Weile in der Luft bleiben, damit die anderen
            // Handys sie im Scan-Intervall wirklich erwischen.
            if (Op.changesRole(msg.op)) {
                handler.removeCallbacksAndMessages("back")
                handler.postDelayed({ advertise(idle) }, COMMAND_HOLD_MS)
            }
        }
    }

    /** Advertising laeuft dauerhaft - senden heisst den Inhalt austauschen. */
    private fun advertise(data: ByteArray?) {
        val adv = advertiser ?: return
        advertising?.let { runCatching { adv.stopAdvertising(it) } }
        advertising = null
        if (data == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            // verbindbar, damit Aufnahmen per GATT abgeholt werden koennen
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val payload = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(COMPANY, data)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                active = false
            }
        }
        advertising = cb
        runCatching { adv.startAdvertising(settings, payload, cb) }
    }

    override fun stop() {
        active = false
        runCatching { scanner?.stopScan(scanCallback) }
        advertising?.let { cb -> runCatching { advertiser?.stopAdvertising(cb) } }
        advertising = null
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
    }
}

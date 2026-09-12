package at.flave.versteckspiel

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Aufnahmen ueber Bluetooth verschicken, wenn kein WLAN da ist.
 *
 * Ueber Advertising waere das aussichtslos - da passen 17 Byte Nutzdaten in ein
 * Paket, eine Aufnahme braeuchte tausende davon und damit Minuten. Also eine
 * echte GATT-Verbindung: die kommt ohne Kopplung aus, solange die Merkmale
 * unverschluesselt sind, und schafft die paar Kilobyte in Sekunden.
 *
 * Wer eine Aufnahme hat, ist Server. Wem sie fehlt, verbindet sich, schreibt
 * die gewuenschte Platznummer und bekommt die Daten als Notifications.
 */
@Suppress("DEPRECATION")   // die neuen API-33-Signaturen wuerden alles verdoppeln
class GattTransfer(
    private val ctx: Context,
    private val recordings: Recordings,
    private val onReceived: (Int) -> Unit,
) {

    companion object {
        val SERVICE: UUID = UUID.fromString("8f1e5a10-9c2b-4f3d-8a71-2b4c6d8e0f12")
        val CONTROL: UUID = UUID.fromString("8f1e5a11-9c2b-4f3d-8a71-2b4c6d8e0f12")
        val DATA: UUID = UUID.fromString("8f1e5a12-9c2b-4f3d-8a71-2b4c6d8e0f12")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TAG = "VSGatt"
        private const val TIMEOUT_MS = 25_000L
        /** Vorsichtig unter der ausgehandelten MTU bleiben. */
        private const val CHUNK = 180

        fun hasConnectPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }

    private val ui = Handler(Looper.getMainLooper())

    // ----------------------------------------------------------------- Server

    private var server: BluetoothGattServer? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<ByteArray>()
    private var sendTo: BluetoothDevice? = null

    fun startServer() {
        if (server != null || !hasConnectPermission(ctx)) return
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return

        val s = runCatching { mgr.openGattServer(ctx, serverCallback) }.getOrNull() ?: return
        server = s

        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val control = BluetoothGattCharacteristic(
            CONTROL,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val data = BluetoothGattCharacteristic(
            DATA,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }
        service.addCharacteristic(control)
        service.addCharacteristic(data)
        dataChar = data
        runCatching { s.addService(service) }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            val slot = value?.firstOrNull()?.toInt()?.and(0xFF) ?: return
            Log.d(TAG, "Server: Anfrage fuer Platz $slot")
            enqueue(device, slot)
        }

        /**
         * Der Client schaltet die Benachrichtigungen ueber diesen Descriptor
         * ein. Ohne Antwort darauf wartet er ewig und es geht nicht weiter -
         * das ist die klassische Falle beim Android-GATT-Server.
         */
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            runCatching {
                server?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            }
        }

        /** Erst wenn ein Paket raus ist, darf das naechste - sonst laeuft der Puffer über. */
        override fun onNotificationSent(device: BluetoothDevice, status: Int) = pump()
    }

    private fun enqueue(device: BluetoothDevice, slot: Int) {
        val data = recordings.bytes(slot) ?: run {
            Log.d(TAG, "Server: Platz $slot ist leer")
            return
        }
        val count = (data.size + CHUNK - 1) / CHUNK
        Log.d(TAG, "Server: sende ${data.size} Byte in $count Stuecken")
        synchronized(queue) {
            queue.clear()
            // Kopfpaket: Platz, Anzahl der Stuecke, Gesamtlaenge
            queue.add(
                byteArrayOf(
                    0, 0, slot.toByte(),
                    (count shr 8).toByte(), count.toByte(),
                    (data.size shr 24).toByte(), (data.size shr 16).toByte(),
                    (data.size shr 8).toByte(), data.size.toByte()
                )
            )
            for (i in 0 until count) {
                val from = i * CHUNK
                val to = minOf(from + CHUNK, data.size)
                val pkt = ByteArray(2 + (to - from))
                val idx = i + 1
                pkt[0] = (idx shr 8).toByte(); pkt[1] = idx.toByte()
                System.arraycopy(data, from, pkt, 2, to - from)
                queue.add(pkt)
            }
        }
        sendTo = device
        pump()
    }

    private fun pump() {
        val s = server ?: return
        val device = sendTo ?: return
        val c = dataChar ?: return
        val next = synchronized(queue) { queue.removeFirstOrNull() }
        if (next == null) {
            sendTo = null
            return
        }
        c.value = next
        runCatching { s.notifyCharacteristicChanged(device, c, false) }
    }

    fun stopServer() {
        runCatching { server?.close() }
        server = null
        dataChar = null
        synchronized(queue) { queue.clear() }
        sendTo = null
    }

    // ----------------------------------------------------------------- Client

    @Volatile var busy = false
        private set

    private var gatt: BluetoothGatt? = null
    private var wantSlot = -1
    private var expected = -1
    private var totalLen = 0
    private val parts = HashMap<Int, ByteArray>()
    private val timeout = Runnable { finish(false) }

    /** Eine fehlende Aufnahme bei dem Handy holen, das sie hat. */
    fun fetch(device: BluetoothDevice, slot: Int) {
        if (busy || !hasConnectPermission(ctx)) return
        busy = true
        wantSlot = slot
        expected = -1
        totalLen = 0
        parts.clear()
        ui.postDelayed(timeout, TIMEOUT_MS)
        Log.d(TAG, "Client: hole Platz $slot von ${device.address}")
        gatt = runCatching {
            device.connectGatt(ctx, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) finish(false)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runCatching { g.requestMtu(247) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (busy) finish(false)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            runCatching { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val data = g.getService(SERVICE)?.getCharacteristic(DATA)
            if (data == null) { Log.d(TAG, "Client: Merkmal fehlt"); return finish(false) }
            val on = g.setCharacteristicNotification(data, true)
            val cccd = data.getDescriptor(CCCD)
            if (cccd == null) { Log.d(TAG, "Client: CCCD fehlt"); return finish(false) }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!on) Log.d(TAG, "Client: Benachrichtigungen liessen sich nicht einschalten")
            runCatching { g.writeDescriptor(cccd) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // Erst nach aktivierter Benachrichtigung den Wunsch aussprechen.
            val ctl = g.getService(SERVICE)?.getCharacteristic(CONTROL)
            if (ctl == null) { Log.d(TAG, "Client: Steuermerkmal fehlt"); return finish(false) }
            ctl.value = byteArrayOf(wantSlot.toByte())
            ctl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            runCatching { g.writeCharacteristic(ctl) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            if (v.size < 2) return
            val idx = ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
            if (idx == 0) {
                if (v.size < 9) return
                expected = ((v[3].toInt() and 0xFF) shl 8) or (v[4].toInt() and 0xFF)
                totalLen = ((v[5].toInt() and 0xFF) shl 24) or ((v[6].toInt() and 0xFF) shl 16) or
                    ((v[7].toInt() and 0xFF) shl 8) or (v[8].toInt() and 0xFF)
                return
            }
            parts[idx] = v.copyOfRange(2, v.size)
            if (expected > 0 && parts.size == expected) assemble()
        }
    }

    private fun assemble() {
        val out = ByteArray(totalLen)
        var p = 0
        for (i in 1..expected) {
            val part = parts[i] ?: return finish(false)
            if (p + part.size > totalLen) return finish(false)
            System.arraycopy(part, 0, out, p, part.size)
            p += part.size
        }
        val slot = wantSlot
        val ok = p == totalLen && recordings.store(slot, out)
        finish(ok)
        if (ok) ui.post { onReceived(slot) }
    }

    private fun finish(ok: Boolean) {
        // Ein Fehlschlag ist kein Drama - der Abgleich versucht es spaeter erneut.
        Log.d(TAG, "Client: Abruf beendet, erfolgreich=$ok")
        ui.removeCallbacks(timeout)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        parts.clear()
        busy = false
    }
}

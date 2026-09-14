package at.flave.versteckspiel

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import kotlin.random.Random

/** Was dieses Handy gerade ist. Es startet immer als Fernbedienung. */
enum class Role { CONTROL, HIDING }

/** Meldungen an die Oberflaeche. */
sealed class Event {
    data class Played(val soundId: String) : Event()
    data class RoleChanged(val role: Role) : Event()
    /** Eine Aufnahme kam an oder wurde gemacht - die Kacheln neu zeichnen. */
    object RecordingsChanged : Event()
}

/** Laedt alle Geraeusche vorab und dreht beim Abspielen die Medienlautstaerke auf. */
class SoundBox(ctx: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = HashMap<String, Int>()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        for (s in SOUNDS) ids[s.id] = pool.load(ctx, s.res, 1)
    }

    fun play(id: String, volume: Float = 1f) {
        // Unterm Sofa soll man es hoeren.
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
            )
        }
        ids[id]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
    }

    fun release() = pool.release()
}

/**
 * Ein Geraet im Spiel. Es gibt keine festen Rollen im Netz, sondern eine Regel:
 *
 *   **Wer sendet, steuert. Wer empfaengt, versteckt sich.**
 *
 * Gesendet wird ueber alle verfuegbaren Wege gleichzeitig (WLAN und Bluetooth).
 * Doppelt ankommende Nachrichten filtert die Empfangsseite ueber die laufende
 * Nummer wieder heraus.
 */
class Peer(private val ctx: Context, private val onEvent: (Event) -> Unit) {

    companion object {
        @Volatile var current: Peer? = null
    }

    /** Eigene Kennung, damit eigene Sendungen erkannt und verworfen werden. */
    private val myId = Random.nextInt()

    private val udp = UdpLink(ctx, { m, rssi, dev -> onIncoming(m, rssi, dev) }, { onBulk(it) })
    private val ble = BleLink(ctx) { m, rssi, dev -> onIncoming(m, rssi, dev) }
    private val links = listOf<Link>(udp, ble)

    @Volatile var role = Role.CONTROL
        private set

    /** Alleine-Modus: ruft von selbst alle 20-40 Sekunden - ohne zu senden. */
    @Volatile var auto = false
        set(value) {
            field = value
            nextAt = if (value) System.currentTimeMillis() + nextWait() else 0L
        }

    /** Selbst aufgenommene Geraeusche dieses Handys. */
    val recordings = Recordings(ctx)

    /** Welche Aufnahmeplaetze die anderen Handys belegt haben. */
    private val slotMasks = HashMap<Int, Int>()

    /** Teilweise eingetroffene Aufnahmen, bis alle Stuecke da sind. */
    private val incoming = HashMap<Int, Array<ByteArray?>>()

    /** Bluetooth-Geraet je Handy - darueber laeuft der Abruf ohne WLAN. */
    private val devices = HashMap<Int, BluetoothDevice>()

    private val gatt = GattTransfer(ctx, recordings) { slot ->
        refreshPresence()
        onEvent(Event.RecordingsChanged)
    }

    /** Wann zuletzt wirklich gespielt wurde - fuer die Abschaltautomatik. */
    @Volatile private var lastActivity = System.currentTimeMillis()

    @Volatile private var nextAt = 0L
    @Volatile private var running = false
    private var seq = 0
    private var sounds: SoundBox? = null

    /** Zuletzt gesehene andere Handys und deren letzte laufende Nummer. */
    private val seen = HashMap<Int, Long>()
    private val lastSeq = HashMap<Int, Int>()

    /** Geglaettete Signalstaerke je Handy - roh springt sie viel zu stark. */
    private val signal = HashMap<Int, Double>()

    fun peerCount(): Int {
        val now = System.currentTimeMillis()
        synchronized(seen) {
            seen.entries.removeAll { now - it.value > 8000 }
            return seen.size
        }
    }

    /**
     * Wie nah das naechste andere Handy ist: 0 = gerade noch zu hoeren,
     * 1 = direkt daneben. null, wenn keins per Bluetooth zu hoeren ist
     * (etwa weil nur WLAN laeuft).
     *
     * Die Grenzen sind Erfahrungswerte fuer BLE im Wohnraum; Waende und Koerper
     * daempfen stark, das bleibt also eine grobe Schaetzung.
     */
    fun nearness(): Float? {
        val now = System.currentTimeMillis()
        synchronized(signal) {
            synchronized(seen) { signal.keys.retainAll { now - (seen[it] ?: 0L) < 8000 } }
            val best = signal.values.maxOrNull() ?: return null
            return (((best + 95.0) / 40.0).coerceIn(0.0, 1.0)).toFloat()
        }
    }

    /** Bitmuster der Aufnahmeplaetze, die bei irgendeinem anderen Handy belegt sind. */
    fun othersSlots(): Int {
        val now = System.currentTimeMillis()
        synchronized(seen) {
            var m = 0
            for ((id, t) in seen) if (now - t < 8000) m = m or (slotMasks[id] ?: 0)
            return m
        }
    }

    /**
     * Fuer jedes gerade erreichbare andere Handy: hat es diese Aufnahme schon?
     * Speist die Sync-Punkte auf den Kacheln.
     */
    fun syncStates(slot: Int): List<Boolean> {
        val now = System.currentTimeMillis()
        synchronized(seen) {
            return seen.entries
                .filter { now - it.value < 8000 }
                .sortedBy { it.key }
                .map { ((slotMasks[it.key] ?: 0) shr slot) and 1 == 1 }
        }
    }

    /** Nach einer neuen Aufnahme muessen die anderen die neue Belegung erfahren. */
    fun refreshPresence() {
        ble.setPresence(Msg(myId, 0, Op.PING, recordings.mask()))
        transmit(Op.PING, recordings.mask())
    }

    /** Welche Wege gerade wirklich laufen, z.B. "WLAN + Bluetooth". */
    fun channels(): String {
        val on = links.filter { it.active }.map { it.name }
        val text = if (on.isEmpty()) "kein Funk" else on.joinToString(" + ")
        // Sonst raetselt man, warum nur der halbe Funk laeuft.
        return if (!ble.active && ble.adapterOff) "$text · Bluetooth aus" else text
    }

    /** Ist Bluetooth vorhanden, aber abgeschaltet? */
    fun bluetoothOff() = !ble.active && ble.adapterOff

    fun secondsLeft(): Int {
        if (!auto || nextAt == 0L) return 0
        return maxOf(0, ((nextAt - System.currentTimeMillis()) / 1000).toInt())
    }

    private fun nextWait() = 20_000L + (Math.random() * 20_000L).toLong()

    /** Lebenszeichen setzen. */
    fun touch() {
        lastActivity = System.currentTimeMillis()
    }

    /** Wie lange niemand mehr gerufen oder gedrueckt hat. */
    fun idleMillis() = System.currentTimeMillis() - lastActivity

    // ------------------------------------------------------------------ Start

    fun start() {
        if (running) return
        running = true
        current = this
        sounds = SoundBox(ctx)
        ble.setPresence(Msg(myId, 0, Op.PING, recordings.mask()))
        links.forEach { it.start() }
        gatt.startServer()
        startAutoLoop()
        startPresenceLoop()
    }

    /** Nach nachtraeglich erteilter Bluetooth-Berechtigung noch einmal versuchen. */
    fun retryBluetooth() {
        if (!running) return
        if (!ble.active) ble.start()
        gatt.startServer()
    }

    // -------------------------------------------------------------- Empfangen

    private fun onIncoming(m: Msg, rssi: Int?, device: BluetoothDevice?) {
        if (m.from == myId) return                       // eigene Sendung
        synchronized(seen) { seen[m.from] = System.currentTimeMillis() }
        if (device != null) synchronized(devices) { devices[m.from] = device }
        if (rssi != null) synchronized(signal) {
            val old = signal[m.from]
            signal[m.from] = if (old == null) rssi.toDouble() else old * 0.7 + rssi * 0.3
        }

        if (Op.changesRole(m.op)) {
            // BLE wiederholt dasselbe Paket laufend, und ueber WLAN kommt es
            // zusaetzlich - beides faellt hier weg.
            synchronized(lastSeq) {
                if (lastSeq[m.from] == m.seq) return
                lastSeq[m.from] = m.seq
            }
        }

        if (Op.changesRole(m.op)) touch()

        when (m.op) {
            // Die Anwesenheitsmeldung traegt nebenbei die belegten Aufnahmeplaetze
            Op.PING -> synchronized(seen) { slotMasks[m.from] = m.arg }
            Op.PLAY -> if (m.arg >= Recordings.BASE) {
                val slot = m.arg - Recordings.BASE
                if (recordings.has(slot)) {
                    recordings.play(slot, playVolume())
                    onEvent(Event.Played("mic$slot"))
                    becomeHiding()
                }
            } else m.sound?.let { play(it.id); becomeHiding() }
            Op.FOUND -> { play("yippie"); becomeHiding() }
        }
    }

    private fun play(id: String) {
        sounds?.play(id, playVolume())
        onEvent(Event.Played(id))
    }

    /**
     * Je naeher der Sucher, desto leiser - dann muss man genauer hinhoeren.
     * Hoechstens auf die Haelfte, sonst findet man das Handy nie.
     */
    private fun playVolume(): Float {
        val near = nearness() ?: return 1f
        return 1f - 0.5f * near
    }

    // ----------------------------------------------------------------- Rollen

    private fun becomeHiding() {
        if (role != Role.HIDING) {
            role = Role.HIDING
            onEvent(Event.RoleChanged(Role.HIDING))
        }
    }

    /** Manuell verstecken, ohne die anderen Handys umzuschalten. */
    fun hideMyself() = becomeHiding()

    /**
     * An alle anderen schicken. Wer sendet, uebernimmt damit die Steuerung.
     */
    private fun transmit(op: Int, arg: Int = 0) {
        if (Op.changesRole(op)) {
            touch()
            auto = false
            if (role != Role.CONTROL) {
                role = Role.CONTROL
                onEvent(Event.RoleChanged(Role.CONTROL))
            }
        }
        val m = Msg(myId, if (op == Op.PING) 0 else nextSeq(), op, arg)
        links.forEach { it.send(m) }
    }

    private fun nextSeq(): Int {
        seq = (seq + 1) and 0xFF
        if (seq == 0) seq = 1                 // 0 bleibt der Anwesenheit vorbehalten
        return seq
    }

    /** Ein Geraeusch auf allen anderen Handys ausloesen. */
    fun call(sound: Sound) = transmit(Op.PLAY, SOUNDS.indexOf(sound))

    /** Diesen Aufnahmeplatz auf allen anderen Handys abspielen. */
    fun callSlot(slot: Int) = transmit(Op.PLAY, Recordings.BASE + slot)

    /**
     * Eine frische Aufnahme an alle anderen Handys schicken, in Stuecken.
     * Gezielt statt per Broadcast und zweimal hintereinander - WLAN wiederholt
     * verlorene Pakete nicht von selbst.
     */
    fun distribute(slot: Int) {
        val data = recordings.bytes(slot) ?: return
        val size = 1024
        val count = (data.size + size - 1) / size
        if (count > 255) return
        Thread {
            repeat(2) {
                for (i in 0 until count) {
                    val from = i * size
                    val to = minOf(from + size, data.size)
                    val pkt = ByteArray(3 + (to - from))
                    pkt[0] = slot.toByte(); pkt[1] = i.toByte(); pkt[2] = count.toByte()
                    System.arraycopy(data, from, pkt, 3, to - from)
                    udp.sendBulk(pkt)
                    Thread.sleep(6)
                }
                Thread.sleep(150)
            }
        }.start()
    }

    /** Stuecke einer fremden Aufnahme einsammeln und zusammensetzen. */
    private fun onBulk(payload: ByteArray) {
        if (payload.size < 4) return
        val slot = payload[0].toInt() and 0xFF
        val idx = payload[1].toInt() and 0xFF
        val count = payload[2].toInt() and 0xFF
        if (slot >= Recordings.SLOTS || count == 0 || idx >= count) return

        val complete: ByteArray? = synchronized(incoming) {
            var arr = incoming[slot]
            if (arr == null || arr.size != count) {
                arr = arrayOfNulls(count)
                incoming[slot] = arr
            }
            arr[idx] = payload.copyOfRange(3, payload.size)
            if (arr.any { it == null }) null
            else {
                incoming.remove(slot)
                val out = ByteArray(arr.sumOf { it!!.size })
                var p = 0
                for (c in arr) { System.arraycopy(c!!, 0, out, p, c.size); p += c.size }
                out
            }
        }
        if (complete != null && recordings.store(slot, complete)) {
            refreshPresence()
            onEvent(Event.RecordingsChanged)
        }
    }

    /** Das Kind hat das Handy gefunden: jubeln und die Steuerung uebernehmen. */
    fun celebrate() {
        play("yippie")
        transmit(Op.FOUND)
    }

    // ------------------------------------------------------------ Hintergrund

    /** Ruft im Alleine-Modus in unregelmaessigen Abstaenden ein zufaelliges Tier. */
    private fun startAutoLoop() = Thread {
        val callable = SOUNDS.filter { it.id != "yippie" }
        while (running) {
            if (auto && nextAt > 0L && System.currentTimeMillis() >= nextAt) {
                play(callable.random().id)     // nur lokal - kein Senden
                nextAt = System.currentTimeMillis() + nextWait()
            }
            Thread.sleep(250)
        }
    }.start()

    /** Regelmaessig melden, damit alle wissen, wer mitspielt. */
    private fun startPresenceLoop() = Thread {
        var round = 0
        while (running) {
            transmit(Op.PING, recordings.mask())
            if (++round % 5 == 0) {
                // Alle zehn Sekunden eine Aufnahme nachliefern, die anderswo
                // fehlt - so holen auch Handys auf, die beim Aufnehmen nicht
                // dabei waren.
                resendMissing()
                // Und Bluetooth nachziehen, falls es erst jetzt eingeschaltet
                // wurde. Beide Aufrufe kosten nichts, wenn schon alles laeuft.
                retryBluetooth()
            }
            Thread.sleep(2000)
        }
    }.start()

    /**
     * Fehlendes ausgleichen: was ich habe und anderen fehlt, schicke ich per
     * WLAN nach; was mir fehlt, hole ich per Bluetooth ab. Der zweite Weg
     * greift auch dann, wenn gar kein WLAN da ist.
     */
    private fun resendMissing() {
        for (slot in 0 until Recordings.SLOTS) {
            if (recordings.has(slot)) {
                if (udp.active && syncStates(slot).any { !it }) {
                    distribute(slot)
                    return
                }
            } else {
                val owner = ownerOf(slot) ?: continue
                if (!gatt.busy) {
                    gatt.fetch(owner, slot)
                    return
                }
            }
        }
    }

    /** Das Bluetooth-Geraet eines Handys, das diesen Platz belegt hat. */
    private fun ownerOf(slot: Int): BluetoothDevice? {
        val now = System.currentTimeMillis()
        synchronized(seen) {
            for ((id, t) in seen) {
                if (now - t > 8000) continue
                if (((slotMasks[id] ?: 0) shr slot) and 1 == 1) {
                    synchronized(devices) { devices[id] }?.let { return it }
                }
            }
        }
        return null
    }

    fun stop() {
        running = false
        current = null
        links.forEach { it.stop() }
        gatt.stopServer()
        synchronized(signal) { signal.clear() }
        sounds?.release()
        sounds = null
    }
}

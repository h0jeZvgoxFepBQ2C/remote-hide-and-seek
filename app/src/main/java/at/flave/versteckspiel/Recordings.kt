package at.flave.versteckspiel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Selbst aufgenommene Geraeusche - die Kinder sprechen ihre eigene Stimme auf.
 *
 * Eine neue Aufnahme wird gleich an alle anderen Handys verteilt, damit man sie
 * ueberall hoert: einmal "Mamaaa!" aufnehmen, und es ertoent aus jedem Versteck.
 * Beim Ausloesen geht dann nur noch die Platznummer ueber die Leitung.
 */
class Recordings(private val ctx: Context) {

    companion object {
        const val SLOTS = 8
        /** Ab hier zaehlen die Plaetze, darunter liegen die festen Geraeusche. */
        const val BASE = 200
        /** Laenger als so lange wird nicht aufgenommen. */
        const val MAX_MS = 10_000L
        /** Darunter bricht MediaRecorder beim Stoppen ab und die Datei ist Muell. */
        private const val MIN_MS = 700L
    }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private var activeSlot = -1
    private var pendingDone: ((Boolean) -> Unit)? = null
    private val autoStop = Runnable { stop() }

    private fun file(slot: Int) = File(ctx.filesDir, "eigenes_$slot.m4a")

    /** Rohdaten zum Verteilen an die anderen Handys. */
    fun bytes(slot: Int): ByteArray? =
        if (has(slot)) runCatching { file(slot).readBytes() }.getOrNull() else null

    /** Eine von einem anderen Handy empfangene Aufnahme ablegen. */
    fun store(slot: Int, data: ByteArray): Boolean =
        slot in 0 until SLOTS && runCatching { file(slot).writeBytes(data) }.isSuccess

    fun has(slot: Int) = slot in 0 until SLOTS && file(slot).length() > 0

    /** Belegte Plaetze als Bitmuster - passt in ein Byte der Anwesenheitsmeldung. */
    fun mask(): Int {
        var m = 0
        for (i in 0 until SLOTS) if (has(i)) m = m or (1 shl i)
        return m
    }

    /** Laeuft gerade eine Aufnahme? */
    fun isRecording() = recorder != null

    /**
     * Startet die Aufnahme. Sie laeuft, bis [stop] gerufen wird - laengstens
     * [MAX_MS]. So bestimmt der Finger auf der Kachel die Laenge.
     */
    fun start(slot: Int, onDone: (Boolean) -> Unit): Boolean {
        // Eine haengengebliebene Aufnahme darf nicht alle weiteren blockieren.
        if (recorder != null) abort()
        val target = file(slot)
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
                else @Suppress("DEPRECATION") MediaRecorder()
        val ok = runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Sparsam, damit die Aufnahme klein bleibt und schnell zu den
            // anderen Handys kommt - fuer Kinderstimmen reicht das locker.
            r.setAudioEncodingBitRate(32_000)
            r.setAudioSamplingRate(22_050)
            r.setAudioChannels(1)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
        }.isSuccess

        if (!ok) {
            runCatching { r.release() }
            onDone(false)
            return false
        }
        recorder = r
        startedAt = System.currentTimeMillis()
        activeSlot = slot
        pendingDone = onDone
        ui.postDelayed(autoStop, MAX_MS)
        return true
    }

    /** Eine Aufnahme wegwerfen, die nie sauber beendet wurde. */
    private fun abort() {
        val r = recorder ?: return
        recorder = null
        ui.removeCallbacks(autoStop)
        runCatching { r.stop() }
        runCatching { r.release() }
        activeSlot = -1
        pendingDone = null
    }

    /** Beendet die laufende Aufnahme - der Finger ging von der Kachel. */
    fun stop() {
        val r = recorder ?: return
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed < MIN_MS) {
            // Zu frueh losgelassen: kurz weiterlaufen lassen, sonst wirft stop().
            ui.postDelayed({ stop() }, MIN_MS - elapsed)
            return
        }
        recorder = null
        ui.removeCallbacks(autoStop)
        val good = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val slot = activeSlot
        val done = pendingDone
        activeSlot = -1
        pendingDone = null
        if (!good && slot >= 0) runCatching { file(slot).delete() }
        done?.invoke(slot >= 0 && has(slot))
    }

    /** Wie lange die laufende Aufnahme schon dauert, in Sekunden. */
    fun elapsedSeconds(): Int =
        if (recorder == null) 0 else ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    fun play(slot: Int, volume: Float = 1f) {
        if (!has(slot)) return
        runCatching {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
            )
        }
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(file(slot).absolutePath)
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                setVolume(volume, volume)
                prepare()
                start()
            }
        }
    }
}

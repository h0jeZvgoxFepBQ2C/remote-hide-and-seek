package at.flave.versteckspiel

/**
 * Eine Nachricht muss in die rund 25 nutzbaren Bytes eines BLE-Advertising-Pakets
 * passen, deshalb ist sie bewusst winzig: Absender, laufende Nummer, Befehl.
 */
data class Msg(val from: Int, val seq: Int, val op: Int, val arg: Int = 0) {

    /** Binaer fuer BLE: Signatur + 7 Nutzbytes. */
    fun toBytes() = byteArrayOf(
        MAGIC[0], MAGIC[1],
        (from ushr 24).toByte(), (from ushr 16).toByte(),
        (from ushr 8).toByte(), from.toByte(),
        seq.toByte(), op.toByte(), arg.toByte()
    )

    /** Klartext fuer UDP - damit bleibt der Verkehr mitlesbar. */
    fun toText() = "%08x|%d|%d|%d".format(from, seq, op, arg)

    val sound: Sound? get() = SOUNDS.getOrNull(arg)

    companion object {
        /**
         * "VS" - Erkennungszeichen am Anfang jedes BLE-Pakets. Die Hersteller-
         * Kennung 0xFFFF ist frei benutzbar, also funken auch fremde Geraete
         * darunter; ohne diese Signatur wuerden sie als Mitspieler gezaehlt.
         */
        val MAGIC = byteArrayOf(0x56, 0x53)

        fun fromBytes(b: ByteArray): Msg? {
            if (b.size < 9) return null
            if (b[0] != MAGIC[0] || b[1] != MAGIC[1]) return null
            val from = (b[2].toInt() and 0xFF shl 24) or (b[3].toInt() and 0xFF shl 16) or
                       (b[4].toInt() and 0xFF shl 8) or (b[5].toInt() and 0xFF)
            return Msg(from, b[6].toInt() and 0xFF, b[7].toInt() and 0xFF, b[8].toInt() and 0xFF)
        }

        fun fromText(s: String): Msg? {
            val p = s.split("|")
            if (p.size < 4) return null
            return runCatching {
                Msg(p[0].toLong(16).toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt())
            }.getOrNull()
        }
    }
}

object Op {
    /** Anwesenheit. Wiederholt sich staendig, aendert nie eine Rolle. */
    const val PING = 0
    /** Geraeusch abspielen und sich verstecken. */
    const val PLAY = 1
    /** Gefunden - jubeln, und der Sender uebernimmt die Steuerung. */
    const val FOUND = 2

    fun changesRole(op: Int) = op == PLAY || op == FOUND
}

/** Ein Weg, auf dem Nachrichten zu den anderen Handys kommen. */
interface Link {
    val name: String
    /** Laeuft der Kanal gerade wirklich? */
    val active: Boolean
    fun start()
    fun send(msg: Msg)
    fun stop()
}

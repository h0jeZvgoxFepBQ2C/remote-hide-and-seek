package at.flave.versteckspiel

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

private const val BG_CONTROL = 0xFF141A2E.toInt()
private const val BG_HIDING = 0xFF2E7D57.toInt()
private const val DIM = 0xFFA8B3CF.toInt()
private const val PURPLE = 0xFF7E57C2.toInt()

/**
 * Zwei Ansichten, die sich von selbst abloesen: Fernbedienung und Versteck.
 * Welche zu sehen ist, entscheidet [Peer] - die Activity zeichnet nur nach.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private var shownRole: Role? = null

    // Felder der gerade sichtbaren Ansicht
    private var bigEmoji: TextView? = null
    private var bigTitle: TextView? = null
    private var status: TextView? = null
    private var autoCard: LinearLayout? = null
    private var level: LevelView? = null
    private var revert: Runnable? = null
    /** Bis wann die Statuszeile das gerufene Tier stehen lassen soll. */
    private var statusHold = 0L
    /** Gewaehlter Reiter der Fernbedienung. */
    private var currentTab = 0

    /** Kleine Geraete (z.B. Handhelds) brauchen knappere Abstaende. */
    private val compact by lazy { resources.configuration.screenHeightDp < 720 }
    private var bluetoothAsked = false
    private var customPage: LinearLayout? = null
    /** Punktreihe je Aufnahmeplatz: ein Punkt pro anderem Handy. */
    private val slotDots = HashMap<Int, LinearLayout>()
    /** Laeuft gerade eine Aufnahme, und auf welcher Kachel? */
    private var holdRunnable: Runnable? = null
    private var recordTick: Runnable? = null
    private var recordingSlot = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!askForPermissions()) askForBatteryFreedom()
        startPlayerService()
        render(Peer.current?.role ?: Role.CONTROL, fresh = false)
    }

    override fun onResume() {
        super.onResume()
        // Nach der Abschaltautomatik ist der Dienst weg, die App aber noch
        // offen - beim Zurueckkommen also wieder anwerfen.
        if (!PlayerService.isRunning) startPlayerService()
        // Kommt man aus den Bluetooth-Einstellungen zurueck, soll der Kanal
        // sofort hochfahren und nicht erst beim naechsten Abgleich.
        Peer.current?.retryBluetooth()
        PlayerService.listener = { ev -> ui.post { handle(ev) } }
        Peer.current?.touch()          // Bedienung zaehlt als Lebenszeichen
        ui.postDelayed({ offerBluetooth() }, 1500)
        render(Peer.current?.role ?: Role.CONTROL, fresh = false)
        startTicking()
    }

    override fun onPause() {
        stopTicking()
        super.onPause()
    }

    override fun onDestroy() {
        PlayerService.listener = null
        stopTicking()
        super.onDestroy()
    }

    // ----------------------------------------------------------- Ereignisse

    private fun handle(ev: Event) = when (ev) {
        is Event.RoleChanged -> render(ev.role, fresh = true)
        is Event.Played -> flash(ev.soundId)
        is Event.RecordingsChanged -> customPage?.let { fillCustom(it) }
    }

    /** Kurz zeigen, welches Tier gerufen hat. */
    private fun flash(soundId: String) {
        if (soundId.startsWith("mic")) {
            revert?.let { ui.removeCallbacks(it) }
            if (shownRole == Role.HIDING) {
                bigEmoji?.text = "🎤"
                bigTitle?.text = "Meine Aufnahme!"
                revert = Runnable {
                    bigEmoji?.text = "🙈"; bigTitle?.text = "Ich bin versteckt!"
                }.also { ui.postDelayed(it, 2500) }
            }
            return
        }
        val a = SOUNDS.firstOrNull { it.id == soundId } ?: return
        revert?.let { ui.removeCallbacks(it) }
        if (shownRole == Role.HIDING) {
            bigEmoji?.text = a.emoji
            bigTitle?.text = a.label + "!"
            revert = Runnable {
                bigEmoji?.text = "🙈"; bigTitle?.text = "Ich bin versteckt!"
            }.also { ui.postDelayed(it, 2500) }
        } else {
            showCalled(a)
        }
    }

    /** Rueckmeldung auf der Fernbedienung: der Ruf ist rausgegangen. */
    private fun showCalled(a: Sound) {
        val st = status ?: return
        statusHold = System.currentTimeMillis() + 1800
        st.text = "🔊 ${a.label}"
        st.setTextColor(Color.WHITE)
    }

    private fun render(role: Role, fresh: Boolean) {
        if (shownRole == role && !fresh) return
        shownRole = role
        revert?.let { ui.removeCallbacks(it) }
        bigEmoji = null; bigTitle = null; status = null; autoCard = null; level = null
        statusHold = 0L
        if (role == Role.CONTROL) showControl(fresh) else showHiding(fresh)
        startTicking()
    }

    // -------------------------------------------------------------- Ansichten

    /** Fernbedienung - so startet jedes Handy. */
    private fun showControl(fresh: Boolean) {
        val root = column().apply {
            setBackgroundColor(BG_CONTROL)
            setPadding(dp(14), dp(24), dp(14), dp(12))
        }
        val title = label(if (fresh) "Du suchst jetzt!" else "Wer soll rufen?",
            25f, Color.WHITE, bold = true)
        val st = label("…", 14f, DIM).apply { setPadding(0, dp(4), 0, dp(6)) }
        status = st
        // Naeherungspegel: zeigt ohne Worte, wie nah das versteckte Handy ist
        val lvl = LevelView(this).apply { visibility = View.INVISIBLE }
        level = lvl
        root.addView(title); root.addView(st)
        root.addView(
            lvl,
            LinearLayout.LayoutParams(dp(if (compact) 160 else 200), dp(if (compact) 30 else 46))
                .apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(2), 0, dp(6)) }
        )

        // Reiter - nur zeigen, wenn es mehr als eine Kategorie gibt
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tabBar { fillGrid(page, it) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 44 else 54))
                .apply { setMargins(dp(5), 0, dp(5), dp(6)) })
        fillGrid(page, currentTab)
        root.addView(page, grow())

        root.addView(
            card("", "🙈 Ich verstecke mich", "", 0x33FFFFFF) { Peer.current?.hideMyself() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62))
                .apply { setMargins(dp(5), dp(6), dp(5), 0) })

        if (fresh) ui.postDelayed({ if (shownRole == Role.CONTROL) title.text = "Wer soll rufen?" }, 2500)
        setContentView(root)
    }

    /** Versteck - dieses Handy macht die Geraeusche. */
    private fun showHiding(fresh: Boolean) {
        val root = column().apply {
            setBackgroundColor(BG_HIDING)
            setPadding(dp(18), dp(20), dp(18), dp(14))
        }
        val emoji = label("🙈", 72f, Color.WHITE)
        val title = label(if (fresh) "Jetzt versteckst du dich!" else "Ich bin versteckt!",
            25f, Color.WHITE, bold = true)
        val hint = label("Spielt auch bei schwarzem oder gesperrtem Bildschirm.",
            13f, 0xFFD3EEE0.toInt()).apply { setPadding(0, dp(8), 0, dp(12)) }
        bigEmoji = emoji; bigTitle = title; status = hint

        root.addView(emoji); root.addView(title); root.addView(hint)

        root.addView(card("🎉", "Gefunden!", "Drück mich!", PURPLE) {
            Peer.current?.celebrate()
        }, grow())

        val auto = card("", "…", " ", 0x33000000) {}
        autoCard = auto
        auto.setOnClickListener {
            Peer.current?.let { it.auto = !it.auto }
            paintAuto()
        }
        paintAuto()
        root.addView(auto, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84))
            .apply { setMargins(0, dp(4), 0, dp(8)) })

        root.addView(card("", "Spiel beenden", "", 0x33FFFFFF) {
            stopService(Intent(this, PlayerService::class.java))
            finish()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        if (fresh) ui.postDelayed({
            if (shownRole == Role.HIDING && title.text.startsWith("Jetzt"))
                title.text = "Ich bin versteckt!"
        }, 2500)
        setContentView(root)
    }

    /** Reiterleiste; meldet die gewaehlte Kategorie zurueck. */
    private fun tabBar(onPick: (Int) -> Unit): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabs = ArrayList<LinearLayout>()
        val titles = CATEGORIES.map { "${it.emoji} ${it.title}" } + "🎤 Meine"
        titles.forEachIndexed { i, caption ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                addView(label(caption, 13f, Color.WHITE, bold = true))
            }
            tabs.add(tab)
            tab.setOnClickListener {
                currentTab = i
                tabs.forEachIndexed { k, t -> paintTab(t, k == i) }
                onPick(i)
            }
            bar.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        tabs.forEachIndexed { k, t -> paintTab(t, k == currentTab) }
        return bar
    }

    private fun paintTab(tab: LinearLayout, selected: Boolean) {
        tab.background = GradientDrawable().apply {
            setColor(if (selected) 0x33FFFFFF else 0x14FFFFFF)
            cornerRadius = dp(16).toFloat()
        }
        (tab.getChildAt(0) as TextView).alpha = if (selected) 1f else 0.55f
    }

    /** Kachelraster der gewaehlten Kategorie, zwei Spalten. */
    private fun fillGrid(page: LinearLayout, tabIndex: Int) {
        page.removeAllViews()
        if (tabIndex >= CATEGORIES.size) {
            customPage = page
            fillCustom(page)
            return
        }
        customPage = null
        CATEGORIES[tabIndex].sounds.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { s ->
                row.addView(card(s.emoji, s.label, "", s.color) {
                    Peer.current?.call(s)
                    showCalled(s)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    .apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            }
            if (pair.size == 1) row.addView(View(this),
                LinearLayout.LayoutParams(0, 1, 1f))      // Luecke fuellen
            page.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /**
     * Die selbst aufgenommenen Geraeusche. Kurz tippen ruft den Platz auf allen
     * anderen Handys auf - dort spielt jedes seine *eigene* Aufnahme. Lang
     * druecken nimmt auf diesem Handy neu auf.
     */
    private fun fillCustom(page: LinearLayout) {
        // Waehrend einer Aufnahme nicht neu zeichnen: sonst verschwindet die
        // Kachel unter dem Finger, das Loslassen kommt nie an und die Aufnahme
        // bleibt haengen.
        if (recordingSlot >= 0) return
        page.removeAllViews()
        slotDots.clear()
        val peer = Peer.current
        val others = peer?.othersSlots() ?: 0
        val hint = label(
            "Gedrückt halten zum Aufnehmen, höchstens 10 Sekunden.\nDie Aufnahme geht an alle Handys.",
            12f, 0x99FFFFFF.toInt()
        ).apply { setPadding(0, 0, 0, dp(6)) }
        page.addView(hint)

        (0 until Recordings.SLOTS).toList().chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { slot ->
                val mine = peer?.recordings?.has(slot) == true
                val anyone = mine || (others and (1 shl slot)) != 0
                row.addView(recordingCard(slot, mine, anyone),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        .apply { setMargins(dp(5), dp(4), dp(5), dp(4)) })
            }
            page.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /**
     * Kompaktere Kachel als bei den festen Geraeuschen - acht Stueck muessen auf
     * den Schirm passen. Statt eines Untertitels zeigt eine Punktreihe, welches
     * Handy die Aufnahme schon hat.
     */
    private fun recordingCard(slot: Int, mine: Boolean, anyone: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(if (anyone) 0xFF00897B.toInt() else 0x33FFFFFF)
                cornerRadius = dp(20).toFloat()
            }
            isClickable = true
        }
        card.addView(
            fitLabel("🎤", 30f, Color.WHITE),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)
        )
        card.addView(
            fitLabel("Aufnahme ${slot + 1}", 15f, Color.WHITE, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        slotDots[slot] = dots
        card.addView(
            dots,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.75f)
        )

        // Kurz tippen ruft auf, gedrueckt halten nimmt auf - und die Aufnahme
        // endet, sobald der Finger die Kachel verlaesst.
        card.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    holdRunnable = Runnable { beginRecording(slot, card) }
                        .also { ui.postDelayed(it, 350) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdRunnable?.let { ui.removeCallbacks(it) }
                    holdRunnable = null
                    if (recordingSlot == slot) {
                        endRecording()
                    } else if (ev.action == MotionEvent.ACTION_UP && anyone) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        Peer.current?.callSlot(slot)
                    }
                }
            }
            true
        }
        return card
    }

    /** Sync-Punkte auffrischen, ohne die Kacheln neu zu bauen. */
    private fun paintDots() {
        val peer = Peer.current ?: return
        for ((slot, row) in slotDots) {
            val states = peer.syncStates(slot)
            if (row.childCount != states.size) {
                row.removeAllViews()
                repeat(states.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(dp(9), dp(9))
                        .apply { setMargins(dp(3), 0, dp(3), 0) })
                }
            }
            val mine = peer.recordings.has(slot)
            states.forEachIndexed { i, ok ->
                row.getChildAt(i)?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        when {
                            ok -> 0xFF66BB6A.toInt()        // hat die Aufnahme
                            mine -> 0xFFFFB300.toInt()      // wird noch uebertragen
                            else -> 0x44FFFFFF              // nichts zu holen
                        }
                    )
                }
            }
        }
    }

    /** Aufnahme starten - sie laeuft, solange der Finger auf der Kachel bleibt. */
    private fun beginRecording(slot: Int, card: LinearLayout) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 43)
            return
        }
        val peer = Peer.current ?: return
        val emojiView = card.getChildAt(0) as TextView
        val titleView = card.getChildAt(1) as TextView

        val started = peer.recordings.start(slot) { ok ->
            ui.post {
                peer.refreshPresence()
                if (ok) peer.distribute(slot)      // gleich an die anderen weitergeben
                customPage?.let { fillCustom(it) }
            }
        }
        if (!started) return

        recordingSlot = slot
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        emojiView.text = "🔴"
        card.background = GradientDrawable().apply {
            setColor(0xFFD32F2F.toInt()); cornerRadius = dp(20).toFloat()
        }
        // Mitlaufende Sekunden statt Countdown - die Laenge bestimmt der Finger.
        recordTick = object : Runnable {
            override fun run() {
                val s = peer.recordings.elapsedSeconds()
                titleView.text = "$s s"
                if (peer.recordings.isRecording()) ui.postDelayed(this, 250)
            }
        }.also { ui.post(it) }
    }

    /** Finger weg: Aufnahme beenden. */
    private fun endRecording() {
        recordingSlot = -1
        recordTick?.let { ui.removeCallbacks(it) }
        recordTick = null
        Peer.current?.recordings?.stop()
    }

    // ---------------------------------------------------------------- Ticker

    /** Haelt Anwesenheitsanzeige bzw. Countdown aktuell. */
    private fun startTicking() {
        stopTicking()
        tick = object : Runnable {
            override fun run() {
                if (shownRole == Role.CONTROL) { paintStatus(); paintHeat(); paintDots() }
                else paintAuto()
                ui.postDelayed(this, 1000)
            }
        }.also { ui.postDelayed(it, 1000) }
        if (shownRole == Role.CONTROL) { paintStatus(); paintHeat(); paintDots() } else paintAuto()
    }

    private fun stopTicking() {
        tick?.let { ui.removeCallbacks(it) }; tick = null
    }

    private fun paintStatus() {
        val st = status ?: return
        if (System.currentTimeMillis() < statusHold) return   // Tier kurz stehen lassen
        val peer = Peer.current
        val n = peer?.peerCount() ?: 0
        val via = peer?.channels() ?: "…"
        if (n > 0) {
            st.text = (if (n == 1) "🟢 1 anderes Handy" else "🟢 $n andere Handys") + " · $via"
            st.setTextColor(0xFF8BE3B0.toInt())
        } else {
            st.text = "🟡 Suche andere Handys · $via"
            st.setTextColor(DIM)
        }
    }

    /** Naeherungspegel aus der Bluetooth-Signalstaerke speisen. */
    private fun paintHeat() {
        val view = level ?: return
        val near = Peer.current?.nearness()
        // INVISIBLE statt GONE: der Platz bleibt reserviert, sonst ruckt beim
        // Auftauchen das halbe Raster nach unten.
        view.visibility = if (near == null) View.INVISIBLE else View.VISIBLE
        view.level = near
    }

    private fun paintAuto() {
        val card = autoCard ?: return
        val peer = Peer.current
        val on = peer?.auto == true
        val views = card.tag as Array<*>
        (views[0] as TextView).text =
            if (on) "🔁 Alleine spielen: AN" else "🔁 Alleine spielen: AUS"
        (views[1] as TextView).text = if (on) {
            val left = peer!!.secondsLeft()
            "Nächstes Geräusch in $left Sekunde" + if (left == 1) "" else "n"
        } else {
            "Das Handy ruft von selbst – ohne zweites Handy"
        }
        card.background = GradientDrawable().apply {
            setColor(if (on) 0xFF10432D.toInt() else 0x33000000)
            cornerRadius = dp(22).toFloat()
        }
    }

    // --------------------------------------------------------------- Helfer

    /**
     * Android und die Hersteller-Aufsaetze halten eine App, die dauernd funkt und
     * einen Wake-Lock haelt, fuer einen Akkufresser und legen sie schlafen. Genau
     * das darf hier nicht passieren, also einmalig um Ausnahme bitten.
     */
    private fun askForBatteryFreedom() {
        val prefs = getSharedPreferences("versteckspiel", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    /** @return true, wenn ein Berechtigungsdialog aufgegangen ist. */
    private fun askForPermissions(): Boolean {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        wanted.addAll(BleLink.requiredPermissions())
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return false
        requestPermissions(missing.toTypedArray(), 42)
        return true
    }

    /**
     * Ist Bluetooth aus, laeuft das Spiel nur ueber WLAN - ohne Hinweis raetselt
     * man daran herum. Einschalten darf die App nicht selbst, aber fragen schon.
     */
    private fun offerBluetooth() {
        if (bluetoothAsked || Peer.current?.bluetoothOff() != true) return
        bluetoothAsked = true
        runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    }

    /** Bluetooth darf erst nach erteilter Berechtigung starten. */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Peer.current?.retryBluetooth()
        ui.postDelayed({ offerBluetooth() }, 800)
        if (requestCode == 43) customPage?.let { fillCustom(it) } else askForBatteryFreedom()
    }

    private fun startPlayerService() {
        val intent = Intent(this, PlayerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun grow() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        .apply { setMargins(dp(5), dp(6), dp(5), dp(6)) }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /**
     * Beschriftung, die sich an die verfuegbare Hoehe anpasst. Ohne das
     * verschwinden auf kleineren Bildschirmen die Tiernamen unter dem Rand.
     */
    private fun fitLabel(text: String, maxSp: Float, color: Int, bold: Boolean = false) =
        label(text, maxSp, color, bold).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(
                    9, maxSp.toInt().coerceAtLeast(10), 1, TypedValue.COMPLEX_UNIT_SP
                )
            }
        }

    private fun card(emoji: String, title: String, sub: String, color: Int, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(22).toFloat()
            }
            isClickable = true
            // Gewichtete Zeilen: die Kachel teilt ihre Hoehe auf, statt den
            // Inhalt bei knappem Platz abzuschneiden.
            if (emoji.isNotEmpty()) addView(
                fitLabel(emoji, 44f, Color.WHITE),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.2f)
            )
            val titleView = fitLabel(title, 20f, Color.WHITE, bold = true)
            val subView = fitLabel(sub, 13f, 0xCCFFFFFF.toInt()).apply {
                if (sub.isEmpty()) visibility = View.GONE
            }
            tag = arrayOf(titleView, subView)      // damit Beschriftungen aenderbar bleiben
            addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(subView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                }.start()
                onClick()
            }
        }
}

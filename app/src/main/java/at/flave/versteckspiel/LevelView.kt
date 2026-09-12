package at.flave.versteckspiel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Naeherungsanzeige ohne ein einziges Wort: fuenf Balken, die sich fuellen wie
 * ein Empfangspegel, von rot (weit weg) bis gruen (ganz nah). Der oberste aktive
 * Balken pulsiert, damit man sieht, dass die Anzeige lebt.
 *
 * Drei Jahre alte Kinder koennen nicht lesen - mehr Balken und roter heisst
 * naeher, das versteht man sofort.
 */
class LevelView(ctx: Context) : View(ctx) {

    companion object {
        private const val STEPS = 5
        /**
         * Ampel-Logik: rot heisst weit weg, gruen heisst gefunden. Das lesen
         * auch Dreijaehrige richtig.
         */
        private val COLORS = intArrayOf(
            0xFFE53935.toInt(),   // rot   - weit weg
            0xFFFB8C00.toInt(),   // orange
            0xFFFDD835.toInt(),   // gelb
            0xFF9CCC65.toInt(),   // hellgruen
            0xFF43A047.toInt(),   // gruen - ganz nah
        )
        private const val OFF = 0x2AFFFFFF
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val box = RectF()
    private var phase = 0f

    /** 0 = ganz weit weg, 1 = direkt daneben. null = kein Kontakt, Anzeige aus. */
    var level: Float? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val l = level ?: return
        // Mindestens ein Balken, sonst sieht es aus als waere die Anzeige kaputt.
        val active = (1 + (l * (STEPS - 1)).toInt()).coerceIn(1, STEPS)
        val hot = COLORS[active - 1]

        val gap = 6f * density
        val w = (width - gap * (STEPS - 1)) / STEPS
        val pulse = 0.75f + 0.25f * kotlin.math.sin(phase * 6.283f)

        for (i in 0 until STEPS) {
            // aufsteigende Hoehe wie bei Empfangsbalken
            val frac = 0.34f + 0.66f * (i / (STEPS - 1f))
            val h = height * frac
            val x = i * (w + gap)
            box.set(x, height - h, x + w, height.toFloat())

            if (i < active) {
                paint.color = hot
                paint.alpha = if (i == active - 1) (255 * pulse).toInt() else 255
            } else {
                paint.color = OFF
                paint.alpha = 0x2A
            }
            val r = 5f * density
            canvas.drawRoundRect(box, r, r, paint)
        }

        phase = (phase + 0.02f) % 1f
        postInvalidateOnAnimation()
    }
}

package pl.drogowskaz.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Okrągły wskaźnik prędkości z GPS. */
class SpeedView(ctx: Context) : View(ctx) {
    var speed = 0
    var over = false
    var palette: Palette = Palette.DAY
    var gpsLost = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) / 2f - 2f
        p.style = Paint.Style.FILL
        p.color = palette.speedBg
        c.drawCircle(w / 2, h / 2, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.07f
        p.color = if (over) 0xFFE53935.toInt() else if (gpsLost) 0xFFFFA000.toInt() else palette.panelSub
        c.drawCircle(w / 2, h / 2, r, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = if (over) 0xFFE53935.toInt() else palette.speedText
        p.textSize = r * 0.95f
        c.drawText(speed.toString(), w / 2, h / 2 + r * 0.22f, p)
        p.typeface = Typeface.DEFAULT
        p.textSize = r * 0.28f
        p.color = palette.panelSub
        c.drawText(if (gpsLost) "brak GPS" else "km/h", w / 2, h / 2 + r * 0.62f, p)
    }
}

/** Znak ograniczenia prędkości (czerwony pierścień). */
class LimitSignView(ctx: Context) : View(ctx) {
    var limit = 0
        set(v) { field = v; visibility = if (v > 0) VISIBLE else GONE; invalidate() }
    var palette: Palette = Palette.DAY
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) / 2f - 2f
        p.style = Paint.Style.FILL
        p.color = palette.signBg
        c.drawCircle(w / 2, h / 2, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.17f
        p.color = 0xFFD50000.toInt()
        c.drawCircle(w / 2, h / 2, r - p.strokeWidth / 2, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = palette.signText
        p.textSize = if (limit >= 100) r * 0.78f else r * 0.95f
        c.drawText(limit.toString(), w / 2, h / 2 + p.textSize * 0.35f, p)
    }
}

/** Strzałka manewru. */
class TurnArrowView(ctx: Context) : View(ctx) {
    var turn: Turn = Turn.STRAIGHT
        set(v) { field = v; invalidate() }
    var exit = 0
    var color = 0xFFFFFFFF.toInt()
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun angleOf(t: Turn): Double = when (t) {
        Turn.STRAIGHT, Turn.START, Turn.ARRIVE -> 0.0
        Turn.SLIGHT_RIGHT, Turn.KEEP_RIGHT -> 35.0
        Turn.RIGHT -> 90.0
        Turn.SHARP_RIGHT -> 140.0
        Turn.SLIGHT_LEFT, Turn.KEEP_LEFT -> -35.0
        Turn.LEFT -> -90.0
        Turn.SHARP_LEFT -> -140.0
        Turn.UTURN -> 180.0
        Turn.ROUNDABOUT -> 0.0
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w, h)
        p.color = color
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        if (turn == Turn.ARRIVE) {
            p.style = Paint.Style.FILL
            c.drawCircle(w / 2, h * 0.42f, s * 0.22f, p)
            c.drawRect(w / 2 - s * 0.04f, h * 0.55f, w / 2 + s * 0.04f, h * 0.85f, p)
            return
        }
        if (turn == Turn.ROUNDABOUT) {
            p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f
            c.drawCircle(w / 2, h * 0.45f, s * 0.26f, p)
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.textSize = s * 0.3f
            p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(exit.toString(), w / 2, h * 0.45f + s * 0.1f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f
            c.drawLine(w / 2, h * 0.95f, w / 2, h * 0.72f, p)
            return
        }
        val a = Math.toRadians(angleOf(turn))
        val cx = w / 2f
        val cy = h * 0.52f
        val armLen = s * 0.34f
        val ex = cx + (sin(a) * armLen).toFloat()
        val ey = cy - (cos(a) * armLen).toFloat()
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.1f
        c.drawLine(cx, h * 0.92f, cx, cy, p)
        c.drawLine(cx, cy, ex, ey, p)
        // grot
        val head = s * 0.2f
        val back = Math.toRadians(150.0)
        val path = Path()
        path.moveTo(ex, ey)
        path.lineTo(ex + (sin(a + back) * head).toFloat(), ey - (cos(a + back) * head).toFloat())
        path.moveTo(ex, ey)
        path.lineTo(ex + (sin(a - back) * head).toFloat(), ey - (cos(a - back) * head).toFloat())
        c.drawPath(path, p)
    }
}

object Icons {
    /** Strzałka pozycji pojazdu (ikona na mapie). */
    fun carArrow(size: Int, fill: Int, outline: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = size.toFloat()
        val path = Path().apply {
            moveTo(s * 0.5f, s * 0.06f)
            lineTo(s * 0.88f, s * 0.9f)
            lineTo(s * 0.5f, s * 0.68f)
            lineTo(s * 0.12f, s * 0.9f)
            close()
        }
        p.style = Paint.Style.FILL; p.color = fill
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.06f; p.color = outline
        p.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, p)
        return bmp
    }

    @Suppress("unused")
    private val unused = RectF()
}

package pl.drogowskaz.nav.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import pl.drogowskaz.nav.Turn
import pl.drogowskaz.nav.TurnArrowView
import android.content.Context
import android.view.View

/** Proste ikony rysowane w kodzie (maski alfa - kolor nadaje system samochodu). */
object CarIcons {
    private const val S = 96

    private fun make(draw: (Canvas, Paint) -> Unit): CarIcon {
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFFFFF.toInt()
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        draw(c, p)
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).setTint(CarColor.DEFAULT).build()
    }

    val search: CarIcon by lazy {
        make { c, p ->
            p.style = Paint.Style.STROKE; p.strokeWidth = 9f
            c.drawCircle(42f, 42f, 22f, p)
            c.drawLine(58f, 58f, 80f, 80f, p)
        }
    }

    val home: CarIcon by lazy {
        make { c, p ->
            p.style = Paint.Style.FILL
            val path = Path().apply {
                moveTo(48f, 14f); lineTo(86f, 48f); lineTo(74f, 48f); lineTo(74f, 82f)
                lineTo(22f, 82f); lineTo(22f, 48f); lineTo(10f, 48f); close()
            }
            c.drawPath(path, p)
        }
    }

    val work: CarIcon by lazy {
        make { c, p ->
            p.style = Paint.Style.FILL
            c.drawRoundRect(12f, 30f, 84f, 80f, 8f, 8f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 8f
            c.drawRoundRect(34f, 14f, 62f, 32f, 4f, 4f, p)
        }
    }

    val star: CarIcon by lazy {
        make { c, p ->
            p.style = Paint.Style.FILL
            val path = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 40.0 else 17.0
                val a = Math.toRadians(-90.0 + i * 36.0)
                val x = (48 + r * Math.cos(a)).toFloat(); val y = (50 + r * Math.sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close(); c.drawPath(path, p)
        }
    }

    val warn: CarIcon by lazy {
        make { c, p ->
            p.style = Paint.Style.FILL
            val path = Path().apply { moveTo(48f, 10f); lineTo(88f, 80f); lineTo(8f, 80f); close() }
            c.drawPath(path, p)
            p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            c.drawRect(44f, 34f, 52f, 58f, p)
            c.drawCircle(48f, 68f, 5f, p)
        }
    }

    /** Ikona manewru (biała strzałka) dla paska instrukcji w samochodzie. */
    fun turn(ctx: Context, turn: Turn, exit: Int): CarIcon {
        val v = TurnArrowView(ctx)
        v.turn = turn; v.exit = exit; v.color = 0xFFFFFFFF.toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(S, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(S, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, S, S)
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).setTint(CarColor.DEFAULT).build()
    }
}

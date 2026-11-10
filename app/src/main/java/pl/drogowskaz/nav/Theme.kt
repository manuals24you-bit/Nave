package pl.drogowskaz.nav

import android.content.Context
import android.content.res.Configuration
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class ThemeMode(val label: String) { AUTO("Auto (zachód/wschód słońca)"), DAY("Dzień"), NIGHT("Noc"), SYSTEM("Jak w systemie") }

/** Paleta interfejsu dla trybu dziennego i nocnego. */
class Palette(
    val night: Boolean,
    val panel: Int,
    val panelText: Int,
    val panelSub: Int,
    val accent: Int,
    val route: Int,
    val routeCasing: Int,
    val signBg: Int,
    val signText: Int,
    val speedBg: Int,
    val speedText: Int,
    val button: Int,
    val buttonIcon: Int,
) {
    companion object {
        val DAY = Palette(
            night = false,
            panel = 0xFFFFFFFF.toInt(), panelText = 0xFF1B1F23.toInt(), panelSub = 0xFF5F6B76.toInt(),
            accent = 0xFF1A73E8.toInt(), route = 0xFF2F7DF6.toInt(), routeCasing = 0xFF1B4FA8.toInt(),
            signBg = 0xFFFFFFFF.toInt(), signText = 0xFF111111.toInt(),
            speedBg = 0xFFFFFFFF.toInt(), speedText = 0xFF111111.toInt(),
            button = 0xFFFFFFFF.toInt(), buttonIcon = 0xFF333B44.toInt(),
        )
        val NIGHT = Palette(
            night = true,
            panel = 0xFF1E232A.toInt(), panelText = 0xFFEAEEF2.toInt(), panelSub = 0xFF9AA5B1.toInt(),
            accent = 0xFF6AA9FF.toInt(), route = 0xFF4C8DFF.toInt(), routeCasing = 0xFF0F2C66.toInt(),
            signBg = 0xFFE9E9E9.toInt(), signText = 0xFF111111.toInt(),
            speedBg = 0xFF1E232A.toInt(), speedText = 0xFFF2F4F6.toInt(),
            button = 0xFF2A313A.toInt(), buttonIcon = 0xFFDDE3EA.toInt(),
        )
    }
}

object ThemeManager {
    private const val PREFS = "theme"

    fun mode(ctx: Context): ThemeMode =
        runCatching { ThemeMode.valueOf(ctx.getSharedPreferences(PREFS, 0).getString("mode", "AUTO")!!) }
            .getOrDefault(ThemeMode.AUTO)

    fun setMode(ctx: Context, m: ThemeMode) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString("mode", m.name).apply()
    }

    /** Czy teraz ma być tryb nocny. lat/lon = ostatnia znana pozycja (może być null). */
    fun isNight(ctx: Context, lat: Double?, lon: Double?, nowMs: Long = System.currentTimeMillis()): Boolean =
        when (mode(ctx)) {
            ThemeMode.DAY -> false
            ThemeMode.NIGHT -> true
            ThemeMode.SYSTEM ->
                (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            ThemeMode.AUTO -> if (lat == null || lon == null) fallbackNight(nowMs) else sunIsDown(lat, lon, nowMs)
        }

    private fun fallbackNight(nowMs: Long): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = nowMs }
        val h = c.get(Calendar.HOUR_OF_DAY)
        return h >= 20 || h < 6
    }

    /** Słońce poniżej horyzontu (z niewielkim zapasem na zmierzch), wg uproszczonych wzorów NOAA. */
    fun sunIsDown(lat: Double, lon: Double, nowMs: Long): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val hourUtc = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val gamma = 2.0 * Math.PI / 365.0 * (doy - 1 + (hourUtc - 12) / 24.0)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val trueSolarMin = (hourUtc * 60.0 + eqTime + 4.0 * lon).mod(1440.0)
        val hourAngle = Math.toRadians(trueSolarMin / 4.0 - 180.0)
        val latR = Math.toRadians(lat)
        val cosZ = sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(hourAngle)
        val elevationDeg = 90.0 - Math.toDegrees(acos(cosZ.coerceIn(-1.0, 1.0)))
        // -2° ≈ zmierzch: ciemniejszy motyw włączamy chwilę po zachodzie, a wyłączamy chwilę po wschodzie
        return elevationDeg < -2.0
    }

    @Suppress("unused")
    private fun unusedTan(x: Double) = tan(x)
}

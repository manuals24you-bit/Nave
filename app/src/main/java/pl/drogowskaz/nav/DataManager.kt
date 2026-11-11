package pl.drogowskaz.nav

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Opis paczki z danymi (region) z pliku index.json. */
class Pack(
    val id: String,
    val name: String,
    val kind: String,                 // "region" (mapa + trasy) albo "routing" (tylko trasy dla całego kraju)
    val files: Map<String, Long>,     // nazwa pliku -> rozmiar [B]
    val date: String,
) {
    val totalBytes: Long get() = files.values.sum()
}

/** Pobieranie i przechowywanie map offline. */
class DataManager(private val ctx: Context) {

    /** Adres danych: ustawienie w aplikacji (menu) albo wartość zapisana w buildzie. */
    var baseUrl: String
        get() = (ctx.getSharedPreferences("settings", 0).getString("dataUrl2", null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DATA_URL).trim().trimEnd('/')
        set(v) { ctx.getSharedPreferences("settings", 0).edit().putString("dataUrl2", v.trim()).apply() }

    private val root: File = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).let { File(it, "packs") }

    @Volatile var cancelled = false

    fun packDir(id: String) = File(root, id).apply { mkdirs() }

    /** Pobiera listę dostępnych paczek (wymaga internetu). */
    fun fetchIndex(): List<Pack> {
        require(baseUrl.isNotEmpty()) { "Nie ustawiono adresu danych (DATA_URL)." }
        val c = URL("$baseUrl/index.json").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.instanceFollowRedirects = true
        val txt = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val arr = JSONObject(txt).getJSONArray("packs")
        val out = ArrayList<Pack>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val f = o.getJSONObject("files")
            val m = LinkedHashMap<String, Long>()
            for (k in f.keys()) m[k] = f.getLong(k)
            out.add(Pack(o.getString("id"), o.getString("name"), o.optString("kind", "region"), m, o.optString("date", "")))
        }
        return out
    }

    fun isInstalled(p: Pack): Boolean {
        val d = File(root, p.id)
        return p.files.keys.all { File(d, it).let { f -> f.exists() && f.length() == p.files[it] } }
    }

    /** Zainstalowane paczki (z lokalnego manifestu, działa offline). */
    fun installed(): List<InstalledPack> {
        val out = ArrayList<InstalledPack>()
        root.listFiles()?.forEach { d ->
            val meta = File(d, "pack.json")
            if (meta.exists()) {
                runCatching {
                    val o = JSONObject(meta.readText())
                    out.add(InstalledPack(o.getString("id"), o.getString("name"), o.getString("kind"), d))
                }
            }
        }
        return out.sortedBy { it.name }
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    /**
     * Pobiera wszystkie pliki paczki. Wznawia przerwane pobieranie (nagłówek Range).
     * @param progress (pobrane bajty, wszystkie bajty)
     */
    fun download(p: Pack, progress: (Long, Long) -> Unit) {
        cancelled = false
        val dir = packDir(p.id)
        val total = p.totalBytes
        var done = 0L
        for ((name, size) in p.files) {
            val target = File(dir, name)
            if (target.exists() && target.length() == size) { done += size; progress(done, total); continue }
            val part = File(dir, "$name.part")
            var have = if (part.exists()) part.length() else 0L
            if (have > size) { part.delete(); have = 0 }
            val url = "$baseUrl/${p.id}-$name"
            var attempts = 0
            while (have < size) {
                if (cancelled) throw InterruptedException("Anulowano")
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000; c.readTimeout = 30000
                    c.instanceFollowRedirects = true
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    val code = c.responseCode
                    if (code == 200 && have > 0) { have = 0; part.delete() } // serwer ignoruje Range
                    if (code != 200 && code != 206) throw java.io.IOException("HTTP $code dla $name")
                    FileOutputStream(part, have > 0).use { fos ->
                        c.inputStream.use { ins ->
                            val buf = ByteArray(128 * 1024)
                            var lastReport = 0L
                            while (true) {
                                if (cancelled) throw InterruptedException("Anulowano")
                                val n = ins.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                have += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 300) { progress(done + have, total); lastReport = now }
                            }
                        }
                    }
                    attempts = 0
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (++attempts > 6) throw e
                    have = if (part.exists()) part.length() else 0L
                    Thread.sleep(1500L * attempts)
                }
            }
            if (part.length() != size) { part.delete(); throw java.io.IOException("Uszkodzony plik $name - spróbuj ponownie") }
            if (!part.renameTo(target)) throw java.io.IOException("Nie można zapisać $name")
            done += size
            progress(done, total)
        }
        File(dir, "pack.json").writeText(
            JSONObject().put("id", p.id).put("name", p.name).put("kind", p.kind).put("date", p.date).toString()
        )
    }
}

class InstalledPack(val id: String, val name: String, val kind: String, val dir: File) {
    val map: File get() = File(dir, "map.pmtiles")
    val roads: File get() = File(dir, "roads.bin")
    val search: File get() = File(dir, "search.tsv")
    val warn: File get() = File(dir, "warn.tsv")
    val hasMap get() = map.exists()
    val hasRoads get() = roads.exists()
}

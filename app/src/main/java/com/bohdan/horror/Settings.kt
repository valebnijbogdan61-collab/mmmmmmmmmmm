package com.bohdan.horror

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object Settings {
    @Volatile var sens = 0.16f
    @Volatile var volume = 1f
    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("idb", Context.MODE_PRIVATE)
        sens = p.getFloat("sens", 0.16f); volume = p.getFloat("vol", 1f)
        Audio.setMaster(volume)
    }
    fun save(ctx: Context) {
        ctx.getSharedPreferences("idb", Context.MODE_PRIVATE).edit().putFloat("sens", sens).putFloat("vol", volume).apply()
    }
}

// Числа гри, які може змінити мод (mod.json)
object Tweak {
    @Volatile var watchSpeed = 0.5f
    @Volatile var rushSpeed = 1.7f
    @Volatile var chaseSpeed = 1.05f
    @Volatile var keyTime = 60f
    @Volatile var walkSpeed = 2.6f
    @Volatile var limpSpeed = 1.5f
    @Volatile var melons = 5
    fun reset() { watchSpeed = 0.5f; rushSpeed = 1.7f; chaseSpeed = 1.05f; keyTime = 60f; walkSpeed = 2.6f; limpSpeed = 1.5f; melons = 5 }
    private fun f(js: JSONObject, k: String, cur: Float, lo: Float, hi: Float): Float =
        js.optDouble(k, cur.toDouble()).toFloat().coerceIn(lo, hi)
    fun apply(js: JSONObject) {
        watchSpeed = f(js, "watchSpeed", watchSpeed, 0f, 6f)
        rushSpeed = f(js, "rushSpeed", rushSpeed, 0f, 6f)
        chaseSpeed = f(js, "chaseSpeed", chaseSpeed, 0f, 6f)
        keyTime = f(js, "keyTime", keyTime, 5f, 600f)
        walkSpeed = f(js, "walkSpeed", walkSpeed, 0.5f, 8f)
        limpSpeed = f(js, "limpSpeed", limpSpeed, 0.5f, 8f)
        melons = js.optInt("melons", melons).coerceIn(1, 20)
    }
}

object Mod {
    @Volatile var name: String? = null
    private val allowed = setOf("monster.glb", "melon.glb", "world.glb", "policecar.glb", "officer.glb", "menu_bg.jpg", "mod.json")
    private fun dir(ctx: Context) = File(ctx.filesDir, "mod")

    fun open(ctx: Context, asset: String): InputStream {
        val f = File(dir(ctx), asset)
        return if (name != null && f.exists()) FileInputStream(f) else ctx.assets.open(asset)
    }

    fun load(ctx: Context) {
        Tweak.reset(); name = null
        val d = dir(ctx)
        if (!d.exists()) return
        var nm = "Мод"
        val j = File(d, "mod.json")
        if (j.exists()) {
            try { val js = JSONObject(j.readText()); nm = js.optString("name", "Мод"); Tweak.apply(js) } catch (e: Exception) { }
        }
        name = nm
    }

    fun clear(ctx: Context) { dir(ctx).deleteRecursively(); name = null; Tweak.reset() }

    fun import(ctx: Context, uri: Uri): String {
        try {
            val tmp = File(ctx.filesDir, "mod_tmp")
            tmp.deleteRecursively(); tmp.mkdirs()
            var count = 0
            var total = 0L
            val ins = ctx.contentResolver.openInputStream(uri) ?: return "Не вдалося відкрити файл"
            ZipInputStream(ins).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val n = e.name.substringAfterLast('/')
                    if (!e.isDirectory && n in allowed) {
                        File(tmp, n).outputStream().use { o ->
                            val buf = ByteArray(65536)
                            while (true) {
                                val r = zin.read(buf)
                                if (r < 0) break
                                total += r
                                if (total > 120L * 1024 * 1024) throw RuntimeException("файл завеликий")
                                o.write(buf, 0, r)
                            }
                        }
                        count++
                    }
                    e = zin.nextEntry
                }
            }
            if (count == 0) { tmp.deleteRecursively(); return "У .zip немає файлів мода" }
            val d = dir(ctx)
            d.deleteRecursively(); tmp.renameTo(d)
            load(ctx)
            return "Мод імпортовано: " + (name ?: "без назви")
        } catch (e: Exception) {
            return "Помилка імпорту: " + (e.message ?: "невідома")
        }
    }
}

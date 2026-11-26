package com.bohdan.horror

import android.app.Activity
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private val holder = GameHolder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = java.io.File(filesDir, "crash.txt")
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { crashFile.writeText(android.util.Log.getStackTraceString(e)) } catch (ex: Exception) { }
            prevHandler?.uncaughtException(t, e)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Settings.load(this)
        Mod.load(this)
        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        glView.setRenderer(GameRenderer(this, holder))
        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(HudView(this, holder, { pickMod() }, { finish() }, { clearMod() }))
        setContentView(root)
        hideBars()
        if (crashFile.exists()) {
            val txt = try { crashFile.readText() } catch (ex: Exception) { "" }
            crashFile.delete()
            val tv = android.widget.TextView(this)
            tv.text = "Минулого разу гра вилетіла. Сфотографуй цей текст і скинь Claude (торкнись, щоб закрити):\n\n" + txt.take(2500)
            tv.setTextColor(0xFFFFFFFF.toInt()); tv.setBackgroundColor(0xEE000000.toInt()); tv.textSize = 11f
            tv.setPadding(24, 24, 24, 24)
            tv.setOnClickListener { root.removeView(tv) }
            root.addView(tv)
        }
    }

    private fun pickMod() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, 77)
    }

    private fun clearMod() {
        Mod.clear(this)
        holder.menuMsg = "Мод вимкнено"
        holder.modVersion++
        holder.reloadModels = true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 77 && resultCode == RESULT_OK && uri != null) {
            Thread {
                val msg = Mod.import(this, uri)
                holder.menuMsg = msg
                holder.modVersion++
                holder.reloadModels = true
            }.start()
        }
    }

    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!holder.inMenu) { holder.inMenu = true; Audio.stopAll() } else finish()
    }

    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onPause() {
        super.onPause()
        if (holder.started) holder.inMenu = true
        glView.onPause()
        Audio.stopAll()
    }
}

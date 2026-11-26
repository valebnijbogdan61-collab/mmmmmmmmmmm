package com.bohdan.horror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Інтерфейс не читає стан гри напряму: лише незмінний знімок holder.hud. Кнопки завжди на екрані.
class HudView(
    ctx: Context,
    private val holder: GameHolder,
    private val onImport: () -> Unit,
    private val onExit: () -> Unit,
    private val onClearMod: () -> Unit
) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val rect = RectF()

    private val ROLE_JOY = 1; private val ROLE_LOOK = 2; private val ROLE_E = 3
    private val ROLE_PAUSE = 8; private val ROLE_F = 4; private val ROLE_C = 5; private val ROLE_T = 6; private val ROLE_PHONE = 7
    private val roles = HashMap<Int, Int>()
    private val lastX = HashMap<Int, Float>()
    private val lastY = HashMap<Int, Float>()
    private var knobX = 0f
    private var knobY = 0f

    private fun joyCx() = width * 0.13f
    private fun joyCy() = height * 0.72f
    private fun joyR() = height * 0.2f
    private fun eX() = width * 0.90f; private fun eY() = height * 0.40f; private fun eR() = height * 0.11f
    private fun tX() = width * 0.86f; private fun tY() = height * 0.72f; private fun tR() = height * 0.15f
    private fun fX() = width * 0.60f; private fun fY() = height * 0.85f; private fun fR() = height * 0.09f
    private fun cX() = width * 0.72f; private fun cY() = height * 0.85f; private fun cR() = height * 0.09f

    private fun pauseX() = width * 0.05f
    private fun pauseY() = height * 0.11f
    private fun pauseR() = height * 0.055f

    private fun hit(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean {
        val dx = x - cx; val dy = y - cy
        return dx * dx + dy * dy <= r * r * 1.4f
    }

    private fun panel(): RectF = RectF(width * 0.26f, height * 0.04f, width * 0.74f, height * 0.96f)
    private fun keyRect(i: Int): RectF {
        val pn = panel()
        val col = i % 3; val row = i / 3
        val kw = (pn.width() - 4 * 14f) / 3f
        val top = pn.top + pn.height() * 0.36f
        val kh = (pn.bottom - top - 5 * 14f) / 4f
        val l = pn.left + 14f + col * (kw + 14f)
        val t = top + 14f + row * (kh + 14f)
        return RectF(l, t, l + kw, t + kh)
    }
    private val keyChars = charArrayOf('1', '2', '3', '4', '5', '6', '7', '8', '9', 'B', '0', 'C')
    private val keyLabels = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "←", "0", "Дзвінок")

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (holder.inMenu) return menuTouch(ev)
        val g = holder.g
        val s = holder.hud
        val act = ev.actionMasked
        val idx = ev.actionIndex
        if (s != null && (s.dead || s.endScreen)) {
            if (act == MotionEvent.ACTION_UP) g.wantRestart = true
            return true
        }
        val phoneOpen = s != null && s.phoneOpen
        when (act) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = ev.getPointerId(idx); val x = ev.getX(idx); val y = ev.getY(idx)
                lastX[id] = x; lastY[id] = y
                var role: Int
                if (hit(x, y, pauseX(), pauseY(), pauseR())) {
                    role = ROLE_PAUSE; g.moveX = 0f; g.moveY = 0f; g.throwHeld = false
                    holder.inMenu = true; menuScreen = 0; Audio.stopAll()
                } else if (phoneOpen && panel().contains(x, y)) {
                    for (i in 0 until 12) if (keyRect(i).contains(x, y)) g.phoneKey(keyChars[i])
                    role = ROLE_PHONE
                } else if (hit(x, y, tX(), tY(), tR())) { role = ROLE_T; g.throwHeld = true }
                else if (hit(x, y, eX(), eY(), eR())) { role = ROLE_E; g.pressE() }
                else if (hit(x, y, fX(), fY(), fR())) { role = ROLE_F; g.pressF() }
                else if (hit(x, y, cX(), cY(), cR())) { role = ROLE_C; g.pressC() }
                else if (x < width * 0.32f && y > height * 0.3f) { role = ROLE_JOY; updateJoy(g, x, y) }
                else role = ROLE_LOOK
                roles[id] = role
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i); val x = ev.getX(i); val y = ev.getY(i)
                    when (roles[id]) {
                        ROLE_JOY -> updateJoy(g, x, y)
                        ROLE_LOOK -> g.addLook(x - (lastX[id] ?: x), y - (lastY[id] ?: y))
                    }
                    lastX[id] = x; lastY[id] = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = ev.getPointerId(idx)
                when (roles[id]) {
                    ROLE_JOY -> { g.moveX = 0f; g.moveY = 0f; knobX = 0f; knobY = 0f }
                    ROLE_T -> g.throwHeld = false
                }
                roles.remove(id); lastX.remove(id); lastY.remove(id)
                if (act == MotionEvent.ACTION_CANCEL) { g.moveX = 0f; g.moveY = 0f; g.throwHeld = false; knobX = 0f; knobY = 0f }
            }
        }
        return true
    }

    private fun updateJoy(g: Game, x: Float, y: Float) {
        var dx = x - joyCx(); var dy = y - joyCy()
        val r = joyR()
        val len = sqrt(dx * dx + dy * dy)
        if (len > r) { dx = dx / len * r; dy = dy / len * r }
        g.moveX = dx / r          // вправо = крок вправо
        g.moveY = -dy / r         // ВГОРУ на екрані = ВПЕРЕД
        knobX = dx / r; knobY = dy / r
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE, bold: Boolean = true, align: Paint.Align = Paint.Align.CENTER) {
        tp.textSize = size; tp.color = color; tp.isFakeBoldText = bold; tp.textAlign = align
        c.drawText(s, x, y, tp)
        tp.textAlign = Paint.Align.CENTER
    }

    private fun button(c: Canvas, cx: Float, cy: Float, r: Float, label: String, on: Boolean, size: Float) {
        p.style = Paint.Style.FILL; p.color = if (on) Color.argb(160, 225, 225, 235) else Color.argb(55, 120, 120, 130)
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = if (on) Color.argb(230, 255, 255, 255) else Color.argb(90, 255, 255, 255)
        c.drawCircle(cx, cy, r, p)
        text(c, label, cx, cy + size * 0.35f, size, if (on) Color.WHITE else Color.argb(120, 255, 255, 255))
    }

    // ---------------- головне меню ----------------
    private var menuScreen = 0
    private var bg: Bitmap? = null
    private var bgVer = -1
    private var pressed = -1
    private var slider = -1

    private fun canContinue(): Boolean { val g = holder.g; return holder.started && !g.dead && !g.endScreen }

    private fun menuLabels(): List<String> {
        val l = ArrayList<String>()
        if (canContinue()) { l.add("Продовжити"); l.add("Нова гра") } else l.add("Грати")
        l.add("Налаштування"); l.add("Імпортувати мод")
        if (Mod.name != null) l.add("Вимкнути мод")
        l.add("Вийти")
        return l
    }

    private fun btnRect(i: Int): RectF {
        val bh = height * 0.085f; val gap = height * 0.02f
        val t = height * 0.30f + i * (bh + gap)
        return RectF(width * 0.07f, t, width * 0.41f, t + bh)
    }
    private fun sliderRect(i: Int): RectF {
        val t = height * (0.38f + i * 0.17f)
        return RectF(width * 0.07f, t, width * 0.50f, t + height * 0.05f)
    }
    private fun backRect(): RectF = RectF(width * 0.07f, height * 0.76f, width * 0.30f, height * 0.76f + height * 0.085f)

    private fun setSlider(i: Int, x: Float) {
        val r = sliderRect(i)
        val k = ((x - r.left) / r.width()).coerceIn(0f, 1f)
        if (i == 0) Settings.sens = 0.05f + k * 0.35f else { Settings.volume = k; Audio.setMaster(k) }
    }

    private fun menuAction(label: String) {
        when (label) {
            "Продовжити" -> { holder.g.resumeAudio(); holder.inMenu = false }
            "Грати", "Нова гра" -> { Audio.stopAll(); holder.hud = null; holder.g = Game(); holder.started = true; holder.inMenu = false }
            "Налаштування" -> menuScreen = 1
            "Імпортувати мод" -> { holder.menuMsg = "Вибери .zip з модом"; onImport() }
            "Вимкнути мод" -> onClearMod()
            "Вийти" -> onExit()
        }
    }

    private fun menuTouch(ev: MotionEvent): Boolean {
        val act = ev.actionMasked
        val x = ev.getX(0); val y = ev.getY(0)
        if (act == MotionEvent.ACTION_DOWN) {
            pressed = -1; slider = -1
            if (menuScreen == 0) {
                val labels = menuLabels()
                for (i in labels.indices) if (btnRect(i).contains(x, y)) pressed = i
            } else {
                for (i in 0 until 2) {
                    val r = RectF(sliderRect(i)); r.inset(0f, -height * 0.04f)
                    if (r.contains(x, y)) { slider = i; setSlider(i, x) }
                }
                if (backRect().contains(x, y)) pressed = 102
            }
        } else if (act == MotionEvent.ACTION_MOVE) {
            if (slider >= 0) setSlider(slider, x)
        } else if (act == MotionEvent.ACTION_UP) {
            if (slider >= 0) { Settings.save(context); slider = -1 }
            else if (menuScreen == 0 && pressed >= 0) {
                val labels = menuLabels()
                if (pressed < labels.size && btnRect(pressed).contains(x, y)) menuAction(labels[pressed])
            } else if (menuScreen == 1 && pressed == 102 && backRect().contains(x, y)) menuScreen = 0
            pressed = -1
        }
        return true
    }

    private fun menuButton(c: Canvas, r: RectF, label: String, down: Boolean) {
        p.style = Paint.Style.FILL; p.color = if (down) Color.argb(200, 90, 20, 20) else Color.argb(150, 10, 10, 14)
        c.drawRoundRect(r, 22f, 22f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.argb(170, 255, 255, 255)
        c.drawRoundRect(r, 22f, 22f, p)
        text(c, label, r.centerX(), r.centerY() + height * 0.02f, height * 0.05f)
    }

    private fun drawMenu(c: Canvas, w: Float, h: Float) {
        if (bg == null || bgVer != holder.modVersion) {
            bg = try { BitmapFactory.decodeStream(Mod.open(context, "menu_bg.jpg")) } catch (e: Exception) { null }
            bgVer = holder.modVersion
        }
        val b = bg
        if (b != null) {
            val sc = max(w / b.width, h / b.height)
            val dw = b.width * sc; val dh = b.height * sc
            c.drawBitmap(b, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
        } else c.drawColor(Color.BLACK)
        val x = w * 0.07f
        text(c, "INSTRUCTION:", x, h * 0.14f, h * 0.06f, Color.rgb(205, 205, 215), true, Paint.Align.LEFT)
        text(c, "DON'T BLINK", x, h * 0.26f, h * 0.11f, Color.WHITE, true, Paint.Align.LEFT)
        val modName = Mod.name
        if (modName != null) text(c, "Мод: " + modName, w * 0.97f, h * 0.06f, h * 0.04f, Color.rgb(160, 230, 160), true, Paint.Align.RIGHT)
        if (menuScreen == 0) {
            val labels = menuLabels()
            for (i in labels.indices) menuButton(c, btnRect(i), labels[i], pressed == i)
        } else {
            text(c, "Налаштування", x, h * 0.34f - h * 0.0f, h * 0.045f, Color.rgb(205, 205, 215), true, Paint.Align.LEFT)
            for (i in 0 until 2) {
                val r = sliderRect(i)
                val k = if (i == 0) ((Settings.sens - 0.05f) / 0.35f).coerceIn(0f, 1f) else Settings.volume
                text(c, if (i == 0) "Чутливість огляду" else "Гучність", r.left, r.top - h * 0.02f, h * 0.04f, Color.WHITE, true, Paint.Align.LEFT)
                p.style = Paint.Style.FILL; p.color = Color.argb(120, 255, 255, 255)
                c.drawRoundRect(RectF(r.left, r.centerY() - h * 0.008f, r.right, r.centerY() + h * 0.008f), 8f, 8f, p)
                p.color = Color.rgb(200, 40, 40)
                c.drawRoundRect(RectF(r.left, r.centerY() - h * 0.008f, r.left + r.width() * k, r.centerY() + h * 0.008f), 8f, 8f, p)
                p.color = Color.WHITE
                c.drawCircle(r.left + r.width() * k, r.centerY(), h * 0.03f, p)
            }
            menuButton(c, backRect(), "Назад", pressed == 102)
        }
        val msg = holder.menuMsg
        if (msg.isNotEmpty()) text(c, msg, w * 0.07f, h * 0.97f, h * 0.04f, Color.rgb(230, 230, 160), false, Paint.Align.LEFT)
        text(c, "IDB 1.0", w * 0.97f, h * 0.97f, h * 0.03f, Color.GRAY, false, Paint.Align.RIGHT)
    }

    override fun onDraw(c: Canvas) {
        val s = holder.hud
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 10f) { postInvalidateOnAnimation(); return }
        if (holder.inMenu) { drawMenu(c, w, h); postInvalidateOnAnimation(); return }

        // приціл
        p.style = Paint.Style.FILL; p.color = Color.argb(160, 255, 255, 255)
        c.drawCircle(w / 2, h / 2, 4f, p)

        // кружечок заряду кавуна
        if (s != null && s.holding && s.charging) {
            val col = if (s.charge < 0.55f) Color.rgb(60, 220, 80) else if (s.charge < 0.8f) Color.rgb(240, 220, 40) else Color.rgb(240, 50, 40)
            val r = h * 0.2f
            rect.set(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r)
            p.style = Paint.Style.STROKE; p.strokeWidth = h * 0.03f; p.color = Color.argb(70, 255, 255, 255)
            c.drawCircle(w / 2, h / 2, r, p)
            p.color = col
            c.drawArc(rect, -90f, min(1f, s.charge) * 360f, false, p)
        }

        // джойстик
        p.style = Paint.Style.FILL; p.color = Color.argb(60, 255, 255, 255)
        c.drawCircle(joyCx(), joyCy(), joyR(), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.argb(140, 255, 255, 255)
        c.drawCircle(joyCx(), joyCy(), joyR(), p)
        p.style = Paint.Style.FILL; p.color = Color.argb(170, 255, 255, 255)
        c.drawCircle(joyCx() + knobX * joyR(), joyCy() + knobY * joyR(), joyR() * 0.38f, p)

        // кнопки завжди на екрані; блідні, коли недоступні
        val prompt = s?.prompt
        button(c, eX(), eY(), eR(), "E", prompt != null, h * 0.07f)
        if (prompt != null) text(c, prompt, eX() - eR() * 1.3f, eY() + h * 0.018f, h * 0.05f, Color.WHITE, true, Paint.Align.RIGHT)
        button(c, tX(), tY(), tR(), "КИНУТИ", s != null && s.holding, h * 0.035f)
        button(c, fX(), fY(), fR(), "F", s != null && (s.showF || s.phoneOpen), h * 0.06f)
        button(c, cX(), cY(), cR(), "C", s != null && s.showC, h * 0.06f)
        p.style = Paint.Style.FILL; p.color = Color.argb(90, 120, 120, 130); c.drawCircle(pauseX(), pauseY(), pauseR(), p)
        p.color = Color.argb(220, 255, 255, 255)
        c.drawRect(pauseX() - pauseR() * 0.38f, pauseY() - pauseR() * 0.4f, pauseX() - pauseR() * 0.1f, pauseY() + pauseR() * 0.4f, p)
        c.drawRect(pauseX() + pauseR() * 0.1f, pauseY() - pauseR() * 0.4f, pauseX() + pauseR() * 0.38f, pauseY() + pauseR() * 0.4f, p)

        if (s != null) {
            if (s.keyTimer >= 0f && s.keyPhase) {
                val t = s.keyTimer.toInt() + 1
                text(c, "$t", w / 2, h * 0.12f, h * 0.12f, if (t <= 15) Color.rgb(255, 60, 50) else Color.WHITE)
            }
            if (s.msgT > 0f && s.msg.isNotEmpty()) text(c, s.msg, w / 2, h * 0.2f, h * 0.05f, Color.argb(min(255, (s.msgT * 255).toInt()), 255, 255, 255))

            if (s.phoneOpen) {
                val pn = panel()
                p.style = Paint.Style.FILL; p.color = Color.argb(235, 15, 15, 20); c.drawRoundRect(pn, 40f, 40f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.argb(255, 90, 90, 110); c.drawRoundRect(pn, 40f, 40f, p)
                text(c, if (s.phoneNum.isEmpty()) "Введи номер" else s.phoneNum, w / 2, pn.top + pn.height() * 0.1f, h * 0.09f, if (s.phoneNum.isEmpty()) Color.GRAY else Color.WHITE)
                var ly = pn.top + pn.height() * 0.17f
                for (ln in s.phoneLines) { text(c, ln, w / 2, ly, h * 0.04f, Color.rgb(150, 230, 160), false); ly += h * 0.055f }
                for (i in 0 until 12) {
                    val k = keyRect(i)
                    p.style = Paint.Style.FILL
                    p.color = if (i == 11) Color.rgb(30, 140, 60) else Color.rgb(45, 45, 58)
                    c.drawRoundRect(k, 24f, 24f, p)
                    text(c, keyLabels[i], k.centerX(), k.centerY() + h * 0.02f, if (i == 11) h * 0.04f else h * 0.07f)
                }
            }

            if (s.gun > 0.01f) {
                val gx = w * 0.60f; val gy = h * (1.12f - 0.50f * s.gun)
                p.style = Paint.Style.FILL; p.color = Color.rgb(28, 28, 32)
                c.drawRoundRect(RectF(gx, gy, gx + w * 0.20f, gy + h * 0.10f), 14f, 14f, p)
                c.drawRoundRect(RectF(gx + w * 0.02f, gy + h * 0.09f, gx + w * 0.07f, gy + h * 0.34f), 12f, 12f, p)
                p.color = Color.rgb(90, 90, 98)
                c.drawRect(gx + w * 0.01f, gy + h * 0.015f, gx + w * 0.19f, gy + h * 0.025f, p)
                if (s.flash > 0.4f) { p.color = Color.argb(230, 255, 220, 120); c.drawCircle(gx + w * 0.205f, gy + h * 0.05f, h * 0.09f, p) }
            }
            if (s.bigT > 0f && s.bigText.isNotEmpty()) text(c, s.bigText, w / 2, h * 0.45f, h * 0.15f, Color.argb(min(255, (s.bigT * 255).toInt()), 200, 0, 0))
            if (s.flash > 0.01f) { p.style = Paint.Style.FILL; p.color = Color.argb((min(1f, s.flash) * 255).toInt(), 255, 255, 255); c.drawRect(0f, 0f, w, h, p) }
            if (s.fade > 0.01f) { p.style = Paint.Style.FILL; p.color = Color.argb((min(1f, s.fade) * 255).toInt(), 0, 0, 0); c.drawRect(0f, 0f, w, h, p) }

            if (s.dead) {
                p.style = Paint.Style.FILL; p.color = Color.argb(150, 40, 0, 0); c.drawRect(0f, 0f, w, h, p)
                text(c, "ТИ ПРОГРАВ", w / 2, h * 0.42f, h * 0.10f, Color.rgb(230, 30, 30))
                text(c, s.deadText, w / 2, h * 0.49f, h * 0.045f)
                text(c, "Торкнись екрана, щоб почати знову", w / 2, h * 0.6f, h * 0.04f, Color.LTGRAY, false)
            }
            if (s.endScreen) {
                p.style = Paint.Style.FILL; p.color = Color.BLACK; c.drawRect(0f, 0f, w, h, p)
                if (!s.endBlack) text(c, "Wait, Chapter 2", w / 2, h * 0.48f, h * 0.11f, Color.WHITE)
                text(c, "Торкнись екрана, щоб зіграти знову", w / 2, h * 0.9f, h * 0.035f, Color.GRAY, false)
            }
        }
        postInvalidateOnAnimation()
    }
}

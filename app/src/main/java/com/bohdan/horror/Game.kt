package com.bohdan.horror

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class Phase { STREET, ROOM, KEY, OUTSIDE, ENDED }
enum class MM { WATCH, HIT, CHASE, EARS, KNEEL, FALLEN, RUSH, DRAGGED, GONE }

class Game {
    companion object {
        const val PI = 3.14159265f
        const val CAR_Z = -8f
        const val CAR_STOP_X = -4f
        const val MAX_CHARGE_SEC = 2.4f
        const val DEG = 57.29578f
        val CAB_TEXT = arrayOf("Порожньо...", "Старі тарілки. Ключів нема", "Крупа й сіль. Нічого", "Каструлі. Ключа тут нема")
    }

    // ---- player ----
    var px = 0f; var pz = -6f; var yaw = 0f; var pitch = 0f
    var camRoll = 0f; var camYOff = 0f
    var limp = false; var grabs = 0
    var phase = Phase.STREET

    // ---- input (written by UI thread) ----
    @Volatile var moveX = 0f
    @Volatile var moveY = 0f
    @Volatile var throwHeld = false
    @Volatile var wantRestart = false
    private var lookDx = 0f; private var lookDy = 0f
    @Volatile private var ePressed = false
    @Volatile private var fPressed = false
    @Volatile private var cPressed = false
    @Volatile private var pendingKey: Char = ' '
    fun addLook(dx: Float, dy: Float) { synchronized(this) { lookDx += dx; lookDy += dy } }
    fun pressE() { ePressed = true }
    fun pressF() { fPressed = true }
    fun pressC() { cPressed = true }
    fun phoneKey(c: Char) { pendingKey = c }

    // ---- monster ----
    var mm = MM.WATCH
    var mx = -0.3f; var mz = 4.6f; var mYaw = 175f
    var mPitch = 0f; var mRoll = 0f; var mYOff = 0f; var mSY = 1f
    var monsterVisible = false
    private var scared = false
    private var whistleWin = 0f; private var whistleCount = 0; private var whistleCd = 0f

    // ---- world state ----
    var doorAng = 0f; private var doorTarget = 0f; private var doorSpeed = 160f
    var fridgeAng = 0f; var fridgeOpen = false
    val cabAng = FloatArray(4); val cabOpen = BooleanArray(4)
    var melons = Tweak.melons; var holding = false; var charging = false; var charge = 0f
    var hasKey = false; var keyTimer = -1f
    var keyVisible = false

    // ---- projectile ----
    var projActive = false; var projT = 0f; var projDur = 0.45f; private var projHit = false; private var projWeak = false
    val projFrom = FloatArray(3); val projTo = FloatArray(3); val projPos = FloatArray(3)

    // ---- HUD state ----
    @Volatile var prompt: String? = null
    var msg = ""; var msgT = 0f
    var fade = 0f; var flash = 0f
    var bigText = ""; var bigT = 0f
    var showF = false; var showC = false
    var phoneOpen = false; var phoneNum = ""
    val phoneLines = java.util.concurrent.CopyOnWriteArrayList<String>()
    var dead = false; var deadText = ""
    var endScreen = false

    // ---- misc timers ----
    var time = 0f
    private var transT = 0f; private var teleported = false
    private var grabT = 0f; private var grabDx = 0f; private var grabDz = 0f; private var grabReset = false; private var grabShock1 = false; private var grabShock2 = false
    private var stepT = 0f
    private var screamT = 0f
    private var callMode = 102
    private var gunT = 0f
    var gunLevel = 0f
    var endBlack = false
    private var callState = 0; private var callT = 0f; private var etaT = 0f; private var callLine = 0
    private var broke = false
    var copStage = 0; private var copT = 0f
    var carX = -60f; var carVisible = false; var carLights = false; var trunkAng = 0f
    val ox = FloatArray(2); val oz = FloatArray(2); val oyaw = FloatArray(2)
    val ovis = BooleanArray(2); val owalk = BooleanArray(2)
    private var dragFromX = 0f; private var dragFromZ = 0f; private var dragDur = 2f
    private var endT = 0f

    private val script911 = arrayOf(
        0.6f to "— 911, what's your emergency?",
        2.8f to "— ...?",
        4.4f to "— Sir, I need your address. Stay on the line.",
        6.4f to "— Do you speak English? Hello?",
        8.2f to "(Ти нічого не розумієш)"
    )

    private val script102 = arrayOf(
        0.6f to "— Поліція, 102. Що трапилось?",
        3.0f to "— У мене в квартирі якийсь монстр!",
        5.4f to "— Назвіть адресу.",
        7.2f to "— П'ятиповерхівка, перший поверх!",
        9.4f to "— Виїжджаємо. Чекайте 30 секунд."
    )

    fun resumeAudio() {
        if (dead || phase == Phase.ENDED) return
        if (phase == Phase.ROOM || phase == Phase.KEY) Audio.loop("drone", 0.6f)
        if (phase == Phase.OUTSIDE) { Audio.loop("tense", 0.7f); if (carVisible && copStage in 1..6) Audio.loop("siren", 0.8f) }
    }

    fun hudSnapshot(): HudState = HudState(
        prompt, msg, msgT, fade, flash, bigText, bigT, keyTimer, phase == Phase.KEY,
        holding, charging, charge, showF, showC, phoneOpen, phoneNum, phoneLines.toList(),
        dead, deadText, endScreen, gunLevel, endBlack
    )

    private fun say(s: String) { msg = s; msgT = 3.2f }

    private fun collide(x0: Float, z0: Float): FloatArray {
        var x = x0; var z = z0
        val r = 0.3f
        for (iter in 0 until 3) {
            for (b in Colliders.boxes) pushOut(b[0], b[1], b[2], b[3], x, z, r).let { x = it[0]; z = it[1] }
            if (doorAng < 75f) pushOut(-0.45f, -0.3f, 0.45f, 0f, x, z, r).let { x = it[0]; z = it[1] }
            if (carVisible && copStage in 2..6) pushOut(carX - 2.3f, CAR_Z - 1.0f, carX + 2.3f, CAR_Z + 1.0f, x, z, r).let { x = it[0]; z = it[1] }
        }
        x = max(-37f, min(37f, x)); z = max(-16.5f, min(5.8f, z))
        return floatArrayOf(x, z)
    }

    private fun pushOut(x0: Float, z0: Float, x1: Float, z1: Float, x: Float, z: Float, r: Float): FloatArray {
        val cx = max(x0, min(x, x1)); val cz = max(z0, min(z, z1))
        val dx = x - cx; val dz = z - cz
        val d2 = dx * dx + dz * dz
        if (d2 >= r * r) return floatArrayOf(x, z)
        if (d2 > 1e-6f) {
            val d = sqrt(d2); val k = (r - d) / d
            return floatArrayOf(x + dx * k, z + dz * k)
        }
        val l = x - x0; val rr = x1 - x; val t = z - z0; val b = z1 - z
        val m = min(min(l, rr), min(t, b))
        return when (m) {
            l -> floatArrayOf(x0 - r, z)
            rr -> floatArrayOf(x1 + r, z)
            t -> floatArrayOf(x, z0 - r)
            else -> floatArrayOf(x, z1 + r)
        }
    }

    private fun dist(ax: Float, az: Float, bx: Float, bz: Float): Float { val dx = ax - bx; val dz = az - bz; return sqrt(dx * dx + dz * dz) }

    private fun isSeen(): Boolean {
        val dx = mx - px; val dy = 1.3f - 1.6f; val dz = mz - pz
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01f) return true
        val fx = cos(pitch / DEG) * sin(yaw / DEG); val fy = sin(pitch / DEG); val fz = cos(pitch / DEG) * cos(yaw / DEG)
        return (dx * fx + dy * fy + dz * fz) / len > 0.80f && len < 14f
    }

    private fun facing(tx: Float, tz: Float): Float {
        val dx = tx - px; val dz = tz - pz; val len = sqrt(dx * dx + dz * dz)
        if (len < 0.01f) return 1f
        return (dx * sin(yaw / DEG) + dz * cos(yaw / DEG)) / len
    }

    private fun die(text: String, black: Boolean = false) {
        if (dead) return
        dead = true; deadText = text
        Audio.stopAll(); Audio.play("scare"); flash = 1f
        if (black) { fade = 1f; Audio.play("scream", 1f); Audio.play("thud") }
        yaw = atan2(mx - px, mz - pz) * DEG; pitch = 8f
        holding = false; charging = false; charge = 0f; phoneOpen = false
    }

    // candidate interaction
    private var cType = 0; private var cIdx = 0

    private fun findCandidate() {
        cType = 0; cIdx = 0
        var found: String? = null
        var best = 99f
        fun consider(type: Int, idx: Int, d: Float, maxD: Float, label: String) {
            if (d < maxD && d < best) { best = d; cType = type; cIdx = idx; found = label }
        }
        if (phase == Phase.STREET) { consider(1, 0, dist(px, pz, 0f, -0.5f), 2.4f, "Зайти") }
        if (phase == Phase.ROOM || phase == Phase.KEY) {
            val fd = dist(px, pz, 0.8f, 5.0f)
            if (!fridgeOpen) consider(2, 0, fd, 1.8f, "Відкрити холодильник")
            else if (melons > 0 && !holding) consider(3, 0, fd, 1.8f, "Взяти кавун")
            for (i in 0 until 4) consider(4, i, dist(px, pz, -3.6f + i * 0.675f + 0.33f, 5.1f), 1.2f, if (cabOpen[i]) "Закрити шафку" else "Відкрити шафку")
            if (phase == Phase.KEY && !hasKey) consider(5, 0, dist(px, pz, 0.15f, 0.55f), 1.4f, "Взяти ключ")
            consider(6, 0, dist(px, pz, 0f, 0.3f), 1.6f, if (hasKey) "Відчинити двері" else "Двері")
        }
        prompt = found
    }

    private fun startGrab() {
        grabs++
        if (grabs > 3) { die("Монстр тебе дістав"); return }
        grabT = 4.2f; grabReset = false; grabShock1 = false; grabShock2 = false
        val dx = px - mx; val dz = pz - mz; val l = max(0.01f, sqrt(dx * dx + dz * dz))
        grabDx = dx / l; grabDz = dz / l
        flash = 1f; Audio.play("thud"); Audio.play("scare", 0.7f)
        charging = false; charge = 0f
    }

    private fun updateGrab(dt: Float) {
        grabT -= dt
        val t = 4.2f - grabT
        if (t < 0.5f) {
            val n = collide(px + grabDx * 8f * dt, pz + grabDz * 8f * dt); px = n[0]; pz = n[1]
            camRoll = 25f * (t / 0.5f); pitch = -20f
        } else if (t < 1.0f) {
            fade = (t - 0.5f) / 0.5f
        } else if (t < 2.8f) {
            fade = 1f
            flash = if (((t * 14f).toInt() % 2) == 0) 0.55f else 0f
            if (!grabShock1) { grabShock1 = true; Audio.play("shock") }
            if (t > 1.8f && !grabShock2) { grabShock2 = true; Audio.play("shock") }
        } else {
            if (!grabReset) {
                grabReset = true; limp = true; camRoll = 0f; pitch = 0f
                val far = if (dist(px, pz, -0.3f, 4.6f) > dist(px, pz, 3.0f, 4.8f)) floatArrayOf(-0.3f, 4.6f) else floatArrayOf(3.0f, 4.8f)
                mx = far[0]; mz = far[1]; mm = MM.WATCH
                say("Нога болить... Але ти живий")
            }
            fade = max(0f, 1f - (t - 2.8f) / 0.8f)
        }
        if (grabT <= 0f) { grabT = 0f; fade = 0f; flash = 0f; camRoll = 0f }
    }

    private fun startCar() { copStage = 1; copT = 0f; carVisible = true; carLights = true; carX = -46f; Audio.loop("siren", 0.8f) }

    private fun officerStart() {
        for (i in 0 until 2) { ox[i] = carX + 0.6f; oz[i] = if (i == 0) CAR_Z + 1.1f else CAR_Z - 1.1f; ovis[i] = true; owalk[i] = false }
    }

    private fun moveOfficer(i: Int, tx: Float, tz: Float, sp: Float, dt: Float): Boolean {
        val dx = tx - ox[i]; val dz = tz - oz[i]; val d = sqrt(dx * dx + dz * dz)
        if (d < 0.12f) { owalk[i] = false; return true }
        val st = min(d, sp * dt)
        ox[i] += dx / d * st; oz[i] += dz / d * st
        oyaw[i] = atan2(dx, dz) * DEG; owalk[i] = true
        return false
    }

    private fun updateCops(dt: Float) {
        copT += dt
        when (copStage) {
            1 -> {
                carX += 6.5f * dt
                if (carX >= CAR_STOP_X) { carX = CAR_STOP_X; copStage = 2; copT = 0f }
            }
            2 -> { if (mm == MM.FALLEN) { copStage = 3; copT = 0f; officerStart() } }
            3 -> {
                val yawRad = mYaw / DEG
                val fx = mx - sin(yawRad) * 1.1f; val fz = mz - cos(yawRad) * 1.1f
                val lx = cos(yawRad) * 0.55f; val lz = -sin(yawRad) * 0.55f
                val a = moveOfficer(0, fx + lx, fz + lz, 2.6f, dt)
                val b = moveOfficer(1, fx - lx, fz - lz, 2.6f, dt)
                if (a && b) {
                    copStage = 4; copT = 0f; mm = MM.DRAGGED; dragFromX = mx; dragFromZ = mz
                    dragDur = max(2f, dist(mx, mz, carX - 2.7f, CAR_Z) / 1.5f)
                }
            }
            4 -> {
                val k = min(1f, copT / dragDur)
                val tx = carX - 2.7f; val tz = CAR_Z
                mx = dragFromX + (tx - dragFromX) * k; mz = dragFromZ + (tz - dragFromZ) * k
                val dx = tx - dragFromX; val dz = tz - dragFromZ
                val dyaw = atan2(dx, dz) * DEG
                mYaw = dyaw + 180f
                val dl = max(0.01f, sqrt(dx * dx + dz * dz))
                val nx = dx / dl; val nz = dz / dl
                for (i in 0 until 2) {
                    val side = if (i == 0) 0.55f else -0.55f
                    ox[i] = mx + nx * 1.1f - nz * side; oz[i] = mz + nz * 1.1f + nx * side
                    oyaw[i] = dyaw; owalk[i] = k < 1f
                }
                if (k >= 1f) { copStage = 5; copT = 0f; for (i in 0 until 2) owalk[i] = false }
            }
            5 -> {
                trunkAng = min(60f, copT / 0.7f * 60f)
                if (copT > 1.0f && mm != MM.GONE) {
                    mm = MM.GONE; Audio.play("thud"); Audio.play("end"); flash = 0.8f
                    bigText = "Що придбав"; bigT = 6f
                }
                if (copT > 1.8f) { trunkAng = max(0f, 60f - (copT - 1.8f) / 0.5f * 60f) }
                if (copT > 2.5f) { copStage = 6; copT = 0f }
            }
            6 -> {
                val a = moveOfficer(0, carX + 0.6f, CAR_Z + 1.1f, 2.6f, dt)
                val b = moveOfficer(1, carX + 0.6f, CAR_Z - 1.1f, 2.6f, dt)
                if (a && b) { ovis[0] = false; ovis[1] = false; copStage = 7; copT = 0f; Audio.play("thud", 0.6f) }
            }
            7 -> {
                carX += min(10f, copT * 3f) * dt
                if (carX > 48f) { carVisible = false; carLights = false; Audio.stopLoop("siren"); copStage = 8; phase = Phase.ENDED; endT = 0f }
            }
        }
    }

    private fun poseMonster(dt: Float) {
        var tp = 0f; var roll = 0f; var yo = 0f; var sy = 1f
        when (mm) {
            MM.WATCH -> { if (!isSeen()) roll = if (((time * 12f).toInt() % 2) == 0) 1.5f else -1.5f }
            MM.HIT -> { tp = 28f; roll = sin(time * 20f) * 2.5f }
            MM.CHASE -> { tp = 10f; roll = sin(time * 5f) * 6f; yo = abs(sin(time * 5f)) * 0.05f }
            MM.RUSH -> { tp = 15f; roll = sin(time * 7f) * 8f; yo = abs(sin(time * 7f)) * 0.06f }
            MM.EARS -> { tp = 20f; roll = sin(time * 30f) * 7f }
            MM.KNEEL -> { tp = 15f; sy = 0.62f; roll = sin(time * 18f) * 3f }
            MM.FALLEN, MM.DRAGGED -> { tp = 90f; yo = 0.2f }
            MM.GONE -> {}
        }
        val k = min(1f, dt * 6f)
        mPitch += (tp - mPitch) * k; mRoll = roll; mYOff += (yo - mYOff) * k; mSY += (sy - mSY) * k
    }

    private fun spawnProjectile(hit: Boolean, weak: Boolean) {
        projActive = true; projT = 0f; projHit = hit; projWeak = weak
        projFrom[0] = px; projFrom[1] = 1.3f; projFrom[2] = pz
        if (hit) { projTo[0] = mx; projTo[1] = 1.9f; projTo[2] = mz; projDur = 0.4f }
        else {
            val d = if (weak) 2.5f else 7f
            projTo[0] = px + sin(yaw / DEG) * d; projTo[1] = 0.12f; projTo[2] = pz + cos(yaw / DEG) * d; projDur = 0.6f
        }
        holding = false
    }

    private fun updateProjectile(dt: Float) {
        projT += dt
        val k = min(1f, projT / projDur)
        for (i in 0 until 3) projPos[i] = projFrom[i] + (projTo[i] - projFrom[i]) * k
        projPos[1] += sin(k * PI) * 0.7f
        if (k >= 1f) {
            projActive = false
            Audio.play("splat"); flash = 0.5f
            if (projHit) {
                mm = MM.HIT; phase = Phase.KEY; keyTimer = Tweak.keyTime; keyVisible = true
                say("Влучив! Монстр прикрив голову. Знайди ключ — ${Tweak.keyTime.toInt()} с!")
            } else say(if (projWeak) "Слабо! Тримай довше, до червоного" else "Промах!")
        }
    }

    fun update(dtIn: Float) {
        val dt = min(dtIn, 0.05f); time += dt
        var ldx = 0f; var ldy = 0f
        synchronized(this) { ldx = lookDx; ldy = lookDy; lookDx = 0f; lookDy = 0f }
        if (msgT > 0f) msgT -= dt
        if (flash > 0f && grabT <= 0f) flash = max(0f, flash - dt * 2.5f)
        if (bigT > 0f) bigT -= dt
        if (whistleCd > 0f) whistleCd -= dt

        val e = ePressed; ePressed = false
        val f = fPressed; fPressed = false
        val c = cPressed; cPressed = false
        val key = pendingKey; pendingKey = ' '

        // door / fridge / cabinets animation
        val dstep = doorSpeed * dt
        if (doorAng < doorTarget) doorAng = min(doorTarget, doorAng + dstep) else if (doorAng > doorTarget) doorAng = max(doorTarget, doorAng - dstep)
        val ft = if (fridgeOpen) 95f else 0f
        fridgeAng += (ft - fridgeAng) * min(1f, dt * 6f)
        for (i in 0 until 4) cabAng[i] += ((if (cabOpen[i]) 100f else 0f) - cabAng[i]) * min(1f, dt * 7f)

        poseMonster(dt)
        monsterVisible = phase != Phase.STREET && mm != MM.GONE
        showF = phase == Phase.OUTSIDE && callState == 0 && !dead
        showC = phase == Phase.OUTSIDE && (mm == MM.CHASE || mm == MM.EARS || mm == MM.KNEEL) && !dead

        if (projActive) updateProjectile(dt)

        if (dead) { camRoll = 0f; return }
        if (phase == Phase.ENDED) {
            endT += dt
            if (endT < 1.5f) fade = endT / 1.5f else { fade = 1f; endScreen = true }
            updateCops(0f)
            return
        }

        // ---- scene transition: street -> room ----
        if (transT > 0f) {
            transT -= dt
            if (transT > 0.7f) { fade = (1.4f - transT) / 0.7f; doorTarget = 100f }
            else {
                if (!teleported) {
                    teleported = true; px = 0f; pz = 0.6f; yaw = 0f; pitch = 0f
                    doorAng = 0f; doorTarget = 0f; phase = Phase.ROOM
                    Audio.loop("drone", 0.6f)
                    mm = MM.WATCH; mx = -0.3f; mz = 4.6f; mYaw = 175f
                }
                fade = max(0f, transT / 0.7f)
            }
            return
        }

        // ---- look / move ----
        if (grabT > 0f) { updateGrab(dt); return }
        yaw -= ldx * Settings.sens; pitch -= ldy * Settings.sens
        if (pitch > 75f) pitch = 75f; if (pitch < -75f) pitch = -75f
        val mvx = moveX; val mvy = moveY
        val ml = min(1f, sqrt(mvx * mvx + mvy * mvy))
        val speed = if (limp) Tweak.limpSpeed else Tweak.walkSpeed
        val fx = sin(yaw / DEG); val fz = cos(yaw / DEG)
        val rx = -fz; val rz = fx
        if (ml > 0.05f) {
            val len = max(0.001f, sqrt(mvx * mvx + mvy * mvy))
            val dirx = (fx * mvy + rx * mvx) / len; val dirz = (fz * mvy + rz * mvx) / len
            val n = collide(px + dirx * speed * ml * dt, pz + dirz * speed * ml * dt)
            px = n[0]; pz = n[1]
            stepT += dt * ml
            if (stepT > 0.5f) { stepT = 0f; Audio.play("step", 0.5f) }
        }
        camYOff = sin(time * (if (limp) 6f else 9f)) * 0.03f * ml
        camRoll = if (limp) sin(time * 3f) * 1.2f * ml else 0f

        // ---- interactions ----
        findCandidate()
        if (e) doInteract()

        // ---- throwing melon ----
        if (holding) {
            if (throwHeld) {
                charging = true; charge += dt / MAX_CHARGE_SEC
                if (charge > 1.0f) {
                    holding = false; charging = false; charge = 0f; flash = 1f; Audio.play("splat")
                    say("Кавун вибухнув у руках!")
                    if (phase == Phase.ROOM && mm == MM.WATCH) mm = MM.RUSH
                }
            } else if (charging) {
                val fill = charge; charging = false; charge = 0f
                val inFront = facing(mx, mz) > 0.85f
                if (fill >= 0.8f && inFront && monsterVisible && (mm == MM.WATCH || mm == MM.RUSH)) spawnProjectile(true, false)
                else spawnProjectile(false, fill < 0.8f)
            }
        } else { charging = false; if (!throwHeld) charge = 0f }

        // ---- monster logic ----
        updateMonsterOnly(dt)
        if (phase == Phase.ROOM && !scared && isSeen() && dist(px, pz, mx, mz) < 9f) { scared = true; Audio.play("scare"); flash = 0.6f }

        // ---- key phase ----
        if (phase == Phase.KEY && keyTimer > 0f) {
            keyTimer -= dt
            if (keyTimer <= 0f) { keyTimer = 0f; mm = MM.RUSH; say("Час вийшов!") }
        }

        // ---- outside logic ----
        if (phase == Phase.OUTSIDE) {
            screamT -= dt
            if (screamT <= 0f) { screamT = 3f + (time * 7f) % 4f; Audio.play("scream", 0.5f) }
            if (mm == MM.HIT && pz < -2.8f && doorTarget != 0f) { doorTarget = 0f; doorSpeed = 160f }
            if (f && callState == 0) phoneOpen = !phoneOpen
            if (phoneOpen) updatePhone(key)
            if (callState >= 1) updateCall(dt)
            if (c && showC) whistle()
            if (whistleWin > 0f) { whistleWin -= dt; if (whistleWin <= 0f && (mm == MM.EARS || mm == MM.KNEEL)) { mm = MM.CHASE; whistleCount = 0 } }
            if (callMode == 911 && gunT <= 0f && !dead && mm == MM.CHASE && dist(px, pz, mx, mz) < 4f) { gunT = 0.001f; showFClear() }
            if (gunT > 0f) updateGun(dt)
            if (copStage >= 1) updateCops(dt)
            if (carVisible && (copStage == 1 || copStage == 7) && abs(pz - CAR_Z) < 1.3f && abs(px - carX) < 2.6f) die("Тебе збила поліцейська машина", true)
        }
    }

    // Монстр проходить між кімнатою і вулицею лише через дверний отвір
    private fun pathTarget(): FloatArray {
        val mIn = mz > 0.05f; val pIn = pz > 0.05f
        if (mIn == pIn) return floatArrayOf(px, pz)
        val sgn = if (mIn) 1f else -1f
        if (mz * sgn > 0.5f && abs(mx) > 0.25f) return floatArrayOf(0f, 0.6f * sgn)
        if (mz * sgn > -0.8f) return floatArrayOf(0f, -0.9f * sgn)
        return floatArrayOf(px, pz)
    }

    private fun showFClear() { phoneOpen = false; throwHeld = false }

    private fun updateGun(dt: Float) {
        gunT += dt
        val want = atan2(mx - px, mz - pz) * DEG
        var dy = want - yaw
        while (dy > 180f) dy -= 360f
        while (dy < -180f) dy += 360f
        yaw += dy * min(1f, dt * 6f); pitch += (4f - pitch) * min(1f, dt * 6f)
        gunLevel = min(1f, gunT / 1.0f)
        if (gunT in 1.3f..1.35f && flash < 0.5f) { Audio.stopAll(); Audio.play("shot", 1f); flash = 1f }
        if (gunT > 1.35f) fade = min(1f, (gunT - 1.35f) / 0.35f)
        if (gunT > 1.7f) { gunLevel = 0f; endBlack = true; phase = Phase.ENDED; endT = 1.5f; fade = 1f; flash = 0f }
    }

    private fun updateMonsterOnly(dt: Float) {
        if (gunT > 0f) return
        if (!monsterVisible && mm != MM.GONE) return
        val d = dist(px, pz, mx, mz)
        val tg = pathTarget()
        val tx = tg[0] - mx; val tz = tg[1] - mz
        val l = max(0.01f, sqrt(tx * tx + tz * tz))
        when (mm) {
            MM.WATCH -> {
                mYaw = atan2(tx, tz) * DEG
                if (!isSeen() && !dead) {
                    mx += tx / l * Tweak.watchSpeed * dt; mz += tz / l * Tweak.watchSpeed * dt
                }
                if (d < 1.0f && grabT <= 0f && !dead) startGrab()
            }
            MM.RUSH -> {
                mYaw = atan2(tx, tz) * DEG
                mx += tx / l * Tweak.rushSpeed * dt; mz += tz / l * Tweak.rushSpeed * dt
                if (d < 1.0f) die("Монстр тебе дістав")
            }
            MM.CHASE -> {
                mYaw = atan2(tx, tz) * DEG
                mx += tx / l * Tweak.chaseSpeed * dt; mz += tz / l * Tweak.chaseSpeed * dt
                if (d < 0.95f) die("Монстр тебе спіймав")
            }
            MM.EARS, MM.KNEEL -> { mYaw = atan2(tx, tz) * DEG }
            MM.HIT -> { mYaw = atan2(tx, tz) * DEG }
            else -> {}
        }
    }

    private fun whistle() {
        if (whistleCd > 0f) return
        whistleCd = 1.1f; Audio.play("whistle")
        when (mm) {
            MM.CHASE -> { mm = MM.EARS; whistleCount = 1; whistleWin = 7f }
            MM.EARS -> { mm = MM.KNEEL; whistleCount = 2; whistleWin = 7f }
            MM.KNEEL -> { mm = MM.FALLEN; whistleCount = 3; whistleWin = 0f; Audio.play("thud"); say("Монстр упав!") }
            else -> {}
        }
    }

    private fun updatePhone(k: Char) {
        if (k == ' ') return
        if (callState != 0) return
        if (k == 'B') { if (phoneNum.isNotEmpty()) phoneNum = phoneNum.dropLast(1); return }
        if (k == 'C') {
            if (phoneNum == "102" || phoneNum == "911") { callMode = if (phoneNum == "911") 911 else 102; callState = 1; callT = 0f; etaT = 0f; callLine = 0; phoneLines.clear(); Audio.play("ring") }
            else { say("Такого номера немає"); phoneNum = "" }
            return
        }
        if (phoneNum.length < 6) { phoneNum += k; Audio.play("beep", 0.6f) }
    }

    private fun updateCall(dt: Float) {
        etaT += dt
        val callScript = if (callMode == 911) script911 else script102
        val breakAt = if (callMode == 911) 14f else 17f
        if (callState == 1) {
            callT += dt
            while (callLine < callScript.size && callT >= callScript[callLine].first) { phoneLines.add(callScript[callLine].second); callLine++ }
            if (callT >= (if (callMode == 911) 9.6f else 11.5f)) { callState = 2; phoneOpen = false; say(if (callMode == 911) "Ти нічого не зрозумів і поклав слухавку" else "Поліція виїхала. Протримайся!") }
        }
        if (!broke && etaT >= breakAt) {
            broke = true; Audio.play("break"); Audio.play("scare", 0.6f)
            doorSpeed = 600f; doorTarget = 100f
            mx = 0f; mz = 0.8f; mm = MM.CHASE; flash = 0.5f
        } else if (!broke && etaT >= breakAt - 4f && etaT < breakAt - 3.9f) Audio.play("knock")
        if (copStage == 0 && etaT >= 30f && callMode == 102) startCar()
    }

    private fun doInteract() {
        when (cType) {
            1 -> { if (transT <= 0f) { transT = 1.4f; teleported = false; Audio.play("thud", 0.6f) } }
            2 -> { fridgeOpen = true; Audio.play("thud", 0.5f); say("У холодильнику лежать кавуни") }
            3 -> { holding = true; melons--; say("Кавун у руках. Затисни кнопку кидка") }
            4 -> { val i = cIdx; cabOpen[i] = !cabOpen[i]; if (cabOpen[i]) say(CAB_TEXT[i]) }
            5 -> { hasKey = true; keyVisible = false; say("Ключ! Швидше до дверей!") }
            6 -> {
                if (!hasKey) say("Двері замкнено")
                else {
                    doorSpeed = 160f; doorTarget = 100f; phase = Phase.OUTSIDE; keyTimer = -1f
                    Audio.stopLoop("drone"); Audio.loop("tense", 0.7f); screamT = 2f; Audio.play("scream", 0.6f)
                    if (mm == MM.RUSH) mm = MM.HIT
                }
            }
        }
    }
}

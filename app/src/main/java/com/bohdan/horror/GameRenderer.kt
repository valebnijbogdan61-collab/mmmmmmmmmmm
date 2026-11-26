package com.bohdan.horror

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class GameHolder {
    @Volatile var g: Game = Game()
    @Volatile var hud: HudState? = null
    @Volatile var inMenu = true
    @Volatile var started = false
    @Volatile var menuMsg = ""
    @Volatile var modVersion = 0
    @Volatile var reloadModels = false
}

class GameRenderer(private val ctx: Context, private val holder: GameHolder) : GLSurfaceView.Renderer {
    private lateinit var shader: Shader
    private lateinit var world: GlbModel
    private lateinit var monster: GlbModel
    private lateinit var melon: GlbModel
    private lateinit var car: GlbModel
    private lateinit var off0: GlbModel
    private lateinit var off1: GlbModel
    private var w = 1; private var h = 1
    private var last = 0L
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private val lp = FloatArray(12); private val li = FloatArray(4)
    private var uCam = 0; private var uDir = 0; private var uFlash = 0; private var uAmb = 0; private var uMoon = 0
    private var uLP = 0; private var uLI = 0; private var uFogC = 0; private var uFogD = 0

    private val VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNor;
layout(location=2) in vec2 aUv;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vW;
out vec3 vN;
out vec2 vUv;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vW = w.xyz;
  vN = mat3(uModel) * aNor;
  vUv = aUv;
  gl_Position = uMVP * vec4(aPos, 1.0);
}
"""
    private val FS = """#version 300 es
precision mediump float;
in vec3 vW;
in vec3 vN;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uUseTex;
uniform vec4 uColor;
uniform vec3 uEmis;
uniform vec3 uCam;
uniform vec3 uDir;
uniform float uFlash;
uniform vec3 uAmb;
uniform float uMoon;
uniform vec3 uLP[4];
uniform float uLI[4];
uniform vec3 uFogC;
uniform float uFogD;
out vec4 o;
void main() {
  vec3 n = normalize(vN);
  if (!gl_FrontFacing) n = -n;
  vec3 base = uColor.rgb;
  if (uUseTex > 0.5) base = texture(uTex, vUv).rgb;
  vec3 L = uAmb + vec3(0.35, 0.42, 0.65) * uMoon * max(dot(n, normalize(vec3(-0.3, 0.8, -0.4))), 0.0);
  for (int i = 0; i < 4; i++) {
    vec3 d = uLP[i] - vW;
    float dist = length(d);
    float att = uLI[i] / (1.0 + 0.12 * dist * dist);
    L += vec3(1.0, 0.85, 0.6) * att * (0.35 + 0.65 * max(dot(n, d / dist), 0.0));
  }
  vec3 tf = vW - uCam;
  float fd = length(tf);
  vec3 tfn = tf / max(fd, 0.001);
  float cone = smoothstep(0.80, 0.93, dot(tfn, uDir));
  L += vec3(1.0, 0.95, 0.85) * uFlash * cone / (1.0 + 0.06 * fd * fd) * max(dot(n, -tfn), 0.25);
  vec3 col = base * L + uEmis;
  float f = 1.0 - exp(-uFogD * fd);
  col = mix(col, uFogC, clamp(f, 0.0, 1.0));
  o = vec4(col, 1.0);
}
"""

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader error: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.01f, 0.015f, 0.04f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, VS))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, FS))
        GLES30.glLinkProgram(p)
        shader = Shader(p)
        uCam = GLES30.glGetUniformLocation(p, "uCam"); uDir = GLES30.glGetUniformLocation(p, "uDir")
        uFlash = GLES30.glGetUniformLocation(p, "uFlash"); uAmb = GLES30.glGetUniformLocation(p, "uAmb")
        uMoon = GLES30.glGetUniformLocation(p, "uMoon"); uLP = GLES30.glGetUniformLocation(p, "uLP")
        uLI = GLES30.glGetUniformLocation(p, "uLI"); uFogC = GLES30.glGetUniformLocation(p, "uFogC")
        uFogD = GLES30.glGetUniformLocation(p, "uFogD")
        loadModels()
        last = System.nanoTime()
    }

    private fun doLoad() {
        world = GlbModel(ctx, "world.glb")
        monster = GlbModel(ctx, "monster.glb")
        melon = GlbModel(ctx, "melon.glb")
        car = GlbModel(ctx, "policecar.glb")
        off0 = GlbModel(ctx, "officer.glb")
        off1 = GlbModel(ctx, "officer.glb")
    }

    private fun loadModels() {
        try { doLoad() } catch (e: Exception) {
            Mod.clear(ctx); holder.menuMsg = "Мод пошкоджено й вимкнено"; holder.modVersion++
            doLoad()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height
        GLES30.glViewport(0, 0, width, height)
    }

    private fun placeOfficer(m: GlbModel, g: Game, i: Int) {
        val ph = g.time * 7f + i * 3.1f
        val walk = g.owalk[i]
        val sw = if (walk) sin(ph) else 0f
        m.node("legL")?.ax = sw * 35f
        m.node("legR")?.ax = -sw * 35f
        val dragging = g.copStage == 4
        m.node("armL")?.ax = if (dragging) -70f else -sw * 28f
        m.node("armR")?.ax = if (dragging) -70f else sw * 28f
        m.node("hips")?.py = if (walk) abs(sin(ph)) * 0.04f else 0f
        val mm = FloatArray(16)
        Matrix.setIdentityM(mm, 0)
        Matrix.translateM(mm, 0, g.ox[i], 0f, g.oz[i])
        Matrix.rotateM(mm, 0, g.oyaw[i], 0f, 1f, 0f)
        m.draw(shader, vp, mm)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (holder.reloadModels) { holder.reloadModels = false; loadModels() }
        var g = holder.g
        if (g.wantRestart) { Audio.stopAll(); holder.g = Game(); g = holder.g }
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
        last = now
        if (holder.inMenu) { GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT); return }
        g.update(dt)
        holder.hud = g.hudSnapshot()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val yawR = g.yaw / Game.DEG; val pitR = g.pitch / Game.DEG; val rollR = g.camRoll / Game.DEG
        val ex = g.px; val ey = 1.6f + g.camYOff; val ez = g.pz
        val fx = cos(pitR) * sin(yawR); val fy = sin(pitR); val fz = cos(pitR) * cos(yawR)
        val rx = -cos(yawR); val rz = sin(yawR)
        val ux = sin(rollR) * rx; val uy = cos(rollR); val uz = sin(rollR) * rz
        Matrix.setLookAtM(view, 0, ex, ey, ez, ex + fx, ey + fy, ez + fz, ux, uy, uz)
        Matrix.perspectiveM(proj, 0, 58f, w.toFloat() / h.toFloat(), 0.05f, 120f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES30.glUseProgram(shader.prog)
        val inside = g.pz > 0.1f
        GLES30.glUniform3f(uCam, ex, ey, ez)
        GLES30.glUniform3f(uDir, fx, fy, fz)
        GLES30.glUniform1f(uFlash, 0.9f)
        if (inside) { GLES30.glUniform3f(uAmb, 0.10f, 0.09f, 0.08f); GLES30.glUniform1f(uMoon, 0f) }
        else { GLES30.glUniform3f(uAmb, 0.07f, 0.08f, 0.13f); GLES30.glUniform1f(uMoon, 0.6f) }
        GLES30.glUniform3f(uFogC, 0.01f, 0.015f, 0.04f)
        GLES30.glUniform1f(uFogD, 0.03f)
        val flick = if (((g.time * 9f).toInt() * 7) % 11 == 0) 0.3f else 1f
        lp[0] = 0f; lp[1] = 2.1f; lp[2] = 3f; li[0] = 1.5f * flick
        lp[3] = -7f; lp[4] = 3.4f; lp[5] = -1.9f; li[1] = 2.5f
        lp[6] = 7f; lp[7] = 3.4f; lp[8] = -1.9f; li[2] = 2.5f
        lp[9] = g.carX; lp[10] = 2f; lp[11] = Game.CAR_Z; li[3] = if (g.carVisible) 2.5f else 0f
        GLES30.glUniform3fv(uLP, 4, lp, 0)
        GLES30.glUniform1fv(uLI, 4, li, 0)

        val id = FloatArray(16); Matrix.setIdentityM(id, 0)
        world.node("door")?.ay = g.doorAng
        world.node("fridgedoor")?.ay = -g.fridgeAng
        for (i in 0 until 4) world.node("cab${i + 1}")?.ay = g.cabAng[i]
        world.node("keyobj")?.visible = g.keyVisible
        world.draw(shader, vp, id)

        if (g.monsterVisible) {
            val m = FloatArray(16); Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, g.mx, g.mYOff, g.mz)
            Matrix.rotateM(m, 0, g.mYaw, 0f, 1f, 0f)
            Matrix.rotateM(m, 0, g.mPitch, 1f, 0f, 0f)
            Matrix.rotateM(m, 0, g.mRoll, 0f, 0f, 1f)
            Matrix.scaleM(m, 0, 1f, g.mSY, 1f)
            monster.draw(shader, vp, m)
        }

        if (g.carVisible) {
            val blink = sin(g.time * 10f) > 0f
            car.node("barR")?.visible = blink
            car.node("barB")?.visible = !blink
            car.node("trunk")?.az = -g.trunkAng
            val m = FloatArray(16); Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, g.carX, 0f, Game.CAR_Z)
            car.draw(shader, vp, m)
        }
        if (g.ovis[0]) placeOfficer(off0, g, 0)
        if (g.ovis[1]) placeOfficer(off1, g, 1)

        if (g.holding && !g.projActive && !g.dead) {
            val fill = g.charge
            val shake = if (g.charging) fill * 0.02f else 0f
            val bx = ex + fx * 0.55f + rx * (0.24f + shake * sin(g.time * 60f)) + ux * 0f
            val by = ey + fy * 0.55f - 0.22f + shake * cos(g.time * 55f)
            val bz = ez + fz * 0.55f + rz * (0.24f + shake * sin(g.time * 60f))
            val m = FloatArray(16); Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, bx, by, bz)
            Matrix.rotateM(m, 0, g.yaw, 0f, 1f, 0f)
            Matrix.rotateM(m, 0, -g.pitch, 1f, 0f, 0f)
            Matrix.rotateM(m, 0, 20f, 0f, 0f, 1f)
            Matrix.scaleM(m, 0, 1.3f + fill * 0.3f, 1.3f + fill * 0.3f, 1.3f + fill * 0.3f)
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            melon.draw(shader, vp, m)
        }
        if (g.projActive) {
            val m = FloatArray(16); Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, g.projPos[0], g.projPos[1], g.projPos[2])
            Matrix.rotateM(m, 0, g.time * 500f, 1f, 0.3f, 0f)
            Matrix.scaleM(m, 0, 1.4f, 1.4f, 1.4f)
            melon.draw(shader, vp, m)
        }
    }
}

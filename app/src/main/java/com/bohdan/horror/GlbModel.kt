package com.bohdan.horror

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Shader(val prog: Int) {
    val uMVP = GLES30.glGetUniformLocation(prog, "uMVP")
    val uModel = GLES30.glGetUniformLocation(prog, "uModel")
    val uColor = GLES30.glGetUniformLocation(prog, "uColor")
    val uEmis = GLES30.glGetUniformLocation(prog, "uEmis")
    val uUseTex = GLES30.glGetUniformLocation(prog, "uUseTex")
    val uTex = GLES30.glGetUniformLocation(prog, "uTex")
}

class Prim(val vbo: Int, val ibo: Int, val count: Int, val color: FloatArray, val emis: FloatArray, val tex: Int)

class GNode(val name: String) {
    val kids = ArrayList<GNode>()
    val prims = ArrayList<Prim>()
    val local = FloatArray(16)
    var ax = 0f
    var ay = 0f
    var az = 0f
    var px = 0f
    var py = 0f
    var pz = 0f
    var visible = true
}

class GlbModel(ctx: Context, asset: String) {
    val nodes = HashMap<String, GNode>()
    lateinit var root: GNode
    private val texCache = HashMap<Int, Int>()
    private val tmpA = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val tmpMVP = FloatArray(16)

    init {
        val bytes = Mod.open(ctx, asset).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLen = bb.getInt(12)
        val json = JSONObject(String(bytes, 20, jsonLen, Charsets.UTF_8))
        val binStart = 20 + jsonLen + 8
        val bin = ByteBuffer.wrap(bytes, binStart, bytes.size - binStart).slice().order(ByteOrder.LITTLE_ENDIAN)
        val jNodes = json.getJSONArray("nodes")
        val built = ArrayList<GNode>()
        for (i in 0 until jNodes.length()) {
            val jn = jNodes.getJSONObject(i)
            val n = GNode(jn.optString("name", "n$i"))
            readLocal(jn, n.local)
            built.add(n)
            nodes[n.name] = n
        }
        for (i in 0 until jNodes.length()) {
            val jn = jNodes.getJSONObject(i)
            if (jn.has("children")) {
                val ch = jn.getJSONArray("children")
                for (k in 0 until ch.length()) built[i].kids.add(built[ch.getInt(k)])
            }
            if (jn.has("mesh")) {
                val mesh = json.getJSONArray("meshes").getJSONObject(jn.getInt("mesh"))
                val prims = mesh.getJSONArray("primitives")
                for (p in 0 until prims.length()) built[i].prims.add(buildPrim(json, bin, prims.getJSONObject(p)))
            }
        }
        val sceneNodes = json.getJSONArray("scenes").getJSONObject(0).getJSONArray("nodes")
        root = built[sceneNodes.getInt(0)]
    }

    fun node(name: String): GNode? = nodes[name]

    private fun readLocal(jn: JSONObject, out: FloatArray) {
        Matrix.setIdentityM(out, 0)
        if (jn.has("matrix")) {
            val a = jn.getJSONArray("matrix")
            for (i in 0 until 16) out[i] = a.getDouble(i).toFloat()
            return
        }
        if (jn.has("translation")) {
            val t = jn.getJSONArray("translation")
            Matrix.translateM(out, 0, t.getDouble(0).toFloat(), t.getDouble(1).toFloat(), t.getDouble(2).toFloat())
        }
        if (jn.has("rotation")) {
            val q = jn.getJSONArray("rotation")
            val r = quatToMat(q.getDouble(0).toFloat(), q.getDouble(1).toFloat(), q.getDouble(2).toFloat(), q.getDouble(3).toFloat())
            val res = FloatArray(16)
            Matrix.multiplyMM(res, 0, out, 0, r, 0)
            System.arraycopy(res, 0, out, 0, 16)
        }
        if (jn.has("scale")) {
            val s = jn.getJSONArray("scale")
            Matrix.scaleM(out, 0, s.getDouble(0).toFloat(), s.getDouble(1).toFloat(), s.getDouble(2).toFloat())
        }
    }

    private fun quatToMat(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val m = FloatArray(16)
        m[0] = 1 - 2 * (y * y + z * z); m[4] = 2 * (x * y - z * w); m[8] = 2 * (x * z + y * w); m[12] = 0f
        m[1] = 2 * (x * y + z * w); m[5] = 1 - 2 * (x * x + z * z); m[9] = 2 * (y * z - x * w); m[13] = 0f
        m[2] = 2 * (x * z - y * w); m[6] = 2 * (y * z + x * w); m[10] = 1 - 2 * (x * x + y * y); m[14] = 0f
        m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        return m
    }

    private fun accessorInfo(json: JSONObject, idx: Int): IntArray {
        val acc = json.getJSONArray("accessors").getJSONObject(idx)
        val bv = json.getJSONArray("bufferViews").getJSONObject(acc.getInt("bufferView"))
        val comps = when (acc.getString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> 1 }
        val off = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val ct = acc.getInt("componentType")
        val csize = if (ct == 5126 || ct == 5125) 4 else if (ct == 5123) 2 else 1
        val stride = bv.optInt("byteStride", comps * csize)
        return intArrayOf(acc.getInt("count"), comps, off, stride, ct)
    }

    private fun readFloats(json: JSONObject, bin: ByteBuffer, idx: Int): FloatArray {
        val i = accessorInfo(json, idx)
        val out = FloatArray(i[0] * i[1])
        for (n in 0 until i[0]) for (c in 0 until i[1]) out[n * i[1] + c] = bin.getFloat(i[2] + n * i[3] + c * 4)
        return out
    }

    private fun readIndices(json: JSONObject, bin: ByteBuffer, idx: Int): IntArray {
        val i = accessorInfo(json, idx)
        val out = IntArray(i[0])
        for (n in 0 until i[0]) {
            out[n] = when (i[4]) {
                5125 -> bin.getInt(i[2] + n * i[3])
                5123 -> bin.getShort(i[2] + n * i[3]).toInt() and 0xFFFF
                else -> bin.get(i[2] + n * i[3]).toInt() and 0xFF
            }
        }
        return out
    }

    private fun buildPrim(json: JSONObject, bin: ByteBuffer, jp: JSONObject): Prim {
        val at = jp.getJSONObject("attributes")
        val pos = readFloats(json, bin, at.getInt("POSITION"))
        val vc = pos.size / 3
        val nor = if (at.has("NORMAL")) readFloats(json, bin, at.getInt("NORMAL")) else FloatArray(vc * 3)
        val uv = if (at.has("TEXCOORD_0")) readFloats(json, bin, at.getInt("TEXCOORD_0")) else FloatArray(vc * 2)
        val idx = readIndices(json, bin, jp.getInt("indices"))
        val inter = FloatArray(vc * 8)
        for (v in 0 until vc) {
            inter[v * 8] = pos[v * 3]; inter[v * 8 + 1] = pos[v * 3 + 1]; inter[v * 8 + 2] = pos[v * 3 + 2]
            inter[v * 8 + 3] = nor[v * 3]; inter[v * 8 + 4] = nor[v * 3 + 1]; inter[v * 8 + 5] = nor[v * 3 + 2]
            inter[v * 8 + 6] = uv[v * 2]; inter[v * 8 + 7] = uv[v * 2 + 1]
        }
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        val fb = ByteBuffer.allocateDirect(inter.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(inter).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, inter.size * 4, fb, GLES30.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(idx).position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GLES30.GL_STATIC_DRAW)
        var color = floatArrayOf(1f, 1f, 1f, 1f)
        var emis = floatArrayOf(0f, 0f, 0f)
        var tex = 0
        if (jp.has("material")) {
            val mat = json.getJSONArray("materials").getJSONObject(jp.getInt("material"))
            if (mat.has("pbrMetallicRoughness")) {
                val pbr = mat.getJSONObject("pbrMetallicRoughness")
                if (pbr.has("baseColorFactor")) {
                    val c = pbr.getJSONArray("baseColorFactor")
                    color = floatArrayOf(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), 1f)
                }
                if (pbr.has("baseColorTexture")) tex = loadTexture(json, bin, pbr.getJSONObject("baseColorTexture").getInt("index"))
            }
            if (mat.has("emissiveFactor")) {
                val e = mat.getJSONArray("emissiveFactor")
                emis = floatArrayOf(e.getDouble(0).toFloat(), e.getDouble(1).toFloat(), e.getDouble(2).toFloat())
            }
        }
        return Prim(ids[0], ids[1], idx.size, color, emis, tex)
    }

    private fun loadTexture(json: JSONObject, bin: ByteBuffer, texIndex: Int): Int {
        val src = json.getJSONArray("textures").getJSONObject(texIndex).getInt("source")
        if (texCache.containsKey(src)) return texCache[src]!!
        val img = json.getJSONArray("images").getJSONObject(src)
        val bv = json.getJSONArray("bufferViews").getJSONObject(img.getInt("bufferView"))
        val off = bv.optInt("byteOffset", 0)
        val len = bv.getInt("byteLength")
        val data = ByteArray(len)
        for (i in 0 until len) data[i] = bin.get(off + i)
        val bmp = BitmapFactory.decodeByteArray(data, 0, len)
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        bmp.recycle()
        texCache[src] = t[0]
        return t[0]
    }

    fun draw(sh: Shader, vp: FloatArray, model: FloatArray) {
        drawNode(sh, vp, root, model)
    }

    private fun drawNode(sh: Shader, vp: FloatArray, n: GNode, parent: FloatArray) {
        if (!n.visible) return
        val m = FloatArray(16)
        Matrix.multiplyMM(tmpM, 0, parent, 0, n.local, 0)
        Matrix.setIdentityM(tmpA, 0)
        Matrix.translateM(tmpA, 0, n.px, n.py, n.pz)
        Matrix.rotateM(tmpA, 0, n.ay, 0f, 1f, 0f)
        Matrix.rotateM(tmpA, 0, n.ax, 1f, 0f, 0f)
        Matrix.rotateM(tmpA, 0, n.az, 0f, 0f, 1f)
        Matrix.multiplyMM(m, 0, tmpM, 0, tmpA, 0)
        if (n.prims.isNotEmpty()) {
            Matrix.multiplyMM(tmpMVP, 0, vp, 0, m, 0)
            GLES30.glUniformMatrix4fv(sh.uMVP, 1, false, tmpMVP, 0)
            GLES30.glUniformMatrix4fv(sh.uModel, 1, false, m, 0)
            for (p in n.prims) {
                GLES30.glUniform4fv(sh.uColor, 1, p.color, 0)
                GLES30.glUniform3fv(sh.uEmis, 1, p.emis, 0)
                if (p.tex != 0) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, p.tex)
                    GLES30.glUniform1i(sh.uTex, 0)
                    GLES30.glUniform1f(sh.uUseTex, 1f)
                } else {
                    GLES30.glUniform1f(sh.uUseTex, 0f)
                }
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, p.vbo)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.ibo)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 32, 12)
                GLES30.glEnableVertexAttribArray(2)
                GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 32, 24)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, p.count, GLES30.GL_UNSIGNED_INT, 0)
            }
        }
        for (k in n.kids) drawNode(sh, vp, k, m)
    }
}

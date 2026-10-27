package br.edward.cadernovivo

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

data class Note(
    val id: String,
    val createdAt: Long,
    val kind: String,
    val text: String,
    val title: String,
    val tags: List<String>,
    val image: String?,
    val embedding: FloatArray?,
)

class Store(private val ctx: Context) {
    private val file = File(ctx.filesDir, "notes.json")
    private val prefs = ctx.getSharedPreferences("cv", Context.MODE_PRIVATE)

    fun getPref(k: String, d: String): String = prefs.getString(k, d) ?: d
    fun setPref(k: String, v: String) {
        prefs.edit().putString(k, v).apply()
    }

    fun load(): List<Note> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(notes: List<Note>) {
        val arr = JSONArray()
        notes.forEach { arr.put(toJson(it)) }
        val tmp = File(ctx.filesDir, "notes.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun toJson(n: Note): JSONObject {
        val o = JSONObject()
            .put("id", n.id)
            .put("t", n.createdAt)
            .put("kind", n.kind)
            .put("text", n.text)
            .put("title", n.title)
            .put("tags", JSONArray(n.tags))
        if (n.image != null) o.put("image", n.image)
        val e = n.embedding
        if (e != null) {
            val bb = ByteBuffer.allocate(e.size * 4)
            e.forEach { bb.putFloat(it) }
            o.put("emb", Base64.encodeToString(bb.array(), Base64.NO_WRAP))
        }
        return o
    }

    private fun fromJson(o: JSONObject): Note {
        val tagsArr = o.optJSONArray("tags")
        val tags = if (tagsArr == null) emptyList() else (0 until tagsArr.length()).map { tagsArr.getString(it) }
        var emb: FloatArray? = null
        if (o.has("emb")) {
            val bytes = Base64.decode(o.getString("emb"), Base64.NO_WRAP)
            val fb = ByteBuffer.wrap(bytes).asFloatBuffer()
            emb = FloatArray(fb.remaining())
            fb.get(emb)
        }
        return Note(
            id = o.getString("id"),
            createdAt = o.getLong("t"),
            kind = o.optString("kind", "texto"),
            text = o.getString("text"),
            title = o.optString("title", ""),
            tags = tags,
            image = if (o.has("image")) o.getString("image") else null,
            embedding = emb,
        )
    }
}

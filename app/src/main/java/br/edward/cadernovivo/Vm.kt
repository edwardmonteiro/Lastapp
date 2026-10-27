package br.edward.cadernovivo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class Cfg(val think: String, val fast: String, val vision: String, val embed: String)
data class Answer(val title: String, val text: String, val sources: List<Note>)

class AppVm(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    var apiKey by mutableStateOf(store.getPref("key", ""))
        private set
    var cfg by mutableStateOf(
        Cfg(
            store.getPref("think", D_THINK),
            store.getPref("fast", D_FAST),
            store.getPref("vision", D_VISION),
            store.getPref("embed", D_EMBED),
        )
    )
        private set
    val notes = mutableStateListOf<Note>()
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var answer by mutableStateOf<Answer?>(null)
        private set
    var results by mutableStateOf<List<Note>?>(null)
        private set
    var catalogInfo by mutableStateOf("")
        private set

    private val api = Nvidia { apiKey }

    init {
        notes.addAll(store.load().sortedByDescending { it.createdAt })
    }

    // ---------- configuração ----------

    fun saveKey(k: String) {
        apiKey = k.trim()
        store.setPref("key", apiKey)
    }

    fun applyCfg(c: Cfg) {
        val embedChanged = c.embed != cfg.embed
        cfg = c
        store.setPref("think", c.think)
        store.setPref("fast", c.fast)
        store.setPref("vision", c.vision)
        store.setPref("embed", c.embed)
        if (embedChanged) {
            for (i in notes.indices) notes[i] = notes[i].copy(embedding = null)
            persist()
        }
    }

    fun detect() {
        if (!ready()) return
        viewModelScope.launch {
            try {
                busy = "Consultando o catálogo"
                val ids = withContext(Dispatchers.IO) { api.models() }
                fun pick(default: String, vararg res: Regex): String {
                    if (ids.contains(default)) return default
                    for (r in res) {
                        val m = ids.filter { r.containsMatchIn(it) }.sortedDescending()
                        if (m.isNotEmpty()) return m.first()
                    }
                    return default
                }
                val c = Cfg(
                    think = pick(cfg.think, Regex("nvidia/.*nemotron.*(super|ultra)"), Regex("nvidia/.*nemotron")),
                    fast = pick(cfg.fast, Regex("nvidia/.*nemotron.*nano(?!.*(omni|vl))"), Regex("nvidia/.*nemotron")),
                    vision = pick(cfg.vision, Regex("nvidia/.*nemotron.*(omni|vl)"), Regex("nvidia/.*(vl|vision)")),
                    embed = pick(cfg.embed, Regex("nvidia/.*embed"), Regex("embed")),
                )
                applyCfg(c)
                val roles = listOf("pensar" to c.think, "rápido" to c.fast, "visão" to c.vision, "embeddings" to c.embed)
                catalogInfo = "${ids.size} modelos no catálogo · " +
                    roles.joinToString(" · ") { (k, v) -> k + (if (ids.contains(v)) " ✓" else " ✗") }
            } catch (e: Exception) {
                message = "Catálogo falhou: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    // ---------- captura ----------

    fun addText(text: String, kind: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        ingest(kind, t, null)
    }

    fun addPhoto(file: File, caption: String) {
        ingest("foto", caption.trim(), file)
    }

    fun delete(n: Note) {
        notes.removeAll { it.id == n.id }
        n.image?.let { File(getApplication<Application>().filesDir, it).delete() }
        results = results?.filter { it.id != n.id }
        persist()
    }

    private fun ingest(kind: String, raw: String, photo: File?) {
        if (!ready()) return
        viewModelScope.launch {
            val id = UUID.randomUUID().toString().take(8)
            var text = raw
            var imageName: String? = null

            if (photo != null) {
                try {
                    busy = "Lendo a imagem · " + short(cfg.vision)
                    val prepared = withContext(Dispatchers.IO) { prepareImage(photo, id) }
                    imageName = prepared.first
                    val desc = withContext(Dispatchers.IO) {
                        api.chat(cfg.vision, visionMessages(raw, prepared.second), 2000, 0.2)
                    }
                    text = if (raw.isBlank()) desc else raw + "\n\n" + desc
                } catch (e: Exception) {
                    message = "Visão falhou: ${e.message}"
                    if (text.isBlank()) text = "(foto sem descrição)"
                }
            }

            var title = text.lineSequence().firstOrNull().orEmpty().take(48)
            var tags: List<String> = emptyList()
            try {
                busy = "Titulando · " + short(cfg.fast)
                val out = withContext(Dispatchers.IO) { api.chat(cfg.fast, tagMessages(text), 1200, 0.2) }
                val j = parseJson(out)
                if (j != null) {
                    title = j.optString("titulo", title).ifBlank { title }.take(70)
                    val arr = j.optJSONArray("tags")
                    if (arr != null) tags = (0 until arr.length()).map { arr.get(it).toString().lowercase().trim() }.filter { it.isNotEmpty() }.take(5)
                }
            } catch (e: Exception) {
                message = "Titulação falhou: ${e.message}"
            }

            var emb: FloatArray? = null
            try {
                busy = "Indexando · " + short(cfg.embed)
                emb = withContext(Dispatchers.IO) { api.embed(cfg.embed, listOf(embedText(title, text)), false)[0] }
            } catch (e: Exception) {
                message = "Indexação falhou (tento de novo depois): ${e.message}"
            }

            notes.add(0, Note(id, System.currentTimeMillis(), kind, text, title, tags, imageName, emb))
            persist()
            busy = null
        }
    }

    // ---------- lembrar e pensar ----------

    fun search(q: String) {
        val query = q.trim()
        if (query.isEmpty()) {
            results = null
            return
        }
        if (!ready()) return
        viewModelScope.launch {
            try {
                busy = "Buscando por significado"
                ensureEmbeddings()
                val qv = withContext(Dispatchers.IO) { api.embed(cfg.embed, listOf(query), true)[0] }
                results = rank(qv, 8).map { it.first }
            } catch (e: Exception) {
                message = "Busca falhou: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun clearSearch() {
        results = null
    }

    fun ask(q: String) {
        val query = q.trim()
        if (query.isEmpty() || !ready()) return
        if (notes.isEmpty()) {
            message = "O caderno está vazio. Capture algumas notas primeiro."
            return
        }
        viewModelScope.launch {
            try {
                busy = "Recuperando notas · " + short(cfg.embed)
                ensureEmbeddings()
                val qv = withContext(Dispatchers.IO) { api.embed(cfg.embed, listOf(query), true)[0] }
                val top = rank(qv, 6).map { it.first }
                if (top.isEmpty()) {
                    message = "Nenhuma nota indexada ainda."
                    return@launch
                }
                busy = "Raciocinando · " + short(cfg.think)
                val out = withContext(Dispatchers.IO) { api.chat(cfg.think, askMessages(query, top), 3000, 0.3) }
                answer = Answer(query, out, top)
            } catch (e: Exception) {
                message = "Pergunta falhou: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun connect() {
        if (!ready()) return
        if (notes.size < 2) {
            message = "Escreva pelo menos 2 notas para conectar ideias."
            return
        }
        viewModelScope.launch {
            try {
                busy = "Indexando · " + short(cfg.embed)
                ensureEmbeddings()
                val picked = LinkedHashMap<String, Note>()
                val seeds = notes.take(3)
                seeds.forEach { picked[it.id] = it }
                seeds.forEach { seed ->
                    val sv = seed.embedding ?: return@forEach
                    rank(sv, 5).forEach { pair -> if (!picked.containsKey(pair.first.id)) picked[pair.first.id] = pair.first }
                }
                val list = picked.values.take(10).toList()
                busy = "Conectando ideias · " + short(cfg.think)
                val out = withContext(Dispatchers.IO) { api.chat(cfg.think, connectMessages(list), 3500, 0.5) }
                answer = Answer("Conexões entre suas notas recentes", out, list)
            } catch (e: Exception) {
                message = "Conexão falhou: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    // ---------- internos ----------

    private fun ready(): Boolean {
        if (apiKey.isBlank()) {
            message = "Cole sua chave nvapi em Ajustes (03)."
            return false
        }
        return true
    }

    private fun persist() {
        val snap = notes.toList()
        viewModelScope.launch(Dispatchers.IO) { store.save(snap) }
    }

    private suspend fun ensureEmbeddings() {
        val missing = notes.filter { it.embedding == null }
        if (missing.isEmpty()) return
        for (chunk in missing.chunked(16)) {
            val vecs = withContext(Dispatchers.IO) {
                api.embed(cfg.embed, chunk.map { embedText(it.title, it.text) }, false)
            }
            chunk.forEachIndexed { i, n ->
                val idx = notes.indexOfFirst { it.id == n.id }
                if (idx >= 0 && i < vecs.size) notes[idx] = notes[idx].copy(embedding = vecs[i])
            }
        }
        persist()
    }

    private fun rank(qv: FloatArray, k: Int): List<Pair<Note, Float>> =
        notes.filter { it.embedding != null && it.embedding.size == qv.size }
            .map { it to cosine(qv, it.embedding!!) }
            .sortedByDescending { it.second }
            .take(k)

    private fun short(id: String): String = id.substringAfterLast('/')

    private fun embedText(title: String, text: String): String = (title + "\n" + text).take(2000)

    private fun parseJson(s: String): JSONObject? {
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try {
            JSONObject(s.substring(a, b + 1))
        } catch (e: Exception) {
            null
        }
    }

    private fun prepareImage(file: File, id: String): Pair<String, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(file.path, opts) ?: throw IllegalStateException("imagem ilegível")
        val rot = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rot != 0f) {
            val m = Matrix()
            m.postRotate(rot)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val scale = 1280f / maxOf(bmp.width, bmp.height)
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        val bytes = bos.toByteArray()
        val name = "img_$id.jpg"
        File(getApplication<Application>().filesDir, name).writeBytes(bytes)
        file.delete()
        return Pair(name, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun msg(role: String, content: Any): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun visionMessages(caption: String, b64: String): JSONArray {
        var prompt = "Descreva esta imagem em português para um caderno de notas: o que é, contexto relevante " +
            "e transcreva qualquer texto visível. Máximo 120 palavras."
        if (caption.isNotBlank()) prompt += "\nLegenda do usuário: $caption"
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        return JSONArray().put(msg("user", parts))
    }

    private fun tagMessages(text: String): JSONArray = JSONArray()
        .put(
            msg(
                "system",
                "Você organiza notas pessoais. Responda SOMENTE com JSON válido, sem explicações: " +
                    "{\"titulo\":\"até 6 palavras\",\"tags\":[\"até 4 tags curtas em minúsculas\"]}. Idioma: português."
            )
        )
        .put(msg("user", text.take(1500)))

    private fun notesBlock(list: List<Note>): String = list.mapIndexed { i, n ->
        val d = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(n.createdAt))
        "[${i + 1}] ($d) ${n.title}: ${n.text.take(700)}"
    }.joinToString("\n\n")

    private fun askMessages(q: String, list: List<Note>): JSONArray = JSONArray()
        .put(
            msg(
                "system",
                "Você é o Caderno Vivo, a memória pessoal do usuário. Responda em português, de forma direta, " +
                    "usando APENAS as notas fornecidas. Cite as notas como [n]. Se as notas não bastarem, diga " +
                    "claramente o que falta. Sem rodeios."
            )
        )
        .put(msg("user", "Notas:\n\n" + notesBlock(list) + "\n\nPergunta: " + q))

    private fun connectMessages(list: List<Note>): JSONArray = JSONArray()
        .put(
            msg(
                "system",
                "Você é o Caderno Vivo. Em português, encontre relações não óbvias entre as notas: " +
                    "1) até 3 conexões entre notas (cite [n]); 2) uma contradição ou tensão, se houver; " +
                    "3) três próximos passos concretos. Seja específico e curto."
            )
        )
        .put(msg("user", "Notas:\n\n" + notesBlock(list)))
}

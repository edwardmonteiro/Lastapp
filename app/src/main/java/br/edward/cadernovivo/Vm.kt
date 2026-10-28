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
            store.getPref("embed", D_EMBED).let { if (it == "nvidia/llama-3.2-nv-embedqa-1b-v2") D_EMBED else it },
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
        noEmbed = false
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
                        api.chat(cfg.vision, visionMessages(raw, prepared.second), 4096, 0.2, noThink = true)
                    }
                    text = if (raw.isBlank()) desc else raw + "\n\n" + desc
                } catch (e: Exception) {
                    warn("Visão falhou: ${e.message}")
                    if (text.isBlank()) text = "(foto sem descrição)"
                }
            }

            var title = text.lineSequence().firstOrNull().orEmpty().take(48)
            var tags: List<String> = emptyList()
            try {
                busy = "Titulando · " + short(cfg.fast)
                val out = withContext(Dispatchers.IO) { api.chat(cfg.fast, tagMessages(text), 2048, 0.2, noThink = true) }
                val j = parseJson(out)
                if (j != null) {
                    title = j.optString("titulo", title).ifBlank { title }.take(70)
                    val arr = j.optJSONArray("tags")
                    if (arr != null) tags = (0 until arr.length()).map { arr.get(it).toString().lowercase().trim() }.filter { it.isNotEmpty() }.take(5)
                }
            } catch (e: Exception) {
                warn("Titulação falhou: ${e.message}")
            }

            var emb: FloatArray? = null
            try {
                busy = "Indexando · " + short(cfg.embed)
                emb = embedSafe(listOf(embedText(title, text)), false)[0]
            } catch (e: Exception) {
                warn("Indexação falhou (tento de novo depois): ${e.message}")
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
                results = retrieve(query, 8)
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
                busy = "Recuperando notas"
                val top = retrieve(query, 6).ifEmpty { notes.take(6).toList() }
                busy = "Raciocinando · " + short(cfg.think)
                val out = withContext(Dispatchers.IO) { api.chat(cfg.think, askMessages(query, top), 6000, 0.3) }
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
                busy = "Indexando"
                try {
                    ensureEmbeddings()
                } catch (e: Exception) {
                    warn("Sem embeddings, conectando pelas notas mais recentes: ${e.message?.take(160)}")
                }
                val picked = LinkedHashMap<String, Note>()
                val seeds = notes.take(3)
                seeds.forEach { picked[it.id] = it }
                seeds.forEach { seed ->
                    val sv = seed.embedding ?: return@forEach
                    rank(sv, 5).forEach { pair -> if (!picked.containsKey(pair.first.id)) picked[pair.first.id] = pair.first }
                }
                notes.take(8).forEach { if (picked.size < 8) picked.putIfAbsent(it.id, it) }
                val list = picked.values.take(10).toList()
                busy = "Conectando ideias · " + short(cfg.think)
                val out = withContext(Dispatchers.IO) { api.chat(cfg.think, connectMessages(list), 6000, 0.5) }
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

    private var embedSwitched = false

    private fun warn(s: String) {
        val cur = message
        message = if (cur == null) s else (cur + "\n" + s).lines().takeLast(3).joinToString("\n")
    }

    private var noEmbed = false

    private val preferred = listOf(
        "llama-nemotron-embed-1b-v2", "nv-embedqa-e5-v5", "nv-embed-v1", "nv-embedqa-mistral-7b-v2",
        "bge-m3", "embed-qa-4", "arctic-embed-l",
    )

    private fun isAccessErr(m: String) =
        m.startsWith("HTTP 404") || m.startsWith("HTTP 410") || m.startsWith("HTTP 403") || m.startsWith("HTTP 400")

    /** Embeddings com auto-recuperação: testa os modelos do catálogo até achar um que a conta consiga usar. */
    private suspend fun embedSafe(texts: List<String>, query: Boolean): List<FloatArray> {
        if (noEmbed) throw java.io.IOException("nenhum modelo de embeddings acessível nesta conta")
        return withContext(Dispatchers.IO) {
            try {
                api.embed(cfg.embed, texts, query)
            } catch (e: java.io.IOException) {
                if (!isAccessErr(e.message.orEmpty())) throw e
                val tried = mutableListOf(short(cfg.embed))
                val ids = api.models()
                val cands = ids.filter { Regex("embed", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                    .filterNot { Regex("-vl|rerank|clip|embedqa-1b-v2", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                    .filter { it != cfg.embed }
                    .sortedBy { id -> preferred.indexOfFirst { id.contains(it) }.let { if (it < 0) 99 else it } }
                for (c in cands) {
                    try {
                        val v = api.embed(c, texts, query)
                        withContext(Dispatchers.Main) {
                            applyCfg(cfg.copy(embed = c))
                            embedSwitched = true
                            warn("Embeddings agora: ${short(c)}")
                        }
                        return@withContext v
                    } catch (e2: java.io.IOException) {
                        tried.add(short(c))
                        if (!isAccessErr(e2.message.orEmpty())) throw e2
                    }
                }
                noEmbed = true
                throw java.io.IOException("nenhum modelo de embeddings acessível (testados: ${tried.joinToString()})")
            }
        }
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

    private fun lexicalRank(q: String, k: Int): List<Note> {
        val qt = tokens(q)
        if (qt.isEmpty()) return emptyList()
        return notes.map { n -> n to tokens(n.title + " " + n.text + " " + n.tags.joinToString(" ")).count { it in qt } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    /** Busca semântica; se não houver embeddings acessíveis, cai para palavras-chave. */
    private suspend fun retrieve(query: String, k: Int): List<Note> {
        try {
            ensureEmbeddings()
            val qv = embedSafe(listOf(query), true)[0]
            val r = rank(qv, k).map { it.first }
            if (r.isNotEmpty()) return r
        } catch (e: Exception) {
            warn("Sem busca semântica, usando palavras-chave: ${e.message?.take(180)}")
        }
        return lexicalRank(query, k)
    }

    private suspend fun ensureEmbeddings() {
        val missing = notes.filter { it.embedding == null }
        if (missing.isEmpty()) return
        for (chunk in missing.chunked(16)) {
            val vecs = embedSafe(chunk.map { embedText(it.title, it.text) }, false)
            if (embedSwitched) {
                embedSwitched = false
                ensureEmbeddings()
                return
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

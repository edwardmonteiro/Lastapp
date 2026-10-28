package br.edward.cadernovivo

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

const val D_THINK = "nvidia/nemotron-3-super-120b-a12b"
const val D_FAST = "nvidia/nemotron-3-nano-30b-a3b"
const val D_VISION = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"
const val D_EMBED = "nvidia/llama-nemotron-embed-1b-v2"

/** Cliente da API OpenAI-compatível do build.nvidia.com. */
class Nvidia(private val key: () -> String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val base = "https://integrate.api.nvidia.com/v1"
    private val jsonType = "application/json".toMediaType()

    private fun call(path: String, body: JSONObject?): JSONObject {
        val b = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer " + key().trim())
            .header("Accept", "application/json")
        val req = if (body == null) b.get().build()
        else b.post(body.toString().toRequestBody(jsonType)).build()
        return http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${txt.take(300)}")
            JSONObject(txt)
        }
    }

    fun models(): List<String> {
        val data = call("/models", null).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it).getString("id") }
    }

    fun chat(model: String, messages: JSONArray, maxTokens: Int, temp: Double, noThink: Boolean = false): String {
        fun body(withKwargs: Boolean): JSONObject {
            val b = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", maxTokens)
                .put("temperature", temp)
                .put("stream", false)
            if (withKwargs) b.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            return b
        }
        val res = if (noThink) {
            try {
                call("/chat/completions", body(true))
            } catch (e: IOException) {
                // alguns modelos não aceitam o parâmetro; tenta de novo sem ele
                if (e.message?.startsWith("HTTP 400") == true || e.message?.startsWith("HTTP 422") == true) {
                    call("/chat/completions", body(false))
                } else throw e
            }
        } else call("/chat/completions", body(false))
        val msg = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        var content = if (msg.isNull("content")) "" else msg.getString("content")
        content = stripThink(content)
        if (content.isBlank()) {
            // modelos de raciocínio podem gastar o orçamento pensando
            val r = when {
                msg.has("reasoning_content") && !msg.isNull("reasoning_content") -> msg.getString("reasoning_content")
                msg.has("reasoning") && !msg.isNull("reasoning") -> msg.getString("reasoning")
                else -> ""
            }
            content = r.trim()
        }
        if (content.isBlank()) throw IOException("resposta vazia de $model")
        return content
    }

    fun embed(model: String, texts: List<String>, query: Boolean): List<FloatArray> {
        fun run(withType: Boolean): List<FloatArray> {
            val body = JSONObject()
                .put("model", model)
                .put("input", JSONArray(texts))
                .put("encoding_format", "float")
            if (withType) {
                body.put("input_type", if (query) "query" else "passage")
                body.put("truncate", "END")
            }
            val data = call("/embeddings", body).getJSONArray("data")
            return (0 until data.length()).map { i ->
                val a = data.getJSONObject(i).getJSONArray("embedding")
                FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }
        }
        return try {
            run(true)
        } catch (e: IOException) {
            val m = e.message.orEmpty()
            if (m.startsWith("HTTP 400") || m.startsWith("HTTP 422")) run(false) else throw e
        }
    }

    private fun stripThink(s: String): String {
        var t = s
        val close = t.lastIndexOf("</think>")
        if (close >= 0) t = t.substring(close + 8)
        return t.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }
}

fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size || a.isEmpty()) return -1f
    var dot = 0f
    var na = 0f
    var nb = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    val d = sqrt(na) * sqrt(nb)
    return if (d == 0f) -1f else dot / d
}

package br.edward.cadernovivo

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0B0D10)
private val Surface = Color(0xFF14171C)
private val Ink = Color(0xFFF4F1EA)
private val Dim = Color(0xFF8A9099)
private val Accent = Color(0xFF76B900)
private val Danger = Color(0xFFFF6B6B)

@Composable
fun CadernoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            background = Bg,
            onBackground = Ink,
            surface = Surface,
            onSurface = Ink,
        ),
        content = content,
    )
}

@Composable
fun App(vm: AppVm) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Bg) {
                listOf("01" to "Caderno", "02" to "Pensar", "03" to "Ajustes").forEachIndexed { i, p ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(p.first, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                        label = { Text(p.second) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent,
                            selectedTextColor = Accent,
                            indicatorColor = Surface,
                            unselectedIconColor = Dim,
                            unselectedTextColor = Dim,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> Caderno(vm)
                1 -> Pensar(vm)
                else -> Ajustes(vm)
            }
        }
    }
}

// ---------- peças comuns ----------

@Composable
private fun Header(title: String, sub: String) {
    Column(Modifier.padding(top = 28.dp, bottom = 12.dp)) {
        Text(
            title,
            fontFamily = FontFamily.Serif,
            fontSize = 38.sp,
            lineHeight = 42.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Text(
            sub.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            color = Accent,
        )
    }
}

@Composable
private fun Status(vm: AppVm) {
    val busy = vm.busy
    val msg = vm.message
    if (busy != null) {
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(busy, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Accent)
        }
    }
    if (msg != null) {
        Text(
            msg,
            color = Danger,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 6.dp).clickable { vm.message = null },
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Color(0xFF2A2F37),
    cursorColor = Accent,
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    focusedPlaceholderColor = Dim,
    unfocusedPlaceholderColor = Dim,
    focusedLabelColor = Accent,
    unfocusedLabelColor = Dim,
)

private fun clean(s: String): String =
    s.replace("**", "").lines().joinToString("\n") { if (it.startsWith("#")) it.trimStart('#', ' ') else it }

// ---------- 01 Caderno ----------

@Composable
private fun Caderno(vm: AppVm) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && t != null) {
            draft = if (draft.isBlank()) t else "$draft $t"
            voice = true
        }
    }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = pendingPath
        if (ok && p != null) {
            vm.addPhoto(File(p), draft)
            draft = ""
        }
        pendingPath = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val f = File(ctx.cacheDir, "gal_${System.currentTimeMillis()}.jpg")
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                vm.addPhoto(f, draft)
                draft = ""
            } catch (e: Exception) {
                vm.message = "Não consegui abrir a imagem: ${e.message}"
            }
        }
    }

    val list: List<Note> = vm.results ?: vm.notes

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Header("Caderno vivo", "memória que entende significado")
        Status(vm)
        OutlinedTextField(
            value = q,
            onValueChange = {
                q = it
                if (it.isBlank()) vm.clearSearch()
            },
            placeholder = { Text("Buscar por significado…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(q) }),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (list.isEmpty()) {
                item {
                    Text(
                        if (vm.results != null) "Nada parecido por aqui." else "Seu caderno está vazio.\nEscreva, fale ou fotografe algo abaixo.",
                        color = Dim,
                        fontFamily = FontFamily.Serif,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(list, key = { it.id }) { n -> NoteCard(n, vm) }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("Escreva, fale ou fotografe…") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                try {
                    speech.launch(i)
                } catch (e: Exception) {
                    vm.message = "Reconhecimento de voz indisponível neste aparelho."
                }
            }) { Text("Voz", color = Accent) }
            TextButton(onClick = {
                try {
                    val f = File(ctx.cacheDir, "cap_${System.currentTimeMillis()}.jpg")
                    pendingPath = f.path
                    cam.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
                } catch (e: Exception) {
                    vm.message = "Câmera indisponível: ${e.message}"
                }
            }) { Text("Foto", color = Accent) }
            TextButton(onClick = { gallery.launch("image/*") }) { Text("Galeria", color = Accent) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    vm.addText(draft, if (voice) "voz" else "texto")
                    draft = ""
                    voice = false
                },
                enabled = draft.isNotBlank() && vm.busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) { Text("Salvar", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun NoteCard(n: Note, vm: AppVm) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val thumb = remember(n.image) {
        n.image?.let { name ->
            runCatching {
                BitmapFactory.decodeFile(
                    File(ctx.filesDir, name).path,
                    BitmapFactory.Options().apply { inSampleSize = 4 },
                )?.asImageBitmap()
            }.getOrNull()
        }
    }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")) }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .clickable { open = !open }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    n.title.ifBlank { "Sem título" },
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Ink,
                )
                Text(
                    n.text,
                    color = Ink.copy(alpha = 0.82f),
                    fontSize = 14.sp,
                    maxLines = if (open) Int.MAX_VALUE else 3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (n.tags.map { "#$it" } + listOf(n.kind, fmt.format(Date(n.createdAt)))).joinToString("  "),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Dim,
                modifier = Modifier.weight(1f),
            )
            if (open) {
                Text("apagar", color = Danger, fontSize = 12.sp, modifier = Modifier.clickable { vm.delete(n) })
            }
        }
    }
}

// ---------- 02 Pensar ----------

@Composable
private fun Pensar(vm: AppVm) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val tts = remember {
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx) { s -> if (s == TextToSpeech.SUCCESS) t?.language = Locale.forLanguageTag("pt-BR") }
        t
    }
    DisposableEffect(Unit) {
        onDispose {
            tts?.stop()
            tts?.shutdown()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Header("Pensar", "pergunte ao que você já sabe")
        Status(vm)
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            placeholder = { Text("O que eu decidi sobre…?") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.ask(q) },
                enabled = q.isNotBlank() && vm.busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) { Text("Perguntar", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = { vm.connect() }, enabled = vm.busy == null) {
                Text("Conectar ideias", color = Accent)
            }
        }
        vm.answer?.let { a ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).padding(16.dp)
            ) {
                Text(
                    a.title,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(clean(a.text), color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { tts?.speak(clean(a.text), TextToSpeech.QUEUE_FLUSH, null, "ans") }) {
                    Text("Ouvir", color = Accent)
                }
                a.sources.forEachIndexed { i, n ->
                    Text(
                        "[${i + 1}] ${n.title.ifBlank { n.text.take(40) }}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Dim,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------- 03 Ajustes ----------

@Composable
private fun Ajustes(vm: AppVm) {
    var key by remember { mutableStateOf(vm.apiKey) }
    var think by remember { mutableStateOf(vm.cfg.think) }
    var fast by remember { mutableStateOf(vm.cfg.fast) }
    var vision by remember { mutableStateOf(vm.cfg.vision) }
    var embed by remember { mutableStateOf(vm.cfg.embed) }

    fun save() {
        vm.saveKey(key)
        vm.applyCfg(Cfg(think.trim(), fast.trim(), vision.trim(), embed.trim()))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Header("Ajustes", "chave e modelos")
        Status(vm)
        Text(
            "Cada modelo tem um papel: o rápido titula e etiqueta, o de visão lê fotos, " +
                "os embeddings dão significado às notas e o grande raciocina sobre tudo.",
            color = Dim,
            fontFamily = FontFamily.Serif,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Chave nvapi- (build.nvidia.com)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        Spacer(Modifier.height(10.dp))
        ModelField("Pensar (raciocínio)", think) { think = it }
        ModelField("Rápido (título e tags)", fast) { fast = it }
        ModelField("Visão (fotos)", vision) { vision = it }
        ModelField("Embeddings (memória)", embed) { embed = it }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { save() },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) { Text("Salvar", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = {
                    save()
                    vm.detect()
                },
                enabled = vm.busy == null,
            ) { Text("Detectar modelos", color = Accent) }
        }
        if (vm.catalogInfo.isNotBlank()) {
            Text(
                vm.catalogInfo,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Dim,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Text(
            "Se o catálogo não listar um modelo, troque o ID acima por outro de build.nvidia.com. " +
                "A chave fica só neste aparelho.",
            color = Dim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun ModelField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = fieldColors(),
    )
}

package com.gquest.questday

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val Ink = Color(0xFF0D1419)
val Panel = Color(0xFF18232B)
val Lime = Color(0xFFB5F36B)
val Muted = Color(0xFF91A2AD)
val Gold = Color(0xFFFFD478)
val Violet = Color(0xFFC6ADFF)

class MainActivity : ComponentActivity() {
    private lateinit var repo: Repository
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri
            ->
            if (uri != null)
                lifecycleScope.launch {
                    runCatching {
                            val s = repo.state.first { it.ready }
                            contentResolver.openOutputStream(uri)?.use {
                                it.write(repo.export(s).toByteArray())
                            } ?: error("Sin acceso al archivo")
                        }
                        .onSuccess { toast("Copia guardada") }
                        .onFailure { toast("No se pudo guardar: ${it.message}") }
                }
        }
    private var pendingImport: String? = null
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                lifecycleScope.launch {
                    runCatching {
                            contentResolver.openInputStream(uri)?.use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                var n = input.read(buffer)
                                while (n != -1) {
                                    require(out.size() + n <= 2_000_000) { "La copia supera 2 MB" }
                                    out.write(buffer, 0, n)
                                    n = input.read(buffer)
                                }
                                out.toString("UTF-8")
                            } ?: error("Sin acceso")
                        }
                        .onSuccess {
                            pendingImport = it
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Restaurar copia")
                                .setMessage(
                                    "Sustituirá tus misiones, progreso y recompensas actuales. ¿Continuar?"
                                )
                                .setNegativeButton("Cancelar") { _, _ -> pendingImport = null }
                                .setPositiveButton("Restaurar") { _, _ ->
                                    lifecycleScope.launch {
                                        runCatching {
                                                repo.restore(pendingImport ?: error("Sin copia"))
                                            }
                                            .onSuccess { toast("Copia restaurada") }
                                            .onFailure { toast("Copia no válida; ${it.message}") }
                                        pendingImport = null
                                    }
                                }
                                .show()
                        }
                        .onFailure { toast("No se puede leer la copia") }
                }
        }
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            lifecycleScope.launch {
                val s = repo.state.first { it.ready }
                repo.profile(s.profile.copy(reminders = ok))
                Reminder.schedule(this@MainActivity, ok, s.profile.hour)
                if (!ok)
                    toast("Notificaciones desactivadas. Puedes permitirlas en Ajustes de Android.")
            }
        }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = Repository(this)
        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Lime,
                        onPrimary = Ink,
                        background = Ink,
                        surface = Panel,
                        onSurface = Color.White,
                        secondary = Violet,
                    )
            ) {
                val state by repo.state.collectAsState(initial = QuestState())
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                fun act(block: suspend () -> Unit) {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                block()
                            } catch (e: Exception) {
                                toast(e.message ?: "No se pudo completar la acción")
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
                Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
                    if (!state.ready)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    else if (state.profile.name.isBlank())
                        Onboarding { n, selected ->
                            act {
                                selected.forEach { repo.dao.put(it) }
                                repo.profile(Profile(name = n))
                            }
                        }
                    else
                        QuestApp(
                            state,
                            busy,
                            onMission = { m -> act { repo.dao.put(m) } },
                            onProgress = { m, a ->
                                act { repo.progress(state, m, a, LocalDate.now()) }
                            },
                            onClaim = { p ->
                                act {
                                    if (repo.claim(state, p))
                                        toast("¡Cofre abierto! Recompensa añadida")
                                }
                            },
                            onBuy = { id ->
                                act {
                                    if (repo.buy(state, id)) toast("Nuevo objeto en tu inventario")
                                    else toast("No tienes monedas suficientes")
                                }
                            },
                            onProfile = { p -> act { repo.profile(p) } },
                            onExport = {
                                exportLauncher.launch("QuestDay-${LocalDate.now()}.json")
                            },
                            onImport = {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/json",
                                        "text/plain",
                                        "application/octet-stream",
                                    )
                                )
                            },
                            onReminders = { enabled, hour ->
                                act {
                                    val permissionNeeded =
                                        enabled &&
                                            Build.VERSION.SDK_INT >= 33 &&
                                            checkSelfPermission(
                                                Manifest.permission.POST_NOTIFICATIONS
                                            ) !=
                                                android.content.pm.PackageManager.PERMISSION_GRANTED
                                    repo.profile(
                                        state.profile.copy(
                                            reminders = enabled && !permissionNeeded,
                                            hour = hour,
                                        )
                                    )
                                    if (permissionNeeded)
                                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else Reminder.schedule(this@MainActivity, enabled, hour)
                                }
                            },
                        )
                }
            }
        }
    }
}

@Composable
fun Onboarding(onStart: (String, List<Mission>) -> Unit) {
    var name by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf(0, 1, 2) }
    val starters =
        listOf(
            "Beber agua" to "💧",
            "Caminar 15 minutos" to "👟",
            "Leer 10 minutos" to "📖",
            "Hacer una pausa" to "🌿",
            "Dormir a tiempo" to "🌙",
        )
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("GQUEST  /  QUESTDAY", color = Lime, letterSpacing = 3.sp, fontSize = 12.sp)
        Landscape(Modifier.fillMaxWidth().height(200.dp), 0, "🧭")
        Text(
            "Tu día.\nTu aventura.",
            fontSize = 40.sp,
            lineHeight = 45.sp,
            fontWeight = FontWeight.Black,
        )
        Text("Pequeños hábitos. Grandes aventuras.", color = Lime)
        Text(
            "Convierte lo que quieres hacer en misiones. Avanza a tu ritmo, sin perder lo que ya has conseguido.",
            color = Muted,
        )
        OutlinedTextField(
            name,
            { name = it.take(30) },
            label = { Text("¿Cómo te llamas, explorador?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("ELIGE TUS PRIMERAS MISIONES", fontSize = 12.sp, color = Muted, letterSpacing = 1.sp)
        starters.forEachIndexed { i, p ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Panel)
                    .clickable { if (i in chosen) chosen.remove(i) else chosen.add(i) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.second, fontSize = 22.sp)
                Text(p.first, Modifier.weight(1f).padding(start = 12.dp))
                Checkbox(i in chosen, { if (it) chosen.add(i) else chosen.remove(i) })
            }
        }
        Button(
            onClick = {
                onStart(
                    name.trim(),
                    chosen.distinct().map {
                        Mission(
                            UUID.randomUUID().toString(),
                            starters[it].first,
                            if (it == 2) "Aprendizaje" else "Bienestar",
                            1,
                            target = if (it == 0) 6 else 1,
                            unit = if (it == 0) "vasos" else "veces",
                        )
                    },
                )
            },
            enabled = name.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Comenzar mi aventura  →")
        }
        Text(
            "Sin registro · Sin anuncios · Tus datos en tu dispositivo",
            fontSize = 11.sp,
            color = Muted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestApp(
    s: QuestState,
    busy: Boolean,
    onMission: (Mission) -> Unit,
    onProgress: (Mission, Int) -> Unit,
    onClaim: (Int) -> Unit,
    onBuy: (String) -> Unit,
    onProfile: (Profile) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReminders: (Boolean, Int) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Mission?>(null) }
    var creating by remember { mutableStateOf(false) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            today = LocalDate.now()
        }
    }
    val pilotState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val labels = listOf("Hoy", "Misiones", "Aventura", "Recompensas", "Progreso", "Piloto")
    val icons =
        listOf(
            Icons.Rounded.WbSunny,
            Icons.Rounded.Checklist,
            Icons.Rounded.Explore,
            Icons.Rounded.Redeem,
            Icons.Rounded.BarChart,
            Icons.Rounded.SportsEsports,
        )
    Scaffold(
        containerColor = Ink,
        topBar = {
            Row(
                Modifier.fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "QUESTDAY",
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp,
                        fontSize = 18.sp,
                    )
                    Text(
                        if (settings) "Tu espacio" else labels[tab],
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
                Pill("● ${s.coins}", Gold)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { settings = !settings }) {
                    Icon(
                        if (settings) Icons.Rounded.Home else Icons.Rounded.Settings,
                        if (settings) "Volver al inicio" else "Ajustes",
                    )
                }
            }
        },
        bottomBar = {
            if (!settings)
                NavigationBar(containerColor = Ink) {
                    labels.forEachIndexed { i, l ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icons[i], l) },
                            label = {
                                Text(
                                    if (i == 3) "Premios" else l,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    selectedIconColor = Ink,
                                    indicatorColor = Lime,
                                    selectedTextColor = Lime,
                                    unselectedIconColor = Muted,
                                    unselectedTextColor = Muted,
                                ),
                        )
                    }
                }
        },
        floatingActionButton = {
            if (!settings && tab in listOf(0, 1))
                FloatingActionButton(
                    onClick = { creating = true },
                    containerColor = Lime,
                    contentColor = Ink,
                ) {
                    Icon(Icons.Rounded.Add, "Nueva misión")
                }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (settings) Settings(s, onProfile, onExport, onImport, onReminders)
            else
                when (tab) {
                    0 -> Today(s, today, busy, onProgress, { editor = it }, { tab = 2 }, onProfile)
                    1 -> Missions(s, today, onProgress, { editor = it }, onMission)
                    2 -> Adventure(s, onClaim)
                    3 -> Rewards(s, onBuy, onProfile)
                    4 -> Progress(s, today)
                    5 -> pilotState.SaveableStateProvider("torch-pilot") { TorchPilot() }
                }
            if (busy)
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = Lime,
                )
        }
    }
    if (creating || editor != null)
        MissionEditor(
            editor,
            onDismiss = {
                creating = false
                editor = null
            },
            onSave = {
                onMission(it)
                creating = false
                editor = null
            },
        )
}

@Composable
fun Pill(text: String, color: Color = Lime) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier =
            Modifier.clip(RoundedCornerShape(20.dp))
                .background(color.copy(alpha = .10f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
fun Eyebrow(text: String) {
    Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
}

@Composable
fun Heading(title: String, description: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black)
        if (description.isNotBlank()) Text(description, color = Muted, fontSize = 13.sp)
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Today(
    s: QuestState,
    date: LocalDate,
    busy: Boolean,
    onProgress: (Mission, Int) -> Unit,
    onEdit: (Mission) -> Unit,
    onAdventure: () -> Unit,
    onProfile: (Profile) -> Unit,
) {
    val ms = s.today(date)
    val done = ms.count { s.done(it, date) }
    val resting = s.rested(date)
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Eyebrow(
                date
                    .format(
                        DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("es"))
                    )
                    .uppercase()
            )
            Spacer(Modifier.height(8.dp))
            Heading(
                "Vamos, ${s.profile.name}.",
                if (resting) "Hoy recargas energía. Tu aventura sigue aquí."
                else "Cada pequeño paso te lleva más lejos.",
            )
        }
        item {
            Box(
                Modifier.clip(RoundedCornerShape(24.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF263C35), Panel)))
                    .clickable(onClick = onAdventure)
            ) {
                Column {
                    Landscape(
                        Modifier.fillMaxWidth().height(160.dp),
                        ((s.points / 15).coerceAtMost(4)),
                        s.profile.avatar,
                    )
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Pill("NIVEL ${s.level}")
                            Text("${s.xp} XP", color = Lime, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "${worlds[(s.points/15).coerceAtMost(4)]}",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        LinearProgressIndicator(
                            progress = { if (s.level == 100) 1f else (s.xp % 200) / 200f },
                            modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                            color = Lime,
                            trackColor = Color.White.copy(alpha = .1f),
                        )
                        Text(
                            if (s.level == 100) "¡Nivel máximo alcanzado!"
                            else "${Rules.nextXp(s.xp)} XP para el siguiente nivel  →",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniStat("$done/${ms.size}", "Hoy", Lime, Modifier.weight(1f))
                MiniStat(
                    "${s.missions.maxOfOrNull{s.streak(it,date)}?:0} días",
                    "Mejor racha",
                    Gold,
                    Modifier.weight(1f),
                )
                MiniStat("${s.points}/75", "Aventura", Violet, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tus misiones",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        onProfile(
                            s.profile.copy(
                                rest =
                                    (s.profile.rest.split(",").filter {
                                            it.isNotBlank() && it != date.toString()
                                        } + if (resting) emptyList() else listOf(date.toString()))
                                        .joinToString(",")
                            )
                        )
                    }
                ) {
                    Text(if (resting) "Reactivar día" else "Descansar hoy", fontSize = 11.sp)
                }
            }
        }
        if (resting)
            item {
                CardBox {
                    Text("🌿  Día de descanso", fontWeight = FontWeight.Bold)
                    Text(
                        "No necesitas completar tus hábitos hoy. Puedes volver a activarlos cuando quieras.",
                        color = Muted,
                    )
                }
            }
        else if (ms.isEmpty())
            item {
                CardBox {
                    Text("Tu próxima aventura empieza aquí", fontWeight = FontWeight.Bold)
                    Text("Pulsa + para crear un hábito, una tarea o un objetivo.", color = Muted)
                }
            }
        else items(ms, key = { it.id }) { m -> MissionCard(m, s, date, !busy, onProgress, onEdit) }
        if (ms.isNotEmpty() && done == ms.size)
            item {
                CardBox {
                    Text("✨  ¡Día completado!", color = Lime, fontWeight = FontWeight.Bold)
                    Text("Has cumplido contigo. Disfruta de tu avance.", color = Muted)
                }
            }
        item {
            Text(
                "SIN PRISA. SIN PERDER TU PROGRESO.",
                fontSize = 10.sp,
                color = Muted,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
fun MiniStat(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(value, color = color, fontSize = 21.sp, fontWeight = FontWeight.Black)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

fun emoji(category: String) =
    when (category) {
        "Salud" -> "💪"
        "Aprendizaje" -> "📖"
        "Trabajo" -> "🎯"
        "Hogar" -> "🏡"
        "Personal" -> "⭐"
        else -> "🌿"
    }

@Composable
fun MissionCard(
    m: Mission,
    s: QuestState,
    date: LocalDate,
    enabled: Boolean,
    onProgress: (Mission, Int) -> Unit,
    onEdit: (Mission) -> Unit,
) {
    val c = s.completion(m, date)
    val done = s.done(m, date)
    val amount = c?.amount ?: 0
    var add by remember { mutableStateOf(false) }
    var delta by remember { mutableStateOf("1") }
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji(m.category), fontSize = 26.sp, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f).clickable { onEdit(m) }) {
                Text(
                    m.title,
                    fontWeight = FontWeight.Bold,
                    color = if (done) Muted else Color.White,
                )
                Text(
                    "${m.category} · ${listOf("","Fácil","Media","Difícil")[m.difficulty]}",
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
            IconButton(
                enabled =
                    enabled &&
                        !(m.kind == "goal" &&
                            done &&
                            s.completions.any {
                                it.missionId == m.id && it.date != date.toString() && it.xp > 0
                            }),
                onClick = {
                    onProgress(m, if (done) 0 else if (m.target > 1) (amount + 1) else m.target)
                },
            ) {
                Icon(
                    if (done) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                    if (done) "Deshacer ${m.title}" else "Avanzar ${m.title}",
                    tint = if (done) Lime else Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                if (done) "✓ ${c?.xp?:0} XP" else "+${Rules.reward(m.difficulty)} XP",
                if (done) Muted else Lime,
            )
            if (m.target > 1) {
                Spacer(Modifier.width(10.dp))
                Text("$amount / ${m.target} ${m.unit}", color = Muted, fontSize = 12.sp)
                if (!done)
                    TextButton(onClick = { add = true }) { Text("+ cantidad", fontSize = 10.sp) }
                if (amount > 0 && !done)
                    TextButton(onClick = { onProgress(m, amount - 1) }) { Text("−") }
            }
            Spacer(Modifier.weight(1f))
            if (m.kind == "habit") Text("🔥 ${s.streak(m,date)}", color = Gold, fontSize = 12.sp)
        }
    }
    if (add)
        AlertDialog(
            onDismissRequest = { add = false },
            title = { Text("Registrar avance") },
            text = {
                OutlinedTextField(
                    delta,
                    { delta = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Cantidad (${m.unit})") },
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onProgress(m, (amount + (delta.toIntOrNull() ?: 0)).coerceAtMost(m.target))
                        add = false
                    },
                    enabled = (delta.toIntOrNull() ?: 0) > 0,
                ) {
                    Text("Añadir")
                }
            },
            dismissButton = { TextButton(onClick = { add = false }) { Text("Cancelar") } },
        )
}

@Composable
fun Missions(
    s: QuestState,
    date: LocalDate,
    onProgress: (Mission, Int) -> Unit,
    onEdit: (Mission) -> Unit,
    onMission: (Mission) -> Unit,
) {
    var filter by remember { mutableStateOf("Activas") }
    val entries = s.missions.filter { if (filter == "Archivadas") it.archived else !it.archived }
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 90.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Heading("Diseña tu camino", "Hábitos, tareas y objetivos con un propósito.")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Activas", "Archivadas").forEach {
                    FilterChip(
                        selected = filter == it,
                        onClick = { filter = it },
                        label = { Text(it) },
                    )
                }
            }
        }
        if (entries.isEmpty())
            item {
                CardBox {
                    Text("Aún no hay misiones aquí")
                    Text("Crea una con + o cambia el filtro.", color = Muted)
                }
            }
        items(entries, key = { it.id }) { m ->
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji(m.category), fontSize = 26.sp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(m.title, fontWeight = FontWeight.Bold)
                        Text(
                            when (m.kind) {
                                "task" -> "Tarea · ${m.date}"
                                "goal" -> "Objetivo · ${m.target} ${m.unit}"
                                else ->
                                    "Hábito · ${m.days.split(",").mapNotNull{it.toIntOrNull()}.joinToString(" "){listOf("L","M","X","J","V","S","D")[it-1]}}"
                            },
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    }
                    IconButton(onClick = { onEdit(m) }) {
                        Icon(Icons.Rounded.Edit, "Editar ${m.title}", tint = Muted)
                    }
                }
                Row {
                    TextButton(onClick = { onEdit(m) }) { Text("Editar") }
                    TextButton(onClick = { onMission(m.copy(archived = !m.archived)) }) {
                        Text(if (m.archived) "Reactivar" else "Archivar", color = Muted)
                    }
                }
            }
        }
    }
}

val worlds =
    listOf(
        "Isla de los Hábitos",
        "Bosque de la Constancia",
        "Ciudad del Equilibrio",
        "Cumbres del Enfoque",
        "Valle del Bienestar",
    )

@Composable
fun Adventure(s: QuestState, onClaim: (Int) -> Unit) {
    var world by remember { mutableIntStateOf((s.points / 15).coerceAtMost(4)) }
    var chest by remember { mutableStateOf<Int?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Eyebrow("CAPÍTULO ${world+1} / 5")
            Spacer(Modifier.height(8.dp))
            Heading(worlds[world], "Cada 100 XP desbloqueas una nueva etapa.")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { world-- }, enabled = world > 0) { Text("← Anterior") }
                OutlinedButton(onClick = { world++ }, enabled = world < 4) { Text("Siguiente →") }
            }
        }
        item {
            Landscape(
                Modifier.fillMaxWidth().height(175.dp).clip(RoundedCornerShape(24.dp)),
                world,
                s.profile.avatar,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("${s.points.coerceIn(world*15,world*15+15)-world*15}/15 etapas")
                Pill("${s.claims.count{it.id.startsWith("point:")}} cofres abiertos", Gold)
            }
        }
        items((1..15).toList()) { local ->
            val p = world * 15 + local
            val unlocked = p <= s.points
            val claimed = s.claims.any { it.id == "point:$p" }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (local % 2 == 0) Arrangement.End else Arrangement.Start,
            ) {
                Row(
                    Modifier.fillMaxWidth(.85f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (unlocked) Panel else Panel.copy(alpha = .5f))
                        .clickable(enabled = unlocked && !claimed) { chest = p }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (claimed) Lime.copy(alpha = .15f)
                                else Color.White.copy(alpha = .05f)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (claimed) "✓" else if (unlocked) "🎁" else "🔒",
                            fontSize = 24.sp,
                            color = Lime,
                        )
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            if (local == 15) "Gran cofre del mundo"
                            else if (local % 5 == 0) "Hito de constancia" else "Etapa $local",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Text(
                            if (claimed) "Recompensa recogida"
                            else if (unlocked) "¡Cofre listo para abrir!"
                            else "${p*100} XP acumulados",
                            fontSize = 11.sp,
                            color = if (unlocked && !claimed) Gold else Muted,
                        )
                    }
                    if (unlocked && !claimed) Badge(containerColor = Gold)
                }
            }
        }
    }
    chest?.let { p ->
        AlertDialog(
            onDismissRequest = { chest = null },
            icon = { Text("🎁", fontSize = 48.sp) },
            title = { Text("Tu esfuerzo tiene recompensa") },
            text = {
                Column {
                    Text(
                        "${if(p%15==0)100 else if(p%5==0)40 else 15} monedas",
                        color = Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    if (p % 5 == 0) Text("${if(p%15==0)5 else 1} cristales", color = Violet)
                    Spacer(Modifier.height(10.dp))
                    Text("Se añaden a tu inventario. Cada cofre se recoge una sola vez.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClaim(p)
                        chest = null
                    }
                ) {
                    Text("Abrir cofre")
                }
            },
            dismissButton = { TextButton(onClick = { chest = null }) { Text("Más tarde") } },
        )
    }
}

@Composable
fun Rewards(s: QuestState, onBuy: (String) -> Unit, onProfile: (Profile) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Heading("Tu toque personal", "Cosméticos que ganas con tu constancia.")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("● ${s.coins} monedas", Gold)
                Pill("◆ ${s.crystals} cristales", Violet)
            }
        }
        item {
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.profile.avatar, fontSize = 54.sp)
                    Column(Modifier.padding(start = 20.dp)) {
                        Text(s.profile.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Explorador · Nivel ${s.level}", color = Muted)
                    }
                }
                TextButton(onClick = { onProfile(s.profile.copy(avatar = "🧭")) }) {
                    Text("Usar brújula original")
                }
            }
        }
        item { Eyebrow("TIENDA DE EXPLORADORES") }
        items(Shop.entries) { (id, icon, cost) ->
            val owned = s.items.any { it.id == id }
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 42.sp)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(
                            when (id) {
                                "fox" -> "Zorro del bosque"
                                "wizard" -> "Guardián de la magia"
                                "astronaut" -> "Explorador estelar"
                                "dragon" -> "Dragón de las cumbres"
                                else -> "Corona de constancia"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (owned) "En tu inventario"
                            else if (Shop.crystalCost(id) > 0) "${Shop.crystalCost(id)} cristales"
                            else "$cost monedas",
                            color = if (owned) Lime else Gold,
                            fontSize = 12.sp,
                        )
                    }
                    Button(
                        onClick = {
                            if (owned) onProfile(s.profile.copy(avatar = icon)) else onBuy(id)
                        },
                        enabled = owned || (s.coins >= cost && s.crystals >= Shop.crystalCost(id)),
                    ) {
                        Text(
                            if (s.profile.avatar == icon) "En uso"
                            else if (owned) "Usar" else "Obtener",
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        item {
            CardBox {
                Text("◆  Tus cristales", color = Violet, fontWeight = FontWeight.Bold)
                Text(
                    "Los ganas al llegar a los hitos de tu aventura. Úsalos para conseguir el Dragón de las cumbres, sin compras ni azar monetizado.",
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
fun Progress(s: QuestState, today: LocalDate) {
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val total = s.completions.count { it.xp > 0 }
    val thisWeek = s.completions.filter { it.date in week.map { it.toString() } }.sumOf { it.xp }
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Heading("Mira cuánto has avanzado", "Tu progreso se construye paso a paso.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniStat("${s.xp}", "XP acumulados", Lime, Modifier.weight(1f))
                MiniStat("$total", "Misiones cumplidas", Gold, Modifier.weight(1f))
            }
        }
        item {
            CardBox {
                Text("Tu semana", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("$thisWeek XP en los últimos 7 días", color = Muted, fontSize = 12.sp)
                Row(
                    Modifier.fillMaxWidth().height(150.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    week.forEach { d ->
                        val xp = s.completions.filter { it.date == d.toString() }.sumOf { it.xp }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("$xp", fontSize = 10.sp, color = Muted)
                            Box(
                                Modifier.width(23.dp)
                                    .height((8 + xp / 600f * 100).dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (d == today) Lime else Lime.copy(alpha = .35f))
                            )
                            Text(
                                d.format(
                                        DateTimeFormatter.ofPattern(
                                            "EE",
                                            Locale.forLanguageTag("es"),
                                        )
                                    )
                                    .take(2),
                                fontSize = 10.sp,
                                color = Muted,
                            )
                        }
                    }
                }
            }
        }
        item {
            CardBox {
                Text("Últimos 28 días", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                (3 downTo 0).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (6 downTo 0).forEach { col ->
                            val d = today.minusDays((row * 7 + col).toLong())
                            val count = s.completions.count { it.date == d.toString() && it.xp > 0 }
                            Box(
                                Modifier.weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(
                                        if (count == 0) Color.White.copy(alpha = .04f)
                                        else
                                            Lime.copy(
                                                alpha = (.25f + count * .13f).coerceAtMost(1f)
                                            )
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${d.dayOfMonth}",
                                    color = if (count > 0) Ink else Muted,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }
                Text("Más color = más misiones completadas", color = Muted, fontSize = 11.sp)
            }
        }
        item { Eyebrow("CONSTANCIA POR HÁBITO") }
        items(s.missions.filter { it.kind == "habit" }) { m ->
            CardBox {
                Row {
                    Text(m.title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("🔥 ${s.streak(m,today)} días", color = Gold)
                }
                Text(
                    "${s.completions.count{it.missionId==m.id&&it.amount>=m.target}} días cumplidos · ${s.completions.filter{it.missionId==m.id}.sumOf{it.xp}} XP",
                    fontSize = 12.sp,
                    color = Muted,
                )
            }
        }
        item {
            CardBox {
                Text("Así funciona tu progreso", fontWeight = FontWeight.Bold)
                Text(
                    "Fácil: 20 XP · Media: 40 XP · Difícil: 60 XP\nMáximo: 600 XP diarios · 200 XP por nivel\nLos días sin completar no restan XP. Las rachas cuentan solo los días programados; descansar hoy protege la continuidad.",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
fun Settings(
    s: QuestState,
    onProfile: (Profile) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReminders: (Boolean, Int) -> Unit,
) {
    var name by remember(s.profile.name) { mutableStateOf(s.profile.name) }
    var premium by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Heading("A tu manera", "QuestDay 0.2.0 · Piloto de La última antorcha") }
        item {
            CardBox {
                Text("Tu perfil", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    name,
                    { name = it.take(30) },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onProfile(s.profile.copy(name = name.trim())) },
                    enabled = name.trim().isNotEmpty() && name.trim() != s.profile.name,
                ) {
                    Text("Guardar nombre")
                }
            }
        }
        item {
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Recordatorio diario", fontWeight = FontWeight.Bold)
                        Text("Una invitación, sin presión", fontSize = 12.sp, color = Muted)
                    }
                    Switch(s.profile.reminders, { onReminders(it, s.profile.hour) })
                }
                Text(
                    "Hora aproximada: ${s.profile.hour.toString().padStart(2,'0')}:00",
                    color = Muted,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(8, 12, 17, 19, 21).forEach { h ->
                        FilterChip(
                            s.profile.hour == h,
                            { onReminders(s.profile.reminders, h) },
                            label = { Text("$h:00") },
                        )
                    }
                }
                Text(
                    "Android puede retrasarlo por ahorro de batería.",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }
        item {
            CardBox {
                Text("Copia de seguridad", fontWeight = FontWeight.Bold)
                Text(
                    "Guarda tus misiones, inventario y progreso en un archivo. Restaurar sustituye los datos actuales.",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onExport) { Text("Exportar") }
                    OutlinedButton(onClick = onImport) { Text("Restaurar") }
                }
            }
        }
        item {
            CardBox {
                Text("QuestDay Premium", fontWeight = FontWeight.Bold, color = Violet)
                Text(
                    "La edición de prueba permite explorar las funciones locales sin límites de misiones. Las compras todavía no están habilitadas.",
                    color = Muted,
                    fontSize = 13.sp,
                )
                TextButton(onClick = { premium = true }) { Text("Estado de Premium") }
            }
        }
        item {
            CardBox {
                Text("Privacidad y datos", fontWeight = FontWeight.Bold)
                Text(
                    "Responsable: GQUEST\nContacto: gquesteos@gmail.com\n\nLos datos de esta versión se guardan en tu dispositivo. No hay cuenta, publicidad, analítica ni conexión a servidores. Las copias se guardan únicamente en el destino que elijas. Al desinstalar se borran los datos locales; conserva una copia si quieres recuperarlos.",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }
        item {
            Text(
                "GQUEST  /  Pequeños hábitos. Grandes aventuras.",
                color = Muted,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (premium)
        AlertDialog(
            onDismissRequest = { premium = false },
            title = { Text("Premium aún no disponible") },
            text = {
                Text(
                    "Esta versión no realiza cobros. La compra y restauración de Premium se conectarán cuando exista un producto de QuestDay configurado en Google Play."
                )
            },
            confirmButton = { TextButton(onClick = { premium = false }) { Text("Entendido") } },
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionEditor(original: Mission?, onDismiss: () -> Unit, onSave: (Mission) -> Unit) {
    var title by remember { mutableStateOf(original?.title ?: "") }
    var category by remember { mutableStateOf(original?.category ?: "Bienestar") }
    var kind by remember { mutableStateOf(original?.kind ?: "habit") }
    var diff by remember { mutableIntStateOf(original?.difficulty ?: 1) }
    var target by remember { mutableStateOf((original?.target ?: 1).toString()) }
    var unit by remember { mutableStateOf(original?.unit ?: "veces") }
    var date by remember {
        mutableStateOf(original?.date?.ifBlank { null } ?: LocalDate.now().toString())
    }
    val days = remember {
        mutableStateListOf<Int>().apply {
            addAll(
                original?.days?.split(",")?.mapNotNull { it.toIntOrNull() }
                    ?: listOf(1, 2, 3, 4, 5, 6, 7)
            )
        }
    }
    val number = target.toIntOrNull()
    val validDate = runCatching { LocalDate.parse(date) }.getOrNull()
    val valid =
        title.trim().isNotEmpty() &&
            number != null &&
            number in 1..10000 &&
            unit.trim().isNotBlank() &&
            (kind != "habit" || days.isNotEmpty()) &&
            (kind == "habit" || validDate != null)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (original == null) "Nueva misión" else "Editar misión",
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                title,
                { title = it.take(80) },
                label = { Text("¿Qué quieres hacer?") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("habit" to "Hábito", "task" to "Tarea", "goal" to "Objetivo").forEach {
                    (id, label) ->
                    FilterChip(kind == id, { kind = id }, label = { Text(label) })
                }
            }
            Eyebrow("CATEGORÍA")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Bienestar", "Salud", "Aprendizaje", "Trabajo", "Hogar", "Personal")
                    .forEach { c ->
                        FilterChip(category == c, { category = c }, label = { Text(c) })
                    }
            }
            Eyebrow("DIFICULTAD")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..3).forEach { d ->
                    FilterChip(
                        diff == d,
                        { diff = d },
                        label = {
                            Text(
                                "${listOf("","Fácil","Media","Difícil")[d]} ${d*20} XP",
                                fontSize = 11.sp,
                            )
                        },
                    )
                }
            }
            if (kind == "habit") {
                Eyebrow("DÍAS DE LA SEMANA")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    (1..7).forEach { d ->
                        Box(
                            Modifier.size(35.dp)
                                .clip(CircleShape)
                                .background(if (d in days) Lime else Panel)
                                .clickable { if (d in days) days.remove(d) else days.add(d) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                listOf("L", "M", "X", "J", "V", "S", "D")[d - 1],
                                color = if (d in days) Ink else Muted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            } else
                OutlinedTextField(
                    date,
                    { date = it.take(10) },
                    label = {
                        Text(
                            if (kind == "task") "Fecha de la tarea (AAAA-MM-DD)"
                            else "Fecha límite (AAAA-MM-DD)"
                        )
                    },
                    supportingText = { if (validDate == null) Text("Introduce una fecha válida") },
                    isError = validDate == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    target,
                    { target = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Meta") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                )
                OutlinedTextField(
                    unit,
                    { unit = it.take(20) },
                    label = { Text("Unidad") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Text(
                if (kind == "goal") "El objetivo acumula avances entre días hasta llegar a la meta."
                else "Se completa al alcanzar la meta del día. Para marcarla de una vez, usa 1.",
                color = Muted,
                fontSize = 12.sp,
            )
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        Mission(
                            original?.id ?: UUID.randomUUID().toString(),
                            title.trim(),
                            category,
                            diff,
                            kind,
                            days.sorted().joinToString(","),
                            if (kind == "habit") "" else date,
                            number ?: 1,
                            unit.trim(),
                            original?.archived ?: false,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Guardar misión")
            }
        }
    }
}

@Composable
fun Landscape(modifier: Modifier, world: Int, avatar: String) {
    val palettes =
        listOf(
            listOf(Color(0xFF172E39), Color(0xFF335D4F)),
            listOf(Color(0xFF122D29), Color(0xFF234A36)),
            listOf(Color(0xFF29283F), Color(0xFF4C3B65)),
            listOf(Color(0xFF1C2C48), Color(0xFF45556B)),
            listOf(Color(0xFF362B39), Color(0xFF625440)),
        )
    Box(modifier.background(Brush.verticalGradient(palettes[world.coerceIn(0, 4)]))) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            (0..20).forEach { i ->
                drawCircle(
                    Color.White.copy(alpha = .3f),
                    if (i % 3 == 0) 2.2f else 1.2f,
                    Offset((i * 137f) % w, ((i * 31f) % (h * .55f))),
                )
            }
            drawCircle(Color(0xFFFFDF9B).copy(alpha = .08f), h * .28f, Offset(w * .78f, h * .25f))
            drawCircle(Color(0xFFFFDF9B), h * .10f, Offset(w * .78f, h * .25f))
            val back =
                Path().apply {
                    moveTo(0f, h * .75f)
                    lineTo(w * .16f, h * .25f)
                    lineTo(w * .35f, h * .65f)
                    lineTo(w * .53f, h * .18f)
                    lineTo(w * .8f, h * .68f)
                    lineTo(w, h * .33f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
            drawPath(back, Color(0xFF366065).copy(alpha = .7f))
            val front =
                Path().apply {
                    moveTo(0f, h * .77f)
                    cubicTo(w * .25f, h * .48f, w * .4f, h * .65f, w * .6f, h * .8f)
                    cubicTo(w * .8f, h * .6f, w * .9f, h * .63f, w, h * .7f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
            drawPath(front, Color(0xFF182F30))
            val road =
                Path().apply {
                    moveTo(w * .43f, h)
                    cubicTo(w * .2f, h * .75f, w * .75f, h * .83f, w * .55f, h * .59f)
                }
            drawPath(
                road,
                Gold.copy(alpha = .55f),
                style = Stroke(7f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))),
            )
            (0..8).forEach { i ->
                val x = (i * w / 8)
                val y = h * (.7f + (i % 3) * .06f)
                val tree =
                    Path().apply {
                        moveTo(x, y - h * .21f)
                        lineTo(x - h * .065f, y)
                        lineTo(x + h * .065f, y)
                        close()
                    }
                drawPath(tree, if (world == 1) Color(0xFF487D58) else Color(0xFF2B5147))
            }
        }
        Text(
            avatar,
            fontSize = 47.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        )
        Text(
            "✦",
            fontSize = 20.sp,
            color = Lime,
            modifier = Modifier.align(Alignment.Center).padding(start = 80.dp, bottom = 18.dp),
        )
    }
}

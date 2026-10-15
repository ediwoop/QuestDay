package com.gquest.questday

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

private val Ember = Color(0xFFFFBE69)
private val TorchSaver =
    listSaver<TorchRun, Any>(
        save = {
            listOf(it.level, it.position, it.light, it.moves, it.collected, it.doors, it.keys)
        },
        restore = {
            TorchRun(
                it[0] as Int,
                it[1] as Int,
                it[2] as Int,
                it[3] as Int,
                it[4] as Long,
                it[5] as Long,
                it[6] as Int,
            )
        },
    )

@Composable
fun TorchPilot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("torch-pilot", 0) }
    var run by
        rememberSaveable(stateSaver = TorchSaver) {
            mutableStateOf(TorchEngine.fresh(prefs.getInt("selected", 0).coerceIn(0, 9)))
        }
    var message by rememberSaveable {
        mutableStateOf(
            "Recoge la reliquia y llega a la salida. Toca una casilla contigua para moverte."
        )
    }
    var showLevels by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(!prefs.getBoolean("tutorialSeen", false)) }
    var resetStats by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", false)) }
    var revision by remember { mutableIntStateOf(0) }
    var hint by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 35) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    val level = TorchEngine.levels[run.level]
    val won = TorchEngine.won(run)
    val lost = TorchEngine.lost(run)
    val resultView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(won, lost) { if (won || lost) resultView.bringIntoView() }
    val best = remember(revision) { (0..9).map { prefs.getInt("stars:$it", 0) } }
    val cleared = best.count { it > 0 }
    val totalStars = best.sum()
    val record = remember(revision, run.level) { prefs.getInt("moves:${run.level}", Int.MAX_VALUE) }
    fun start(index: Int) {
        run = TorchEngine.fresh(index)
        hint = false
        message = TorchEngine.levels[index].hint
        prefs.edit().putInt("selected", index).apply()
        showLevels = false
    }
    fun move(to: Int) {
        val result = TorchEngine.move(run, to)
        run = result.run
        message = result.message
        if (result.changed) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (TorchEngine.won(run)) {
                val old = prefs.getInt("stars:${run.level}", 0)
                val stars = TorchEngine.stars(run)
                val oldMoves = prefs.getInt("moves:${run.level}", Int.MAX_VALUE)
                prefs
                    .edit()
                    .putInt("stars:${run.level}", maxOf(old, stars))
                    .putInt("moves:${run.level}", minOf(oldMoves, run.moves))
                    .apply()
                revision++
                if (sound) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            } else if (sound && result.message.startsWith("¡"))
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
        }
    }
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.LocalFireDepartment,
                null,
                tint = Ember,
                modifier = Modifier.size(30.dp),
            )
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Eyebrow("LABORATORIO · INTENTOS ILIMITADOS")
                Text("La última antorcha", fontSize = 23.sp, fontWeight = FontWeight.Black)
            }
            IconButton(onClick = { help = true }) {
                Icon(Icons.Rounded.HelpOutline, "Cómo jugar", tint = Muted)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${(run.level+1).toString().padStart(2,'0')} / 10  ·  ${level.title}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Text(
                    "${run.moves} pasos · Ruta experta: ${level.par}" +
                        if (record != Int.MAX_VALUE) " · Mejor: $record" else "",
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
            TextButton(onClick = { showLevels = true }) { Text("Niveles", color = Ember) }
        }
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Ember.copy(alpha = .07f))
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.LocalFireDepartment,
                    null,
                    tint = if (run.light <= 3) Color(0xFFFF8E78) else Ember,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    " ${run.light} de luz",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = Ember,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (TorchEngine.relic(run)) "◆ Reliquia ✓" else "◇ Falta reliquia",
                    color = if (TorchEngine.relic(run)) Lime else Muted,
                    fontSize = 11.sp,
                )
            }
            LinearProgressIndicator(
                progress = { (run.light.toFloat() / level.light).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = Ember,
                trackColor = Ember.copy(alpha = .12f),
            )
        }
        TorchBoard(run, ::move)
        if (won || lost) {
            Column(
                Modifier.fillMaxWidth()
                    .bringIntoViewRequester(resultView)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (won) Lime.copy(alpha = .1f) else Color(0xFF3B2629))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (won) "¡De vuelta al campamento!" else "La luz se ha apagado",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    color = if (won) Lime else Color(0xFFFFADA0),
                )
                if (won) {
                    val stars = TorchEngine.stars(run)
                    Text(
                        (1..3).joinToString(" ") { if (it <= stars) "★" else "☆" },
                        fontSize = 36.sp,
                        color = Gold,
                    )
                    Text(
                        if (stars == 3) "Reliquia, tesoro y ruta experta. ¡Expedición perfecta!"
                        else if (!TorchEngine.allTreasure(run))
                            "Has salvado la reliquia. ¿Puedes volver recogiendo también el cofre?"
                        else
                            "Has recuperado todo. Intenta salir en ${level.par} pasos para conseguir tres estrellas.",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { start(run.level) },
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = Panel,
                                    contentColor = Color.White,
                                ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Repetir")
                        }
                        Button(
                            onClick = {
                                if (run.level < 9) start(run.level + 1) else showLevels = true
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (run.level < 9) "Siguiente →" else "Ver niveles",
                                fontSize = 12.sp,
                            )
                        }
                    }
                } else {
                    Text(
                        "Cada intento empieza igual. Puedes estudiar el tablero sin que se consuma luz.",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                    Button(
                        onClick = { start(run.level) },
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink),
                    ) {
                        Text("Probar otra ruta")
                    }
                }
            }
        } else {
            Text(
                message,
                color = if (message.startsWith("¡")) Ember else Muted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { start(run.level) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(17.dp))
                    Text(" Reiniciar", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { hint = !hint }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Lightbulb, null, Modifier.size(17.dp))
                    Text(" Pista", fontSize = 12.sp)
                }
            }
            if (hint)
                Text(
                    level.hint,
                    color = Ember,
                    fontSize = 13.sp,
                    modifier =
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(Ember.copy(alpha = .08f))
                            .padding(12.dp),
                )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Legend("◆", "Reliquia", Lime)
            Legend("▣", "Cofre", Gold)
            Legend("⚿", "Llave", Gold)
            Legend("♨", "+5 luz", Ember)
            Legend("↗", "Salida", Violet)
        }
        PilotCamp(cleared, totalStars)
        Text(
            "Modo de prueba: todos los niveles abiertos. Repetir no consume tareas ni modifica el XP de QuestDay.",
            fontSize = 11.sp,
            color = Muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                sound,
                {
                    sound = it
                    prefs.edit().putBoolean("sound", it).apply()
                },
            )
            Text("Sonidos", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { resetStats = true }) {
                Text("Borrar marcas", color = Muted, fontSize = 11.sp)
            }
        }
    }
    if (help)
        AlertDialog(
            onDismissRequest = {
                help = false
                prefs.edit().putBoolean("tutorialSeen", true).apply()
            },
            icon = {
                Icon(
                    Icons.Rounded.LocalFireDepartment,
                    null,
                    tint = Ember,
                    modifier = Modifier.size(42.dp),
                )
            },
            title = { Text("Una reliquia. Una salida. Poca luz.") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "1. Toca una casilla contigua para moverte. Cada paso consume 1 de luz. Pensar no consume nada."
                    )
                    Text(
                        "2. Recoge la reliquia verde ◆ y llega a la salida violeta. Puedes ganar usando tu último punto de luz."
                    )
                    Text(
                        "3. El cofre es opcional. Las llaves abren puertas; los braseros dan +5 de luz una sola vez."
                    )
                    Text(
                        "★ Escapa con la reliquia.\n★ Recoge también el cofre, si lo hay.\n★ Sal en el número de pasos de la ruta experta.",
                        color = Gold,
                    )
                    Text(
                        "Puedes reiniciar y repetir todas las veces que quieras.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        help = false
                        prefs.edit().putBoolean("tutorialSeen", true).apply()
                    }
                ) {
                    Text("¡A explorar!")
                }
            },
        )
    if (showLevels)
        AlertDialog(
            onDismissRequest = { showLevels = false },
            title = { Text("Elige tu expedición") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    TorchEngine.levels.forEachIndexed { i, l ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (i == run.level) Ember.copy(alpha = .1f) else Panel)
                                .clickable { start(i) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${i+1}",
                                color = Ember,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.width(26.dp),
                            )
                            Text(l.title, Modifier.weight(1f), fontSize = 12.sp)
                            Text(
                                if (best[i] == 0) "☆☆☆"
                                else (1..3).joinToString("") { if (it <= best[i]) "★" else "☆" },
                                color = Gold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLevels = false }) { Text("Volver") } },
        )
    if (resetStats)
        AlertDialog(
            onDismissRequest = { resetStats = false },
            title = { Text("¿Empezar el piloto desde cero?") },
            text = {
                Text(
                    "Se borrarán las estrellas y mejores marcas de este juego. Tus misiones y el progreso de QuestDay se conservan."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.edit().clear().putBoolean("tutorialSeen", true).apply()
                        revision++
                        sound = false
                        start(0)
                        resetStats = false
                    }
                ) {
                    Text("Borrar marcas")
                }
            },
            dismissButton = { TextButton(onClick = { resetStats = false }) { Text("Cancelar") } },
        )
}

@Composable
private fun Legend(symbol: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(symbol, color = color, fontWeight = FontWeight.Bold)
        Text(" $label", fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun TorchBoard(run: TorchRun, onMove: (Int) -> Unit) {
    val level = TorchEngine.levels[run.level]
    val finished = TorchEngine.won(run) || TorchEngine.lost(run)
    Box(Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)) {
        Column(
            Modifier.widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.radialGradient(listOf(Color(0xFF33403E), Color(0xFF141D24))))
                .border(1.dp, Ember.copy(alpha = .17f), RoundedCornerShape(22.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(5) { y ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(5) { x ->
                        val p = y * 5 + x
                        val tile = level.tiles[p]
                        val collected = run.collected and (1L shl p) != 0L
                        val open = run.doors and (1L shl p) != 0L
                        val player = run.position == p
                        val adjacent =
                            kotlin.math.abs(x - run.position % 5) +
                                kotlin.math.abs(y - run.position / 5) == 1 && !finished
                        val walkable = tile != '#' && (tile != 'D' || open || run.keys > 0)
                        val floor by
                            animateColorAsState(
                                if (player) Color(0xFF72563B)
                                else if (tile == '#') Color(0xFF0D151B)
                                else if (adjacent && walkable) Color(0xFF3D4E49)
                                else Color(0xFF26332F),
                                label = "floor",
                            )
                        val scale by
                            animateFloatAsState(if (player) 1.04f else 1f, label = "explorer")
                        val description =
                            when (tile) {
                                '#' -> "Muro"
                                'S' -> "Entrada"
                                'E' -> "Salida"
                                'R' -> if (collected) "Reliquia recogida" else "Reliquia"
                                'C' -> if (collected) "Cofre recogido" else "Cofre opcional"
                                'K' -> if (collected) "Llave recogida" else "Llave"
                                'D' -> if (open) "Puerta abierta" else "Puerta cerrada"
                                'T' ->
                                    if (collected) "Brasero gastado"
                                    else "Brasero, recupera cinco de luz"
                                else -> "Suelo"
                            }
                        Box(
                            Modifier.weight(1f)
                                .aspectRatio(1f)
                                .scale(scale)
                                .clip(RoundedCornerShape(10.dp))
                                .background(floor)
                                .border(
                                    if (player) 2.dp else 1.dp,
                                    if (player) Ember
                                    else if (adjacent && walkable) Ember.copy(alpha = .35f)
                                    else Color.White.copy(alpha = .035f),
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable(enabled = !finished) { onMove(p) }
                                .semantics {
                                    contentDescription =
                                        "Fila ${y+1}, columna ${x+1}. ${if(player)"Explorador. "else ""}$description"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (tile == '#') {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawLine(
                                        Color.White.copy(alpha = .04f),
                                        Offset(0f, size.height * .35f),
                                        Offset(size.width, size.height * .35f),
                                        2f,
                                    )
                                    drawLine(
                                        Color.White.copy(alpha = .04f),
                                        Offset(size.width * .4f, 0f),
                                        Offset(size.width * .4f, size.height * .35f),
                                        2f,
                                    )
                                }
                            } else if (player) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(
                                            Brush.radialGradient(
                                                listOf(Ember.copy(alpha = .3f), Color.Transparent)
                                            )
                                        )
                                )
                                Icon(
                                    Icons.Rounded.Person,
                                    null,
                                    tint = Color(0xFFFFEAC9),
                                    modifier = Modifier.fillMaxSize(.68f),
                                )
                                Icon(
                                    Icons.Rounded.LocalFireDepartment,
                                    null,
                                    tint = Ember,
                                    modifier =
                                        Modifier.size(15.dp)
                                            .align(Alignment.BottomEnd)
                                            .padding(1.dp),
                                )
                            } else if (
                                !collected ||
                                    tile == 'T' ||
                                    tile == 'D' ||
                                    tile == 'S' ||
                                    tile == 'E'
                            ) {
                                val icon =
                                    when (tile) {
                                        'R' -> Icons.Rounded.Diamond
                                        'C' -> Icons.Rounded.Inventory2
                                        'K' -> Icons.Rounded.VpnKey
                                        'D' ->
                                            if (open) Icons.Rounded.LockOpen else Icons.Rounded.Lock
                                        'T' -> Icons.Rounded.LocalFireDepartment
                                        'S' -> Icons.Rounded.Home
                                        'E' -> Icons.Rounded.ExitToApp
                                        else -> null
                                    }
                                val color =
                                    when (tile) {
                                        'R' -> Lime
                                        'C',
                                        'K' -> Gold
                                        'D' -> if (open) Muted else Gold
                                        'T' -> if (collected) Muted.copy(alpha = .35f) else Ember
                                        'E' -> Violet
                                        else -> Muted.copy(alpha = .3f)
                                    }
                                if (icon != null)
                                    Icon(
                                        icon,
                                        null,
                                        tint = color,
                                        modifier =
                                            Modifier.fillMaxSize(if (tile == 'S') .4f else .55f),
                                    )
                            } else Text("·", color = Ember.copy(alpha = .45f), fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PilotCamp(cleared: Int, stars: Int) {
    val progress =
        when {
            cleared >= 10 -> "Campamento completo"
            cleared >= 7 -> "Torre de exploración"
            cleared >= 4 -> "Puente restaurado"
            cleared >= 1 -> "Refugio encendido"
            else -> "Un campamento por recuperar"
        }
    CardBox {
        Row {
            Eyebrow("TU CAMPAMENTO")
            Spacer(Modifier.weight(1f))
            Text("$cleared/10 ◆  ·  $stars/30 ★", color = Gold, fontSize = 11.sp)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(
                    1 to Icons.Rounded.LocalFireDepartment,
                    4 to Icons.Rounded.Home,
                    7 to Icons.Rounded.Castle,
                    10 to Icons.Rounded.EmojiFlags,
                )
                .forEach { (needed, icon) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            icon,
                            null,
                            tint = if (cleared >= needed) Ember else Muted.copy(alpha = .2f),
                            modifier = Modifier.size(if (needed == 7) 40.dp else 30.dp),
                        )
                        Text(
                            "$needed ◆",
                            color = if (cleared >= needed) Ember else Muted,
                            fontSize = 10.sp,
                        )
                    }
                }
        }
        Text(progress, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            when {
                cleared >= 10 ->
                    "¡Has recuperado las diez reliquias! Puedes perfeccionar tus rutas y conseguir las 30 estrellas."
                cleared >= 7 -> "Recupera las diez reliquias para completar el campamento."
                cleared >= 4 -> "Con siete reliquias levantarás la torre."
                cleared >= 1 -> "Con cuatro reliquias restaurarás el puente del campamento."
                else ->
                    "Tu primera reliquia encenderá el refugio. Cada nivel superado aporta una reliquia nueva."
            },
            fontSize = 11.sp,
            color = Muted,
        )
    }
}

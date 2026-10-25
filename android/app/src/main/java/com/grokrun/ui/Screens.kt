@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.grokrun.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.grokrun.GrokApplication
import com.grokrun.data.AppPrefs
import com.grokrun.data.PointEntity
import com.grokrun.data.PresetEntity
import com.grokrun.data.WhistleId
import com.grokrun.data.WhistleMoment
import com.grokrun.data.WorkoutEntity
import com.grokrun.domain.IntervalConfig
import com.grokrun.domain.PhaseKind
import com.grokrun.domain.RunStatus
import com.grokrun.domain.buildIntervalPhases
import com.grokrun.domain.totalSeconds
import com.grokrun.service.Live
import com.grokrun.service.WorkoutService
import com.grokrun.service.Whistles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Calendar
import java.util.UUID
import kotlin.math.roundToInt

// =====================================================================================
// Dados
// =====================================================================================

data class PhaseRow(
    val kind: PhaseKind, val label: String, val cycle: Int?, val sprint: Int?,
    val durationSec: Double, val distanceM: Double, val paceSec: Double?, val speedKmh: Double?,
)

data class SplitRow(val km: Int, val splitSec: Double)

fun parsePhases(json: String): List<PhaseRow> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        PhaseRow(
            kind = runCatching { PhaseKind.valueOf(o.getString("kind")) }.getOrDefault(PhaseKind.RUN),
            label = o.optString("label"),
            cycle = if (o.isNull("cycle") || !o.has("cycle")) null else o.getInt("cycle"),
            sprint = if (o.isNull("sprint") || !o.has("sprint")) null else o.getInt("sprint"),
            durationSec = o.optDouble("duration", 0.0),
            distanceM = o.optDouble("distance", 0.0),
            paceSec = if (o.isNull("pace") || !o.has("pace")) null else o.optDouble("pace").takeIf { it.isFinite() },
            speedKmh = o.optDouble("speed").takeIf { it.isFinite() },
        )
    }
}.getOrDefault(emptyList())

fun parseSplits(json: String): List<SplitRow> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i -> arr.getJSONObject(i).let { SplitRow(it.getInt("km"), it.getDouble("split")) } }
}.getOrDefault(emptyList())

private fun modeName(mode: String) = if (mode == "tiros") "Tiros" else "Corrida"

@Composable
private fun dbApp(): GrokApplication = LocalContext.current.applicationContext as GrokApplication

// =====================================================================================
// Modo
// =====================================================================================

@Composable
fun ModeScreen(onBack: () -> Unit, onPick: (String) -> Unit) {
    val m = MascotPrefs.current
    MeadowBackground {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BackBar("Modo", onBack)
            ModeCard("Corrida", "O grupo atravessa junto, no ritmo da faixa.", { onPick("corrida") }) { GroupRunAnimation() }
            ModeCard("Tiros", "Um só, bem mais rápido, de um lado ao outro.", { onPick("tiros") }) { SprintAnimation(m) }
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun ModeCard(title: String, text: String, onClick: () -> Unit, art: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 4.dp).grokCard().clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick)) {
        Column {
            art()
            Column(Modifier.padding(12.dp)) {
                DisplayText(title, 26.sp)
                Hint(text)
            }
        }
    }
}

// =====================================================================================
// Montar treino (setup-screen.tsx)
// =====================================================================================

private fun sortedPresets(all: List<PresetEntity>, mode: String): List<PresetEntity> {
    val order = GrokApplication.BUILTIN_ORDER
    return all.filter { it.mode == mode }.sortedWith(compareBy({ order.indexOf(it.id).let { i -> if (i < 0) 99 else i } }, { it.name }))
}

@Composable
private fun PresetChips(presets: List<PresetEntity>, onUse: (PresetEntity) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { p -> Chip(p.name, false, { onUse(p) }) }
    }
}

@Composable
private fun SavePresetRow(onSave: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val t = LocalGrokTheme.current
    if (naming) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                textStyle = TextStyle(color = t.ink, fontSize = 16.sp, fontFamily = Nunito), cursorBrush = SolidColor(t.ink),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(t.cream).border(3.dp, t.ink, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner -> Box { if (name.isEmpty()) Hint("Nome do preset", alpha = 0.45f, size = 16.sp); inner() } },
            )
            GrokButton("Salvar", { if (name.isNotBlank()) { onSave(name.trim()); name = ""; naming = false } }, kind = BtnKind.INK, size = 18.sp, height = 48.dp)
        }
    } else {
        GrokButton("Salvar preset", { naming = true }, Modifier.fillMaxWidth(), BtnKind.CREAM, 18.sp, 46.dp)
    }
}

private fun presetToast(ctx: Context, p: PresetEntity) =
    android.widget.Toast.makeText(ctx, "Preset “${p.name}” carregado", android.widget.Toast.LENGTH_SHORT).show()

/** Resumo de uma linha do preset (o que ele carrega). */
private fun presetSummary(p: PresetEntity): String = try {
    val j = JSONObject(p.configJson)
    if (p.mode == "corrida") {
        "Pace ${formatClock(j.optInt("fast", 360))}–${formatClock(j.optInt("slow", 390))} /km · janela ${j.optInt("window", 20)} s"
    } else {
        val cfg = IntervalConfig(
            j.optInt("warmup", 300), j.optInt("sprint", 60), j.optInt("rest", 60), j.optInt("sprints", 4).coerceAtLeast(1),
            j.optInt("cycles", 1).coerceAtLeast(1), j.optInt("cycleRest", 0), j.optInt("cooldown", 180),
        )
        val cyc = if (cfg.cycles > 1) " · ${cfg.cycles} ciclos" else ""
        "${cfg.sprintsPerCycle}× ${formatClock(cfg.sprintSec)} / ${formatClock(cfg.restSec)}$cyc · total ${formatClock(totalSeconds(buildIntervalPhases(cfg)))}"
    }
} catch (_: Exception) { "" }

/**
 * Lista de presets (de fábrica + os salvos) que sobe de baixo. Tocar carrega os valores e fecha,
 * voltando para a montagem do treino.
 */
@Composable
private fun PresetsSheet(
    presets: List<PresetEntity>,
    onDismiss: () -> Unit,
    onPick: (PresetEntity) -> Unit,
    onDelete: (PresetEntity) -> Unit,
) {
    val t = LocalGrokTheme.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null, onClick = onDismiss,
        ), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(t.paper)
                    .border(3.dp, t.ink, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { }
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(48.dp, 5.dp).clip(RoundedCornerShape(50)).background(t.ink.copy(alpha = 0.3f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DisplayText("Presets", 28.sp, Modifier.weight(1f))
                    GrokButton("Fechar", onDismiss, kind = BtnKind.CREAM, size = 16.sp, height = 40.dp)
                }
                if (presets.isEmpty()) Hint("Nenhum preset ainda.")
                Column(Modifier.verticalScroll(rememberScrollState()).padding(end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { p ->
                        val custom = !p.id.startsWith("preset-")
                        Box(Modifier.fillMaxWidth().grokCard().clip(RoundedCornerShape(24.dp)).clickable { onPick(p) }) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    DisplayText(p.name, 20.sp)
                                    Hint(presetSummary(p) + if (custom) " · seu" else "", alpha = 0.7f, size = 13.sp)
                                }
                                if (custom) Text2("Apagar", Modifier.padding(start = 8.dp).clickable { onDelete(p) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Text2(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(text, modifier, color = Ink.copy(alpha = 0.7f), fontSize = 14.sp, fontFamily = Nunito,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
}

private fun startWorkout(ctx: Context, json: JSONObject) {
    json.put("mascot", MascotPrefs.current.id)
    ContextCompat.startForegroundService(ctx, WorkoutService.startIntent(ctx, json.toString()))
}

@Composable
fun TirosSetupScreen(onBack: () -> Unit, onGo: () -> Unit) {
    val ctx = LocalContext.current
    val app = dbApp()
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("grokrun_prefs", Context.MODE_PRIVATE) }
    val last = remember { runCatching { JSONObject(prefs.getString("lastTiros", null)!!) }.getOrNull() }
    var warmup by remember { mutableIntStateOf(last?.optInt("warmup", 300) ?: 300) }
    var sprint by remember { mutableIntStateOf(last?.optInt("sprint", 60) ?: 60) }
    var rest by remember { mutableIntStateOf(last?.optInt("rest", 60) ?: 60) }
    var n by remember { mutableIntStateOf(last?.optInt("sprints", 4) ?: 4) }
    var cycles by remember { mutableIntStateOf(last?.optInt("cycles", 1) ?: 1) }
    var cycleRest by remember { mutableIntStateOf(last?.optInt("cycleRest", 0) ?: 0) }
    var cool by remember { mutableIntStateOf(last?.optInt("cooldown", 180) ?: 180) }
    var presets by remember { mutableStateOf<List<PresetEntity>>(emptyList()) }
    suspend fun reload() { presets = sortedPresets(withContext(Dispatchers.IO) { app.db.workouts().presets() }, "tiros") }
    LaunchedEffect(Unit) { reload() }
    fun cfgJson() = JSONObject().put("mode", "tiros").put("warmup", warmup).put("sprint", sprint).put("rest", rest)
        .put("sprints", n).put("cycles", cycles).put("cycleRest", cycleRest).put("cooldown", cool)
    val phases = buildIntervalPhases(IntervalConfig(warmup, sprint, rest, n, cycles, cycleRest, cool))
    val total = totalSeconds(phases)
    var showPresets by remember { mutableStateOf(false) }
    fun load(p: PresetEntity) {
        val j = JSONObject(p.configJson)
        warmup = j.optInt("warmup", 300); sprint = j.optInt("sprint", 60); rest = j.optInt("rest", 60); n = j.optInt("sprints", 4).coerceAtLeast(1)
        cycles = j.optInt("cycles", 1).coerceAtLeast(1); cycleRest = j.optInt("cycleRest", 0); cool = j.optInt("cooldown", 180)
    }
    if (showPresets) PresetsSheet(presets, { showPresets = false }, { p -> load(p); showPresets = false; presetToast(ctx, p) }) { p ->
        scope.launch { withContext(Dispatchers.IO) { app.db.workouts().deletePreset(p.id) }; reload() }
    }
    MeadowBackground {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackBar("Tiros", onBack)
        PresetChips(presets) { p -> load(p) }
        GrokCard(padding = 12.dp) {
            Column {
                TimeStepper("Aquecimento", warmup, { warmup = it }, step = 15)
                TimeStepper("Duração do tiro", sprint, { sprint = it }, min = 5, step = 5)
                TimeStepper("Descanso entre tiros", rest, { rest = it }, step = 5)
                CountStepper("Tiros por ciclo", n, 1, 30) { n = it }
                CountStepper("Ciclos", cycles, 1, 20) { cycles = it }
                TimeStepper("Descanso entre ciclos", cycleRest, { cycleRest = it }, hint = "Pode ser zero", step = 15)
                TimeStepper("Desaquecimento", cool, { cool = it }, step = 15)
            }
        }
        GrokCard {
            Column {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Hint("Tempo total", Modifier.weight(1f))
                    DisplayText(formatClock(total), 38.sp)
                }
                PhaseBar(phases.map { it.kind to it.durationSec })
                Hint("Sem descanso depois do último tiro de cada ciclo.", Modifier.padding(top = 8.dp), alpha = 0.65f)
            }
        }
        SavePresetRow { name ->
            scope.launch {
                withContext(Dispatchers.IO) { app.db.workouts().insertPreset(PresetEntity("p-" + UUID.randomUUID(), name, "tiros", cfgJson().toString())) }
                reload()
            }
        }
        GrokButton("Presets", { showPresets = true }, Modifier.fillMaxWidth(), BtnKind.GOLD, 18.sp, 46.dp)
        GrokButton("Começar tiros", {
            prefs.edit().putString("lastTiros", cfgJson().toString()).apply()
            startWorkout(ctx, cfgJson()); onGo()
        }, Modifier.fillMaxWidth(), BtnKind.CINDER, 24.sp, 58.dp, enabled = sprint > 0)
        Spacer(Modifier.height(64.dp))
    }
    }
}

@Composable
fun CorridaSetupScreen(onBack: () -> Unit, onGo: () -> Unit) {
    val ctx = LocalContext.current
    val app = dbApp()
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("grokrun_prefs", Context.MODE_PRIVATE) }
    val last = remember { runCatching { JSONObject(prefs.getString("lastRun", null)!!) }.getOrNull() }
    var fast by remember { mutableIntStateOf(last?.optInt("fast", 360) ?: 360) }
    var slow by remember { mutableIntStateOf(last?.optInt("slow", 390) ?: 390) }
    var window by remember { mutableIntStateOf(last?.optInt("window", 20) ?: 20) }
    var presets by remember { mutableStateOf<List<PresetEntity>>(emptyList()) }
    suspend fun reload() { presets = sortedPresets(withContext(Dispatchers.IO) { app.db.workouts().presets() }, "corrida") }
    LaunchedEffect(Unit) { reload() }
    fun cfgJson() = JSONObject().put("mode", "corrida").put("fast", minOf(fast, slow)).put("slow", maxOf(fast, slow)).put("window", window)
    var showPresets by remember { mutableStateOf(false) }
    fun load(p: PresetEntity) {
        val j = JSONObject(p.configJson)
        fast = j.optInt("fast", 360); slow = j.optInt("slow", 390); window = j.optInt("window", 20)
    }
    if (showPresets) PresetsSheet(presets, { showPresets = false }, { p -> load(p); showPresets = false; presetToast(ctx, p) }) { p ->
        scope.launch { withContext(Dispatchers.IO) { app.db.workouts().deletePreset(p.id) }; reload() }
    }
    MeadowBackground {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackBar("Corrida", onBack)
        PresetChips(presets) { p -> load(p) }
        GrokCard {
            Column {
                TimeStepper("Pace mais rápido", fast, { fast = minOf(it, slow - 5) }, hint = "Abaixo disso, apito agudo", min = 180, max = 720, step = 5)
                TimeStepper("Pace mais lento", slow, { slow = maxOf(it, fast + 5) }, hint = "Acima disso, apito grave", min = 185, max = 900, step = 5)
                CountStepper("Janela do pace", window, 15, 30) { window = it }
            }
        }
        Hint("O pace é a média dos últimos $window s. O alerta espera 5 s fora da faixa e não repete antes de 15 s. Dentro da faixa, silêncio.")
        SavePresetRow { name ->
            scope.launch {
                withContext(Dispatchers.IO) { app.db.workouts().insertPreset(PresetEntity("p-" + UUID.randomUUID(), name, "corrida", cfgJson().toString())) }
                reload()
            }
        }
        GrokButton("Presets", { showPresets = true }, Modifier.fillMaxWidth(), BtnKind.GOLD, 18.sp, 46.dp)
        GrokButton("Começar corrida", {
            prefs.edit().putString("lastRun", cfgJson().toString()).apply()
            startWorkout(ctx, cfgJson()); onGo()
        }, Modifier.fillMaxWidth(), BtnKind.CINDER, 24.sp, 58.dp)
        Spacer(Modifier.height(64.dp))
    }
    }
}

// =====================================================================================
// Ao vivo (live-screen.tsx) + mapa ao vivo
// =====================================================================================

private fun send(ctx: Context, action: String) {
    ctx.startService(Intent(ctx, WorkoutService::class.java).setAction(action))
}

@Composable
fun LiveScreen(onSaved: (String) -> Unit, onMinimize: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalGrokTheme.current
    val hud by Live.hud.collectAsState()
    val position by Live.position.collectAsState()
    val route by Live.route.collectAsState()
    val currentId by Live.currentId.collectAsState()
    val lastSaved by Live.lastSaved.collectAsState()
    val saving by Live.saving.collectAsState()
    val active by Live.active.collectAsState()
    val saveFailed by Live.saveFailed.collectAsState()
    var myId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var stopping by rememberSaveable { mutableStateOf(false) }
    var stuck by remember { mutableStateOf(false) }
    LaunchedEffect(currentId) { if (currentId != null) myId = currentId }
    // Treino salvo (no fim natural ou depois do "Sim") → resumo.
    LaunchedEffect(lastSaved, myId, stopping) {
        val s = lastSaved
        if (s != null && (s == myId || stopping)) onSaved(s)
    }
    // Pediu para finalizar e o serviço encerrou sem salvar nada (sessão perdida): volta para a Pista.
    LaunchedEffect(stopping, active, saving, currentId, saveFailed) {
        if (stopping && !active && !saving && currentId == null && lastSaved == null && saveFailed) {
            android.widget.Toast.makeText(ctx, "Não havia treino para salvar.", android.widget.Toast.LENGTH_SHORT).show()
            onMinimize()
        }
    }
    // Rede de segurança: se em 10 s nada respondeu, oferece voltar para a Pista.
    LaunchedEffect(stopping) {
        if (stopping) { kotlinx.coroutines.delay(10_000); stuck = true }
    }
    val m = MascotPrefs.current
    val hasLocation = remember {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val h = hud
    val paused = h?.status == RunStatus.PAUSED
    val prepare = h?.status == RunStatus.PREPARE
    val restPose = h?.phaseKind == PhaseKind.REST || h?.phaseKind == PhaseKind.CYCLE_REST || h?.phaseKind == PhaseKind.COOLDOWN || paused
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            // Voltar para a Pista: o treino continua rodando (a pílula na Pista traz de volta).
            Box(
                Modifier.padding(end = 8.dp, top = 2.dp).size(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(t.cream).border(3.dp, t.ink, RoundedCornerShape(14.dp))
                    .clickable(onClick = onMinimize),
                contentAlignment = Alignment.Center,
            ) { Icon(GrokIcon.BACK, t.ink, 22.dp) }
            Column(Modifier.weight(1f)) {
                Hint(if (paused) (if (h?.pauseReason == "auto") "Parado na rua" else "Pausado") else "Agora", alpha = 0.65f)
                DisplayText(h?.phaseLabel ?: "Preparar", 30.sp)
                val sub = buildString {
                    if (h?.sprint != null) append("Tiro ${h.sprint}")
                    if (h?.sprint != null && h.cycle != null) append(" · ")
                    if (h?.cycle != null) append("Ciclo ${h.cycle}")
                    if (h?.nextLabel != null) append(" · depois: ${h.nextLabel}")
                }
                if (sub.isNotEmpty()) Hint(sub)
            }
            when {
                prepare -> Image(painterResource(m.idle), m.label, Modifier.size(72.dp, 86.dp))
                restPose -> Image(painterResource(m.rest), "${m.label} descansando", Modifier.size(72.dp, 86.dp))
                else -> RunningMascot(m, 80.dp)
            }
        }
        GrokCard(padding = 10.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val big = when {
                    prepare -> "${h?.prepare ?: 3}"
                    h?.remainSec != null -> formatClock(h.remainSec)
                    else -> formatClock(h?.elapsedSec ?: 0)
                }
                DisplayText(big, 64.sp, align = TextAlign.Center)
                Hint(
                    (if (prepare) "segundos" else if (h?.remainSec != null) "restante" else "decorrido") +
                        (h?.totalSec?.let { " · total ${formatClock(it)}" } ?: ""),
                    alpha = 0.6f,
                )
            }
        }
        if (paused && h?.pauseReason == "auto") Hint("Você parou. O tempo pausou. Quando andar de novo, continua sozinho.", Modifier.fillMaxWidth(), align = TextAlign.Center)
        if (h?.alert == "fast") GrokCard(fill = t.cinder, padding = 6.dp) { DisplayText("Rápido demais", 20.sp, Modifier.fillMaxWidth(), color = t.onCinder, align = TextAlign.Center) }
        if (h?.alert == "slow") GrokCard(fill = t.ink, padding = 6.dp) { DisplayText("Lento demais", 20.sp, Modifier.fillMaxWidth(), color = t.paper, align = TextAlign.Center) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("Pace", formatPace(h?.paceSec), Modifier.weight(1f))
            Stat("Velocidade", formatSpeed(h?.speedKmh), Modifier.weight(1f))
            Stat("Distância", formatKm(h?.distanceM ?: 0.0), Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (hasLocation) {
                LiveMap(position, route, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).border(3.dp, t.ink, RoundedCornerShape(20.dp)))
            } else NoMapPlaceholder(Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (h?.gps == "gps") "GPS" else "Buscando GPS", false, {})
            Spacer(Modifier.weight(1f))
            if (h?.mode == com.grokrun.domain.Mode.CORRIDA && h.inZoneRatio != null) Body("Na faixa ${formatPct(h.inZoneRatio)}", size = 14.sp)
        }
        if (stopping || saving) {
            GrokCard(padding = 10.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DisplayText("Salvando corrida…", 22.sp, align = TextAlign.Center)
                    if (stuck) GrokButton("Voltar para a Pista", onMinimize, kind = BtnKind.CREAM, size = 16.sp, height = 42.dp)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paused) GrokButton("Retomar", { send(ctx, WorkoutService.ACTION_RESUME) }, Modifier.weight(1f), BtnKind.GOLD, 22.sp, 54.dp)
                else GrokButton("Pausar", { send(ctx, WorkoutService.ACTION_PAUSE) }, Modifier.weight(1f), BtnKind.CREAM, 22.sp, 54.dp, enabled = !prepare && h != null)
                GrokButton("Finalizar", { confirming = true }, Modifier.weight(1f), BtnKind.INK, 22.sp, 54.dp)
            }
            if (paused && h?.pauseReason == "auto") GrokButton("Segurar pausa mesmo se eu andar", { send(ctx, WorkoutService.ACTION_HOLD) }, Modifier.fillMaxWidth(), BtnKind.CREAM, 16.sp, 44.dp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text2("Minimizar e continuar correndo", Modifier.clickable(onClick = onMinimize))
        }
        Hint("Sair desta tela não pausa. O relógio segue.", Modifier.fillMaxWidth(), alpha = 0.6f, size = 12.sp, align = TextAlign.Center)
        if (!active && !Live.running && h == null && !stopping) Hint("Nenhum treino rodando.")
    }
    if (confirming) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirming = false }) {
            GrokCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DisplayText("Finalizar corrida?", 26.sp)
                    Hint("O que você já correu fica salvo e aparece o resumo.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GrokButton("Sim", {
                            confirming = false
                            stopping = true
                            if (currentId != null) myId = currentId
                            send(ctx, WorkoutService.ACTION_STOP)
                        }, Modifier.weight(1f), BtnKind.CINDER, 20.sp, 50.dp)
                        GrokButton("Continuar", { confirming = false }, Modifier.weight(1f), BtnKind.CREAM, 20.sp, 50.dp)
                    }
                }
            }
        }
    }
}

// =====================================================================================
// Resumo pós-treino (after-screen.tsx: Summary)
// =====================================================================================

@Composable
private fun rememberWorkout(id: String): Pair<WorkoutEntity?, List<PointEntity>>? {
    val app = dbApp()
    var data by remember(id) { mutableStateOf<Pair<WorkoutEntity?, List<PointEntity>>?>(null) }
    LaunchedEffect(id) {
        data = withContext(Dispatchers.IO) { app.db.workouts().one(id) to app.db.workouts().points(id) }
    }
    return data
}

private fun elevSourceText(src: String) = when (src) {
    "dem" -> "Altitude corrigida pelo Open-Meteo."
    "baro" -> "Altitude do barômetro, ancorada no GPS."
    else -> "Altitude estimada (GPS suavizado)."
}

@Composable
fun SummaryScreen(id: String, onMap: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val data = rememberWorkout(id) ?: return
    val w = data.first
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (w == null) {
            Body("Treino não encontrado.")
            GrokButton("Fechar", onClose, kind = BtnKind.CREAM)
            return@Column
        }
        val phases = parsePhases(w.phasesJson)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(Mascot.of(w.mascot).rest), null, Modifier.size(96.dp))
            Column {
                DisplayText("Treino salvo", 30.sp)
                Hint("${modeName(w.mode)} · ${formatWhen(w.startedAt)}")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("Tempo", formatClock(w.durationSec), Modifier.weight(1f))
            Stat("Distância", formatKm(w.distanceM), Modifier.weight(1f))
            Stat("Elevação", "+${w.elevGain.roundToInt()} m", Modifier.weight(1f))
        }
        Hint(elevSourceText(w.elevSource), alpha = 0.65f)
        SprintTable(phases)
        PhaseList(phases)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GrokButton("Ver percurso", onMap, Modifier.weight(1f), BtnKind.GOLD, 18.sp)
            GrokButton("Exportar GPX", { shareGpx(ctx, w, data.second) }, Modifier.weight(1f), BtnKind.CREAM, 18.sp)
        }
        GrokButton("Fechar", onClose, Modifier.fillMaxWidth(), BtnKind.INK, 20.sp)
    }
}

@Composable
private fun SprintTable(phases: List<PhaseRow>) {
    val sprints = phases.filter { it.kind == PhaseKind.SPRINT }
    if (sprints.isEmpty()) return
    val paces = sprints.mapNotNull { it.paceSec }
    val best = paces.minOrNull()
    val worst = paces.maxOrNull()
    GrokCard(padding = 10.dp) {
        Column {
            Row { listOf("Tiro" to 1.5f, "Tempo" to 1f, "Dist." to 1.2f, "Pace" to 0.9f, "km/h" to 1.1f).forEach { (k, wgt) -> DisplayText(k, 15.sp, Modifier.weight(wgt)) } }
            sprints.forEach { s ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.copy(alpha = 0.15f)))
                Row(Modifier.padding(vertical = 4.dp)) {
                    val tag = (if (s.paceSec != null && s.paceSec == best) " · melhor" else "") +
                        (if (s.paceSec != null && worst != null && best != worst && s.paceSec == worst) " · lento" else "")
                    Body("${if (s.cycle != null) "${s.cycle}.${s.sprint}" else "${s.sprint}"}$tag", Modifier.weight(1.5f), 13.sp)
                    Body(formatClock(s.durationSec), Modifier.weight(1f), 13.sp)
                    Body(formatKm(s.distanceM), Modifier.weight(1.2f), 13.sp)
                    Body(formatPace(s.paceSec), Modifier.weight(0.9f), 13.sp)
                    Body(formatSpeed(s.speedKmh).removeSuffix(" km/h"), Modifier.weight(1.1f), 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PhaseList(phases: List<PhaseRow>) {
    if (phases.isEmpty()) return
    GrokCard {
        Column {
            DisplayText("Por fase", 18.sp)
            phases.forEach { p ->
                Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(1.dp).background(Ink.copy(alpha = 0.1f)))
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Body(p.label, Modifier.weight(1f), 14.sp)
                    Hint("${formatClock(p.durationSec)} · ${formatPace(p.paceSec)} · ${formatSpeed(p.speedKmh)}", alpha = 0.75f)
                }
            }
        }
    }
}

// =====================================================================================
// Atividades + detalhe
// =====================================================================================

@Composable
fun ActivitiesScreen(onOpen: (String) -> Unit) {
    val app = dbApp()
    var rows by remember { mutableStateOf<List<WorkoutEntity>?>(null) }
    LaunchedEffect(Unit) { rows = withContext(Dispatchers.IO) { app.db.workouts().all() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DisplayText("Atividades", 32.sp)
        val list = rows ?: return@Column
        if (list.isEmpty()) Body("Nenhum treino ainda. A pista está esperando.", color = Ink.copy(alpha = 0.7f))
        list.forEach { w ->
            Box(Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 4.dp).grokCard().clip(RoundedCornerShape(24.dp)).clickable { onOpen(w.id) }.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(
                        painterResource(Mascot.of(w.mascot).rest), null,
                        Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).border(3.dp, Ink, RoundedCornerShape(16.dp)).background(Paper),
                    )
                    Column(Modifier.weight(1f)) {
                        DisplayText(modeName(w.mode), 20.sp)
                        Hint("${formatWhen(w.startedAt)} · ${formatKm(w.distanceM)} · ${formatClock(w.durationSec)}")
                    }
                }
            }
        }
    }
}

@Composable
fun DetailScreen(id: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val app = dbApp()
    val scope = rememberCoroutineScope()
    val data = rememberWorkout(id) ?: return
    val w = data.first
    val points = data.second
    var ask by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (w == null) {
            BackBar("Atividade", onBack); Body("Não encontrada."); return@Column
        }
        BackBar(modeName(w.mode), onBack)
        Hint("${formatWhen(w.startedAt)} · ${Mascot.of(w.mascot).label} · ${formatKm(w.distanceM)}")
        RouteMap(points, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp)).border(3.dp, Ink, RoundedCornerShape(20.dp)))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(Gold, "Aquecimento"); Legend(Cinder, "Tiro"); Legend(Ink, "Descanso"); Legend(Grass, "Entre ciclos")
        }
        Column {
            DisplayText("Elevação × distância", 18.sp, Modifier.padding(bottom = 4.dp))
            val elev = remember(points) {
                val pts = points.filter { it.alt != null }
                val step = maxOf(1, pts.size / 80)
                pts.filterIndexed { i, _ -> i % step == 0 }.map { (it.distM / 1000.0).toFloat() to it.alt!!.toFloat() }
            }
            if (elev.size >= 2) ElevationChart(elev)
            Hint(
                "+${formatMeters(w.elevGain)} / −${formatMeters(w.elevLoss)}" +
                    if (w.elevEstimated) " · estimada" else if (w.elevSource == "dem") " · Open-Meteo" else " · barômetro",
                Modifier.padding(top = 4.dp),
            )
        }
        val splits = parseSplits(w.splitsJson)
        if (splits.isNotEmpty()) {
            GrokCard {
                Column {
                    DisplayText("Parciais", 18.sp)
                    splits.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Body("km ${s.km}", Modifier.weight(1f), 14.sp)
                            Body("${formatClock(s.splitSec)} · ${formatPace(s.splitSec)}", size = 14.sp)
                        }
                    }
                }
            }
        }
        PhaseList(parsePhases(w.phasesJson))
        GrokButton("Exportar GPX", { shareGpx(ctx, w, points) }, Modifier.fillMaxWidth(), BtnKind.GOLD, 20.sp)
        if (ask) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GrokButton("Apagar", {
                    scope.launch { withContext(Dispatchers.IO) { app.db.workouts().delete(w.id) }; onDeleted() }
                }, Modifier.weight(1f), BtnKind.CINDER, 18.sp, 46.dp)
                GrokButton("Cancelar", { ask = false }, kind = BtnKind.CREAM, size = 18.sp, height = 46.dp)
            }
        } else {
            GrokButton("Apagar atividade", { ask = true }, Modifier.fillMaxWidth(), BtnKind.CREAM, 18.sp, 46.dp)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(12.dp).background(color).border(2.dp, Ink))
        Body(label, size = 12.sp)
    }
}

/** Percurso salvo colorido por fase (MapView.tsx da web). */
@Composable
private fun RouteMap(points: List<PointEntity>, modifier: Modifier) {
    val gold = Gold.toArgb(); val cinder = Cinder.toArgb(); val ink = Color(0xFF1C140C).toArgb(); val grass = Grass.toArgb()
    if (points.size < 2) {
        Box(modifier.background(Card), contentAlignment = Alignment.Center) { Hint("Sem percurso de GPS neste treino.") }
        return
    }
    AndroidView(
        factory = { c ->
            MapView(c).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(16.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            fun colorOf(kind: String) = when (kind) {
                "WARMUP", "COOLDOWN" -> gold
                "SPRINT", "RUN" -> cinder
                "REST" -> ink
                else -> grass
            }
            var seg = ArrayList<GeoPoint>()
            var kind = points.first().phase
            fun flush() {
                if (seg.size < 2) return
                val line = Polyline()
                line.setPoints(seg)
                line.outlinePaint.strokeWidth = 12f
                line.outlinePaint.color = colorOf(kind)
                line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                map.overlays.add(line)
            }
            for (p in points) {
                if (p.phase != kind) {
                    seg.add(GeoPoint(p.lat, p.lon)); flush()
                    seg = ArrayList(); kind = p.phase
                }
                seg.add(GeoPoint(p.lat, p.lon))
            }
            flush()
            val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lon) })
            map.post { runCatching { map.zoomToBoundingBox(box.increaseByScale(1.25f), false) } }
            map.invalidate()
        },
        onRelease = { it.onDetach() },
        modifier = modifier,
    )
}

// =====================================================================================
// Evolução (more-screen.tsx: Evolution)
// =====================================================================================

fun weekBars(rows: List<WorkoutEntity>): List<Pair<Long, Double>> {
    val c = Calendar.getInstance()
    val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // segunda = 0
    c.add(Calendar.DAY_OF_MONTH, -dow)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    val monday = c.timeInMillis
    val week = 7L * 86_400_000L
    return (7 downTo 0).map { i ->
        val start = monday - i * week
        start to rows.filter { it.startedAt >= start && it.startedAt < start + week }.sumOf { it.distanceM }
    }
}

@Composable
fun EvolutionScreen() {
    val app = dbApp()
    var rows by remember { mutableStateOf<List<WorkoutEntity>>(emptyList()) }
    LaunchedEffect(Unit) { rows = withContext(Dispatchers.IO) { app.db.workouts().all() } }
    val tiros = rows.filter { it.mode == "tiros" }
    val runs = rows.filter { it.mode == "corrida" }
    data class PaceDay(val t: Long, val dia: String, val pace: Double, val best: Double, val delta: Double)
    val paceData = tiros.mapNotNull { a ->
        val paces = parsePhases(a.phasesJson).filter { it.kind == PhaseKind.SPRINT }.mapNotNull { it.paceSec }
        if (paces.isEmpty()) null else PaceDay(a.startedAt, formatDay(a.startedAt), paces.average(), paces.min(), paces.max() - paces.min())
    }.sortedBy { it.t }
    val bestEver = paceData.minOfOrNull { it.best }
    val lastDelta = paceData.lastOrNull()?.delta
    val zoned = runs.mapNotNull { it.inZoneRatio }
    val zone = if (zoned.isEmpty()) null else zoned.average()
    val weeks = weekBars(rows)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DisplayText("Evolução", 32.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("Melhor tiro", formatPace(bestEver), Modifier.weight(1f))
            Stat("Consistência", lastDelta?.let { formatClock(it) } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("Na faixa", formatPct(zone), Modifier.weight(1f))
            Stat("Semana", formatKm(weeks.lastOrNull()?.second ?: 0.0), Modifier.weight(1f))
        }
        Column {
            DisplayText("Pace médio dos tiros", 18.sp)
            Hint("Quanto menor, mais rápido.", Modifier.padding(bottom = 4.dp), alpha = 0.65f)
            LineChart(paceData.map { it.dia to it.pace.toFloat() }) { formatPace(it) }
        }
        Column {
            DisplayText("Consistência (lento − rápido)", 18.sp, Modifier.padding(bottom = 4.dp))
            BarMini(paceData.map { it.dia to it.delta.roundToInt().toFloat() }, Color(0xFFE6A317))
        }
        Column {
            DisplayText("Distância semanal", 18.sp, Modifier.padding(bottom = 4.dp))
            BarMini(weeks.map { formatDay(it.first) to (it.second / 100.0).roundToInt() / 10f }, Color(0xFFE23D2B))
        }
        if (runs.isNotEmpty()) Column {
            DisplayText("Tempo na faixa", 18.sp, Modifier.padding(bottom = 4.dp))
            BarMini(runs.sortedBy { it.startedAt }.map { formatDay(it.startedAt) to ((it.inZoneRatio ?: 0.0) * 100).roundToInt().toFloat() }, Color(0xFF2F6B3A))
        }
    }
}

// ---------- Gráficos simples em Canvas (no lugar do recharts) ----------

private fun labelPaint(color: Color, sizePx: Float) = android.graphics.Paint().apply {
    this.color = color.toArgb(); textSize = sizePx; isAntiAlias = true
}

@Composable
private fun ChartCard(height: Dp, content: @Composable () -> Unit) {
    GrokCard(Modifier.fillMaxWidth().height(height), padding = 10.dp) { content() }
}

@Composable
fun LineChart(data: List<Pair<String, Float>>, fmt: (Float) -> String) {
    val ink = Ink; val cinder = Cinder; val cream = Card
    ChartCard(210.dp) {
        if (data.isEmpty()) return@ChartCard
        Canvas(Modifier.fillMaxSize()) {
            val left = 44.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val top = 10.dp.toPx(); val right = size.width - 8.dp.toPx()
            var lo = data.minOf { it.second }; var hi = data.maxOf { it.second }
            if (hi - lo < 1f) { lo -= 10f; hi += 10f }
            val paint = labelPaint(ink, 11.sp.toPx())
            for (k in 0..3) {
                val v = lo + (hi - lo) * k / 3f
                val y = bottom - (bottom - top) * k / 3f
                drawLine(ink.copy(alpha = 0.13f), Offset(left, y), Offset(right, y), 1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(fmt(v), 0f, y + 4.dp.toPx(), paint)
            }
            val n = data.size
            fun x(i: Int) = if (n == 1) (left + right) / 2 else left + (right - left) * i / (n - 1)
            fun y(v: Float) = bottom - (bottom - top) * (v - lo) / (hi - lo)
            val path = Path()
            data.forEachIndexed { i, (_, v) -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(path, cinder, style = Stroke(3.dp.toPx()))
            data.forEachIndexed { i, (d, v) ->
                drawCircle(cream, 4.dp.toPx(), Offset(x(i), y(v)))
                drawCircle(ink, 4.dp.toPx(), Offset(x(i), y(v)), style = Stroke(2.dp.toPx()))
                if (n <= 8 || i % (n / 6 + 1) == 0) {
                    val w = paint.measureText(d)
                    drawContext.canvas.nativeCanvas.drawText(d, x(i) - w / 2, size.height, paint)
                }
            }
        }
    }
}

@Composable
fun BarMini(data: List<Pair<String, Float>>, color: Color) {
    val ink = Ink
    ChartCard(180.dp) {
        if (data.isEmpty()) return@ChartCard
        Canvas(Modifier.fillMaxSize()) {
            val left = 30.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val top = 12.dp.toPx(); val right = size.width
            val hi = data.maxOf { it.second }.coerceAtLeast(1f)
            val paint = labelPaint(ink, 11.sp.toPx())
            for (k in 0..2) {
                val v = hi * k / 2f
                val y = bottom - (bottom - top) * k / 2f
                val txt = if (hi < 10) "%.1f".format(v) else v.roundToInt().toString()
                drawContext.canvas.nativeCanvas.drawText(txt, 0f, y + 4.dp.toPx(), paint)
            }
            val slot = (right - left) / data.size
            data.forEachIndexed { i, (d, v) ->
                val bw = slot * 0.62f
                val x = left + slot * i + (slot - bw) / 2
                val h = (bottom - top) * (v / hi)
                if (h > 0) drawRoundRect(color, Offset(x, bottom - h), Size(bw, h), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                if (data.size <= 8 || i % (data.size / 6 + 1) == 0) {
                    val w = paint.measureText(d)
                    drawContext.canvas.nativeCanvas.drawText(d, x + bw / 2 - w / 2, size.height, paint)
                }
            }
            drawLine(ink.copy(alpha = 0.3f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        }
    }
}

@Composable
fun ElevationChart(data: List<Pair<Float, Float>>) {
    val ink = Ink; val green = Color(0xFF2F6B3A)
    ChartCard(190.dp) {
        Canvas(Modifier.fillMaxSize()) {
            val left = 40.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val top = 10.dp.toPx(); val right = size.width - 6.dp.toPx()
            var lo = data.minOf { it.second }; var hi = data.maxOf { it.second }
            if (hi - lo < 4f) { lo -= 2f; hi += 2f }
            val x0 = data.first().first; val x1 = data.last().first.coerceAtLeast(x0 + 0.001f)
            fun x(v: Float) = left + (right - left) * (v - x0) / (x1 - x0)
            fun y(v: Float) = bottom - (bottom - top) * (v - lo) / (hi - lo)
            val paint = labelPaint(ink, 11.sp.toPx())
            for (k in 0..3) {
                val v = lo + (hi - lo) * k / 3f
                val yy = y(v)
                drawLine(ink.copy(alpha = 0.13f), Offset(left, yy), Offset(right, yy), 1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText("${v.roundToInt()}", 0f, yy + 4.dp.toPx(), paint)
            }
            val line = Path(); val area = Path()
            data.forEachIndexed { i, (d, a) ->
                if (i == 0) { line.moveTo(x(d), y(a)); area.moveTo(x(d), bottom); area.lineTo(x(d), y(a)) } else { line.lineTo(x(d), y(a)); area.lineTo(x(d), y(a)) }
            }
            area.lineTo(x(data.last().first), bottom); area.close()
            drawPath(area, green.copy(alpha = 0.33f))
            drawPath(line, green, style = Stroke(3.dp.toPx()))
            for (k in 0..3) {
                val v = x0 + (x1 - x0) * k / 3f
                val txt = "%.1f".format(java.util.Locale("pt", "BR"), v)
                val w = paint.measureText(txt)
                drawContext.canvas.nativeCanvas.drawText(txt, (x(v) - w / 2).coerceIn(left, right - w), size.height, paint)
            }
        }
    }
}

// =====================================================================================
// Ajustes (more-screen.tsx: Perms)
// =====================================================================================

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalGrokTheme.current
    var name by remember { mutableStateOf(AppPrefs.runnerName) }
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var gpsText by remember { mutableStateOf(if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) "Permitida" else "Ainda não") }
    var noteText by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) "Permitidas" else "Ainda não")
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.containsKey(Manifest.permission.ACCESS_FINE_LOCATION)) {
            gpsText = if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) "Permitida" else if (res[Manifest.permission.ACCESS_COARSE_LOCATION] == true) "Só aproximada" else "Negada — o treino segue só com o relógio"
        }
        if (Build.VERSION.SDK_INT >= 33 && res.containsKey(Manifest.permission.POST_NOTIFICATIONS)) {
            noteText = if (res[Manifest.permission.POST_NOTIFICATIONS] == true) "Permitidas" else "Negadas"
        }
    }
    fun saveName() { AppPrefs.setProfile(ctx, name.ifBlank { "Grok" }) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackBar("Ajustes") { saveName(); onBack() }
        SettingsCard("Seu bot", "O nome e a forma ficam salvos. Não precisa escolher de novo a cada treino.") {
            NameField(name, { name = it; AppPrefs.setName(ctx, it) }) { saveName() }
            Spacer(Modifier.height(8.dp))
            MascotGrid(MascotPrefs.current, 64.dp) { MascotPrefs.set(ctx, it); saveName() }
        }
        SettingsCard("Fundo", null) { ThemePicker() }
        SettingsCard("Pausa na rua", "Se você parar, o tempo pausa. O botão Pausar continua aí — diferente do Strava, a pausa na mão não solta sozinha.") {
            Chip(if (AppPrefs.autoPause) "Pausa automática ligada" else "Pausa automática desligada", AppPrefs.autoPause, { AppPrefs.setAutoPause(ctx, !AppPrefs.autoPause) })
        }
        SettingsCard("Apitos", "Escolhe o apito de cada momento. Toque em Ouvir antes de salvar o treino.") {
            WhistleMoment.entries.forEach { mo ->
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(t.ink.copy(alpha = 0.1f)))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    DisplayText(mo.label, 17.sp, Modifier.weight(1f))
                    GrokButton("Ouvir", { Whistles.play(AppPrefs.whistles[mo] ?: mo.def) }, kind = BtnKind.CREAM, size = 14.sp, height = 36.dp)
                }
                if (mo.hint.isNotEmpty()) Hint(mo.hint, alpha = 0.6f)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WhistleId.entries.forEach { w -> Chip(w.label, AppPrefs.whistles[mo] == w, { AppPrefs.setWhistle(ctx, mo, w); Whistles.play(w) }) }
                }
            }
        }
        Body("Se você sair da tela, o relógio não pausa. Ao voltar, a fase certa já está na frente.", color = t.ink.copy(alpha = 0.8f))
        SettingsCard("Localização precisa", "Desenha o percurso e calcula o pace. Pontos com erro acima de 20 m, ou saltos acima de 25 km/h, são ignorados.") {
            GrokButton("Permitir localização", {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, kind = BtnKind.GOLD, size = 17.sp, height = 46.dp)
            Body(gpsText, Modifier.padding(top = 4.dp), 14.sp)
            if (Build.VERSION.SDK_INT >= 29) {
                Spacer(Modifier.height(6.dp))
                GrokButton("Localização em segundo plano", {
                    launcher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }, kind = BtnKind.CREAM, size = 15.sp, height = 42.dp)
            }
        }
        SettingsCard("Notificações", "Fase e tempo quando você sai da tela.") {
            GrokButton("Permitir notificações", {
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }, kind = BtnKind.CREAM, size = 17.sp, height = 46.dp)
            Body(noteText, Modifier.padding(top = 4.dp), 14.sp)
        }
        SettingsCard("Tela e bateria", "Durante o treino um serviço em primeiro plano com wake lock segue com a tela apagada. Ignorar a otimização de bateria evita o Android matar o treino.") {
            GrokButton("Ignorar otimização de bateria", {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
            }, kind = BtnKind.CREAM, size = 15.sp, height = 44.dp)
        }
        GrokButton("Ouvir apitos", { Whistles.play(AppPrefs.whistles[WhistleMoment.SPRINT] ?: WhistleId.DUPLO) }, Modifier.fillMaxWidth(), BtnKind.INK, 18.sp, 48.dp,
            leading = { Icon(GrokIcon.VOLUME, t.paper, 18.dp) })
        GrokButton("Voltar à pista", { saveName(); onBack() }, Modifier.fillMaxWidth(), BtnKind.CINDER, 22.sp, 54.dp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsCard(title: String, text: String?, content: @Composable ColumnScope.() -> Unit) {
    GrokCard {
        Column(Modifier.fillMaxWidth()) {
            DisplayText(title, 21.sp)
            if (text != null) Hint(text, Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

/** Seletor de tema: 4 quadradinhos com amostra; o escolhido tem borda ink e fundo gold (igual à web). */
@Composable
fun ThemePicker() {
    val ctx = LocalContext.current
    val cur = LocalGrokTheme.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GrokTheme.entries.forEach { th ->
            val on = th == cur
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (on) cur.gold else cur.cream)
                    .border(3.dp, if (on) cur.ink else cur.ink.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .clickable { ThemePrefs.set(ctx, th) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Canvas(Modifier.size(34.dp)) {
                    drawCircle(th.paper)
                    drawArc(th.cinder, 0f, 120f, true)
                    drawArc(th.grass, 120f, 120f, true)
                    drawCircle(th.cream, size.minDimension * 0.18f)
                    drawCircle(Color(0xFF1C140C), style = Stroke(2.5.dp.toPx()))
                }
                DisplayText(th.label, 13.sp, color = if (on) Color(0xFF1C140C) else cur.ink, align = TextAlign.Center)
                androidx.compose.material3.Text(th.hint, fontSize = 10.sp, fontFamily = Nunito, color = (if (on) Color(0xFF1C140C) else cur.ink).copy(alpha = 0.7f))
            }
        }
    }
}

// =====================================================================================
// GPX
// =====================================================================================

fun shareGpx(ctx: Context, workout: WorkoutEntity, points: List<PointEntity>) {
    val dir = File(ctx.cacheDir, "gpx").apply { mkdirs() }
    val file = File(dir, "grokrun-${workout.id}.gpx")
    val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val body = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<gpx version="1.1" creator="GrokRun" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>GrokRun ${modeName(workout.mode)}</name><trkseg>""")
        for (p in points) {
            append("""<trkpt lat="${p.lat}" lon="${p.lon}">""")
            if (p.alt != null) append("<ele>${"%.1f".format(java.util.Locale.US, p.alt)}</ele>")
            append("<time>${iso.format(java.util.Date(p.t))}</time>")
            append("</trkpt>")
        }
        append("</trkseg></trk></gpx>")
    }
    file.writeText(body)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Exportar GPX"))
}

package com.grokrun.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.grokrun.GrokApplication
import com.grokrun.R
import com.grokrun.data.PointEntity
import com.grokrun.data.PresetEntity
import com.grokrun.data.WorkoutEntity
import com.grokrun.domain.RunStatus
import com.grokrun.domain.totalSeconds
import com.grokrun.domain.buildIntervalPhases
import com.grokrun.domain.IntervalConfig
import com.grokrun.service.Live
import com.grokrun.service.WorkoutService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.roundToInt

private val Ink = Color(0xFF1C140C)
private val Paper = Color(0xFFF4E4C4)
private val Cinder = Color(0xFFE23D2B)
private val Gold = Color(0xFFE6A317)

@Composable
fun GrokNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize().background(Paper)) {
        composable("home") { HomeScreen(onStart = { nav.navigate("mode") }, onHistory = { nav.navigate("history") }, onEvo = { nav.navigate("evo") }, onPerms = { nav.navigate("perms") }) }
        composable("mode") { ModeScreen(onBack = { nav.popBackStack() }, onTiros = { nav.navigate("tiros") }, onRun = { nav.navigate("corrida") }) }
        composable("tiros") { TirosScreen(onBack = { nav.popBackStack() }, onGo = { nav.navigate("live") }) }
        composable("corrida") { CorridaScreen(onBack = { nav.popBackStack() }, onGo = { nav.navigate("live") }) }
        composable("live") { LiveScreen(onDone = { nav.navigate("history") { popUpTo("home") } }) }
        composable("history") { HistoryScreen(onOpen = { nav.navigate("detail/$it") }, onBack = { nav.popBackStack() }) }
        composable("detail/{id}") { entry -> DetailScreen(entry.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() }) }
        composable("evo") { EvoScreen(onBack = { nav.popBackStack() }) }
        composable("perms") { PermsScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun HomeScreen(onStart: () -> Unit, onHistory: () -> Unit, onEvo: () -> Unit, onPerms: () -> Unit) {
    var mascot by remember { mutableIntStateOf(R.drawable.ic_mascot_ball) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("GrokRun", color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Black)
        Text("tiros, pace e percurso", color = Ink)
        Spacer(Modifier.height(12.dp))
        Image(painterResource(mascot), contentDescription = "Mascote parado na raia", modifier = Modifier.fillMaxWidth().height(220.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.drawable.ic_mascot_ball, R.drawable.ic_mascot_cloud, R.drawable.ic_mascot_cone).forEach { id ->
                Button(onClick = { mascot = id }, colors = ButtonDefaults.buttonColors(containerColor = if (mascot == id) Gold else Color.White)) {
                    Text(if (id == R.drawable.ic_mascot_ball) "Bola" else if (id == R.drawable.ic_mascot_cloud) "Nuvem" else "Cone", color = Ink)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Cinder)) { Text("Começar") }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onHistory, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("Atividades") }
            Button(onClick = onEvo, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("Evolução") }
            Button(onClick = onPerms, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("Ajustes") }
        }
    }
}

@Composable
private fun ModeScreen(onBack: () -> Unit, onTiros: () -> Unit, onRun: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Modo", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Ink)
        Button(onClick = onRun, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold)) {
            Text("Corrida — faixa de pace", color = Ink)
        }
        Button(onClick = onTiros, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Cinder)) {
            Text("Tiros — intervalo")
        }
        Button(onClick = onBack) { Text("Voltar") }
    }
}

@Composable
private fun TirosScreen(onBack: () -> Unit, onGo: () -> Unit) {
    val ctx = LocalContext.current
    var warmup by remember { mutableIntStateOf(300) }
    var sprint by remember { mutableIntStateOf(60) }
    var rest by remember { mutableIntStateOf(60) }
    var n by remember { mutableIntStateOf(4) }
    var cycles by remember { mutableIntStateOf(1) }
    var cycleRest by remember { mutableIntStateOf(0) }
    var cool by remember { mutableIntStateOf(180) }
    val total = totalSeconds(buildIntervalPhases(IntervalConfig(warmup, sprint, rest, n, cycles, cycleRest, cool)))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Tiros", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Ink)
        Step("Aquecimento", warmup) { warmup = it }
        Step("Duração do tiro", sprint) { sprint = it.coerceAtLeast(5) }
        Step("Descanso entre tiros", rest) { rest = it }
        Step("Tiros por ciclo", n, 1) { n = it.coerceIn(1, 30) }
        Step("Ciclos", cycles, 1) { cycles = it.coerceIn(1, 20) }
        Step("Descanso entre ciclos", cycleRest) { cycleRest = it }
        Step("Desaquecimento", cool) { cool = it }
        Text("Tempo total  ${fmt(total)}", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Ink)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val json = JSONObject()
                .put("mode", "tiros").put("mascot", "bola")
                .put("warmup", warmup).put("sprint", sprint).put("rest", rest)
                .put("sprints", n).put("cycles", cycles).put("cycleRest", cycleRest).put("cooldown", cool)
            ContextCompat.startForegroundService(ctx, WorkoutService.startIntent(ctx, json.toString()))
            onGo()
        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Cinder)) { Text("Começar tiros") }
        Button(onClick = onBack) { Text("Voltar") }
    }
}

@Composable
private fun CorridaScreen(onBack: () -> Unit, onGo: () -> Unit) {
    val ctx = LocalContext.current
    var fast by remember { mutableIntStateOf(360) }
    var slow by remember { mutableIntStateOf(390) }
    var window by remember { mutableIntStateOf(20) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Corrida", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Ink)
        Text("O pace é a média móvel da janela. O alerta espera 5 s fora da faixa e repete no máximo a cada 15 s.", color = Ink)
        Step("Pace rápido (s/km)", fast, 5) { fast = it.coerceIn(180, slow - 5) }
        Step("Pace lento (s/km)", slow, 5) { slow = it.coerceIn(fast + 5, 900) }
        Step("Janela (s)", window, 1) { window = it.coerceIn(15, 30) }
        Button(onClick = {
            val json = JSONObject().put("mode", "corrida").put("mascot", "nuvem")
                .put("fast", fast).put("slow", slow).put("window", window)
            ContextCompat.startForegroundService(ctx, WorkoutService.startIntent(ctx, json.toString()))
            onGo()
        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Cinder)) { Text("Começar corrida") }
        Button(onClick = onBack) { Text("Voltar") }
    }
}

@Composable
private fun Step(label: String, value: Int, step: Int = 15, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Ink, modifier = Modifier.weight(1f))
        Button(onClick = { onChange((value - step).coerceAtLeast(0)) }) { Text("-") }
        Text(if (label.contains("s/km") || label.startsWith("Janela") || label.startsWith("Tiros") || label.startsWith("Ciclos")) "$value" else fmt(value), color = Ink, modifier = Modifier.padding(horizontal = 8.dp))
        Button(onClick = { onChange(value + step) }) { Text("+") }
    }
}

@Composable
private fun LiveScreen(onDone: () -> Unit) {
    val hud by Live.hud.collectAsState()
    val ctx = LocalContext.current
    val h = hud
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(h?.phaseLabel ?: "Preparar", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Ink)
        Text(
            when {
                h?.prepare != null -> "${h.prepare}"
                h?.remainSec != null -> fmt(h.remainSec)
                else -> fmt(h?.elapsedSec ?: 0)
            },
            fontSize = 72.sp, fontWeight = FontWeight.Black, color = Ink,
        )
        Text("Tiro ${h?.sprint ?: "—"}   Ciclo ${h?.cycle ?: "—"}", color = Ink)
        Text("Pace ${h?.paceSec?.let { fmt(it) } ?: "—"}   ${h?.speedKmh?.let { "%.1f km/h".format(it) } ?: "—"}", color = Ink)
        Text("Distância ${"%.2f km".format((h?.distanceM ?: 0.0) / 1000.0)}", color = Ink)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val action = if (h?.status == RunStatus.PAUSED) WorkoutService.ACTION_RESUME else WorkoutService.ACTION_PAUSE
                ctx.startService(Intent(ctx, WorkoutService::class.java).setAction(action))
            }) { Text(if (h?.status == RunStatus.PAUSED) "Retomar" else "Pausar") }
            Button(onClick = {
                ctx.startService(Intent(ctx, WorkoutService::class.java).setAction(WorkoutService.ACTION_STOP))
                onDone()
            }, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("Parar") }
        }
        if (!Live.running && h?.status == RunStatus.DONE) {
            Text("Treino salvo.", color = Ink)
        }
    }
}

@Composable
private fun HistoryScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GrokApplication
    var rows by remember { mutableStateOf<List<WorkoutEntity>>(emptyList()) }
    LaunchedEffect(Unit) { rows = withContext(Dispatchers.IO) { app.db.workouts().all() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Atividades", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Ink)
        rows.forEach { w ->
            Button(onClick = { onOpen(w.id) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                Text("${w.mode}  ${"%.2f".format(w.distanceM / 1000)} km  ${fmt(w.durationSec.roundToInt())}", color = Ink)
            }
        }
        if (rows.isEmpty()) Text("Nenhum treino ainda.", color = Ink)
        Button(onClick = onBack) { Text("Voltar") }
    }
}

@Composable
private fun DetailScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GrokApplication
    var workout by remember { mutableStateOf<WorkoutEntity?>(null) }
    var points by remember { mutableStateOf<List<PointEntity>>(emptyList()) }
    LaunchedEffect(id) {
        workout = withContext(Dispatchers.IO) { app.db.workouts().one(id) }
        points = withContext(Dispatchers.IO) { app.db.workouts().points(id) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(workout?.mode ?: "Atividade", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Ink)
        Text("Elevação +${workout?.elevGain?.roundToInt() ?: 0} m / -${workout?.elevLoss?.roundToInt() ?: 0} m ${if (workout?.elevEstimated == true) "(estimada)" else ""}", color = Ink)
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c ->
                Configuration.getInstance().userAgentValue = "GrokRun"
                MapView(c).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                }
            },
            update = { map ->
                map.overlays.clear()
                if (points.size >= 2) {
                    var seg = ArrayList<GeoPoint>()
                    var kind = points.first().phase
                    fun flush() {
                        if (seg.size < 2) return
                        val line = Polyline()
                        line.setPoints(seg)
                        line.outlinePaint.strokeWidth = 12f
                        map.overlays.add(line)
                    }
                    for (p in points) {
                        if (p.phase != kind) {
                            flush(); seg = ArrayList(); kind = p.phase
                        }
                        seg.add(GeoPoint(p.lat, p.lon))
                    }
                    flush()
                    map.controller.setCenter(GeoPoint(points.first().lat, points.first().lon))
                }
            },
            modifier = Modifier.fillMaxWidth().height(240.dp).border(3.dp, Ink, RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text("Por fase", fontWeight = FontWeight.Black, color = Ink)
        val phases = workout?.phasesJson
        if (phases != null) Text(phases, color = Ink, fontSize = 12.sp)
        Button(onClick = {
            val w = workout ?: return@Button
            shareGpx(ctx, w, points)
        }) { Text("Exportar GPX") }
        Button(onClick = onBack) { Text("Voltar") }
    }
}

@Composable
private fun EvoScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as GrokApplication
    var rows by remember { mutableStateOf<List<WorkoutEntity>>(emptyList()) }
    LaunchedEffect(Unit) { rows = withContext(Dispatchers.IO) { app.db.workouts().all() } }
    val week = rows.filter { System.currentTimeMillis() - it.startedAt < 7L * 86400000 }.sumOf { it.distanceM }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Evolução", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Ink)
        Text("Distância nos últimos 7 dias: ${"%.2f km".format(week / 1000.0)}", color = Ink)
        Text("Treinos guardados: ${rows.size}", color = Ink)
        rows.take(8).forEach { w ->
            Text("${w.mode} · ${"%.2f".format(w.distanceM / 1000)} km · zona ${w.inZoneRatio?.let { "${(it * 100).roundToInt()}%" } ?: "—"}", color = Ink)
        }
        Button(onClick = onBack) { Text("Voltar") }
    }
}

@Composable
private fun PermsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var presets by remember { mutableStateOf<List<PresetEntity>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        presets = withContext(Dispatchers.IO) { (ctx.applicationContext as GrokApplication).db.workouts().presets() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Permissões", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Ink)
        Text("Localização precisa desenha o percurso. O serviço em primeiro plano continua com a tela apagada. O wake lock parcial impede o relógio de dormir. Ignorar a otimização de bateria evita o Android matar o treino.", color = Ink)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            launcher.launch(perms.toTypedArray())
        }) { Text("Permitir localização e notificações") }
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 29) {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
        }) { Text("Localização em segundo plano") }
        Button(onClick = {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            ctx.startActivity(intent)
        }) { Text("Ignorar otimização de bateria") }
        Text("Presets", fontWeight = FontWeight.Black, color = Ink)
        presets.forEach { Text(it.name, color = Ink) }
        Button(onClick = onBack) { Text("Voltar") }
        Spacer(Modifier.height(8.dp))
        Text("Artes em res/drawable (ic_mascot_*) são placeholders para trocar pelos desenhos finais.", color = Ink, fontSize = 12.sp)
    }
}

private fun fmt(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun shareGpx(ctx: android.content.Context, workout: WorkoutEntity, points: List<PointEntity>) {
    val dir = File(ctx.cacheDir, "gpx").apply { mkdirs() }
    val file = File(dir, "grokrun-${workout.id}.gpx")
    val body = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<gpx version="1.1" creator="GrokRun" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>GrokRun</name><trkseg>""")
        for (p in points) {
            append("""<trkpt lat="${p.lat}" lon="${p.lon}">""")
            if (p.alt != null) append("<ele>${p.alt}</ele>")
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

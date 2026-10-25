package com.grokrun.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.grokrun.GrokApplication
import com.grokrun.MainActivity
import com.grokrun.R
import com.grokrun.data.AppPrefs
import com.grokrun.data.PointEntity
import com.grokrun.data.WhistleMoment
import com.grokrun.data.WorkoutEntity
import com.grokrun.domain.IntervalConfig
import com.grokrun.domain.MascotId
import com.grokrun.domain.Mode
import com.grokrun.domain.PaceWindow
import com.grokrun.domain.PhaseKind
import com.grokrun.domain.PhasePlan
import com.grokrun.domain.PhaseRecord
import com.grokrun.domain.RunConfig
import com.grokrun.domain.RunStatus
import com.grokrun.domain.TrackFilter
import com.grokrun.domain.TrackPoint
import com.grokrun.domain.WorkoutClock
import com.grokrun.domain.buildIntervalPhases
import com.grokrun.domain.buildRunPhase
import com.grokrun.domain.elevationChange
import com.grokrun.domain.makeRecord
import com.grokrun.domain.totalSeconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class Hud(
    val status: RunStatus,
    val phaseLabel: String,
    val remainSec: Int?,
    val elapsedSec: Int,
    val sprint: String?,
    val cycle: String?,
    val paceSec: Int?,
    val speedKmh: Double?,
    val distanceM: Double,
    val prepare: Int?,
    val mode: Mode = Mode.TIROS,
    val phaseKind: PhaseKind? = null,
    val nextLabel: String? = null,
    val totalSec: Int? = null,
    /** "manual" | "auto" (parado na rua) | null */
    val pauseReason: String? = null,
    /** "fast" | "slow" | null — faixa de pace na corrida */
    val alert: String? = null,
    /** "buscando" | "gps" */
    val gps: String = "buscando",
    val inZoneRatio: Double? = null,
)

/** Posição atual crua do GPS, só para o mapa ao vivo (o filtro do percurso continua no serviço). */
data class LivePos(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val bearing: Float?,
    val t: Long,
)

/** Ponto do percurso já aceito pelo filtro. */
data class RoutePt(val lat: Double, val lon: Double)

object Live {
    val hud = MutableStateFlow<Hud?>(null)
    val position = MutableStateFlow<LivePos?>(null)
    val route = MutableStateFlow<List<RoutePt>>(emptyList())
    @Volatile var running: Boolean = false
    /** id do treino em andamento e do último salvo (a tela ao vivo abre o resumo quando ele chega). */
    val currentId = MutableStateFlow<String?>(null)
    val lastSaved = MutableStateFlow<String?>(null)
    val saving = MutableStateFlow(false)
}

class WorkoutService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var clock: WorkoutClock? = null
    private var mode: Mode = Mode.TIROS
    private var mascot: MascotId = MascotId.BOLA
    private var interval: IntervalConfig? = null
    private var run: RunConfig? = null
    private val points = ArrayList<TrackPoint>()
    private val records = ArrayList<PhaseRecord>()
    private var closedUntil = 0
    private var dist = 0.0
    private val filter = TrackFilter()
    private val pace = PaceWindow()
    private var startedAt = 0L
    private var id = ""
    private var configJson = ""
    private var baroAnchor: Double? = null
    private var baroAlt: Double? = null
    private var outsideSince: Long? = null
    private var outsideKind: String? = null
    private var lastAlert = 0L
    private var inZone = 0L
    private var sampled = 0L
    private var lastTickWall = 0L
    private var lastPace: Double? = null
    private var lastSpeed: Double? = null
    private val finished = AtomicBoolean(false)
    // Pausa automática (session.ts: noteMotion)
    private var pauseReason: String? = null
    private var stillSince: Long? = null
    private var moveSince: Long? = null
    private var movedAt = 0L
    private var lastFixAt = 0L
    private var instKmh: Double? = null
    private var lastRaw: android.location.Location? = null
    private var alertKind: String? = null
    // Parciais por km
    private val splits = JSONArray()
    private var nextSplitKm = 1
    private var splitElapsedSec = 0.0
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var tones: ToneGenerator? = null
    private var focus: AudioFocusRequest? = null
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val c = clock ?: return
            // Só exposição para a UI: o mapa mostra a bolinha mesmo em Preparar/Pausado.
            Live.position.value = LivePos(
                loc.latitude, loc.longitude, loc.accuracy,
                if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.7f) loc.bearing else null,
                loc.time,
            )
            val nowE = SystemClock.elapsedRealtime()
            lastFixAt = nowE
            val prev = lastRaw
            instKmh = when {
                loc.hasSpeed() -> loc.speed * 3.6
                prev != null && loc.time > prev.time -> prev.distanceTo(loc) / ((loc.time - prev.time) / 1000.0) * 3.6
                else -> null
            }
            lastRaw = loc
            noteMotion(c, nowE, instKmh)
            if (c.status != RunStatus.RUNNING) return
            val alt = baroAlt ?: loc.altitude
            val source = if (baroAlt != null) "baro" else "gps"
            val accepted = filter.push(loc.latitude, loc.longitude, loc.time, loc.accuracy) ?: return
            dist += accepted.stepM
            if (accepted.stepM >= 0.8) movedAt = nowE
            val elapsedSec = c.elapsed(nowE) / 1000.0
            while (dist >= nextSplitKm * 1000.0) {
                val splitSec = elapsedSec - splitElapsedSec
                splits.put(JSONObject().put("km", nextSplitKm).put("time", elapsedSec).put("split", splitSec))
                splitElapsedSec = elapsedSec
                nextSplitKm += 1
            }
            val idx = c.indexAt(SystemClock.elapsedRealtime()).coerceAtLeast(0)
            val phase = c.phases.getOrNull(idx)
            points += TrackPoint(
                accepted.lat, accepted.lon, loc.time, loc.accuracy, alt, source,
                phase?.kind ?: PhaseKind.RUN, idx, dist,
            )
            Live.route.value = Live.route.value + RoutePt(accepted.lat, accepted.lon)
            pace.add(loc.time, dist)
            val window = run?.windowSec ?: 20
            val p = pace.pace(loc.time, window)
            if (mode == Mode.TIROS) {
                lastPace = p
                lastSpeed = p?.let { if (it in 1.0..2000.0) 3600.0 / it else 0.0 }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grokrun:workout").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> clock?.let {
                if (it.status == RunStatus.RUNNING) { it.pause(SystemClock.elapsedRealtime()); pauseReason = "manual"; alertKind = null }
            }
            ACTION_RESUME -> clock?.let {
                it.resume(SystemClock.elapsedRealtime()); pauseReason = null; stillSince = null; moveSince = null
                movedAt = SystemClock.elapsedRealtime()
            }
            // "Segurar pausa mesmo se eu andar": a pausa automática vira manual.
            ACTION_HOLD -> if (pauseReason == "auto") pauseReason = "manual"
            ACTION_STOP -> {
                scope.launch { finish(false) }
            }
            ACTION_START -> if (!Live.running) begin(intent)
        }
        refreshHud()
        return START_STICKY
    }

    private fun begin(intent: Intent) {
        configJson = intent.getStringExtra(EXTRA_CONFIG) ?: return
        val json = JSONObject(configJson)
        mode = if (json.optString("mode") == "corrida") Mode.CORRIDA else Mode.TIROS
        mascot = when (json.optString("mascot")) {
            "triangulo" -> MascotId.TRIANGULO
            "hexagono" -> MascotId.HEXAGONO
            "fantasma" -> MascotId.FANTASMA
            "oblongo" -> MascotId.OBLONGO
            "quadrado" -> MascotId.QUADRADO
            else -> MascotId.BOLA
        }
        val phases: List<PhasePlan> = if (mode == Mode.TIROS) {
            interval = IntervalConfig(
                json.getInt("warmup"), json.getInt("sprint"), json.getInt("rest"),
                json.getInt("sprints"), json.getInt("cycles"), json.getInt("cycleRest"),
                json.getInt("cooldown"),
            )
            buildIntervalPhases(interval!!)
        } else {
            run = RunConfig(json.getInt("fast"), json.getInt("slow"), json.getInt("window"))
            listOf(buildRunPhase())
        }
        id = UUID.randomUUID().toString()
        startedAt = System.currentTimeMillis()
        Live.position.value = null
        Live.route.value = emptyList()
        clock = WorkoutClock(phases).also { it.start(SystemClock.elapsedRealtime()) }
        Live.running = true
        Live.currentId.value = id
        Live.lastSaved.value = null
        movedAt = SystemClock.elapsedRealtime()
        goForeground("Preparar")
        acquireWake()
        startAudio()
        startGps()
        startBaro()
        lastTickWall = System.currentTimeMillis()
        loop = scope.launch {
            while (Live.running) {
                val now = SystemClock.elapsedRealtime()
                val c = clock ?: break
                val events = c.tick(now)
                handle(events)
                sampleZone()
                refreshHud()
                if (c.status == RunStatus.DONE) {
                    finish(true)
                    break
                }
                delay(200)
            }
        }
    }

    private fun sampleZone() {
        val c = clock ?: return
        val cfg = run ?: return
        if (mode != Mode.CORRIDA || c.status != RunStatus.RUNNING) return
        val nowWall = System.currentTimeMillis()
        val dt = (nowWall - lastTickWall).coerceIn(0L, 2000L)
        lastTickWall = nowWall
        val p = pace.pace(nowWall, cfg.windowSec) ?: return
        lastPace = p
        lastSpeed = if (p in 1.0..2000.0) 3600.0 / p else 0.0
        sampled += dt
        val fast = minOf(cfg.fastSec, cfg.slowSec)
        val slow = maxOf(cfg.fastSec, cfg.slowSec)
        val kind = when {
            p < fast -> "fast"
            p > slow -> "slow"
            else -> "in"
        }
        if (kind == "in") {
            alertKind = null
            inZone += dt
            outsideSince = null
            outsideKind = null
            return
        }
        if (outsideKind != kind) {
            outsideKind = kind
            outsideSince = nowWall
            return
        }
        val since = outsideSince ?: nowWall
        if (nowWall - since >= 5_000 && nowWall - lastAlert >= 15_000) {
            lastAlert = nowWall
            if (kind == "fast") beepFast() else beepSlow()
        }
        if (nowWall - since >= 5_000) alertKind = kind
    }

    private fun handle(events: List<String>) {
        val important = events.filter { it != "cd" }
        val last = important.lastOrNull()
        if (events.contains("cd") && last == null) beepCount()
        when {
            last == "done" -> whistle(WhistleMoment.DONE)
            last?.startsWith("phase:") == true -> {
                val kind = last.removePrefix("phase:")
                when (kind) {
                    "SPRINT" -> whistle(WhistleMoment.SPRINT)
                    "REST" -> whistle(WhistleMoment.REST)
                    "CYCLE_REST", "COOLDOWN" -> whistle(WhistleMoment.FINAL_REST)
                    else -> whistle(WhistleMoment.START)
                }
            }
        }
    }

    private suspend fun finish(natural: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        Live.running = false
        val c = clock ?: return
        val now = SystemClock.elapsedRealtime()
        if (c.status != RunStatus.DONE) {
            closeUpTo(c, c.indexAt(now), c.elapsed(now), partial = true)
        } else {
            closeUpTo(c, c.phases.size, c.elapsed(now), partial = false)
        }
        Live.saving.value = true
        if (!natural) whistle(WhistleMoment.DONE)
        stopGps()
        val corrected = correctElevation()
        val (gain, loss) = elevationChange(corrected.map { it.alt })
        val estimated = corrected.firstOrNull()?.altSource == "estimated"
        val source = corrected.firstOrNull()?.altSource ?: "estimated"
        val entity = WorkoutEntity(
            id = id,
            startedAt = startedAt,
            mode = if (mode == Mode.TIROS) "tiros" else "corrida",
            mascot = mascot.name.lowercase(),
            configJson = configJson,
            distanceM = dist,
            durationSec = c.elapsed(now) / 1000.0,
            elevGain = gain,
            elevLoss = loss,
            elevEstimated = estimated,
            elevSource = source,
            inZoneRatio = if (sampled > 0) inZone.toDouble() / sampled else null,
            phasesJson = recordsToJson(),
            splitsJson = splits.toString(),
        )
        val db = (application as GrokApplication).db
        withContext(NonCancellable) {
            db.workouts().insert(entity)
            db.workouts().insertPoints(corrected.map {
                PointEntity(
                    workoutId = id, lat = it.lat, lon = it.lon, t = it.t, acc = it.acc,
                    alt = it.alt, altSource = it.altSource, phase = it.phase.name,
                    phaseIndex = it.phaseIndex, distM = it.distM,
                )
            })
        }
        Live.saving.value = false
        Live.lastSaved.value = id
        Live.currentId.value = null
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeUpTo(c: WorkoutClock, idx: Int, elapsed: Long, partial: Boolean) {
        val limit = if (partial) idx else c.phases.size
        while (closedUntil < limit && closedUntil < c.phases.size) {
            records += makeRecord(c.phases[closedUntil], elapsed, points, closedUntil)
            closedUntil += 1
        }
        if (partial && idx < c.phases.size && closedUntil == idx) {
            records += makeRecord(c.phases[idx], elapsed, points, idx)
            closedUntil += 1
        }
    }

    private fun recordsToJson(): String {
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("kind", r.kind.name)
                put("label", r.label)
                put("cycle", r.cycle)
                put("sprint", r.sprint)
                put("duration", r.durationSec)
                put("distance", r.distanceM)
                put("pace", r.avgPaceSec)
                put("speed", r.avgSpeedKmh)
            })
        }
        return arr.toString()
    }

    private fun correctElevation(): List<TrackPoint> {
        if (points.isEmpty()) return points
        if (points.any { it.altSource == "baro" }) return points
        return try {
            val elev = fetchElevations(points)
            if (elev == null) points.map { it.copy(altSource = "estimated") }
            else points.mapIndexed { i, p -> p.copy(alt = elev[i], altSource = "dem") }
        } catch (_: Exception) {
            points.map { it.copy(altSource = "estimated") }
        }
    }

    private fun fetchElevations(src: List<TrackPoint>): List<Double>? {
        val out = ArrayList<Double>(src.size)
        var i = 0
        while (i < src.size) {
            val slice = src.subList(i, minOf(src.size, i + 80))
            val lat = slice.joinToString(",") { "%.5f".format(Locale.US, it.lat) }
            val lon = slice.joinToString(",") { "%.5f".format(Locale.US, it.lon) }
            val url = URL("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val arr = JSONObject(body).getJSONArray("elevation")
            if (arr.length() != slice.size) return null
            for (k in 0 until arr.length()) out += arr.getDouble(k)
            i += slice.size
        }
        return if (out.size == src.size) out else null
    }

    private fun refreshHud() {
        val c = clock ?: return
        val now = SystemClock.elapsedRealtime()
        val idx = c.indexAt(now)
        val phase = c.phases.getOrNull(idx)
        val remain = if (phase != null && phase.durationSec > 0 && c.status == RunStatus.RUNNING) {
            ((phase.endMs - c.elapsed(now)) / 1000.0).roundToInt().coerceAtLeast(0)
        } else null
        Live.hud.value = Hud(
            status = c.status,
            phaseLabel = if (c.status == RunStatus.PREPARE) "Preparar" else phase?.label ?: "Fim",
            remainSec = remain,
            elapsedSec = (c.elapsed(now) / 1000).toInt(),
            sprint = phase?.sprint?.let { "$it/${interval?.sprintsPerCycle ?: it}" },
            cycle = phase?.cycle?.let { "$it/${interval?.cycles ?: it}" },
            paceSec = lastPace?.takeIf { it < 20 * 60 }?.roundToInt(),
            speedKmh = lastSpeed,
            distanceM = dist,
            prepare = c.prepareDigit(now),
            mode = mode,
            phaseKind = if (c.status == RunStatus.PREPARE) null else phase?.kind,
            nextLabel = if (c.status == RunStatus.PREPARE) c.phases.firstOrNull()?.label
                else c.phases.getOrNull(idx + 1)?.label,
            totalSec = if (mode == Mode.TIROS) totalSeconds(c.phases) else null,
            pauseReason = if (c.status == RunStatus.PAUSED) pauseReason else null,
            alert = if (c.status == RunStatus.RUNNING) alertKind else null,
            gps = if (lastFixAt > 0 && now - lastFixAt < 8000) "gps" else "buscando",
            inZoneRatio = if (sampled > 0) inZone.toDouble() / sampled else null,
        )
        val label = Live.hud.value?.phaseLabel ?: "Treino"
        val time = remain?.let { "%d:%02d".format(it / 60, it % 60) } ?: ""
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification("$label $time"))
    }

    private fun startGps() {
        val req = LocationRequest.Builder(1000L)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        try {
            fused.requestLocationUpdates(req, callback, mainLooper)
        } catch (_: SecurityException) {
            /* a tela de permissão explica; sem GPS o treino segue só com o relógio */
        }
    }

    private fun stopGps() {
        fused.removeLocationUpdates(callback)
    }

    private fun startBaro() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
        sm.registerListener(object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rel = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0]).toDouble()
                if (baroAnchor == null) {
                    val gps = points.lastOrNull()?.alt ?: rel
                    baroAnchor = gps - rel
                }
                baroAlt = rel + (baroAnchor ?: 0.0)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private val audioHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val abandonFocus = Runnable {
        focus?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
    }

    private fun startAudio() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // O foco só é pedido na hora de cada bipe (ver tone()), para não abaixar a música o treino todo.
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .build()
        tones = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
    }

    /** Pede o foco (abaixa a música), toca o bipe e solta o foco ~600 ms depois do fim. */
    private fun tone(type: Int, durationMs: Int) {
        val f = focus
        if (f != null) {
            audioHandler.removeCallbacks(abandonFocus)
            (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(f)
            audioHandler.postDelayed(abandonFocus, durationMs + 600L)
        }
        tones?.startTone(type, durationMs)
    }

    private val STOP_KMH = 1.15
    private val GO_KMH = 2.3
    private val STOP_GAP_MS = 4500L

    /** Pausa o relógio se a pessoa parou na rua e solta quando volta a andar (igual à web). Pausa manual não é desfeita. */
    private fun noteMotion(c: WorkoutClock, now: Long, kmh: Double?) {
        if (!AppPrefs.autoPause || pauseReason == "manual" || c.status == RunStatus.PREPARE || c.status == RunStatus.DONE) return
        if (lastFixAt == 0L || now - lastFixAt > 8000) return
        val slow = kmh == null || kmh < STOP_KMH
        val stale = now - movedAt >= STOP_GAP_MS
        if (c.status == RunStatus.RUNNING) {
            if (slow && stale && points.size > 1) {
                if (stillSince == null) stillSince = now
                if (now - (stillSince ?: now) >= 700) {
                    c.pause(now); pauseReason = "auto"; alertKind = null; stillSince = null; moveSince = null
                }
            } else stillSince = null
            return
        }
        if (c.status == RunStatus.PAUSED && pauseReason == "auto") {
            if (kmh != null && kmh >= GO_KMH) {
                if (moveSince == null) moveSince = now
                if (now - (moveSince ?: now) >= 1200) {
                    c.resume(now); pauseReason = null; stillSince = null; moveSince = null; movedAt = now
                }
            } else moveSince = null
        }
    }

    /** Apito escolhido em Ajustes para o momento; pede foco só durante o apito (abaixa a música). */
    private fun whistle(moment: WhistleMoment) {
        val w = AppPrefs.whistles[moment] ?: moment.def
        val f = focus
        val dur = Whistles.durationMs(w)
        if (f != null) {
            audioHandler.removeCallbacks(abandonFocus)
            (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(f)
            audioHandler.postDelayed(abandonFocus, dur + 600L)
        }
        Whistles.play(w)
        buzz(Whistles.vibration(w))
    }

    private fun beepCount() {
        tone(ToneGenerator.TONE_PROP_BEEP, 80)
        buzz(longArrayOf(0, 30))
    }
    private fun beepSprint() {
        tone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 160)
        buzz(longArrayOf(0, 40, 50, 40))
    }
    private fun beepRest() {
        tone(ToneGenerator.TONE_CDMA_LOW_L, 280)
        buzz(longArrayOf(0, 180))
    }
    private fun beepStart() {
        tone(ToneGenerator.TONE_PROP_ACK, 140)
        buzz(longArrayOf(0, 60, 40, 60))
    }
    private fun beepDone() {
        tone(ToneGenerator.TONE_CDMA_CONFIRM, 400)
        buzz(longArrayOf(0, 80, 50, 80, 50, 220))
    }
    private fun beepFast() {
        tone(ToneGenerator.TONE_PROP_BEEP2, 70)
        scope.launch {
            delay(110)
            tone(ToneGenerator.TONE_PROP_BEEP2, 70)
        }
        buzz(longArrayOf(0, 30, 40, 30))
    }
    private fun beepSlow() {
        tone(ToneGenerator.TONE_CDMA_LOW_L, 520)
        buzz(longArrayOf(0, 280))
    }

    private fun buzz(pattern: LongArray) {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun acquireWake() {
        wake?.acquire(4 * 60 * 60 * 1000L)
    }

    private fun releaseWake() {
        if (wake?.isHeld == true) wake?.release()
        audioHandler.removeCallbacks(abandonFocus)
        focus?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        tones?.release()
        tones = null
    }

    private fun goForeground(text: String) {
        val notif = buildNotification(text)
        val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 34 && !hasLocation) {
            // Sem permissão de localização o tipo "location" lança SecurityException; segue só com o timer.
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val paused = clock?.status == RunStatus.PAUSED
        val toggle = pending(if (paused) ACTION_RESUME else ACTION_PAUSE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("GrokRun")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "Retomar" else "Pausar", toggle)
            .addAction(0, "Parar", pending(ACTION_STOP))
            .build()
    }

    private fun pending(action: String): PendingIntent {
        val i = Intent(this, WorkoutService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        ch.description = getString(R.string.channel_desc)
        nm.createNotificationChannel(ch)
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        releaseWake()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "grokrun"
        const val NOTIF_ID = 42
        const val ACTION_START = "com.grokrun.START"
        const val ACTION_PAUSE = "com.grokrun.PAUSE"
        const val ACTION_RESUME = "com.grokrun.RESUME"
        const val ACTION_STOP = "com.grokrun.STOP"
        const val ACTION_HOLD = "com.grokrun.HOLD"
        const val EXTRA_CONFIG = "config"

        fun startIntent(context: Context, config: String): Intent =
            Intent(context, WorkoutService::class.java).setAction(ACTION_START).putExtra(EXTRA_CONFIG, config)
    }
}

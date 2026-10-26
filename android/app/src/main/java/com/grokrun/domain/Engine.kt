package com.grokrun.domain

enum class MascotId { BOLA, TRIANGULO, HEXAGONO, FANTASMA, OBLONGO, QUADRADO }

enum class Mode { TIROS, CORRIDA }

enum class PhaseKind { WARMUP, SPRINT, REST, CYCLE_REST, COOLDOWN, RUN }

enum class RunStatus { PREPARE, RUNNING, PAUSED, DONE }

data class IntervalConfig(
    val warmupSec: Int,
    val sprintSec: Int,
    val restSec: Int,
    val sprintsPerCycle: Int,
    val cycles: Int,
    val cycleRestSec: Int,
    val cooldownSec: Int,
)

data class RunConfig(
    val fastSec: Int,
    val slowSec: Int,
    val windowSec: Int,
)

data class PhasePlan(
    val kind: PhaseKind,
    val durationSec: Int,
    val cycle: Int?,
    val sprint: Int?,
    val label: String,
    val startMs: Long,
    val endMs: Long,
)

data class PhaseRecord(
    val kind: PhaseKind,
    val label: String,
    val cycle: Int?,
    val sprint: Int?,
    val durationSec: Double,
    val distanceM: Double,
    val avgPaceSec: Double?,
    val avgSpeedKmh: Double,
)

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val t: Long,
    val acc: Float,
    val alt: Double?,
    val altSource: String,
    val phase: PhaseKind,
    val phaseIndex: Int,
    val distM: Double,
)

fun phaseLabel(kind: PhaseKind, sprint: Int?): String = when (kind) {
    PhaseKind.WARMUP -> "Aquecimento"
    PhaseKind.SPRINT -> if (sprint != null) "Tiro $sprint" else "Tiro"
    PhaseKind.REST -> "Descanso"
    PhaseKind.CYCLE_REST -> "Descanso entre ciclos"
    PhaseKind.COOLDOWN -> "Desaquecimento"
    PhaseKind.RUN -> "Corrida"
}

/** Aquecimento → [tiro → descanso] × n (sem descanso no último) → descanso de ciclo → desaquecimento. */
fun buildIntervalPhases(cfg: IntervalConfig): List<PhasePlan> {
    val out = ArrayList<PhasePlan>()
    var t = 0L
    fun push(kind: PhaseKind, sec: Int, cycle: Int?, sprint: Int?) {
        if (sec <= 0) return
        val start = t
        t += sec * 1000L
        out += PhasePlan(kind, sec, cycle, sprint, phaseLabel(kind, sprint), start, t)
    }
    push(PhaseKind.WARMUP, cfg.warmupSec, null, null)
    val cycles = cfg.cycles.coerceAtLeast(1)
    val sprints = cfg.sprintsPerCycle.coerceAtLeast(1)
    for (c in 1..cycles) {
        for (s in 1..sprints) {
            push(PhaseKind.SPRINT, cfg.sprintSec, c, s)
            if (s != sprints) push(PhaseKind.REST, cfg.restSec, c, s)
        }
        if (c != cycles) push(PhaseKind.CYCLE_REST, cfg.cycleRestSec, c, null)
    }
    push(PhaseKind.COOLDOWN, cfg.cooldownSec, null, null)
    return out
}

fun totalSeconds(phases: List<PhasePlan>): Int = phases.sumOf { it.durationSec.coerceAtLeast(0) }

fun buildRunPhase(): PhasePlan =
    PhasePlan(PhaseKind.RUN, -1, null, null, "Corrida", 0L, Long.MAX_VALUE / 4)

/**
 * Relógio ancorado em elapsedRealtime. As trocas são horários absolutos
 * recalculados ao retomar — nunca um contador que incrementa por tick.
 */
class WorkoutClock(val phases: List<PhasePlan>) {
    var status: RunStatus = RunStatus.PREPARE
        private set
    private var anchor = 0L
    private var pausedAccum = 0L
    private var pauseStarted: Long? = null
    private var prepareEnds = 0L
    var endsAt: LongArray = LongArray(0)
        private set
    val fired = HashSet<String>()

    fun start(nowElapsed: Long) {
        prepareEnds = nowElapsed + 3_000L
        anchor = prepareEnds
        status = RunStatus.PREPARE
        pausedAccum = 0L
        pauseStarted = null
    }

    fun elapsed(now: Long): Long {
        if (status == RunStatus.PREPARE) return 0L
        val freeze = if ((status == RunStatus.PAUSED || status == RunStatus.DONE) && pauseStarted != null) {
            pauseStarted!!
        } else now
        return (freeze - anchor - pausedAccum).coerceAtLeast(0L)
    }

    fun indexAt(now: Long): Int {
        val e = elapsed(now)
        if (phases.size == 1 && phases[0].kind == PhaseKind.RUN) {
            return if (status == RunStatus.PREPARE) -1 else 0
        }
        for (i in phases.indices) if (e < phases[i].endMs) return i
        return phases.size
    }

    fun pause(now: Long) {
        if (status != RunStatus.RUNNING) return
        pauseStarted = now
        status = RunStatus.PAUSED
    }

    fun resume(now: Long) {
        val started = pauseStarted ?: return
        if (status != RunStatus.PAUSED) return
        pausedAccum += now - started
        pauseStarted = null
        status = RunStatus.RUNNING
        recompute(anchor)
    }

    private fun recompute(anchorElapsed: Long) {
        endsAt = LongArray(phases.size) { i -> anchorElapsed + pausedAccum + phases[i].endMs }
    }

    /** Devolve pistas: "cd", "phase:KIND", "done". */
    fun tick(now: Long): List<String> {
        val events = ArrayList<String>()
        if (status == RunStatus.DONE) return events
        if (status == RunStatus.PREPARE) {
            val left = prepareEnds - now
            val n = when {
                left > 2000 -> 3
                left > 1000 -> 2
                left > 0 -> 1
                else -> 0
            }
            if (n > 0 && fired.add("prep-$n")) events += "cd"
            if (now < prepareEnds) return events
            status = RunStatus.RUNNING
            anchor = prepareEnds
            pausedAccum = 0
            recompute(anchor)
            fired.add("enter-0")
            events += "phase:${phases.firstOrNull()?.kind ?: PhaseKind.RUN}"
        }
        if (status == RunStatus.PAUSED) return events
        val e = elapsed(now)
        val idx = indexAt(now)
        val open = phases.size == 1 && phases[0].durationSec < 0
        if (!open) {
            val current = if (idx < phases.size) idx else phases.lastIndex
            val phase = phases.getOrNull(current)
            if (phase != null && idx < phases.size && phase.durationSec > 0) {
                val remain = phase.endMs - e
                val n = when {
                    remain <= 1000 -> 1
                    remain <= 2000 -> 2
                    remain <= 3000 -> 3
                    else -> 0
                }
                if (n > 0 && phase.durationSec >= n && fired.add("cd-$current-$n")) events += "cd"
            }
            if (idx >= phases.size) {
                // O plano acabou (apito de fim), mas a corrida só termina no Finalizar:
                // o relógio segue contando, como no site.
                if (fired.add("plan-done")) events += "done"
                return events
            }
            for (i in 1..idx) {
                if (fired.add("enter-$i")) events += "phase:${phases[i].kind}"
            }
        }
        return events
    }

    /** Estado para sobreviver à morte do processo (ver Session). */
    fun snapshot(): org.json.JSONObject = org.json.JSONObject()
        .put("status", status.name)
        .put("anchor", anchor)
        .put("pausedAccum", pausedAccum)
        .put("pauseStarted", pauseStarted ?: -1L)
        .put("prepareEnds", prepareEnds)
        .put("fired", org.json.JSONArray(fired.toList()))

    /** Restaura um [snapshot]; [shift] corrige o relógio de boot se o aparelho reiniciou. */
    fun restore(j: org.json.JSONObject, shift: Long) {
        status = RunStatus.valueOf(j.getString("status"))
        anchor = j.getLong("anchor") + shift
        pausedAccum = j.getLong("pausedAccum")
        pauseStarted = j.getLong("pauseStarted").takeIf { it >= 0 }?.plus(shift)
        prepareEnds = j.getLong("prepareEnds") + shift
        fired.clear()
        val arr = j.optJSONArray("fired")
        if (arr != null) for (i in 0 until arr.length()) fired += arr.getString(i)
        if (status != RunStatus.PREPARE) recompute(anchor)
    }

    fun prepareDigit(now: Long): Int? {
        if (status != RunStatus.PREPARE) return null
        val left = prepareEnds - now
        return when {
            left > 2000 -> 3
            left > 1000 -> 2
            left > 0 -> 1
            else -> null
        }
    }
}

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1)
    val dl = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dp / 2) * Math.sin(dp / 2) +
        Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
    return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
}

/** Precisão > 20 m e saltos > 25 km/h são descartados. Suavização leve por EMA. */
class TrackFilter {
    private var lat = 0.0
    private var lon = 0.0
    private var ready = false
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastT = 0L
    private var hasLast = false

    data class Accepted(val lat: Double, val lon: Double, val stepM: Double)

    fun push(latIn: Double, lonIn: Double, t: Long, acc: Float, maxAcc: Float = 20f, maxKmh: Double = 25.0): Accepted? {
        if (!latIn.isFinite() || !lonIn.isFinite()) return null
        if (!acc.isFinite() || acc > maxAcc) return null
        var step = 0.0
        if (hasLast) {
            val dt = (t - lastT) / 1000.0
            if (dt < 0.3) return null
            step = haversine(lastLat, lastLon, latIn, lonIn)
            if (step / dt * 3.6 > maxKmh) return null
        }
        if (!ready) {
            lat = latIn
            lon = lonIn
            ready = true
        } else {
            lat += 0.6 * (latIn - lat)
            lon += 0.6 * (lonIn - lon)
        }
        lastLat = latIn
        lastLon = lonIn
        lastT = t
        hasLast = true
        return Accepted(lat, lon, step)
    }
}

class PaceWindow {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(t: Long, distM: Double) {
        samples.addLast(t to distM)
    }

    fun pace(now: Long, windowSec: Int): Double? {
        val cutoff = now - windowSec * 1000L
        while (samples.size > 2 && samples.first().first < cutoff) samples.removeFirst()
        val inWin = samples.filter { it.first >= cutoff }
        if (inWin.size < 2) return null
        val dt = (inWin.last().first - inWin.first().first) / 1000.0
        val d = inWin.last().second - inWin.first().second
        if (dt < minOf(5.0, windowSec * 0.4)) return null
        if (d < 1.0) return 99_999.0
        return dt / (d / 1000.0)
    }
}

fun phaseDistance(points: List<TrackPoint>, index: Int): Double {
    var base = 0.0
    var last = 0.0
    var any = false
    for (p in points) {
        if (p.phaseIndex < index) base = p.distM
        if (p.phaseIndex == index) {
            any = true
            last = p.distM
        }
    }
    return if (any) (last - base).coerceAtLeast(0.0) else 0.0
}

fun makeRecord(phase: PhasePlan, elapsedMs: Long, points: List<TrackPoint>, index: Int): PhaseRecord {
    val end = if (phase.durationSec < 0) elapsedMs else minOf(elapsedMs, phase.endMs)
    val duration = ((end - phase.startMs).coerceAtLeast(0L)) / 1000.0
    val distance = phaseDistance(points, index)
    val speed = if (duration > 0) distance / 1000.0 / (duration / 3600.0) else 0.0
    val pace = if (distance >= 8.0) duration / (distance / 1000.0) else null
    return PhaseRecord(phase.kind, phase.label, phase.cycle, phase.sprint, duration, distance, pace, speed)
}

fun elevationChange(alts: List<Double?>, threshold: Double = 2.5): Pair<Double, Double> {
    var gain = 0.0
    var loss = 0.0
    var extreme: Double? = null
    for (alt in alts) {
        if (alt == null || !alt.isFinite()) continue
        if (extreme == null) {
            extreme = alt
            continue
        }
        val diff = alt - extreme
        if (diff >= threshold) {
            gain += diff
            extreme = alt
        } else if (diff <= -threshold) {
            loss += -diff
            extreme = alt
        }
    }
    return gain to loss
}

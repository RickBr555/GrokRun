package com.grokrun.service

import android.content.Context
import com.grokrun.domain.PhaseKind
import com.grokrun.domain.TrackPoint
import org.json.JSONObject
import java.io.File

/**
 * Treino em andamento salvo no disco: se o Android matar o processo (app tirado dos recentes,
 * falta de memória, reinício), o serviço volta (START_STICKY) ou a próxima abertura do app
 * retoma do ponto em que parou. Só some quando o treino é finalizado.
 */
object Session {
    private const val PREFS = "grokrun_session"
    private const val KEY = "state"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun pointsFile(ctx: Context) = File(ctx.filesDir, "session_points.csv")

    fun exists(ctx: Context): Boolean = prefs(ctx).contains(KEY)

    fun save(ctx: Context, state: JSONObject) {
        prefs(ctx).edit().putString(KEY, state.toString()).commit()
    }

    fun load(ctx: Context): JSONObject? = try {
        prefs(ctx).getString(KEY, null)?.let { JSONObject(it) }
    } catch (_: Exception) { null }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).commit()
        pointsFile(ctx).delete()
    }

    /** Acrescenta pontos novos (uma linha por ponto). */
    fun appendPoints(ctx: Context, pts: List<TrackPoint>) {
        if (pts.isEmpty()) return
        val sb = StringBuilder()
        for (p in pts) {
            sb.append(p.lat).append(',').append(p.lon).append(',').append(p.t).append(',').append(p.acc).append(',')
                .append(p.alt ?: "").append(',').append(p.altSource).append(',').append(p.phase.name).append(',')
                .append(p.phaseIndex).append(',').append(p.distM).append('\n')
        }
        pointsFile(ctx).appendText(sb.toString())
    }

    fun readPoints(ctx: Context): List<TrackPoint> {
        val f = pointsFile(ctx)
        if (!f.exists()) return emptyList()
        val out = ArrayList<TrackPoint>()
        f.forEachLine { line ->
            val c = line.split(',')
            if (c.size < 9) return@forEachLine
            try {
                out += TrackPoint(
                    c[0].toDouble(), c[1].toDouble(), c[2].toLong(), c[3].toFloat(), c[4].toDoubleOrNull(),
                    c[5], PhaseKind.valueOf(c[6]), c[7].toInt(), c[8].toDouble(),
                )
            } catch (_: Exception) { /* linha cortada no meio: ignora */ }
        }
        return out
    }
}

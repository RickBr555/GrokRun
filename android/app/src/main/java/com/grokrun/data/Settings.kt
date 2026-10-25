package com.grokrun.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Apitos da web (audio.ts). */
enum class WhistleId(val id: String, val label: String) {
    CURTO("curto", "Curto"),
    LONGO("longo", "Longo"),
    DUPLO("duplo", "Duplo"),
    TRIPLO("triplo", "Triplo"),
    SOBE("sobe", "Sobe"),
    DESCE("desce", "Desce"),
    TRINADO("trinado", "Trinado"),
    AGUDO("agudo", "Agudo");

    companion object {
        fun of(id: String?, def: WhistleId) = entries.firstOrNull { it.id == id } ?: def
    }
}

/** Momentos do treino que tocam apito (WhistleMoment). */
enum class WhistleMoment(val id: String, val label: String, val hint: String, val def: WhistleId) {
    START("start", "Começa a corrida", "aquecimento ou largada", WhistleId.TRIPLO),
    SPRINT("sprint", "Começa o tiro", "", WhistleId.DUPLO),
    REST("rest", "Descanso entre tiros", "", WhistleId.LONGO),
    FINAL_REST("finalRest", "Descanso final", "fim das repetições", WhistleId.DESCE),
    DONE("done", "Final", "treino encerrado", WhistleId.SOBE),
}

/**
 * Ajustes persistentes (equivalente ao Settings + profile do storage.ts da web).
 * Estado observável para a UI; o serviço lê os mesmos valores (mesmo processo).
 */
object AppPrefs {
    private const val FILE = "grokrun_prefs"
    var runnerName by mutableStateOf("")
        private set
    var profileReady by mutableStateOf(false)
        private set
    var autoPause by mutableStateOf(true)
        private set
    var whistles by mutableStateOf(WhistleMoment.entries.associateWith { it.def })
        private set
    var raceBest by mutableStateOf(0)
        private set

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = sp(ctx)
        runnerName = p.getString("runnerName", "") ?: ""
        profileReady = p.getBoolean("profileReady", false)
        autoPause = p.getBoolean("autoPause", true)
        whistles = WhistleMoment.entries.associateWith { WhistleId.of(p.getString("whistle_${it.id}", null), it.def) }
        raceBest = p.getInt("raceBest", 0)
    }

    fun setProfile(ctx: Context, name: String) {
        sp(ctx).edit().putString("runnerName", name.trim().take(24)).putBoolean("profileReady", true).apply()
        runnerName = name.trim().take(24); profileReady = true
    }

    fun setName(ctx: Context, name: String) {
        val n = name.take(24)
        sp(ctx).edit().putString("runnerName", n).apply(); runnerName = n
    }

    fun setAutoPause(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("autoPause", on).apply(); autoPause = on
    }

    fun setWhistle(ctx: Context, moment: WhistleMoment, w: WhistleId) {
        sp(ctx).edit().putString("whistle_${moment.id}", w.id).apply()
        whistles = whistles.toMutableMap().also { it[moment] = w }
    }

    fun setRaceBest(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("raceBest", v).apply(); raceBest = v
    }
}

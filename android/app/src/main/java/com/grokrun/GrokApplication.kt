package com.grokrun

import android.app.Application
import com.grokrun.data.AppDatabase
import com.grokrun.data.PresetEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class GrokApplication : Application() {
    lateinit var db: AppDatabase
        private set
    /** Trabalho que pode passar do fim de uma tela/serviço (ex.: corrigir altitude depois de salvar). */
    val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        com.grokrun.ui.ThemePrefs.load(this)
        com.grokrun.data.AppPrefs.load(this)
        com.grokrun.ui.MascotPrefs.load(this)
        // osmdroid: carrega preferências (cache de tiles) e define o user agent exigido pelos servidores do OSM.
        org.osmdroid.config.Configuration.getInstance().apply {
            load(this@GrokApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        CoroutineScope(Dispatchers.IO).launch { seedPresets() }
    }

    /** Presets iguais aos da web (seed.ts). Troca os antigos p1..p4 uma vez, sem mexer nos presets do usuário. */
    private suspend fun seedPresets() {
        val dao = db.workouts()
        val prefs = getSharedPreferences("grokrun_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("seed_v2", false)) return
        listOf("p1", "p2", "p3", "p4").forEach { dao.deletePreset(it) }
        BUILTIN.forEach { dao.insertPreset(it) }
        prefs.edit().putBoolean("seed_v2", true).apply()
    }

    companion object {
        val BUILTIN_ORDER = listOf("preset-classico", "preset-3x2", "preset-curto", "preset-teste", "preset-leve", "preset-limiar")
        val BUILTIN: List<PresetEntity> by lazy {
            listOf(
                presetTiros("preset-classico", "Clássico 4×1 min", 300, 60, 60, 4, 1, 0, 180),
                presetTiros("preset-3x2", "3 tiros de 1 min, 2 ciclos", 300, 60, 60, 3, 2, 120, 180),
                presetTiros("preset-curto", "Tiros curtos 8×30 s", 240, 30, 30, 8, 1, 0, 180),
                presetTiros("preset-teste", "Teste rápido", 5, 8, 5, 2, 1, 0, 5),
                presetRun("preset-leve", "Leve 6:00–6:30", 360, 390),
                presetRun("preset-limiar", "Limiar 5:00–5:20", 300, 320),
            )
        }

        fun presetRun(id: String, name: String, fast: Int, slow: Int, window: Int = 20) = PresetEntity(
            id, name, "corrida",
            JSONObject().put("mode", "corrida").put("fast", fast).put("slow", slow).put("window", window).toString(),
        )

        fun presetTiros(id: String, name: String, w: Int, s: Int, r: Int, n: Int, c: Int, cr: Int, cd: Int): PresetEntity {
            val json = JSONObject()
                .put("mode", "tiros")
                .put("warmup", w).put("sprint", s).put("rest", r)
                .put("sprints", n).put("cycles", c).put("cycleRest", cr).put("cooldown", cd)
                .toString()
            return PresetEntity(id, name, "tiros", json)
        }
    }
}

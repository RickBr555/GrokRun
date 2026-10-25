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

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        CoroutineScope(Dispatchers.IO).launch { seedPresets() }
    }

    private suspend fun seedPresets() {
        val dao = db.workouts()
        if (dao.presets().isNotEmpty()) return
        val presets = listOf(
            presetTiros("p1", "Clássico 4×1 min", 300, 60, 60, 4, 1, 0, 180),
            presetTiros("p2", "3 tiros de 1 min, 2 ciclos", 300, 60, 60, 3, 2, 120, 180),
            presetTiros("p3", "Teste rápido", 5, 8, 5, 2, 1, 0, 5),
            PresetEntity(
                "p4",
                "Leve 6:00–6:30",
                "corrida",
                JSONObject().put("mode", "corrida").put("fast", 360).put("slow", 390).put("window", 20).toString(),
            ),
        )
        presets.forEach { dao.insertPreset(it) }
    }

    private fun presetTiros(id: String, name: String, w: Int, s: Int, r: Int, n: Int, c: Int, cr: Int, cd: Int): PresetEntity {
        val json = JSONObject()
            .put("mode", "tiros")
            .put("warmup", w).put("sprint", s).put("rest", r)
            .put("sprints", n).put("cycles", c).put("cycleRest", cr).put("cooldown", cd)
            .toString()
        return PresetEntity(id, name, "tiros", json)
    }
}

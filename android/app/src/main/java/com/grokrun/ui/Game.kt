package com.grokrun.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.grokrun.data.AppPrefs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private class Slot { var x = 0f; var on = false }

private class RaceSim {
    var on = false
    var y = 0f
    var vy = 0f
    var speed = 340f
    var dist = 0f
    var wait = 0.7f
    var travel = 0f
    var time = 0f
    var w = 360f
    val slots = List(3) { Slot() }
}

/**
 * "Jogo de rua" — porte do RaceGame da web (Jogo da pista): o bot fica parado,
 * a rua (o mesmo cenário vivo da Pista) anda e os obstáculos vêm da direita. Toque = pulo.
 */
@Composable
fun RaceGameScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalGrokTheme.current
    val m = MascotPrefs.current
    val sim = remember { RaceSim() }
    val frame = remember { mutableLongStateOf(0L) }
    var phase by remember { mutableStateOf("ready") }
    var finalScore by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun finish() {
        if (!sim.on) return
        sim.on = false
        val score = floor(sim.dist).toInt()
        if (score > AppPrefs.raceBest) AppPrefs.setRaceBest(ctx, score)
        finalScore = score
        phase = "dead"
    }

    fun tap() {
        if (sim.on) {
            if (sim.y <= 2f) sim.vy = 860f
            return
        }
        sim.on = true; sim.y = 0f; sim.vy = 860f; sim.speed = 340f; sim.dist = 0f; sim.wait = 0.85f
        sim.slots.forEach { it.on = false; it.x = 0f }
        phase = "run"
    }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L
            while (true) {
                withFrameNanos { n ->
                    val dt = if (last == 0L) 0f else min(0.033f, (n - last) / 1e9f)
                    last = n
                    sim.time += dt
                    if (sim.on) {
                        val w = sim.w
                        sim.vy -= 2400f * dt
                        sim.y = max(0f, sim.y + sim.vy * dt)
                        if (sim.y == 0f) sim.vy = 0f
                        sim.speed = min(640f, sim.speed + 8f * dt)
                        sim.dist += sim.speed * dt / 18f
                        sim.travel += sim.speed * dt
                        sim.wait -= dt
                        if (sim.wait <= 0f) {
                            sim.slots.firstOrNull { !it.on }?.let { it.on = true; it.x = w + 10f }
                            sim.wait = max(0.72f, 1.35f - sim.dist / 400f)
                        }
                        val runnerLeft = w * 0.16f + 28f
                        val runnerRight = runnerLeft + 36f
                        for (s in sim.slots) {
                            if (!s.on) continue
                            s.x -= sim.speed * dt
                            if (s.x < -80f) s.on = false
                            val hitX = s.x < runnerRight && s.x + 28f > runnerLeft
                            if (hitX && sim.y < 46f) finish()
                        }
                    } else {
                        // parado: a rua anda devagar, só para o fundo não congelar
                        sim.travel += 40f * dt
                    }
                    frame.longValue = n
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackBar("Jogo", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { Score(sim, frame) }
            Hint("recorde ${AppPrefs.raceBest} m")
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(22.dp)).border(3.dp, t.ink, RoundedCornerShape(22.dp))
                .pointerInput(Unit) { detectTapGestures(onPress = { tap() }) },
        ) {
            val h = maxHeight
            sim.w = maxWidth.value
            val ground = h * 0.905f
            ParallaxScene({ frame.longValue; sim.travel }, Modifier.fillMaxSize())
            // obstáculos (.race-hurdle)
            Canvas(Modifier.fillMaxSize()) {
                frame.longValue
                val hw = 42.dp.toPx(); val hh = 50.dp.toPx()
                val base = ground.toPx() + 2.dp.toPx()
                for (s in sim.slots) {
                    if (!s.on) continue
                    val x = s.x * density
                    val top = base - hh
                    val ink = Color(0xFF1C140C)
                    drawRoundRect(Color(0xFFF4E7C4), Offset(x, top), Size(hw, hh), CornerRadius(11.dp.toPx()))
                    drawRect(Color.White, Offset(x + 3.dp.toPx(), top + 6.dp.toPx()), Size(4.dp.toPx(), hh - 6.dp.toPx()))
                    drawRect(Color.White, Offset(x + hw - 7.dp.toPx(), top + 6.dp.toPx()), Size(4.dp.toPx(), hh - 6.dp.toPx()))
                    drawRect(ink, Offset(x, top + 8.dp.toPx()), Size(hw, 4.dp.toPx()))
                    drawRoundRect(ink, Offset(x, top), Size(hw, hh), CornerRadius(11.dp.toPx()), style = Stroke(3.dp.toPx()))
                }
            }
            val mh = 118.dp
            Box(
                Modifier.offset(x = maxWidth * 0.12f, y = ground - mh).size(mh * 0.9f, mh),
                contentAlignment = Alignment.Center,
            ) {
                RunCycle(
                    m, mh,
                    Modifier.graphicsLayer {
                        frame.longValue
                        translationY = -sim.y * density
                        rotationZ = if (sim.y > 2f) -8f else 0f
                    },
                    time = { frame.longValue; sim.time },
                    hz = 1.5f,
                    // no ar: perna da frente encolhida, sem "correr no vento"
                    frozenFrame = { if (sim.y > 2f) 11 else null },
                )
            }
            if (phase != "run") {
                Column(
                    Modifier.fillMaxSize().background(Color(0xB8F4E7C4)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val ink = Color(0xFF1C140C)
                    if (phase == "ready") {
                        DisplayText("Toque para pular", 30.sp, color = ink, align = TextAlign.Center)
                        Body("O obstáculo vem da direita. Pule por cima.", size = 14.sp, color = ink, align = TextAlign.Center)
                    } else {
                        DisplayText("Bateu", 30.sp, color = ink)
                        Body("$finalScore m${if (finalScore >= AppPrefs.raceBest && finalScore > 0) " · novo recorde" else ""}", size = 14.sp, color = ink)
                        Body("Toque para tentar de novo", size = 14.sp, color = ink)
                    }
                }
            }
        }
        Hint("O boneco fica parado. A rua anda. Cada toque é um pulo, só com o pé no chão.", alpha = 0.65f)
    }
}

/** Placar num escopo próprio: só ele recompõe a cada quadro. */
@Composable
private fun Score(sim: RaceSim, frame: androidx.compose.runtime.MutableLongState) {
    frame.longValue
    DisplayText("${floor(sim.dist).toInt()} m", 30.sp)
}

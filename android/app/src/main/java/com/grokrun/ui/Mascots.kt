package com.grokrun.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grokrun.R

/** Os seis mascotes desenhados pelo Ricardo. [id] é o valor salvo no JSON do treino e no Room. */
enum class Mascot(
    val id: String,
    val label: String,
    @DrawableRes val idle: Int,
    @DrawableRes val run: Int,
    @DrawableRes val rest: Int,
) {
    BOLA("bola", "Bola", R.drawable.mascot_bola_idle, R.drawable.mascot_bola_run, R.drawable.mascot_bola_rest),
    TRIANGULO("triangulo", "Triângulo", R.drawable.mascot_triangulo_idle, R.drawable.mascot_triangulo_run, R.drawable.mascot_triangulo_rest),
    HEXAGONO("hexagono", "Hexágono", R.drawable.mascot_hexagono_idle, R.drawable.mascot_hexagono_run, R.drawable.mascot_hexagono_rest),
    FANTASMA("fantasma", "Fantasma", R.drawable.mascot_fantasma_idle, R.drawable.mascot_fantasma_run, R.drawable.mascot_fantasma_rest),
    OBLONGO("oblongo", "Boné", R.drawable.mascot_oblongo_idle, R.drawable.mascot_oblongo_run, R.drawable.mascot_oblongo_rest),
    QUADRADO("quadrado", "Quadrado", R.drawable.mascot_quadrado_idle, R.drawable.mascot_quadrado_run, R.drawable.mascot_quadrado_rest);

    companion object {
        fun of(id: String?): Mascot = entries.firstOrNull { it.id == id } ?: BOLA
    }
}

object MascotPrefs {
    private const val FILE = "grokrun_prefs"
    private const val KEY = "mascot"
    /** Observável: trocar o bot em Ajustes atualiza a Pista na hora. */
    var current by androidx.compose.runtime.mutableStateOf(Mascot.BOLA)
        private set
    fun load(ctx: Context) { current = get(ctx) }
    fun get(ctx: Context): Mascot = Mascot.of(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
    fun set(ctx: Context, m: Mascot) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, m.id).apply()
        current = m
    }
}

/** Sprite de corrida com balanço vertical e leve inclinação, estilo rubber hose. */
@Composable
fun RunningMascot(m: Mascot, size: Dp, modifier: Modifier = Modifier, periodMs: Int = 420) {
    val t = rememberInfiniteTransition(label = "run")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Reverse), label = "bob")
    Image(
        painterResource(m.run),
        contentDescription = "${m.label} correndo",
        modifier = modifier.size(size).graphicsLayer {
            translationY = -p * size.toPx() * 0.08f
            rotationZ = -4f + p * 8f
            scaleY = 1f - (1f - p) * 0.04f
        },
    )
}

/** Raia de pista: fundo terracota com linhas brancas. */
@Composable
fun RunningLane(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clipToBounds(), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            drawRect(Color(0xFFC8553D))
            val w = 4.dp.toPx()
            drawLine(Color.White, Offset(0f, w), Offset(size.width, w), w)
            drawLine(Color.White, Offset(0f, size.height - w), Offset(size.width, size.height - w), w)
            var x = 0f
            while (x < size.width) {
                drawLine(Color.White.copy(alpha = 0.7f), Offset(x, size.height / 2), Offset(x + 30.dp.toPx(), size.height / 2), 3.dp.toPx())
                x += 60.dp.toPx()
            }
        }
        content()
    }
}

/** "Corrida": vários mascotes diferentes correndo juntos. */
@Composable
fun GroupRunAnimation(modifier: Modifier = Modifier) {
    RunningLane(modifier.fillMaxWidth().height(130.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            Mascot.entries.forEachIndexed { i, m ->
                RunningMascot(m, 56.dp, Modifier.offset(y = (-8 - (i % 2) * 10).dp), periodMs = 360 + i * 40)
            }
        }
    }
}

/** "Tiros": um mascote passando muito rápido, em loop. */
@Composable
fun SprintAnimation(m: Mascot, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "sprint")
    val x by t.animateFloat(-0.3f, 1.1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "x")
    BoxWithConstraints(modifier.fillMaxWidth().height(130.dp)) {
        val w = maxWidth
        RunningLane(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                RunningMascot(m, 90.dp, Modifier.offset(x = w * x, y = (-10).dp), periodMs = 160)
            }
        }
    }
}

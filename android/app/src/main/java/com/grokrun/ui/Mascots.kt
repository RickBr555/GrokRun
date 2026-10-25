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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import kotlin.math.roundToInt
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

/** Folha 4×4 com o ciclo de corrida (16 quadros) de cada mascote, gerada a partir dos desenhos do Ricardo. */
val Mascot.runSheet: Int
    get() = when (this) {
        Mascot.BOLA -> R.drawable.runsheet_bola
        Mascot.TRIANGULO -> R.drawable.runsheet_triangulo
        Mascot.HEXAGONO -> R.drawable.runsheet_hexagono
        Mascot.FANTASMA -> R.drawable.runsheet_fantasma
        Mascot.OBLONGO -> R.drawable.runsheet_oblongo
        Mascot.QUADRADO -> R.drawable.runsheet_quadrado
    }

/** A mesma folha em tamanho pequeno (para mascotes de até ~120 dp). */
val Mascot.runSheetSmall: Int
    get() = when (this) {
        Mascot.BOLA -> R.drawable.runsheet_bola_s
        Mascot.TRIANGULO -> R.drawable.runsheet_triangulo_s
        Mascot.HEXAGONO -> R.drawable.runsheet_hexagono_s
        Mascot.FANTASMA -> R.drawable.runsheet_fantasma_s
        Mascot.OBLONGO -> R.drawable.runsheet_oblongo_s
        Mascot.QUADRADO -> R.drawable.runsheet_quadrado_s
    }

const val RUN_FRAMES = 16
/** 1,3 ciclo por segundo = 2,6 passadas por segundo. */
const val RUN_HZ = 1.3f
/** O quadro tem 600×540 no espaço do sprite antigo (512 px de altura), com o boneco centralizado. */
private const val FRAME_W = 600f / 512f
private const val FRAME_H = 540f / 512f

/** Relógio próprio (segundos) para quem não tem um. Só lê o estado no desenho: não recompõe a cada quadro. */
@Composable
fun rememberRunTime(): State<Float> {
    val s = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { s.floatValue = (it - start) / 1_000_000_000f }
    }
    return s
}

/**
 * Ciclo de corrida de verdade: braços e pernas rubber hose balançando em fase oposta,
 * quique do corpo e amasso na pisada (já desenhados nos quadros). [height] é a altura do
 * sprite antigo; o quadro sai um pouco maior e centralizado no mesmo lugar.
 */
@Composable
fun RunCycle(
    m: Mascot,
    height: Dp,
    modifier: Modifier = Modifier,
    small: Boolean = height < 130.dp,
    time: (() -> Float)? = null,
    hz: Float = RUN_HZ,
    frozenFrame: (() -> Int?)? = null,
) {
    val img = ImageBitmap.imageResource(if (small) m.runSheetSmall else m.runSheet)
    val own = if (time == null) rememberRunTime() else null
    val fw = img.width / 4
    val fh = img.height / 4
    Canvas(modifier.requiredSize(height * FRAME_W, height * FRAME_H)) {
        val t = time?.invoke() ?: own!!.value
        val f = frozenFrame?.invoke() ?: floor(t * hz * RUN_FRAMES).toInt().mod(RUN_FRAMES)
        drawImage(
            img,
            srcOffset = IntOffset((f % 4) * fw, (f / 4) * fh),
            srcSize = IntSize(fw, fh),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** Mascote correndo (ciclo completo). [periodMs] antigo vira velocidade: menor = mais rápido. */
@Composable
fun RunningMascot(m: Mascot, size: Dp, modifier: Modifier = Modifier, periodMs: Int = 420) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        RunCycle(m, size, hz = RUN_HZ * 420f / periodMs)
    }
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

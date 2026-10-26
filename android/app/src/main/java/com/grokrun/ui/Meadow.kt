package com.grokrun.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.grokrun.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Desenha a faixa de grama (azulada no tema escuro) repetida na horizontal, de [top] até [top]+[h]. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrassStrip(grass: ImageBitmap, top: Float, h: Float, dark: Boolean) {
    val filter = if (dark) ColorFilter.lighting(Color(0xFF5F7FA8), Color(0xFF02101F)) else null
    val tileW = grass.width * h / grass.height
    val n = ceil(size.width / tileW).toInt() + 1
    for (i in 0 until n) {
        drawImage(
            grass,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(grass.width, grass.height),
            dstOffset = IntOffset((i * tileW).roundToInt(), top.roundToInt()),
            dstSize = IntSize(ceil(tileW).toInt() + 1, ceil(h).toInt() + 1),
            colorFilter = filter,
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** Céu em degradê das telas de campo. */
fun meadowSky(t: GrokTheme): Brush = if (t.dark) Brush.verticalGradient(
    0f to Color(0xFF001A33), 0.6f to Color(0xFF0A3D6E), 1f to Color(0xFF1B5287),
) else Brush.verticalGradient(
    0f to Color(0xFF9ED6F0), 0.5f to Color(0xFFDDF0F5), 1f to lerp(t.paper, Color.White, 0.35f),
)

/**
 * Fundo das telas de Modo e Montar treino: céu em degradê (cores do tema) e uma faixa de
 * grama desenhada embaixo. Sobe por trás da barra de status; o conteúdo começa abaixo dela.
 */
@Composable
fun MeadowBackground(
    modifier: Modifier = Modifier,
    grassHeight: Dp = 96.dp,
    critters: List<Int> = emptyList(),
    content: @Composable BoxScope.() -> Unit,
) {
    val t = LocalGrokTheme.current
    val grass = ImageBitmap.imageResource(R.drawable.bg_meadow_grass)
    // Mascotes parados na grama (sprites idle/rest): o primeiro à esquerda, os outros à direita.
    val critterImgs = critters.map { ImageBitmap.imageResource(it) }
    val stops: Array<Pair<Float, Color>> = if (t.dark) arrayOf(
        0f to Color(0xFF001A33),
        0.6f to Color(0xFF0A3D6E),
        1f to Color(0xFF1B5287),
    ) else arrayOf(
        0f to Color(0xFF9ED6F0),
        0.5f to Color(0xFFDDF0F5),
        1f to lerp(t.paper, Color.White, 0.35f),
    )
    // De noite a grama fica azulada e escura, como o resto do tema Azul.
    val filter = if (t.dark) ColorFilter.lighting(Color(0xFF5F7FA8), Color(0xFF02101F)) else null
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(*stops))
            val gh = grassHeight.toPx()
            val scale = gh / grass.height
            val tileW = grass.width * scale
            val y = size.height - gh
            val n = ceil(size.width / tileW).toInt() + 1
            for (i in 0 until n) {
                drawImage(
                    grass,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(grass.width, grass.height),
                    dstOffset = IntOffset((i * tileW).roundToInt(), y.roundToInt()),
                    dstSize = IntSize(ceil(tileW).toInt() + 1, ceil(gh).toInt() + 1),
                    colorFilter = filter,
                    filterQuality = FilterQuality.Medium,
                )
            }
            if (critterImgs.isNotEmpty()) {
                val ch = 58.dp.toPx()
                val feet = size.height - gh * 0.42f
                val margin = 6.dp.toPx()
                var right = size.width - margin
                critterImgs.forEachIndexed { i, img ->
                    val cw = img.width * ch / img.height
                    val x = if (i == 0) margin else (right - cw).also { right -= cw + 2.dp.toPx() }
                    drawImage(
                        img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(x.roundToInt(), (feet - ch).roundToInt()),
                        dstSize = IntSize(cw.roundToInt(), ch.roundToInt()),
                        colorFilter = if (t.dark) ColorFilter.lighting(Color(0xFFB8C8E0), Color(0xFF000000)) else null,
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().statusBarsPadding(), content = content)
    }
}


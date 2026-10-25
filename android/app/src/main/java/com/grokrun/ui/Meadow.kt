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

/**
 * Fundo das telas de Modo e Montar treino: céu em degradê (cores do tema) e uma faixa de
 * grama desenhada embaixo. Sobe por trás da barra de status; o conteúdo começa abaixo dela.
 */
@Composable
fun MeadowBackground(modifier: Modifier = Modifier, grassHeight: Dp = 96.dp, content: @Composable BoxScope.() -> Unit) {
    val t = LocalGrokTheme.current
    val grass = ImageBitmap.imageResource(R.drawable.bg_meadow_grass)
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
        }
        Box(Modifier.fillMaxSize().statusBarsPadding(), content = content)
    }
}


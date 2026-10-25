package com.grokrun.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.grokrun.R
import com.grokrun.data.AppPrefs
import kotlin.math.ceil
import kotlin.math.sin

/** Velocidades das camadas em dp/s (a pista é a referência). */
private const val SKY = 8f
private const val TOWN = 22f
private const val TREES = 60f
private const val TRACK = 160f

/**
 * Relógio de quadros que só anda com a tela em RESUMED: pausa sozinho em segundo plano
 * (zero CPU), e a leitura acontece só na fase de desenho, sem recompor.
 */
@Composable
fun rememberFrameClock(): () -> Float {
    val nanos = remember { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L
            while (true) {
                withFrameNanos { n ->
                    if (last != 0L) nanos.longValue += (n - last).coerceAtMost(50_000_000L)
                    last = n
                }
            }
        }
    }
    return { nanos.longValue / 1_000_000_000f }
}

class SceneLayers(val sky: ImageBitmap, val town: ImageBitmap, val trees: ImageBitmap, val track: ImageBitmap)

@Composable
fun rememberSceneLayers(): SceneLayers {
    val sky = ImageBitmap.imageResource(R.drawable.bg_sky)
    val town = ImageBitmap.imageResource(R.drawable.bg_town)
    val trees = ImageBitmap.imageResource(R.drawable.bg_trees)
    val track = ImageBitmap.imageResource(R.drawable.bg_track)
    return remember(sky, town, trees, track) { SceneLayers(sky, town, trees, track) }
}

/** Desenha uma camada repetida na horizontal, deslocada [offsetPx]. */
private fun DrawScope.tiled(img: ImageBitmap, topF: Float, hF: Float, offsetPx: Float, extendToF: Float = 0f) {
    val h = size.height * hF
    val w = img.width * h / img.height
    if (w < 1f) return
    val top = (size.height * topF).toInt()
    var x = -(offsetPx % w)
    val dw = ceil(w).toInt() + 1
    val dh = ceil(h).toInt()
    val extY = ceil(size.height * extendToF).toInt()
    while (x < size.width) {
        drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset(x.toInt(), top), dstSize = IntSize(dw, dh))
        // Chão contínuo: estica as 2 últimas linhas da camada até [extendToF], por trás das
        // árvores e da calçada, para o céu nunca aparecer entre a paisagem e a pista.
        if (extY > top + dh) {
            drawImage(img, srcOffset = IntOffset(0, img.height - 2), srcSize = IntSize(img.width, 2),
                dstOffset = IntOffset(x.toInt(), top + dh), dstSize = IntSize(dw, extY - top - dh))
        }
        x += w
    }
}

/**
 * Cenário vivo: céu, cidade, árvores e pista rolando em velocidades diferentes (paralaxe).
 * [travelDp] = quantos dp a pista já andou; as outras camadas andam proporcionalmente.
 */
@Composable
fun ParallaxScene(travelDp: () -> Float, modifier: Modifier = Modifier) {
    val layers = rememberSceneLayers()
    Canvas(modifier.clipToBounds()) {
        val d = travelDp() * density
        tiled(layers.sky, 0f, 1f, d * SKY / TRACK)
        // Cidade ancorada no chão: a base (0,69) passa da linha onde árvores/calçada ficam opacas
        // (~0,67) e ainda é estendida até 0,74, então nunca sobra faixa de céu acima da calçada.
        tiled(layers.town, 0.25f, 0.44f, d * TOWN / TRACK, extendToF = 0.74f)
        tiled(layers.trees, 0.40f, 0.33f, d * TREES / TRACK)
        tiled(layers.track, 0.66f, 0.34f, d)
    }
}

/** Mascote "flutuando" sobre a pista: sprite de corrida, balanço e sombra. */
@Composable
fun FloatingRunner(m: Mascot, clock: () -> Float, sceneH: Dp, modifier: Modifier = Modifier, lift: () -> Float = { 0f }) {
    val mh = sceneH * 0.44f
    Box(modifier.size(mh * 1.1f, mh + 18.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.width(mh * 0.66f).height(18.dp)) {
            val l = lift()
            val k = (1f - (l / 120f)).coerceIn(0.45f, 1f)
            drawOval(Color(0x501C140C), topLeft = Offset(size.width * (1 - k) / 2, size.height * 0.2f), size = Size(size.width * k, size.height * 0.6f))
        }
        Image(
            painterResource(m.run),
            contentDescription = "${m.label} correndo",
            modifier = Modifier.size(mh * 1.1f, mh).offset(y = (-14).dp).graphicsLayer {
                val t = clock()
                val p = (sin(t * 2 * Math.PI.toFloat() / 0.42f) + 1f) / 2f
                translationY = -p * mh.toPx() * 0.07f - lift() * density
                rotationZ = -3f + p * 6f
                scaleY = 1f - (1f - p) * 0.035f
            },
        )
    }
}

@Composable
fun HomeScreen(running: Boolean, onStart: () -> Unit, onGame: () -> Unit, onSettings: () -> Unit) {
    if (!AppPrefs.profileReady) {
        Onboarding()
        return
    }
    val t = LocalGrokTheme.current
    val m = MascotPrefs.current
    val clock = rememberFrameClock()
    val stage = if (t.dark) t.grass else t.ink
    val onStage = if (t.dark) t.ink else t.paper
    Column(Modifier.fillMaxSize().background(t.paper)) {
        // Barra de cima do desenho: engrenagem à esquerda, GROK RUN no meio.
        Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp)) {
            Box(
                Modifier.align(Alignment.CenterStart).size(44.dp)
                    .drawBehind { drawCircle(t.ink, size.minDimension / 2, center + Offset(3.dp.toPx(), 3.dp.toPx())) }
                    .clip(CircleShape).background(t.cream).border(3.dp, t.ink, CircleShape)
                    .clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { Icon(GrokIcon.GEAR, t.ink, 26.dp) }
            DisplayText(stringResource(R.string.home_title), 34.sp, Modifier.align(Alignment.Center))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val h = maxHeight
            ParallaxScene({ clock() * TRACK }, Modifier.fillMaxSize())
            FloatingRunner(
                m, clock, h,
                Modifier.align(Alignment.TopCenter).offset(y = h * 0.905f - (h * 0.44f + 18.dp) + 9.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(t.ink))
        Column(
            Modifier.fillMaxWidth().background(stage).padding(horizontal = 28.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DisplayText("${AppPrefs.runnerName.ifBlank { "Grok" }} · ${m.label}", 22.sp, color = onStage)
            val line = if (t.dark) t.ink else onStage
            GrokButton(
                if (running) "VOLTAR À CORRIDA" else stringResource(R.string.home_start),
                onStart, Modifier.fillMaxWidth(), BtnKind.CINDER, 28.sp, 58.dp, outline = line,
            )
            GrokButton(stringResource(R.string.home_street_game), onGame, Modifier.fillMaxWidth(), BtnKind.CREAM, 24.sp, 58.dp, outline = line)
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** "Quem corre?" — primeira abertura: nome + bot (igual à web). */
@Composable
private fun Onboarding() {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(AppPrefs.runnerName) }
    var pick by remember { mutableStateOf(MascotPrefs.current) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            DisplayText("Quem corre?", 32.sp)
            Hint("Escolhe o bot e o nome uma vez. Depois isso fica nos Ajustes.")
        }
        NameField(name, { name = it })
        MascotGrid(pick, 96.dp) { pick = it }
        GrokButton("É esse", {
            MascotPrefs.set(ctx, pick)
            AppPrefs.setProfile(ctx, name.ifBlank { "Grok" })
        }, Modifier.fillMaxWidth(), BtnKind.CINDER, 24.sp, 56.dp)
    }
}

@Composable
fun NameField(value: String, onChange: (String) -> Unit, onDone: (() -> Unit)? = null) {
    val t = LocalGrokTheme.current
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(24)) },
        singleLine = true,
        textStyle = TextStyle(color = t.ink, fontSize = 17.sp, fontFamily = Nunito),
        cursorBrush = SolidColor(t.ink),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(t.cream).border(3.dp, t.ink, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 13.dp),
        decorationBox = { inner ->
            Box { if (value.isEmpty()) Hint("Seu nome", alpha = 0.45f, size = 17.sp); inner() }
        },
    )
}

@Composable
fun MascotGrid(selected: Mascot, height: Dp, onPick: (Mascot) -> Unit) {
    val t = LocalGrokTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Mascot.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .border(3.dp, if (m == selected) t.ink else Color.Transparent, RoundedCornerShape(16.dp))
                            .clickable { onPick(m) }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(painterResource(m.idle), contentDescription = m.label, modifier = Modifier.fillMaxWidth().height(height))
                        DisplayText(m.label, 14.sp, align = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Barra de baixo: Pista, Atividades, Evolução (estilo .nav-btn da web). */
@Composable
fun BottomNav(current: String, onPick: (String) -> Unit) {
    val t = LocalGrokTheme.current
    Column(Modifier.fillMaxWidth().background(t.paper)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(t.ink))
        Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple("home", stringResource(R.string.nav_workouts), GrokIcon.FLAG),
                Triple("activities", stringResource(R.string.nav_history), GrokIcon.HISTORY),
                Triple("evolution", stringResource(R.string.nav_evolution), GrokIcon.TRENDING),
            ).forEach { (route, label, icon) ->
                val on = current == route
                val col = if (on) t.paper else t.ink
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).background(if (on) t.ink else Color.Transparent)
                        .clickable { onPick(route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(icon, col, 20.dp)
                    DisplayText(label, 14.sp, color = col)
                }
            }
        }
    }
}

/** Faixa "Corrida em andamento" nas outras telas. */
@Composable
fun RunningBanner(onClick: () -> Unit) {
    val t = LocalGrokTheme.current
    Row(
        Modifier.fillMaxWidth().background(t.cinder).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DisplayText("Corrida em andamento", 18.sp, Modifier.weight(1f), color = t.onCinder)
        DisplayText("Voltar ›", 16.sp, color = t.onCinder)
    }
}

fun comingSoon(ctx: android.content.Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

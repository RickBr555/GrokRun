package com.grokrun.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grokrun.R
import com.grokrun.domain.PhaseKind
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ---------- Fontes da versão web: Lilita One (títulos/botões) e Nunito (texto). OFL, ver /licenses. ----------
val Display = FontFamily(Font(R.font.lilita_one))
val Nunito = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_black, FontWeight.Black),
)

/** Texto "font-display" da web. */
@Composable
fun DisplayText(
    text: String,
    size: TextUnit = 20.sp,
    modifier: Modifier = Modifier,
    color: Color = Ink,
    align: TextAlign? = null,
) {
    Text(
        text, modifier = modifier, color = color, textAlign = align,
        style = TextStyle(fontFamily = Display, fontSize = size, lineHeight = size * 1.05f, letterSpacing = 0.02.sp),
    )
}

/** Texto pequeno esmaecido (text-sm text-ink/70). */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, alpha: Float = 0.7f, size: TextUnit = 14.sp, align: TextAlign? = null) {
    Text(text, modifier = modifier, color = Ink.copy(alpha = alpha), fontSize = size, fontFamily = Nunito, fontWeight = FontWeight.SemiBold, textAlign = align, lineHeight = size * 1.3f)
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, size: TextUnit = 16.sp, color: Color = Ink, align: TextAlign? = null) {
    Text(text, modifier = modifier, color = color, fontSize = size, fontFamily = Nunito, fontWeight = FontWeight.SemiBold, textAlign = align)
}

enum class BtnKind { CINDER, CREAM, GOLD, INK }

/** Botão pílula da web (.btn): borda ink 3dp, sombra dura 4dp, afunda ao tocar. */
@Composable
fun GrokButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.CREAM,
    size: TextUnit = 20.sp,
    height: Dp = 52.dp,
    enabled: Boolean = true,
    outline: Color? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val t = LocalGrokTheme.current
    val (bg, fg) = when (kind) {
        BtnKind.CINDER -> t.cinder to t.onCinder
        BtnKind.CREAM -> t.cream to t.ink
        BtnKind.GOLD -> t.gold to Color(0xFF1C140C)
        BtnKind.INK -> t.ink to t.paper
    }
    val line = outline ?: t.ink
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shift = if (pressed && enabled) 2.dp else 0.dp
    Box(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .drawBehind {
                if (!(pressed && enabled)) {
                    val o = 4.dp.toPx()
                    drawRoundRect(line, topLeft = Offset(o, o), size = this.size, cornerRadius = CornerRadius(this.size.height / 2))
                }
            }
            .offset(shift, shift)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(3.dp, line, RoundedCornerShape(50))
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            leading?.invoke()
            DisplayText(text, size, color = fg, align = TextAlign.Center)
        }
    }
}

/** .chip / .chip-on */
@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalGrokTheme.current
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) t.ink else t.cream)
            .border(3.dp, t.ink, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { DisplayText(text, 16.sp, color = if (on) t.cream else t.ink) }
}

/** .card */
@Composable
fun GrokCard(modifier: Modifier = Modifier, padding: Dp = 12.dp, fill: Color? = null, content: @Composable () -> Unit) {
    Box(modifier.padding(end = 4.dp, bottom = 4.dp).grokCard(fill = fill).padding(padding)) { content() }
}

/** .step-btn */
@Composable
fun StepButton(label: String, onClick: () -> Unit) {
    val t = LocalGrokTheme.current
    Box(
        Modifier.size(46.dp).drawBehind {
            drawCircle(t.ink, radius = size.minDimension / 2, center = center + Offset(2.dp.toPx(), 2.dp.toPx()))
        }.clip(CircleShape).background(t.cream).border(3.dp, t.ink, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { DisplayText(label, 24.sp) }
}

@Composable
fun Stepper(label: String, hint: String?, display: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            DisplayText(label, 18.sp)
            if (hint != null) Hint(hint, alpha = 0.65f)
        }
        StepButton("–", onMinus)
        DisplayText(display, 20.sp, Modifier.width(72.dp), align = TextAlign.Center)
        StepButton("+", onPlus)
    }
}

@Composable
fun TimeStepper(label: String, value: Int, onChange: (Int) -> Unit, hint: String? = null, min: Int = 0, max: Int = 60 * 40, step: Int = 5) =
    Stepper(label, hint, formatClock(value), { onChange((value - step).coerceAtLeast(min)) }, { onChange((value + step).coerceAtMost(max)) })

@Composable
fun CountStepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) =
    Stepper(label, null, "$value", { onChange((value - 1).coerceAtLeast(min)) }, { onChange((value + 1).coerceAtMost(max)) })

@Composable
fun BackBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BackArrow(onBack)
        DisplayText(title, 26.sp, Modifier.weight(1f))
    }
}

/** Seta "voltar" (caixa creme 44 dp, borda tinta), a mesma da tela de corrida. */
@Composable
fun BackArrow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalGrokTheme.current
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
            .background(t.cream).border(3.dp, t.ink, RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "Voltar", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(GrokIcon.BACK, t.ink, 22.dp) }
}

/** Stat da web: cartão com rótulo pequeno e valor em display. */
@Composable
fun Stat(k: String, v: String, modifier: Modifier = Modifier) {
    GrokCard(modifier, padding = 8.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Hint(k, alpha = 0.6f, size = 12.sp)
            DisplayText(v, 18.sp, align = TextAlign.Center)
        }
    }
}

@Composable
fun phaseColor(kind: PhaseKind): Color = when (kind) {
    PhaseKind.WARMUP, PhaseKind.COOLDOWN -> Gold
    PhaseKind.SPRINT, PhaseKind.RUN -> Cinder
    PhaseKind.REST -> Ink
    PhaseKind.CYCLE_REST -> Grass
}

/** Barra de fases colorida (PhaseBar). */
@Composable
fun PhaseBar(parts: List<Pair<PhaseKind, Int>>, modifier: Modifier = Modifier) {
    val total = parts.sumOf { it.second }.coerceAtLeast(1)
    val ink = Ink
    Row(modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(50)).border(3.dp, ink, RoundedCornerShape(50))) {
        parts.forEach { (k, d) ->
            if (d > 0) Box(Modifier.weight(d.toFloat() / total).fillMaxHeight().background(phaseColor(k)))
        }
    }
}

// ---------- Ícones (traço estilo lucide, desenhados à mão para não depender de pacote de ícones) ----------
enum class GrokIcon { FLAG, HISTORY, TRENDING, GEAR, VOLUME, CROSSHAIR, BACK, LIST }

@Composable
fun Icon(icon: GrokIcon, color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) { drawGrokIcon(icon, color) }
}

fun DrawScope.drawGrokIcon(icon: GrokIcon, color: Color) {
    val s = size.minDimension / 24f
    val st = Stroke(width = 2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(vararg pts: Pair<Float, Float>) {
        val path = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s) }
        drawPath(path, color, style = st)
    }
    when (icon) {
        GrokIcon.FLAG -> {
            line(4f to 22f, 4f to 3f)
            val path = Path().apply {
                moveTo(4f * s, 4f * s); cubicTo(8f * s, 1f * s, 12f * s, 6f * s, 20f * s, 3f * s)
                lineTo(20f * s, 15f * s); cubicTo(12f * s, 18f * s, 8f * s, 13f * s, 4f * s, 16f * s); close()
            }
            drawPath(path, color, style = st)
        }
        GrokIcon.HISTORY -> {
            drawArc(color, 200f, 290f, false, topLeft = p(3f, 3f), size = Size(18f * s, 18f * s), style = st)
            line(3f to 3f, 3f to 8f, 8f to 8f)
            line(12f to 7f, 12f to 12f, 16f to 14f)
        }
        GrokIcon.TRENDING -> {
            line(22f to 7f, 13.5f to 15.5f, 8.5f to 10.5f, 2f to 17f)
            line(16f to 7f, 22f to 7f, 22f to 13f)
        }
        GrokIcon.GEAR -> {
            val c = p(12f, 12f)
            for (i in 0 until 8) rotate(i * 45f, c) {
                drawRoundRect(color, topLeft = p(10f, 1.5f), size = Size(4f * s, 5f * s), cornerRadius = CornerRadius(1f * s))
            }
            drawCircle(color, radius = 6.6f * s, center = c, style = st)
            drawCircle(color, radius = 2.8f * s, center = c, style = st)
        }
        GrokIcon.VOLUME -> {
            val path = Path().apply {
                moveTo(11f * s, 5f * s); lineTo(6f * s, 9f * s); lineTo(2f * s, 9f * s); lineTo(2f * s, 15f * s)
                lineTo(6f * s, 15f * s); lineTo(11f * s, 19f * s); close()
            }
            drawPath(path, color, style = st)
            drawArc(color, -50f, 100f, false, topLeft = p(11f, 7f), size = Size(10f * s, 10f * s), style = st)
            drawArc(color, -50f, 100f, false, topLeft = p(7f, 3f), size = Size(18f * s, 18f * s), style = st)
        }
        GrokIcon.BACK -> {
            line(20f to 12f, 4f to 12f)
            line(11f to 5f, 4f to 12f, 11f to 19f)
        }
        GrokIcon.LIST -> {
            line(8f to 6f, 21f to 6f); line(8f to 12f, 21f to 12f); line(8f to 18f, 21f to 18f)
            drawCircle(color, radius = 1.4f * s, center = p(3.5f, 6f))
            drawCircle(color, radius = 1.4f * s, center = p(3.5f, 12f))
            drawCircle(color, radius = 1.4f * s, center = p(3.5f, 18f))
        }
        GrokIcon.CROSSHAIR -> {
            drawCircle(color, radius = 8f * s, center = p(12f, 12f), style = st)
            drawCircle(color, radius = 2.5f * s, center = p(12f, 12f))
            line(12f to 1f, 12f to 4f); line(12f to 20f, 12f to 23f); line(1f to 12f, 4f to 12f); line(20f to 12f, 23f to 12f)
        }
    }
}

// ---------- Formatação (format.ts) ----------
private val ptBR = Locale("pt", "BR")

fun formatClock(totalSec: Number): String {
    val sec = totalSec.toDouble().roundToInt().coerceAtLeast(0)
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatPace(secPerKm: Number?): String {
    val v = secPerKm?.toDouble() ?: return "—"
    if (!v.isFinite() || v <= 0 || v > 20 * 60) return "—"
    val s = v.roundToInt()
    return "%d:%02d".format(s / 60, s % 60)
}

private fun dec(n: Double, d: Int): String = NumberFormat.getNumberInstance(ptBR).apply {
    minimumFractionDigits = d; maximumFractionDigits = d
}.format(n)

fun formatKm(meters: Double): String = "${dec(meters / 1000.0, 2)} km"
fun formatSpeed(kmh: Double?): String = if (kmh == null || !kmh.isFinite()) "—" else "${dec(kmh, 1)} km/h"
fun formatMeters(m: Double): String = "${NumberFormat.getIntegerInstance(ptBR).format(m.roundToInt())} m"
fun formatPct(r: Double?): String = if (r == null || !r.isFinite()) "—" else "${(r * 100).roundToInt()}%"
fun formatWhen(ts: Long): String = SimpleDateFormat("EEE, dd 'de' MMM", ptBR).format(Date(ts))
fun formatDay(ts: Long): String = SimpleDateFormat("dd/MM", ptBR).format(Date(ts))

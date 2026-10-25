package com.grokrun.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Os 4 temas da versão web do Grok Build (styles.css / more-screen.tsx).
 * paper = fundo das telas, cream = cartões, ink = texto/traço, cinder = destaque vermelho,
 * gold = selecionado/realce, grass = fundo externo e barras (onGrass = texto em cima dele).
 */
enum class GrokTheme(
    val id: String,
    val label: String,
    val hint: String,
    val paper: Color,
    val cream: Color,
    val ink: Color,
    val cinder: Color,
    val gold: Color,
    val grass: Color,
    val onGrass: Color,
    val dark: Boolean,
) {
    AMARELO("amarelo", "Amarelo", "pastel", Color(0xFFF4E4C4), Color(0xFFFFF8EA), Color(0xFF1C140C), Color(0xFFE23D2B), Color(0xFFE6A317), Color(0xFF2F6B3A), Color(0xFFFFF8EA), false),
    LARANJA("laranja", "Laranja", "quente", Color(0xFFFFB15A), Color(0xFFFFF6EA), Color(0xFF2A1408), Color(0xFFD94A1E), Color(0xFFFFE08A), Color(0xFFE07A28), Color(0xFF2A1408), false),
    VERDE("verde", "Verde", "da pista", Color(0xFF4E9A52), Color(0xFFF4FFF0), Color(0xFF10240F), Color(0xFFE23D2B), Color(0xFFF0C14A), Color(0xFF1F6B32), Color(0xFFF4FFF0), false),
    AZUL("azul", "Azul", "noite", Color(0xFF002F5C), Color(0xFF0A3D6E), Color(0xFFF6F1E6), Color(0xFFFF6A45), Color(0xFFF0B429), Color(0xFF001A33), Color(0xFFF6F1E6), true);

    /** Texto em cima do botão cinder (o "sticker" da web). */
    val onCinder: Color get() = Color(0xFFFFF8EA)

    companion object {
        fun of(id: String?): GrokTheme = entries.firstOrNull { it.id == id } ?: AMARELO
    }
}

/** Tema salvo (SharedPreferences) + estado observável: trocar em Ajustes atualiza a UI na hora. */
object ThemePrefs {
    private const val FILE = "grokrun_prefs"
    private const val KEY = "theme"
    var current by mutableStateOf(GrokTheme.AMARELO)
        private set

    fun load(ctx: Context) {
        current = GrokTheme.of(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun set(ctx: Context, t: GrokTheme) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, t.id).apply()
        current = t
    }
}

val LocalGrokTheme = staticCompositionLocalOf { GrokTheme.AMARELO }

// Atalhos usados pelas telas (antes eram constantes fixas).
val Ink: Color @Composable @ReadOnlyComposable get() = LocalGrokTheme.current.ink
val Paper: Color @Composable @ReadOnlyComposable get() = LocalGrokTheme.current.paper
val Card: Color @Composable @ReadOnlyComposable get() = LocalGrokTheme.current.cream
val Cinder: Color @Composable @ReadOnlyComposable get() = LocalGrokTheme.current.cinder
val OnCinder: Color @Composable @ReadOnlyComposable get() = LocalGrokTheme.current.onCinder
val Gold: Color @Composable @ReadOnlyComposable get() = LocalGrokTheme.current.gold
val Grass: Color @Composable @ReadOnlyComposable get() = LocalGrokTheme.current.grass
val OnGrass: Color @Composable @ReadOnlyComposable get() = LocalGrokTheme.current.onGrass

@Composable
fun GrokRunTheme(theme: GrokTheme, content: @Composable () -> Unit) {
    val scheme = if (theme.dark) {
        darkColorScheme(
            primary = theme.cinder, onPrimary = theme.onCinder,
            secondary = theme.gold, onSecondary = Color(0xFF1C140C),
            background = theme.paper, onBackground = theme.ink,
            surface = theme.cream, onSurface = theme.ink,
        )
    } else {
        lightColorScheme(
            primary = theme.cinder, onPrimary = theme.onCinder,
            secondary = theme.gold, onSecondary = Color(0xFF1C140C),
            background = theme.paper, onBackground = theme.ink,
            surface = theme.cream, onSurface = theme.ink,
        )
    }
    CompositionLocalProvider(LocalGrokTheme provides theme) {
        val base = androidx.compose.material3.Typography()
        fun androidx.compose.ui.text.TextStyle.n() = copy(fontFamily = Nunito, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        val typo = base.copy(
            bodyLarge = base.bodyLarge.n(), bodyMedium = base.bodyMedium.n(), bodySmall = base.bodySmall.n(),
            labelLarge = base.labelLarge.copy(fontFamily = Display), labelMedium = base.labelMedium.n(), labelSmall = base.labelSmall.n(),
            titleLarge = base.titleLarge.copy(fontFamily = Display), titleMedium = base.titleMedium.copy(fontFamily = Display),
            headlineLarge = base.headlineLarge.copy(fontFamily = Display), headlineMedium = base.headlineMedium.copy(fontFamily = Display),
            headlineSmall = base.headlineSmall.copy(fontFamily = Display),
        )
        MaterialTheme(colorScheme = scheme, typography = typo) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides theme.ink) {
                content()
            }
        }
    }
}

/**
 * Cartão estilo web (.card): fundo cream, borda ink de 3dp, cantos 24dp e sombra dura 4dp deslocada.
 */
@Composable
fun Modifier.grokCard(radius: Dp = 24.dp, fill: Color? = null): Modifier {
    val ink = Ink
    val bg = fill ?: Card
    val shape = RoundedCornerShape(radius)
    return this
        .drawBehind {
            val r = radius.toPx()
            val o = 4.dp.toPx()
            drawRoundRect(ink, topLeft = Offset(o, o), size = size, cornerRadius = CornerRadius(r, r))
        }
        .background(bg, shape)
        .border(3.dp, ink, shape)
}

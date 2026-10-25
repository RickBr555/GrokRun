package com.grokrun

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.grokrun.ui.DisplayText
import com.grokrun.ui.GrokNav
import com.grokrun.ui.GrokRunTheme
import com.grokrun.ui.ThemePrefs
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private var styledFor: com.grokrun.ui.GrokTheme? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // A janela abre preta (Theme.GrokRun.Starting), sem flash branco; depois troca para o tema do app.
        setTheme(R.style.Theme_GrokRun)
        super.onCreate(savedInstanceState)
        ThemePrefs.load(this)
        applyBars(true)
        // Sem localização o Android 14+ derruba o serviço de treino (foregroundServiceType=location),
        // então pedimos logo na abertura.
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) askPerms.launch(missing.toTypedArray())
        val skipSplash = savedInstanceState != null
        // Treino salvo e serviço parado (o sistema matou o processo)? Retoma agora.
        com.grokrun.service.WorkoutService.resumeIfNeeded(this)
        handleOpenLive(intent)
        setContent {
            var splash by rememberSaveable { mutableStateOf(!skipSplash) }
            LaunchedEffect(Unit) {
                if (splash) { delay(1000); splash = false }
            }
            val theme = ThemePrefs.current
            LaunchedEffect(splash, theme) { applyBars(splash) }
            Crossfade(splash, animationSpec = tween(250), label = "splash") { s ->
                if (s) {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        DisplayText("GROK RUN", 44.sp, color = Color.White)
                    }
                } else {
                    GrokRunTheme(theme) { GrokNav() }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenLive(intent)
    }

    /** Toque na notificação do treino: abre a tela ao vivo. */
    private fun handleOpenLive(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_LIVE, false) == true) {
            intent.removeExtra(EXTRA_OPEN_LIVE)
            com.grokrun.service.Live.openLiveRequests.value += 1
        }
    }

    companion object {
        const val EXTRA_OPEN_LIVE = "open_live"
    }

    /** Ícones da barra de status/navegação conforme o tema (preto no splash). */
    private fun applyBars(splash: Boolean) {
        val t = ThemePrefs.current
        val bar = if (splash) Color.Black else t.paper
        val style = if (splash || t.dark) SystemBarStyle.dark(bar.toArgb()) else SystemBarStyle.light(bar.toArgb(), bar.toArgb())
        // Barra de status transparente: na Pista o céu do cenário sobe por trás dela;
        // nas outras telas o fundo do app (paper) aparece ali.
        val clear = android.graphics.Color.TRANSPARENT
        val status = if (splash || t.dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        enableEdgeToEdge(statusBarStyle = status, navigationBarStyle = style)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bar.toArgb()))
    }
}

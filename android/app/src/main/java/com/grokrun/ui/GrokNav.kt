package com.grokrun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.grokrun.service.Live

private val TABS = setOf("home", "activities", "evolution")

private fun NavHostController.tab(route: String) = navigate(route) {
    popUpTo("home") { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/**
 * Navegação igual à web: abas Pista / Atividades / Evolução embaixo,
 * Ajustes pela engrenagem, faixa "Corrida em andamento" fora da tela ao vivo.
 */
@Composable
fun GrokNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val hud by Live.hud.collectAsState()
    val currentId by Live.currentId.collectAsState()
    val active by Live.active.collectAsState()
    val running = active || (currentId != null && Live.running)
    // Toque na notificação → tela ao vivo (espera o serviço retomar o treino, se precisou).
    val openReq by Live.openLiveRequests.collectAsState()
    var handledReq by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(openReq, running) {
        if (openReq > handledReq && running) {
            handledReq = openReq
            if (route != "live") nav.navigate("live") { launchSingleTop = true }
        }
    }
    val showBanner = running && route != "live" && route != "home" && hud != null
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        if (showBanner) RunningBanner { nav.navigate("live") { launchSingleTop = true } }
        // Na Pista o cenário sobe por trás da barra de status; nas outras telas o conteúdo começa abaixo dela.
        val edgeToEdge = route == "home" || route == "mode" || route.startsWith("setup")
        val top = if (edgeToEdge || showBanner) Modifier else Modifier.statusBarsPadding()
        Box(Modifier.weight(1f).fillMaxWidth().then(top)) {
            NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
                composable("home") {
                    HomeScreen(
                        running = running,
                        onStart = { if (running) nav.navigate("live") else nav.navigate("mode") },
                        onGame = { nav.navigate("game") },
                        onSettings = { nav.navigate("settings") },
                    )
                }
                composable("mode") { ModeScreen(onBack = { nav.popBackStack() }, onPick = { nav.navigate("setup/$it") }) }
                composable("setup/{mode}") { e ->
                    val go = { nav.navigate("live") { popUpTo("home") } }
                    if (e.arguments?.getString("mode") == "corrida") CorridaSetupScreen({ nav.popBackStack() }, go)
                    else TirosSetupScreen({ nav.popBackStack() }, go)
                }
                composable("live") {
                    LiveScreen(
                        onSaved = { id -> nav.navigate("summary/$id") { popUpTo("home") } },
                        onMinimize = { nav.tab("home") },
                    )
                }
                composable("summary/{id}") { e ->
                    val id = e.arguments?.getString("id") ?: ""
                    SummaryScreen(id, onMap = { nav.navigate("detail/$id") }, onClose = { nav.tab("home") })
                }
                composable("activities") { ActivitiesScreen(onOpen = { nav.navigate("detail/$it") }) }
                composable("detail/{id}") { e ->
                    DetailScreen(
                        e.arguments?.getString("id") ?: "",
                        onBack = { nav.popBackStack() },
                        onDeleted = { nav.tab("activities") },
                    )
                }
                composable("evolution") { EvolutionScreen() }
                composable("game") { RaceGameScreen(onBack = { nav.popBackStack() }) }
                composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            }
        }
        if (route in TABS) BottomNav(route) { nav.tab(it) }
    }
}

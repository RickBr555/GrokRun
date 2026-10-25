package com.grokrun.ui

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.grokrun.service.LivePos
import com.grokrun.service.RoutePt
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.cos

private const val FOLLOW_ZOOM = 17.0

/** Bolinha azul estilo Strava/Waze: círculo de precisão, ponto com borda branca e seta de direção. */
private class RunnerDotOverlay : Overlay() {
    var pos: LivePos? = null
    private val pt = Point()
    private val accFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x302196F3; style = Paint.Style.FILL }
    private val accStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x802196F3.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
    private val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt(); style = Paint.Style.FILL }
    private val arrow = Path()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val p = pos ?: return
        val proj = map.projection
        proj.toPixels(GeoPoint(p.lat, p.lon), pt)
        val d = map.resources.displayMetrics.density
        val accPx = proj.metersToEquatorPixels(p.accuracyM) / cos(Math.toRadians(p.lat)).toFloat()
        if (accPx > 12f * d) {
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), accPx, accFill)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), accPx, accStroke)
        }
        p.bearing?.let { b ->
            c.save()
            c.rotate(b - map.mapOrientation, pt.x.toFloat(), pt.y.toFloat())
            arrow.reset()
            arrow.moveTo(pt.x.toFloat(), pt.y - 20f * d)
            arrow.lineTo(pt.x - 7f * d, pt.y - 9f * d)
            arrow.lineTo(pt.x + 7f * d, pt.y - 9f * d)
            arrow.close()
            c.drawPath(arrow, blue)
            c.restore()
        }
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 10f * d, white)
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7f * d, blue)
    }
}

/**
 * Mapa ao vivo (OpenStreetMap via osmdroid). Só UI: lê Live.position / Live.route,
 * não mexe no serviço. Segue o corredor até ele arrastar o mapa; aí aparece "Centralizar".
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun LiveMap(position: LivePos?, route: List<RoutePt>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var follow by remember { mutableStateOf(true) }
    val dot = remember { RunnerDotOverlay() }
    val lastCentered = remember { longArrayOf(Long.MIN_VALUE) }
    val line = remember {
        Polyline().apply {
            outlinePaint.color = 0xFFE23D2B.toInt()
            outlinePaint.strokeWidth = 14f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }
    }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(FOLLOW_ZOOM)
            overlays.add(line)
            overlays.add(dot)
        }
    }
    // Arrastou/pinçou o mapa: para de seguir até tocar em "Centralizar".
    DisposableEffect(map) {
        map.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_MOVE) follow = false
            false
        }
        onDispose { map.setOnTouchListener(null) }
    }
    // Ciclo de vida do MapView: resume/pause com a tela e onDetach ao sair (evita vazamento).
    DisposableEffect(lifecycle, map) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { map },
            update = { m ->
                dot.pos = position
                if (line.actualPoints.size != route.size) {
                    line.setPoints(route.map { GeoPoint(it.lat, it.lon) })
                }
                if (follow && position != null && position.t != lastCentered[0]) {
                    lastCentered[0] = position.t
                    val target = GeoPoint(position.lat, position.lon)
                    if (m.zoomLevelDouble < 15.0) m.controller.setZoom(FOLLOW_ZOOM)
                    m.controller.animateTo(target)
                }
                m.invalidate()
            },
            modifier = Modifier.matchParentSize(),
        )
        if (position == null) {
            Text(
                "Procurando GPS…",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xCC1C140C), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (!follow) {
            Button(
                onClick = {
                    follow = true
                    position?.let {
                        lastCentered[0] = it.t
                        map.controller.setZoom(FOLLOW_ZOOM)
                        map.controller.animateTo(GeoPoint(it.lat, it.lon))
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) { Text("◎ Centralizar", fontSize = 14.sp) }
        }
        Text(
            "© OpenStreetMap",
            fontSize = 9.sp,
            color = Color(0xFF1C140C),
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                .background(Color(0xAAFFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
        )
    }
}

/** Placeholder do modo só-timer (sem permissão de localização). */
@Composable
fun NoMapPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier.background(Gold.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .border(2.dp, Ink.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Mapa desligado: sem permissão de localização.\nO treino segue só com o relógio.\n(Ajustes → Permitir localização)",
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )
    }
}

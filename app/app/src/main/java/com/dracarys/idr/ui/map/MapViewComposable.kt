package com.dracarys.idr.ui.map

import android.content.Context
import android.location.LocationManager
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dracarys.idr.ui.components.VehicleIcon
import com.dracarys.idr.ui.state.NavigationMode
import com.dracarys.idr.ui.state.NavigationState
import com.dracarys.idr.ui.theme.DracarysBackground
import com.dracarys.idr.ui.theme.DracarysSecondaryText
import com.dracarys.idr.ui.theme.DracarysTypography
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File

private const val TAG = "MapViewComposable"

/**
 * Original OpenStreetMap Map Composable.
 *
 * Renders authentic OpenStreetMap cartography:
 * 1. Worldwide online and offline OpenStreetMap tiles via MapTileProviderBasic.
 * 2. Unaltered, natural map colors (streets, parks, buildings, waterways).
 * 3. Centers strictly on the real device position.
 * 4. Renders live vehicle heading arrow and trailing path.
 * 5. Displays expanding dashed uncertainty corridor during Dead Reckoning.
 */
@Composable
fun MapViewComposable(
    state: NavigationState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Discover real device location for initial centering if state is not yet ready
    val initialGeoPoint = remember {
        var point: GeoPoint? = null
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            for (provider in providers) {
                val loc = lm?.getLastKnownLocation(provider)
                if (loc != null) {
                    point = GeoPoint(loc.latitude, loc.longitude)
                    Log.i(TAG, "Centered initial map on real device fix: ${loc.latitude}, ${loc.longitude}")
                    break
                }
            }
        } catch (_: Exception) {}
        point
    }

    val mapView = remember {
        // Initialize OSMDroid with compliant User-Agent
        Configuration.getInstance().userAgentValue = "DracarysIDR/1.0 (Android; OpenStreetMap Cartography)"
        Configuration.getInstance().osmdroidBasePath = context.cacheDir
        val osmCache = File(context.cacheDir, "osm")
        osmCache.mkdirs()
        Configuration.getInstance().osmdroidTileCache = osmCache

        // Universal MapTileProvider with online worldwide tile download + local cache
        val tileProvider = MapTileProviderBasic(context, TileSourceFactory.MAPNIK)

        val trailPolyline = org.osmdroid.views.overlay.Polyline().apply {
            outlinePaint.color = android.graphics.Color.parseColor("#14B8A6")
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }

        MapView(context, tileProvider).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(17.0)

            overlays.add(trailPolyline)

            // Center on real device position
            val startLat = if (state.latitude != 0.0) state.latitude else initialGeoPoint?.latitude
            val startLon = if (state.longitude != 0.0) state.longitude else initialGeoPoint?.longitude
            if (startLat != null && startLon != null && startLat != 0.0 && startLon != 0.0) {
                controller.setCenter(GeoPoint(startLat, startLon))
            }

            addOnFirstLayoutListener { _, _, _, _, _ ->
                val targetLat = if (state.latitude != 0.0) state.latitude else initialGeoPoint?.latitude
                val targetLon = if (state.longitude != 0.0) state.longitude else initialGeoPoint?.longitude
                if (targetLat != null && targetLon != null && targetLat != 0.0 && targetLon != 0.0) {
                    controller.setZoom(17.0)
                    controller.setCenter(GeoPoint(targetLat, targetLon))
                }
            }
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DracarysBackground),
    ) {
        // 1. Real authentic OpenStreetMap View
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (state.latitude != 0.0 && state.longitude != 0.0) {
                    view.post {
                        val currentCenter = view.mapCenter
                        val latDiff = Math.abs(currentCenter.latitude - state.latitude)
                        val lonDiff = Math.abs(currentCenter.longitude - state.longitude)
                        if (latDiff > 3.5e-5 || lonDiff > 3.5e-5 || currentCenter.latitude == 0.0) {
                            view.controller.setCenter(GeoPoint(state.latitude, state.longitude))
                        }

                        // Update native breadcrumb polyline on actual OpenStreetMap geography
                        val trailOverlay = view.overlays.filterIsInstance<org.osmdroid.views.overlay.Polyline>().firstOrNull()
                        if (trailOverlay != null) {
                            if (state.recentTrail.size >= 2) {
                                val pts = state.recentTrail.map { GeoPoint(it.first, it.second) }
                                trailOverlay.setPoints(pts)
                                val colorInt = when (state.mode) {
                                    NavigationMode.Gnss -> android.graphics.Color.parseColor("#14B8A6")
                                    NavigationMode.Fused -> android.graphics.Color.parseColor("#F59E0B")
                                    NavigationMode.DeadReckoning -> android.graphics.Color.parseColor("#8B5CF6")
                                    NavigationMode.AcquiringGps -> android.graphics.Color.parseColor("#6B7280")
                                }
                                trailOverlay.outlinePaint.color = colorInt
                            } else {
                                trailOverlay.setPoints(emptyList())
                            }
                            view.invalidate()
                        }
                    }
                }
            }
        )

        // 2. Navigation Overlays
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f

            // Uncertainty corridor during Dead Reckoning (only when moving to prevent clutter)
            if (state.mode == NavigationMode.DeadReckoning && state.distanceToLastFixM >= 3f) {
                drawDeadReckoningCorridor(cx = cx, cy = cy, state = state)
            }

            // Positioning rings
            drawCircle(
                color = state.mode.color.copy(alpha = 0.22f),
                radius = 28.dp.toPx(),
                center = Offset(cx, cy),
            )
            drawCircle(
                color = state.mode.color.copy(alpha = 0.45f),
                radius = 18.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        // 3. Vehicle Heading Icon
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            VehicleIcon(
                mode = state.mode,
                heading = state.headingDeg,
                size = 40.dp,
            )
        }

        // 4. Cartographic attribution
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 120.dp),
        ) {
            Text(
                text = "© OpenStreetMap contributors",
                style = DracarysTypography.bodySmall,
                color = DracarysSecondaryText.copy(alpha = 0.7f),
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Renders the expanding dashed uncertainty corridor during Dead Reckoning.
 */
private fun DrawScope.drawDeadReckoningCorridor(cx: Float, cy: Float, state: NavigationState) {
    val baseWidthPx = 36.dp.toPx()
    val growthFactor = 1.0f + (state.distanceToLastFixM / 40f).coerceIn(0f, 4f)
    val widthPx = baseWidthPx * growthFactor
    val lengthPx = 140.dp.toPx()

    rotate(degrees = state.headingDeg, pivot = Offset(cx, cy)) {
        val corridorPath = Path().apply {
            moveTo(cx - widthPx / 2f, cy + 20.dp.toPx())
            lineTo(cx - widthPx * 0.7f, cy - lengthPx)
            lineTo(cx + widthPx * 0.7f, cy - lengthPx)
            lineTo(cx + widthPx / 2f, cy + 20.dp.toPx())
            close()
        }

        drawPath(
            path = corridorPath,
            color = state.mode.color.copy(alpha = 0.15f),
            style = Fill,
        )

        drawPath(
            path = corridorPath,
            color = state.mode.color.copy(alpha = 0.90f),
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), phase = 0f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

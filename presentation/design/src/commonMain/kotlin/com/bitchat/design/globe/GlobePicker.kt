package com.bitchat.design.globe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.domain.location.geo.Geohash

/**
 * The geohash picker: a vector globe rendered from bundled Natural Earth data.
 *
 * This replaces a WebView that loaded Leaflet from unpkg.com and map tiles from
 * basemaps.cartocdn.com. That disclosed the user's IP address and every tile coordinate they
 * panned over to two third parties, on Android, iOS and desktop alike, and neither WebView nor
 * WKWebView honours the JVM proxy so Tor did not cover any of it. Nothing here touches the network.
 *
 * The tradeoff is deliberate: this picks a region or city rather than zooming to street level. The
 * geohash text field still accepts any precision for anyone who needs a finer cell.
 */
@Composable
fun GlobePicker(
    initialGeohash: String?,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.luminance() < 0.5f

    var land by remember { mutableStateOf<List<LandData.Ring>?>(null) }
    var borders by remember { mutableStateOf<List<LandData.Ring>>(emptyList()) }
    var cities by remember { mutableStateOf<List<LandData.City>>(emptyList()) }

    LaunchedEffect(Unit) {
        // Parsing ~235KB of geojson; done once, off the first frame.
        land = LandData.load()
        borders = LandData.loadBorders()
        cities = LandData.loadCities()
    }

    val start = remember(initialGeohash) {
        initialGeohash?.takeIf { it.isNotBlank() }?.let { gh ->
            runCatching { Geohash.decodeToCenter(gh) }.getOrNull()?.let { (lat, lon) ->
                Triple(lat, lon, gh.length)
            }
        }
    }

    val scope = rememberCoroutineScope()
    val state = remember(start) {
        GlobeState(
            targetLat = start?.first ?: 20.0,
            targetLon = start?.second ?: 0.0,
            initialPrecision = start?.third ?: 4,
            startZoomedOut = start == null
        ).also { globe ->
            // selectedGeohash is derived from the view centre, so seeding is done by asking the
            // globe to fly to the incoming cell; GlobeView plays this once the viewport is known.
            start?.let { (lat, lon, precision) -> globe.introTarget = Triple(lat, lon, precision) }
        }
    }
    LaunchedEffect(state, scope) { state.attach(scope) }

    val globeColors = remember(colorScheme, isDark) {
        if (isDark) {
            GlobeColors(
                accent = colorScheme.primary,
                land = Color(0xFF1E3A2A),
                coastline = colorScheme.primary.copy(alpha = 0.6f),
                border = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                oceanCenter = Color(0xFF0A1A24),
                oceanEdge = Color(0xFF050D14),
                atmosphere = colorScheme.primary,
                graticule = colorScheme.onSurface.copy(alpha = 0.10f),
                grid = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                label = colorScheme.onSurfaceVariant,
                labelHalo = colorScheme.background,
                star = colorScheme.onSurface
            )
        } else {
            GlobeColors(
                accent = colorScheme.primary,
                land = Color(0xFFBCD2C0),
                coastline = colorScheme.primary.copy(alpha = 0.5f),
                border = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                oceanCenter = Color(0xFFEAF2EC),
                oceanEdge = Color(0xFFD4E2D7),
                atmosphere = colorScheme.primary,
                graticule = colorScheme.onSurface.copy(alpha = 0.08f),
                grid = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                label = colorScheme.onSurfaceVariant,
                labelHalo = colorScheme.background,
                star = colorScheme.onSurfaceVariant
            )
        }
    }

    Box(modifier.fillMaxSize().background(colorScheme.background)) {
        land?.let { rings ->
            GlobeView(
                state = state,
                colors = globeColors,
                land = rings,
                borders = borders,
                cities = cities,
                modifier = Modifier.fillMaxSize()
            )
        } ?: Text(
            text = "Loading map…",
            color = colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.Center)
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
                .fillMaxWidth(0.8f),
            color = colorScheme.surface.copy(alpha = 0.85f),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 3.dp
        ) {
            Text(
                text = "Drag to rotate, pinch to zoom, tap to drop a pin",
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = colorScheme.surface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 3.dp
            ) {
                Text(
                    text = state.selectedGeohash.ifEmpty { "no cell selected" },
                    fontSize = 15.sp,
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { state.animatePrecision(state.precision - 1) }) { Text("−") }
                Text(
                    text = "precision ${state.precision}",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                TextButton(onClick = { state.animatePrecision(state.precision + 1) }) { Text("+") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    onClick = { state.selectedGeohash.takeIf { it.isNotEmpty() }?.let(onConfirm) },
                    enabled = state.selectedGeohash.isNotEmpty()
                ) { Text("Use this location") }
            }
        }
    }
}

/** Perceptual-ish luminance, enough to pick a light or dark globe palette. */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

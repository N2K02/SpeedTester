package com.speedtester.app

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/*
 * A small, dependency-free OpenStreetMap view written in Jetpack Compose.
 *
 * Tiles are standard 256px Web Mercator "slippy map" tiles fetched with OkHttp and drawn on a Compose Canvas.
 * OpenStreetMap's tile usage policy (https://operations.osmfoundation.org/policies/tiles/) requires:
 *  - a distinct, app-identifying User-Agent (generic library user agents such as okhttp/x.y are blocked),
 *  - honouring HTTP cache headers (the OkHttp disk cache below does this) and no bulk/prefetch downloading,
 *  - visible "© OpenStreetMap contributors" attribution on the map.
 */

internal data class MapTileSource(
    val name: String,
    val urlTemplate: String,
    val maxZoom: Int,
    val attribution: String
) {
    fun url(z: Int, x: Int, y: Int): String =
        urlTemplate.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
}

internal object MapTileSources {
    val Standard = MapTileSource(
        "Standard", "https://tile.openstreetmap.org/{z}/{x}/{y}.png", 19,
        "© OpenStreetMap contributors"
    )
    val Topographic = MapTileSource(
        "Topographic", "https://a.tile.opentopomap.org/{z}/{x}/{y}.png", 17,
        "© OpenStreetMap contributors, SRTM · © OpenTopoMap (CC-BY-SA)"
    )
    val Satellite = MapTileSource(
        "Satellite", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", 19,
        "Imagery © Esri, Maxar, Earthstar Geographics"
    )
    val all = listOf(Standard, Topographic, Satellite)
}

internal data class MapMarker(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val snippet: String,
    val isOrigin: Boolean = false
)

private const val MAX_MERCATOR_LAT = 85.05112878

private fun lonToWorldX(lon: Double): Double = (lon + 180.0) / 360.0

private fun latToWorldY(lat: Double): Double {
    val r = Math.toRadians(lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT))
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
}

private fun wrapUnit(value: Double): Double {
    val w = value % 1.0
    return if (w < 0) w + 1.0 else w
}

/** On-screen size of one 256px tile. Scaled up on dense screens so map labels stay readable. */
internal fun mapTileSizePx(density: Float): Float = 256f * max(1f, density / 2f)

/** Map camera in normalised Web Mercator coordinates (0..1 on both axes) plus a fractional zoom level. */
@Stable
internal class MapCameraState(latitude: Double, longitude: Double, zoom: Double) {
    var centerX by mutableDoubleStateOf(lonToWorldX(longitude))
    var centerY by mutableDoubleStateOf(latToWorldY(latitude))
    var zoom by mutableDoubleStateOf(zoom)
    var minZoom = 2.0
    var maxZoom = 19.0

    fun worldSizePx(tileSize: Float): Double = tileSize * 2.0.pow(zoom)

    fun moveTo(latitude: Double, longitude: Double, newZoom: Double = zoom) {
        centerX = lonToWorldX(longitude)
        centerY = latToWorldY(latitude)
        zoom = newZoom.coerceIn(minZoom, maxZoom)
    }

    fun panBy(dxPx: Float, dyPx: Float, tileSize: Float) {
        val world = worldSizePx(tileSize)
        centerX = wrapUnit(centerX - dxPx / world)
        centerY = (centerY - dyPx / world).coerceIn(0.0, 1.0)
    }

    /** Zooms while keeping the map point under [focus] fixed on screen. */
    fun zoomAround(focus: Offset, viewWidth: Int, viewHeight: Int, tileSize: Float, newZoom: Double) {
        val target = newZoom.coerceIn(minZoom, maxZoom)
        if (target == zoom) return
        val fx = focus.x - viewWidth / 2.0
        val fy = focus.y - viewHeight / 2.0
        val before = worldSizePx(tileSize)
        val px = centerX + fx / before
        val py = centerY + fy / before
        zoom = target
        val after = worldSizePx(tileSize)
        centerX = wrapUnit(px - fx / after)
        centerY = (py - fy / after).coerceIn(0.0, 1.0)
    }

    /** Centres and zooms so every point is visible with [paddingPx] around the edges. */
    fun fit(points: List<Pair<Double, Double>>, viewWidth: Int, viewHeight: Int, paddingPx: Float, tileSize: Float, maxFitZoom: Double = 14.0) {
        if (points.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return
        if (points.size == 1) {
            moveTo(points[0].first, points[0].second, min(10.0, maxZoom))
            return
        }
        val xs = points.map { lonToWorldX(it.second) }
        val ys = points.map { latToWorldY(it.first) }
        val spanX = max(xs.max() - xs.min(), 1e-9)
        val spanY = max(ys.max() - ys.min(), 1e-9)
        val usableW = max(viewWidth - 2 * paddingPx, viewWidth * 0.5f)
        val usableH = max(viewHeight - 2 * paddingPx, viewHeight * 0.5f)
        val zoomX = log2(usableW / (spanX * tileSize))
        val zoomY = log2(usableH / (spanY * tileSize))
        centerX = (xs.max() + xs.min()) / 2.0
        centerY = (ys.max() + ys.min()) / 2.0
        zoom = min(min(zoomX, zoomY), maxFitZoom).coerceIn(minZoom, maxZoom)
    }
}

internal sealed class TileLoadResult {
    data class Loaded(val url: String, val fromCache: Boolean) : TileLoadResult()
    data class Failed(val url: String, val reason: String, val httpCode: Int? = null) : TileLoadResult()
}

/** Downloads, caches and decodes map tiles. Shared by every map on screen. */
internal object MapTileLoader {
    val userAgent: String = "SpeedTester/${BuildConfig.VERSION_NAME} (Android; +https://github.com/N2K02/SpeedTester)"

    /** Bumped on the main thread whenever a tile finishes loading, so the map redraws. */
    val version = mutableIntStateOf(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val inFlight = HashMap<String, Call>()
    private val failedAt = HashMap<String, Long>()
    private const val RETRY_AFTER_MS = 20_000L

    // Size-based in-memory cache (about 24 MB of decoded tiles).
    private val memory = object : LruCache<String, ImageBitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = max(1, value.asAndroidBitmap().byteCount / 1024)
    }

    @Volatile private var client: OkHttpClient? = null

    private fun client(context: Context): OkHttpClient {
        client?.let { return it }
        return synchronized(this) { client ?: buildClient(context).also { client = it } }
    }

    private fun buildClient(context: Context): OkHttpClient =
        OkHttpClient.Builder()
            // Disk cache honours the tile servers' Cache-Control/ETag headers, as the OSM policy asks.
            .cache(Cache(File(context.applicationContext.cacheDir, "map_tiles"), 50L * 1024 * 1024))
            // Keep concurrency low for the public tile servers.
            .dispatcher(Dispatcher().apply { maxRequests = 6; maxRequestsPerHost = 2 })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .build()

    fun get(url: String): ImageBitmap? = memory.get(url)

    fun clearFailures() = synchronized(lock) { failedAt.clear() }

    /** Cancels queued or running downloads that are no longer on screen. */
    fun cancelExcept(keep: Set<String>) {
        val toCancel = synchronized(lock) {
            val stale = inFlight.filterKeys { it !in keep }
            stale.keys.forEach { inFlight.remove(it) }
            stale.values.toList()
        }
        toCancel.forEach { it.cancel() }
    }

    fun request(context: Context, url: String, onResult: (TileLoadResult) -> Unit) {
        if (memory.get(url) != null) return
        val httpClient = client(context)
        val call: Call? = synchronized(lock) {
            val failed = failedAt[url]
            val retryBlocked = failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS
            if (inFlight.containsKey(url) || retryBlocked) {
                null
            } else {
                httpClient.newCall(Request.Builder().url(url).build()).also { inFlight[url] = it }
            }
        }
        if (call == null) return
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val wasTracked = synchronized(lock) { inFlight.remove(url) != null }
                if (call.isCanceled() || !wasTracked) return
                synchronized(lock) { failedAt[url] = System.currentTimeMillis() }
                mainHandler.post { onResult(TileLoadResult.Failed(url, e.javaClass.simpleName + ": " + (e.message ?: "network error"))) }
            }

            override fun onResponse(call: Call, response: Response) {
                val result: TileLoadResult = response.use { res ->
                    if (!res.isSuccessful) {
                        TileLoadResult.Failed(url, "HTTP ${res.code} ${res.message}".trim(), res.code)
                    } else {
                        val bytes = res.body?.bytes()
                        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        if (bitmap == null) {
                            TileLoadResult.Failed(url, "Response was not an image (Content-Type ${res.header("Content-Type") ?: "unknown"})", res.code)
                        } else {
                            memory.put(url, bitmap.asImageBitmap())
                            TileLoadResult.Loaded(url, fromCache = res.networkResponse == null)
                        }
                    }
                }
                synchronized(lock) {
                    inFlight.remove(url)
                    if (result is TileLoadResult.Failed) failedAt[url] = System.currentTimeMillis()
                }
                mainHandler.post {
                    if (result is TileLoadResult.Loaded) version.intValue++
                    onResult(result)
                }
            }
        })
    }
}

private data class TileRef(val z: Int, val x: Int, val y: Int, val wrappedX: Int)

private fun visibleTiles(camera: MapCameraState, width: Int, height: Int, tileSize: Float, maxSourceZoom: Int): List<TileRef> {
    if (width <= 0 || height <= 0) return emptyList()
    val z = floor(camera.zoom).toInt().coerceIn(0, maxSourceZoom)
    val n = 1 shl z
    val world = camera.worldSizePx(tileSize)
    val left = camera.centerX - width / 2.0 / world
    val right = camera.centerX + width / 2.0 / world
    val top = camera.centerY - height / 2.0 / world
    val bottom = camera.centerY + height / 2.0 / world
    val x0 = floor(left * n).toInt()
    val x1 = floor(right * n).toInt()
    val y0 = floor(top * n).toInt().coerceAtLeast(0)
    val y1 = floor(bottom * n).toInt().coerceAtMost(n - 1)
    val tiles = ArrayList<TileRef>()
    for (y in y0..y1) for (x in x0..x1) {
        if (tiles.size >= 96) return tiles
        tiles.add(TileRef(z, x, y, ((x % n) + n) % n))
    }
    return tiles
}

/** Screen position of a lat/lon, choosing the horizontal world copy nearest the camera centre. */
private fun markerScreenPosition(marker: MapMarker, camera: MapCameraState, width: Int, height: Int, tileSize: Float): Offset {
    val world = camera.worldSizePx(tileSize)
    var dx = lonToWorldX(marker.longitude) - camera.centerX
    dx -= Math.round(dx).toDouble()
    val dy = latToWorldY(marker.latitude) - camera.centerY
    return Offset((width / 2.0 + dx * world).toFloat(), (height / 2.0 + dy * world).toFloat())
}

private val MapBackground = Color(0xFF0D151B)
private val MapGridLine = Color(0xFF18242E)
private val OriginBlue = Color(0xFF3D8BFF)
private val PinTeal = Color(0xFF13B8A6)
private val PinSelected = Color(0xFFFFA64D)

@Composable
internal fun OsmTileMap(
    markers: List<MapMarker>,
    source: MapTileSource,
    camera: MapCameraState,
    modifier: Modifier = Modifier,
    reloadToken: Int = 0,
    onMapLog: (String) -> Unit = {}
) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current.density
    val tileSize = mapTileSizePx(density)
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var successes by remember { mutableIntStateOf(0) }
    var failures by remember { mutableIntStateOf(0) }
    var lastError by remember { mutableStateOf<String?>(null) }
    val log by rememberUpdatedState(onMapLog)
    val currentMarkers by rememberUpdatedState(markers)

    LaunchedEffect(source) {
        // Allow one level of over-zoom beyond what the tile source serves.
        camera.maxZoom = (source.maxZoom + 1).toDouble()
        if (camera.zoom > camera.maxZoom) camera.zoom = camera.maxZoom
    }

    val neededUrls by remember(source, tileSize) {
        derivedStateOf {
            visibleTiles(camera, viewSize.width, viewSize.height, tileSize, source.maxZoom)
                .map { source.url(it.z, it.wrappedX, it.y) }
                .toSet()
        }
    }

    LaunchedEffect(source, reloadToken) {
        successes = 0
        failures = 0
        lastError = null
        MapTileLoader.clearFailures()
        log("Tile source=${source.name}; endpoint=${source.urlTemplate}; User-Agent=${MapTileLoader.userAgent}")
    }

    LaunchedEffect(neededUrls, source, reloadToken) {
        MapTileLoader.cancelExcept(neededUrls)
        neededUrls.forEach { url ->
            MapTileLoader.request(context, url) { result ->
                when (result) {
                    is TileLoadResult.Loaded -> {
                        successes++
                        if (successes <= 3 || successes % 25 == 0) {
                            log("Tile loaded (${if (result.fromCache) "disk cache" else "network"}); total=$successes")
                        }
                    }
                    is TileLoadResult.Failed -> {
                        failures++
                        lastError = result.reason
                        if (failures <= 5 || failures % 10 == 0) {
                            val hint = when (result.httpCode) {
                                403, 418, 429 -> " — the tile server refused the request (blocked User-Agent or rate limit)"
                                else -> ""
                            }
                            log("TILE FAILURE ${result.url}: ${result.reason}$hint; failures=$failures, successes=$successes")
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { MapTileLoader.cancelExcept(emptySet()) }
    }

    // Reading the version here recomposes the overlays when tiles arrive.
    val tileVersion = MapTileLoader.version.intValue
    val anyVisibleTileLoaded = remember(neededUrls, tileVersion) { neededUrls.any { MapTileLoader.get(it) != null } }
    val selected = markers.firstOrNull { it.id == selectedId }

    Box(modifier.background(MapBackground)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        camera.panBy(pan.x, pan.y, tileSize)
                        if (gestureZoom != 1f) {
                            camera.zoomAround(centroid, viewSize.width, viewSize.height, tileSize, camera.zoom + log2(gestureZoom.toDouble()))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { point ->
                            camera.zoomAround(point, viewSize.width, viewSize.height, tileSize, floor(camera.zoom) + 1.0)
                        },
                        onTap = { point ->
                            val hitRadius = 28.dp.toPx()
                            val pinLift = 14.dp.toPx()
                            val hit = currentMarkers
                                .map { marker ->
                                    val p = markerScreenPosition(marker, camera, viewSize.width, viewSize.height, tileSize)
                                    val anchor = if (marker.isOrigin) p else Offset(p.x, p.y - pinLift)
                                    marker to (anchor - point).getDistance()
                                }
                                .filter { it.second <= hitRadius }
                                .minByOrNull { it.second }
                            selectedId = hit?.first?.id
                        }
                    )
                }
        ) {
            MapTileLoader.version.intValue // redraw when tiles arrive
            drawTiles(camera, source, tileSize)
            currentMarkers.filter { it.isOrigin }.forEach { drawOriginMarker(markerScreenPosition(it, camera, size.width.toInt(), size.height.toInt(), tileSize)) }
            currentMarkers.filter { !it.isOrigin }.forEach {
                drawPin(markerScreenPosition(it, camera, size.width.toInt(), size.height.toInt(), tileSize), selected = it.id == selectedId)
            }
        }

        if (!anyVisibleTileLoaded) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp)
                    .background(Color(0xDD101820), RoundedCornerShape(12.dp)).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (failures >= 3) {
                    Text("Map tiles could not be loaded", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(lastError ?: "Check your internet connection, then tap Reload.", color = Color(0xFF9FB0BA), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                } else {
                    CircularProgressIndicator(color = PinTeal, modifier = Modifier.padding(4.dp))
                    Text("Loading map…", color = Color(0xFF9FB0BA), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (selected != null) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 84.dp, bottom = 30.dp)
                    .widthIn(max = 320.dp)
                    .background(Color(0xF0101820), RoundedCornerShape(12.dp)).padding(12.dp)
            ) {
                Text(selected.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                if (selected.snippet.isNotBlank()) Text(selected.snippet, color = Color(0xFF9FB0BA), style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(
            source.attribution,
            color = Color(0xFF1A1A1A),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(Color(0xCCFFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

private fun DrawScope.drawTiles(camera: MapCameraState, source: MapTileSource, tileSize: Float) {
    val width = size.width.toInt()
    val height = size.height.toInt()
    val world = camera.worldSizePx(tileSize)
    for (tile in visibleTiles(camera, width, height, tileSize, source.maxZoom)) {
        val n = 1 shl tile.z
        val left = (width / 2.0 + (tile.x.toDouble() / n - camera.centerX) * world).roundToInt()
        val right = (width / 2.0 + ((tile.x + 1).toDouble() / n - camera.centerX) * world).roundToInt()
        val top = (height / 2.0 + (tile.y.toDouble() / n - camera.centerY) * world).roundToInt()
        val bottom = (height / 2.0 + ((tile.y + 1).toDouble() / n - camera.centerY) * world).roundToInt()
        val dstOffset = IntOffset(left, top)
        val dstSize = IntSize(max(1, right - left), max(1, bottom - top))

        val image = MapTileLoader.get(source.url(tile.z, tile.wrappedX, tile.y))
        if (image != null) {
            drawImage(image, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.Low)
            continue
        }
        // While a tile loads, stretch the matching part of an already-loaded lower-zoom tile.
        var drewFallback = false
        for (k in 1..4) {
            val parentZ = tile.z - k
            if (parentZ < 0) break
            val parent = MapTileLoader.get(source.url(parentZ, tile.wrappedX shr k, tile.y shr k)) ?: continue
            val parts = 1 shl k
            val partW = max(1, parent.width / parts)
            val partH = max(1, parent.height / parts)
            val sx = (tile.wrappedX - ((tile.wrappedX shr k) shl k)) * partW
            val sy = (tile.y - ((tile.y shr k) shl k)) * partH
            drawImage(parent, srcOffset = IntOffset(sx, sy), srcSize = IntSize(partW, partH), dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.Low)
            drewFallback = true
            break
        }
        if (!drewFallback) {
            drawRect(MapGridLine, topLeft = Offset(left.toFloat(), top.toFloat()), size = Size(dstSize.width.toFloat(), dstSize.height.toFloat()), style = Stroke(1f))
        }
    }
}

private fun DrawScope.drawOriginMarker(position: Offset) {
    drawCircle(OriginBlue.copy(alpha = 0.25f), radius = 16.dp.toPx(), center = position)
    drawCircle(Color.White, radius = 8.dp.toPx(), center = position)
    drawCircle(OriginBlue, radius = 6.dp.toPx(), center = position)
}

private fun DrawScope.drawPin(tip: Offset, selected: Boolean) {
    val color = if (selected) PinSelected else PinTeal
    val radius = 10.dp.toPx()
    val head = Offset(tip.x, tip.y - 14.dp.toPx() - radius / 2f)
    val body = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(head.x - radius * 0.8f, head.y + radius * 0.35f)
        lineTo(head.x + radius * 0.8f, head.y + radius * 0.35f)
        close()
    }
    drawCircle(Color.Black.copy(alpha = 0.25f), radius = 3.dp.toPx(), center = tip)
    drawPath(body, Color.White)
    drawCircle(Color.White, radius = radius + 2.dp.toPx(), center = head)
    drawPath(Path().apply {
        moveTo(tip.x, tip.y - 2.dp.toPx())
        lineTo(head.x - radius * 0.7f, head.y + radius * 0.3f)
        lineTo(head.x + radius * 0.7f, head.y + radius * 0.3f)
        close()
    }, color)
    drawCircle(color, radius = radius, center = head)
    drawCircle(Color.White, radius = radius * 0.38f, center = head)
}

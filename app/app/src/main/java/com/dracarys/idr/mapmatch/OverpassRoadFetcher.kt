package com.dracarys.idr.mapmatch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Proactively fetches road polyline geometry from the Overpass API (OpenStreetMap)
 * for a bounding box around the current GPS position. Converts WGS84 road nodes
 * to local ENU meter coordinates for [SoftMapMatcher.loadSegments].
 *
 * Strategy:
 * - Continuously monitors current GPS position while healthy
 * - Pre-fetches road data for a generous bbox (default ~500m radius)
 * - Caches results to on-device storage for offline/repeat use
 * - Re-fetches only when the vehicle moves outside the cached bbox
 * - Falls back to GPS-trace self-matching when no cached road data is available
 *
 * Thread safety: All network I/O runs on [Dispatchers.IO]. The resulting segment
 * arrays are delivered back to the caller for loading into [SoftMapMatcher].
 */
class OverpassRoadFetcher(
    private val cacheDir: File,
) {
    companion object {
        private const val TAG = "OverpassRoadFetcher"

        /** Default half-width of the fetch bounding box in degrees (~500m at mid-latitudes). */
        private const val BBOX_HALF_DEG = 0.005

        /** Re-fetch threshold: if the vehicle moves this far outside the cached bbox center, re-fetch. */
        private const val REFETCH_THRESHOLD_DEG = 0.003

        /** Maximum number of cached tiles to keep on disk. */
        private const val MAX_CACHE_FILES = 20

        /** Minimum interval between Overpass API requests (ms) to respect rate limits. */
        private const val MIN_FETCH_INTERVAL_MS = 30_000L

        /** HTTP connect and read timeout (ms). */
        private const val HTTP_TIMEOUT_MS = 15_000

        /** Overpass API endpoints (round-robin for load distribution). */
        private val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )

        /** Segment spacing for interpolation along road polylines (meters). */
        private const val SEGMENT_SPACING_M = 5.0
    }

    init {
        cacheDir.mkdirs()
    }

    // Cached bbox center and loaded segments
    @Volatile var cachedCenterLat: Double = 0.0; private set
    @Volatile var cachedCenterLon: Double = 0.0; private set
    @Volatile var cachedBboxHalfDeg: Double = 0.0; private set
    @Volatile var totalSegmentsLoaded: Int = 0; private set
    @Volatile var lastFetchStatus: String = "INIT"; private set

    private var lastFetchTimeMs: Long = 0L
    private var endpointIndex: Int = 0

    /**
     * Check if we need to re-fetch road data for the given position.
     * Returns true if the position is outside the current cached coverage.
     */
    fun needsRefetch(lat: Double, lon: Double): Boolean {
        if (cachedBboxHalfDeg == 0.0) return true
        val dLat = abs(lat - cachedCenterLat)
        val dLon = abs(lon - cachedCenterLon)
        return dLat > (cachedBboxHalfDeg - REFETCH_THRESHOLD_DEG) ||
            dLon > (cachedBboxHalfDeg - REFETCH_THRESHOLD_DEG)
    }

    /**
     * Proactively fetch road segments for the area around [lat]/[lon].
     * Returns the segment arrays (p1, p2) in ENU meters relative to [refLat]/[refLon],
     * or null if fetching failed and no cache is available.
     *
     * Must be called from a coroutine (runs on [Dispatchers.IO]).
     */
    suspend fun fetchRoadSegments(
        lat: Double,
        lon: Double,
        refLat: Double,
        refLon: Double,
    ): Pair<Array<DoubleArray>, Array<DoubleArray>>? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // Rate limiting
        if (now - lastFetchTimeMs < MIN_FETCH_INTERVAL_MS) {
            Log.d(TAG, "Rate limited, skipping fetch (${now - lastFetchTimeMs}ms since last)")
            // Try to load from cache instead
            return@withContext loadFromCache(lat, lon, refLat, refLon)
        }

        val south = lat - BBOX_HALF_DEG
        val north = lat + BBOX_HALF_DEG
        val west = lon - BBOX_HALF_DEG
        val east = lon + BBOX_HALF_DEG

        // Check cache first
        val cacheFile = getCacheFile(lat, lon)
        if (cacheFile.exists()) {
            try {
                val segments = parseCachedSegments(cacheFile, refLat, refLon)
                if (segments != null) {
                    cachedCenterLat = lat
                    cachedCenterLon = lon
                    cachedBboxHalfDeg = BBOX_HALF_DEG
                    totalSegmentsLoaded = segments.first.size
                    lastFetchStatus = "CACHE_HIT (${segments.first.size} segs)"
                    Log.i(TAG, "Cache hit: ${segments.first.size} segments for ${"%.4f".format(lat)},${
                        "%.4f".format(lon)
                    }")
                    return@withContext segments
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cache parse failed, will re-fetch: ${e.message}")
            }
        }

        // Build Overpass QL query for drivable roads
        val query = buildOverpassQuery(south, west, north, east)

        // Try fetching from Overpass API
        var lastException: Exception? = null
        for (attempt in 0 until OVERPASS_ENDPOINTS.size) {
            val endpoint = OVERPASS_ENDPOINTS[(endpointIndex + attempt) % OVERPASS_ENDPOINTS.size]
            try {
                val responseJson = executeOverpassQuery(endpoint, query)
                lastFetchTimeMs = System.currentTimeMillis()
                endpointIndex = (endpointIndex + attempt + 1) % OVERPASS_ENDPOINTS.size

                // Parse OSM way nodes into road segments
                val segments = parseOverpassResponse(responseJson, refLat, refLon)
                if (segments != null && segments.first.isNotEmpty()) {
                    // Cache the result
                    saveToCacheFile(cacheFile, responseJson)
                    evictOldCacheFiles()

                    cachedCenterLat = lat
                    cachedCenterLon = lon
                    cachedBboxHalfDeg = BBOX_HALF_DEG
                    totalSegmentsLoaded = segments.first.size
                    lastFetchStatus = "API_OK (${segments.first.size} segs)"
                    Log.i(TAG, "Fetched ${segments.first.size} road segments from Overpass")
                    return@withContext segments
                } else {
                    lastFetchStatus = "API_EMPTY"
                    Log.w(TAG, "Overpass returned no road elements for bbox")
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Overpass fetch failed from $endpoint: ${e.message}")
            }
        }

        lastFetchStatus = "API_FAIL: ${lastException?.message?.take(60)}"
        Log.e(TAG, "All Overpass endpoints failed", lastException)

        // Final fallback: try any cached data
        return@withContext loadFromCache(lat, lon, refLat, refLon)
    }

    /**
     * Build road segments from accumulated GPS breadcrumb fixes (fallback mode).
     * Resamples the GPS trace into ~5m segments in ENU coordinates.
     */
    fun buildGpsTraceSegments(
        gpsPoints: List<Pair<Double, Double>>,
        refLat: Double,
        refLon: Double,
    ): Pair<Array<DoubleArray>, Array<DoubleArray>>? {
        if (gpsPoints.size < 2) return null

        val p1List = mutableListOf<DoubleArray>()
        val p2List = mutableListOf<DoubleArray>()

        // Convert to ENU and interpolate
        val enuPoints = gpsPoints.map { (lat, lon) -> geodeticToEnu(lat, lon, refLat, refLon) }

        for (i in 0 until enuPoints.size - 1) {
            val start = enuPoints[i]
            val end = enuPoints[i + 1]
            val dx = end.first - start.first
            val dy = end.second - start.second
            val dist = sqrt(dx * dx + dy * dy)

            if (dist < 0.5) continue // Skip near-duplicate points

            val numSub = max(1, (dist / SEGMENT_SPACING_M).toInt())
            for (s in 0 until numSub) {
                val t1 = s.toDouble() / numSub
                val t2 = (s + 1).toDouble() / numSub
                p1List.add(doubleArrayOf(start.first + t1 * dx, start.second + t1 * dy))
                p2List.add(doubleArrayOf(start.first + t2 * dx, start.second + t2 * dy))
            }
        }

        if (p1List.isEmpty()) return null

        lastFetchStatus = "GPS_TRACE (${p1List.size} segs)"
        totalSegmentsLoaded = p1List.size
        return Pair(p1List.toTypedArray(), p2List.toTypedArray())
    }

    // ---------- Overpass Query Building ----------

    private fun buildOverpassQuery(south: Double, west: Double, north: Double, east: Double): String {
        val bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", south, west, north, east)
        // Fetch all drivable roads (motorway through residential) within the bbox
        return """
            [out:json][timeout:10];
            (
              way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|service|living_street)$"]($bbox);
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    private fun executeOverpassQuery(endpoint: String, query: String): String {
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "DracarysIDR/1.0 (Android; Academic Research)")
            conn.doOutput = true

            conn.outputStream.use { os ->
                os.write("data=${java.net.URLEncoder.encode(query, "UTF-8")}".toByteArray())
            }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = try {
                    conn.errorStream?.use { BufferedReader(InputStreamReader(it)).readText() } ?: ""
                } catch (_: Exception) { "" }
                throw RuntimeException("Overpass HTTP $responseCode: ${errorBody.take(200)}")
            }

            return conn.inputStream.use { BufferedReader(InputStreamReader(it)).readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Response Parsing ----------

    private fun parseOverpassResponse(
        jsonStr: String,
        refLat: Double,
        refLon: Double,
    ): Pair<Array<DoubleArray>, Array<DoubleArray>>? {
        try {
            val json = JSONObject(jsonStr)
            val elements = json.getJSONArray("elements")

            // First pass: collect all node coordinates by ID
            val nodeCoords = HashMap<Long, Pair<Double, Double>>()
            for (i in 0 until elements.length()) {
                val elem = elements.getJSONObject(i)
                if (elem.getString("type") == "node") {
                    val id = elem.getLong("id")
                    val lat = elem.getDouble("lat")
                    val lon = elem.getDouble("lon")
                    nodeCoords[id] = Pair(lat, lon)
                }
            }

            // Second pass: build road segments from way node sequences
            val p1List = mutableListOf<DoubleArray>()
            val p2List = mutableListOf<DoubleArray>()

            for (i in 0 until elements.length()) {
                val elem = elements.getJSONObject(i)
                if (elem.getString("type") != "way") continue

                val nodes = elem.getJSONArray("nodes")
                if (nodes.length() < 2) continue

                // Convert node sequence to ENU segments with interpolation
                var prevEnu: Pair<Double, Double>? = null
                for (n in 0 until nodes.length()) {
                    val nodeId = nodes.getLong(n)
                    val coords = nodeCoords[nodeId] ?: continue
                    val enu = geodeticToEnu(coords.first, coords.second, refLat, refLon)

                    if (prevEnu != null) {
                        val dx = enu.first - prevEnu.first
                        val dy = enu.second - prevEnu.second
                        val dist = sqrt(dx * dx + dy * dy)

                        if (dist < 0.5) {
                            prevEnu = enu
                            continue
                        }

                        // Interpolate long segments into ~5m sub-segments
                        val numSub = max(1, (dist / SEGMENT_SPACING_M).toInt())
                        for (s in 0 until numSub) {
                            val t1 = s.toDouble() / numSub
                            val t2 = (s + 1).toDouble() / numSub
                            p1List.add(doubleArrayOf(
                                prevEnu.first + t1 * dx,
                                prevEnu.second + t1 * dy
                            ))
                            p2List.add(doubleArrayOf(
                                prevEnu.first + t2 * dx,
                                prevEnu.second + t2 * dy
                            ))
                        }
                    }
                    prevEnu = enu
                }
            }

            if (p1List.isEmpty()) return null
            return Pair(p1List.toTypedArray(), p2List.toTypedArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Overpass response: ${e.message}", e)
            return null
        }
    }

    // ---------- Caching ----------

    private fun getCacheFile(lat: Double, lon: Double): File {
        // Grid-quantize to ~0.01° tiles (~1.1km) for cache keying
        val gridLat = "%.3f".format(lat)
        val gridLon = "%.3f".format(lon)
        return File(cacheDir, "roads_${gridLat}_${gridLon}.json")
    }

    private fun loadFromCache(
        lat: Double,
        lon: Double,
        refLat: Double,
        refLon: Double,
    ): Pair<Array<DoubleArray>, Array<DoubleArray>>? {
        val cacheFile = getCacheFile(lat, lon)
        if (!cacheFile.exists()) return null
        return parseCachedSegments(cacheFile, refLat, refLon)
    }

    private fun parseCachedSegments(
        file: File,
        refLat: Double,
        refLon: Double,
    ): Pair<Array<DoubleArray>, Array<DoubleArray>>? {
        return try {
            val content = file.readText()
            parseOverpassResponse(content, refLat, refLon)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cache file ${file.name}: ${e.message}")
            null
        }
    }

    private fun saveToCacheFile(file: File, jsonStr: String) {
        try {
            file.writeText(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache file: ${e.message}")
        }
    }

    private fun evictOldCacheFiles() {
        try {
            val files = cacheDir.listFiles { f -> f.name.startsWith("roads_") && f.name.endsWith(".json") }
                ?: return
            if (files.size > MAX_CACHE_FILES) {
                files.sortedBy { it.lastModified() }
                    .take(files.size - MAX_CACHE_FILES)
                    .forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    // ---------- Coordinate Conversion ----------

    private fun geodeticToEnu(lat: Double, lon: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val rEarth = 6378137.0
        val dLat = Math.toRadians(lat - lat0)
        val dLon = Math.toRadians(lon - lon0)
        val lat0Rad = Math.toRadians(lat0)
        val x = dLon * rEarth * cos(lat0Rad)
        val y = dLat * rEarth
        return Pair(x, y)
    }
}

package com.dracarys.idr.mapmatch

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Maximum perpendicular search corridor width (meters) within which road candidates are evaluated. */
const val DEFAULT_MAP_CORRIDOR_M: Double = 35.0

/** Soft correction gain pulling dead reckoning position toward candidate road polylines (0.0 = no map aid, 1.0 = hard snap). */
const val DEFAULT_MAP_PULL_FACTOR: Double = 0.45

/** Gaussian distance weighting decay parameter (meters) for multi-candidate road likelihood. */
const val DEFAULT_MAP_SIGMA_DIST: Double = 12.0

/**
 * Multi-candidate probabilistic road matcher with 2D KD-Tree spatial acceleration.
 *
 * Evaluates candidate road segments within [corridorM], weights candidates by perpendicular
 * distance and heading consistency, and gently pulls dead reckoning position toward road network.
 */
class SoftMapMatcher(
    val corridorM: Double = DEFAULT_MAP_CORRIDOR_M,
    val pullFactor: Double = DEFAULT_MAP_PULL_FACTOR,
    val sigmaDist: Double = DEFAULT_MAP_SIGMA_DIST,
) {
    // Road segment endpoints: p1 and p2 in ENU meters (N, 2)
    var p1List: Array<DoubleArray> = emptyArray()
        private set
    var p2List: Array<DoubleArray> = emptyArray()
        private set

    private var kdTree: KdTree2D? = null

    // ---- Diagnostic state from last matchPoint() call ----
    /** Number of candidate road segments found within the search corridor on the last tick. */
    @Volatile var lastCandidateCount: Int = 0; private set
    /** Perpendicular distance to the nearest candidate segment (meters) on the last tick. Double.MAX_VALUE if none. */
    @Volatile var lastNearestDistM: Double = Double.MAX_VALUE; private set
    /** Magnitude of the correction vector applied to the position (meters) on the last tick. */
    @Volatile var lastCorrectionM: Double = 0.0; private set

    /** Total number of road segments currently loaded. Zero means SMM is a no-op. */
    val segmentCount: Int get() = p1List.size

    /**
     * Loads segments from a list of start/end pairs in ENU meters.
     */
    fun loadSegments(p1: Array<DoubleArray>, p2: Array<DoubleArray>) {
        require(p1.size == p2.size) { "p1 and p2 must have equal size" }
        p1List = p1
        p2List = p2

        val midpoints = Array(p1.size) { i ->
            doubleArrayOf((p1[i][0] + p2[i][0]) * 0.5, (p1[i][1] + p2[i][1]) * 0.5)
        }
        kdTree = if (midpoints.isNotEmpty()) KdTree2D(midpoints) else null
    }

    /**
     * Matches a single estimated position (x, y) with vehicle heading to nearby road candidates.
     *
     * @return Triple of (matchedX, matchedY, confidenceScore in [0, 1])
     */
    fun matchPoint(
        x: Double,
        y: Double,
        headingRad: Double,
    ): Triple<Double, Double, Double> {
        val tree = kdTree
        if (tree == null) {
            lastCandidateCount = 0
            lastNearestDistM = Double.MAX_VALUE
            lastCorrectionM = 0.0
            return Triple(x, y, 0.0)
        }
        if (p1List.isEmpty()) {
            lastCandidateCount = 0
            lastNearestDistM = Double.MAX_VALUE
            lastCorrectionM = 0.0
            return Triple(x, y, 0.0)
        }

        // Query segments within search corridor + margin
        val candIndices = tree.queryRadius(x, y, corridorM + 10.0)
        if (candIndices.isEmpty()) {
            lastCandidateCount = 0
            lastNearestDistM = Double.MAX_VALUE
            lastCorrectionM = 0.0
            return Triple(x, y, 0.0)
        }

        val vehDirX = cos(headingRad)
        val vehDirY = sin(headingRad)

        var totalWeight = 0.0
        var targetX = 0.0
        var targetY = 0.0
        var minDist = Double.MAX_VALUE
        var candidatesInCorridor = 0

        for (idx in candIndices) {
            val p1 = p1List[idx]
            val p2 = p2List[idx]

            val segVecX = p2[0] - p1[0]
            val segVecY = p2[1] - p1[1]
            val segLen = sqrt(segVecX * segVecX + segVecY * segVecY)
            if (segLen < 1e-4) continue

            val unitSegX = segVecX / segLen
            val unitSegY = segVecY / segLen

            val diffX = x - p1[0]
            val diffY = y - p1[1]
            val projT = diffX * unitSegX + diffY * unitSegY
            val projClamped = projT.coerceIn(0.0, segLen)

            val closestX = p1[0] + projClamped * unitSegX
            val closestY = p1[1] + projClamped * unitSegY

            val distX = x - closestX
            val distY = y - closestY
            val dist = sqrt(distX * distX + distY * distY)

            if (dist < minDist) minDist = dist

            if (dist < corridorM) {
                candidatesInCorridor++
                val headingScore = max(0.1, abs(vehDirX * unitSegX + vehDirY * unitSegY))
                val distProb = exp(-(dist * dist) / (2.0 * sigmaDist * sigmaDist))
                val w = distProb * headingScore

                targetX += w * closestX
                targetY += w * closestY
                totalWeight += w
            }
        }

        lastCandidateCount = candidatesInCorridor
        lastNearestDistM = if (minDist == Double.MAX_VALUE) Double.MAX_VALUE else minDist

        if (totalWeight < 1e-6) {
            lastCorrectionM = 0.0
            return Triple(x, y, 0.0)
        }

        val avgTargetX = targetX / totalWeight
        val avgTargetY = targetY / totalWeight

        val pulledX = (1.0 - pullFactor) * x + pullFactor * avgTargetX
        val pulledY = (1.0 - pullFactor) * y + pullFactor * avgTargetY

        lastCorrectionM = sqrt((pulledX - x) * (pulledX - x) + (pulledY - y) * (pulledY - y))

        return Triple(pulledX, pulledY, min(1.0, totalWeight))
    }

    /**
     * Performs closed-loop sequential soft map matching across a trajectory.
     */
    fun matchTrajectory(
        x: DoubleArray,
        y: DoubleArray,
        psi: DoubleArray,
    ): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        if (n == 0) return Pair(x, y)

        val xOut = DoubleArray(n)
        val yOut = DoubleArray(n)

        val (x0, y0, _) = matchPoint(x[0], y[0], psi[0])
        xOut[0] = x0
        yOut[0] = y0

        for (k in 1 until n) {
            val dx = x[k] - x[k - 1]
            val dy = y[k] - y[k - 1]

            val xProp = xOut[k - 1] + dx
            val yProp = yOut[k - 1] + dy

            val (xk, yk, _) = matchPoint(xProp, yProp, psi[k])
            xOut[k] = xk
            yOut[k] = yk
        }

        return Pair(xOut, yOut)
    }

    /**
     * Compact 2D KD-Tree for sub-millisecond radius search.
     */
    private class KdTree2D(points: Array<DoubleArray>) {
        private class Node(
            val point: DoubleArray,
            val index: Int,
            val axis: Int,
            var left: Node? = null,
            var right: Node? = null,
        )

        private val root: Node?

        init {
            val items = points.indices.map { i -> Pair(points[i], i) }
            root = buildTree(items, depth = 0)
        }

        private fun buildTree(items: List<Pair<DoubleArray, Int>>, depth: Int): Node? {
            if (items.isEmpty()) return null
            val axis = depth % 2
            val sorted = items.sortedBy { it.first[axis] }
            val mid = sorted.size / 2
            val node = Node(sorted[mid].first, sorted[mid].second, axis)
            node.left = buildTree(sorted.subList(0, mid), depth + 1)
            node.right = buildTree(sorted.subList(mid + 1, sorted.size), depth + 1)
            return node
        }

        fun queryRadius(qx: Double, qy: Double, radius: Double): List<Int> {
            val result = mutableListOf<Int>()
            val r2 = radius * radius
            search(root, qx, qy, radius, r2, result)
            return result
        }

        private fun search(node: Node?, qx: Double, qy: Double, r: Double, r2: Double, out: MutableList<Int>) {
            if (node == null) return
            val dx = qx - node.point[0]
            val dy = qy - node.point[1]
            val dist2 = dx * dx + dy * dy
            if (dist2 <= r2) {
                out.add(node.index)
            }

            val axisDiff = if (node.axis == 0) dx else dy
            val first = if (axisDiff < 0) node.left else node.right
            val second = if (axisDiff < 0) node.right else node.left

            search(first, qx, qy, r, r2, out)
            if (axisDiff * axisDiff <= r2) {
                search(second, qx, qy, r, r2, out)
            }
        }
    }
}

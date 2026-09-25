package com.dracarys.idr.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dracarys.idr.ui.components.ConfidenceBar
import com.dracarys.idr.ui.components.DebugToggle
import com.dracarys.idr.ui.components.DriftBar
import com.dracarys.idr.ui.components.LastFixDistance
import com.dracarys.idr.ui.components.ModeBadge
import com.dracarys.idr.ui.components.OutageTimer
import com.dracarys.idr.ui.components.VehicleIcon
import com.dracarys.idr.ui.state.FakeNavigationRepository
import com.dracarys.idr.ui.state.NavigationMode
import com.dracarys.idr.ui.state.NavigationState
import com.dracarys.idr.ui.theme.DracarysBackground
import com.dracarys.idr.ui.theme.DracarysSurface
import com.dracarys.idr.ui.theme.DracarysTheme

private val CAPSULE_CORNER = 20.dp
private val CAPSULE_PADDING = 16.dp
private val ANIMATION_DURATION_MS = 350

/**
 * Bottom-anchored instrument capsule.
 *
 * ### Two states
 * - **Collapsed** (mode == [NavigationMode.Gnss]): compact row showing badge + vehicle icon.
 * - **Expanded** (mode == [NavigationMode.Fused] or [NavigationMode.DeadReckoning]):
 *   full instrument panel with [ConfidenceBar], [DriftBar], [OutageTimer], [LastFixDistance].
 *
 * ### Animation
 * - [AnimatedContent] with [SizeTransform] handles height change (350 ms, [FastOutSlowInEasing]).
 * - Radial glow pulse fires once per mode change via [LaunchedEffect].
 *
 * ### Color
 * All sub-composables receive [NavigationState.mode] and read [NavigationMode.color] internally.
 * No hex literals appear in this file.
 */
@Composable
fun InstrumentCapsule(
    state: NavigationState,
    modifier: Modifier = Modifier,
    onDebugToggle: () -> Unit = {},
    debugEnabled: Boolean = false,
) {
    val isExpanded = state.mode != NavigationMode.Gnss

    // Glow pulse — one radial gradient animation per mode change
    var glowAlpha by remember { mutableStateOf(0f) }
    LaunchedEffect(state.mode) {
        glowAlpha = 0.55f
        kotlinx.coroutines.delay(400)
        glowAlpha = 0f
    }
    val animatedGlow by animateFloatAsState(
        targetValue = glowAlpha,
        animationSpec = tween(durationMillis = 400),
        label = "glow",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CAPSULE_CORNER))
            .background(DracarysSurface)
            .drawBehind {
                // Radial glow in current mode color, fading after each mode change
                if (animatedGlow > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                state.mode.color.copy(alpha = animatedGlow * 0.5f),
                                Color.Transparent,
                            ),
                            radius = size.minDimension * 1.2f,
                        ),
                    )
                }
            }
            .padding(CAPSULE_PADDING),
    ) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                (fadeIn(tween(ANIMATION_DURATION_MS, easing = FastOutSlowInEasing))
                    togetherWith fadeOut(tween(ANIMATION_DURATION_MS, easing = FastOutSlowInEasing)))
                    .using(SizeTransform(clip = true) { _, _ ->
                        tween(ANIMATION_DURATION_MS, easing = FastOutSlowInEasing)
                    })
            },
            label = "capsule_expand",
        ) { expanded ->
            if (!expanded) {
                CapsuleCollapsed(
                    state = state,
                    onDebugToggle = onDebugToggle,
                    debugEnabled = debugEnabled,
                )
            } else {
                CapsuleExpanded(
                    state = state,
                    onDebugToggle = onDebugToggle,
                    debugEnabled = debugEnabled,
                )
            }
        }
    }
}

// ── Collapsed state ───────────────────────────────────────────────────────────

@Composable
private fun CapsuleCollapsed(
    state: NavigationState,
    onDebugToggle: () -> Unit,
    debugEnabled: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VehicleIcon(mode = state.mode, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            ModeBadge(mode = state.mode)
        }
        DebugToggle(onToggle = onDebugToggle, enabled = debugEnabled)
    }
}

// ── Expanded state ────────────────────────────────────────────────────────────

@Composable
private fun CapsuleExpanded(
    state: NavigationState,
    onDebugToggle: () -> Unit,
    debugEnabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VehicleIcon(mode = state.mode, size = 28.dp)
                Spacer(Modifier.width(10.dp))
                ModeBadge(mode = state.mode)
            }
            DebugToggle(onToggle = onDebugToggle, enabled = debugEnabled)
        }

        Spacer(Modifier.height(14.dp))

        // Confidence and drift bars — full width
        ConfidenceBar(
            confidence = state.confidence,
            mode = state.mode,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        DriftBar(
            driftPercent = state.driftPercent,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        // Timer and distance side by side
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutageTimer(
                outageDurationSec = state.outageDurationSec,
                modifier = Modifier.weight(1f),
            )
            LastFixDistance(
                distanceToLastFixM = state.distanceToLastFixM,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Previews (backed by FakeNavigationRepository snapshots) ───────────────────

private val fake = FakeNavigationRepository()

@Preview(
    name = "Capsule — Collapsed (GNSS)",
    showBackground = true,
    backgroundColor = 0xFF0B0E14,
    widthDp = 380,
)
@Composable
private fun PreviewCapsuleCollapsed() {
    DracarysTheme {
        InstrumentCapsule(
            state = fake.snapshotGnss(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Preview(
    name = "Capsule — Expanded (Dead Reckoning)",
    showBackground = true,
    backgroundColor = 0xFF0B0E14,
    widthDp = 380,
)
@Composable
private fun PreviewCapsuleExpanded() {
    DracarysTheme {
        InstrumentCapsule(
            state = fake.snapshotDeadReckoning(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Preview(
    name = "Capsule — High Outage (stress)",
    showBackground = true,
    backgroundColor = 0xFF0B0E14,
    widthDp = 380,
)
@Composable
private fun PreviewCapsuleHighOutage() {
    DracarysTheme {
        InstrumentCapsule(
            state = fake.snapshotHighOutage(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

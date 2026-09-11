package com.dracarys.idr.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dracarys.idr.ui.state.NavigationState
import com.dracarys.idr.ui.theme.DracarysTheme
import org.junit.Rule
import org.junit.Test

class DriftBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun drift_bar_shows_label_with_drift_value_below_target() {
        composeTestRule.setContent {
            DracarysTheme { DriftBar(driftPercent = 3.2f) }
        }
        // Label should mention drift value and target
        composeTestRule.onNodeWithText("3.2", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("target", substring = true).assertIsDisplayed()
    }

    @Test
    fun drift_bar_shows_label_with_drift_value_above_target() {
        composeTestRule.setContent {
            DracarysTheme { DriftBar(driftPercent = 11.22f) }
        }
        composeTestRule.onNodeWithText("11.2", substring = true).assertIsDisplayed()
    }

    @Test
    fun drift_bar_shows_high_outage_drift_value() {
        composeTestRule.setContent {
            DracarysTheme { DriftBar(driftPercent = 24.8f) }
        }
        composeTestRule.onNodeWithText("24.8", substring = true).assertIsDisplayed()
    }

    @Test
    fun drift_target_threshold_constant_is_10_percent() {
        // Architectural: DriftBar reads from NavigationState.DRIFT_TARGET_PERCENT, not a hardcoded literal.
        // If this test is green and the constant changes, DriftBar changes with it.
        assert(NavigationState.DRIFT_TARGET_PERCENT == 10.0f) {
            "DRIFT_TARGET_PERCENT is ${NavigationState.DRIFT_TARGET_PERCENT}, expected 10.0"
        }
    }
}

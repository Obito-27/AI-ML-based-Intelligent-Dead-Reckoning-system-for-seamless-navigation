package com.dracarys.idr.ui.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dracarys.idr.ui.state.FakeNavigationRepository
import com.dracarys.idr.ui.theme.DracarysTheme
import org.junit.Rule
import org.junit.Test

class CapsuleAnimationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fake = FakeNavigationRepository()

    // ── Collapsed state ───────────────────────────────────────────────────────

    @Test
    fun collapsed_state_shows_GNSS_mode_label() {
        composeTestRule.setContent {
            DracarysTheme {
                InstrumentCapsule(state = fake.snapshotGnss())
            }
        }
        composeTestRule.onNodeWithText("GNSS", substring = true).assertIsDisplayed()
    }

    // ── Expanded state (Dead Reckoning snapshot) ──────────────────────────────

    @Test
    fun expanded_state_shows_mode_label() {
        composeTestRule.setContent {
            DracarysTheme {
                InstrumentCapsule(state = fake.snapshotDeadReckoning())
            }
        }
        composeTestRule.onNodeWithText("Dead Reckoning", substring = true).assertIsDisplayed()
    }

    @Test
    fun expanded_state_shows_confidence_label() {
        composeTestRule.setContent {
            DracarysTheme {
                InstrumentCapsule(state = fake.snapshotDeadReckoning())
            }
        }
        composeTestRule.onNodeWithText("Confidence", substring = true).assertIsDisplayed()
    }

    @Test
    fun expanded_state_shows_drift_label() {
        composeTestRule.setContent {
            DracarysTheme {
                InstrumentCapsule(state = fake.snapshotDeadReckoning())
            }
        }
        composeTestRule.onNodeWithText("Drift", substring = true).assertIsDisplayed()
    }

    @Test
    fun expanded_state_shows_outage_timer() {
        composeTestRule.setContent {
            DracarysTheme {
                InstrumentCapsule(state = fake.snapshotDeadReckoning())
            }
        }
        composeTestRule.onNodeWithText("Outage", substring = true).assertIsDisplayed()
    }

    @Test
    fun expanded_state_shows_last_fix_distance() {
        composeTestRule.setContent {
            DracarysTheme {
                InstrumentCapsule(state = fake.snapshotDeadReckoning())
            }
        }
        composeTestRule.onNodeWithText("Last fix", substring = true).assertIsDisplayed()
    }

    // ── High outage state ─────────────────────────────────────────────────────

    @Test
    fun high_outage_state_shows_over_1km_distance() {
        composeTestRule.setContent {
            DracarysTheme {
                InstrumentCapsule(state = fake.snapshotHighOutage())
            }
        }
        composeTestRule.onNodeWithText(">1 km", substring = true).assertIsDisplayed()
    }

    @Test
    fun high_outage_state_shows_outage_timer_above_1_minute() {
        composeTestRule.setContent {
            DracarysTheme {
                InstrumentCapsule(state = fake.snapshotHighOutage())
            }
        }
        // 182s = 03:02
        composeTestRule.onNodeWithText("03:02", substring = true).assertIsDisplayed()
    }
}

package com.dracarys.idr.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.dracarys.idr.ui.state.FakeNavigationRepository
import com.dracarys.idr.ui.state.NavigationMode
import com.dracarys.idr.ui.theme.DracarysTheme
import org.junit.Rule
import org.junit.Test

class ConfidenceBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun confidence_bar_renders_with_expected_label_for_61_percent() {
        composeTestRule.setContent {
            DracarysTheme {
                ConfidenceBar(confidence = 0.61f, mode = NavigationMode.DeadReckoning)
            }
        }
        composeTestRule.onNodeWithText("Confidence  61%", substring = true).assertIsDisplayed()
    }

    @Test
    fun confidence_bar_renders_with_expected_label_for_97_percent() {
        composeTestRule.setContent {
            DracarysTheme {
                ConfidenceBar(confidence = 0.97f, mode = NavigationMode.Gnss)
            }
        }
        composeTestRule.onNodeWithText("Confidence  97%", substring = true).assertIsDisplayed()
    }

    @Test
    fun confidence_bar_coerces_values_above_1() {
        // Should not crash or display > 100%
        composeTestRule.setContent {
            DracarysTheme {
                ConfidenceBar(confidence = 1.5f, mode = NavigationMode.Gnss)
            }
        }
        composeTestRule.onNodeWithText("Confidence  100%", substring = true).assertIsDisplayed()
    }
}

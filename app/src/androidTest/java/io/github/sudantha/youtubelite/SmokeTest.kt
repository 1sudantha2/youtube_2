package io.github.sudantha.youtubelite

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `home screen renders brand and navigation`() {
        rule.onNodeWithText("You-Tube").assertIsDisplayed()
        rule.onNodeWithContentDescription("Search").assertIsDisplayed()
        rule.onNodeWithContentDescription("Account and settings").assertIsDisplayed()
    }
}

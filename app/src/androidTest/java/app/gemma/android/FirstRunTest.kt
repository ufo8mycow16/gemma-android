package app.gemma.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first milestone, driven through the real screens on a real Android
 * system: set up, connect, read history, send, watch the reply arrive, stop a
 * long reply, and find everything still there after the app is restarted.
 * Talks to tools/fake_gemma.py on the host machine (10.0.2.2 from the emulator).
 */
@RunWith(AndroidJUnit4::class)
class FirstRunTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shown(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, substring: Boolean = false, seconds: Int = 30) =
        rule.waitUntil(seconds * 1000L) { shown(text, substring) }

    @Test
    fun setUpConnectChatAndStop() {
        // First launch: nothing is configured, so the app opens on Settings.
        waitFor("Settings")
        rule.onNodeWithTag("address").performTextReplacement("10.0.2.2:7862")

        // A wrong key is reported as a key problem, not as a network problem.
        rule.onNodeWithTag("key").performTextReplacement("not-the-right-key-0000")
        rule.onNodeWithText("Test connection").performClick()
        waitFor("Vision needs a valid access key.", substring = true)

        rule.onNodeWithTag("key").performTextReplacement("emulator-key-0123456789")
        rule.onNodeWithText("Test connection").performClick()
        waitFor("Connected to Gemma on Vision.", substring = true)
        rule.onNodeWithText("Save").performClick()

        // The conversation screen, with the server's existing history.
        waitFor("● Connected")
        waitFor("Earlier reply from Gemma.")
        assertTrue(shown("Earlier message from Glen"))

        // Send a message and receive the reply.
        rule.onNodeWithTag("draft").performTextInput("Hello Gem.")
        rule.onNodeWithText("Send").performClick()
        waitFor("Fake Gemma heard you: Hello Gem.")
        waitFor("Send")

        // A long reply appears while it is still being written, and Stop really stops it.
        rule.onNodeWithTag("draft").performTextInput("Tell me a long story")
        rule.onNodeWithText("Send").performClick()
        waitFor("Stop")
        waitFor("word word word", substring = true)
        assertTrue("text is visible before the reply has finished", shown("Stop"))
        rule.onNodeWithText("Stop").performClick()
        waitFor("Send", seconds = 30)
        assertTrue("the reply was cut short on the server", !shown("THE END", substring = true))
        assertTrue(shown("word word word", substring = true))

        // Restart the app: it goes straight to the conversation and shows the server's record of it.
        rule.activityRule.scenario.recreate()
        waitFor("● Connected")
        waitFor("Fake Gemma heard you: Hello Gem.")
        assertTrue(shown("Tell me a long story"))
        assertTrue(!shown("THE END", substring = true))
    }
}

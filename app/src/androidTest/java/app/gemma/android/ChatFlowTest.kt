package app.gemma.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The conversation, driven through the real screens against the real server (tools/ci_server.py).
 * Runs after PersistenceTest, so the app is already set up.
 */
@RunWith(AndroidJUnit4::class)
class ChatFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shown(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, substring: Boolean = false, seconds: Int = 40) =
        rule.waitUntil(seconds * 1000L) { shown(text, substring) }

    private fun tagged(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun waitTag(tag: String, seconds: Int = 40) = rule.waitUntil(seconds * 1000L) { tagged(tag) > 0 }

    private fun say(text: String) {
        rule.onNodeWithTag("draft").performTextInput(text)
        rule.onNodeWithText("Send").performClick()
    }

    private fun idle(seconds: Int = 60) = rule.waitUntil(seconds * 1000L) { shown("Send") }

    @Test
    fun chatWithCardsTimesAndScrolling() {
        waitFor("● Connected", seconds = 60)

        say("Hello Gem.")
        waitFor("Fake Gemma heard you: Hello Gem.")
        idle()
        assertTrue("my message has a relative time", tagged("time-mine") > 0)
        assertTrue("my latest message says Just now",
            rule.onAllNodesWithTag("time-mine", useUnmergedTree = true).fetchSemanticsNodes().last().config[SemanticsProperties.Text].joinToString() == "Just now")
        assertTrue("her reply has one too", tagged("time-hers") > 0)

        say("Write me a player controller")
        waitFor("player.gd", substring = true)
        idle()
        assertTrue("the code block has its copy button", tagged("copy-code") > 0)
        Shots.take("chat-1-code-block")

        say("Make me a utility file")
        waitTag("file-card")
        idle()
        assertTrue(shown("utility.py"))

        say("Please commit that")
        waitTag("approval-card")
        idle()
        Shots.take("chat-2-approval")
        rule.onNodeWithText("Allow once").performClick()
        waitFor("Done")

        say("Generate an image of a lighthouse")
        waitTag("job-card")
        // The percentage on the card is ComfyUI's own step count, read from Vision.
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("job-percent", useUnmergedTree = true).fetchSemanticsNodes()
                .any { n -> n.config[SemanticsProperties.Text].joinToString().endsWith("%") }
        }
        Shots.take("chat-3-image-progress")
        waitTag("image-card", seconds = 60)
        Shots.take("chat-4-image-done")

        // A long reply while scrolled up: nothing pulls the list down, and the arrow appears.
        say("Tell me a long story")
        waitFor("word word", substring = true)
        rule.onNodeWithTag("messages").performTouchInput { swipeDown() }
        rule.onNodeWithTag("messages").performTouchInput { swipeDown() }
        waitTag("jump", seconds = 10)
        Shots.take("chat-5-scrolled-up")
        rule.onNodeWithText("Stop").performClick()
        idle()
        rule.onNodeWithTag("jump").performClick()
        rule.waitUntil(10_000) { tagged("jump") == 0 }

        // Restart: everything comes back from the server.
        rule.activityRule.scenario.recreate()
        waitFor("● Connected", seconds = 60)
        waitTag("file-card")
        assertTrue("approval outcome kept", shown("Done"))
        Shots.take("chat-6-after-restart")
    }
}

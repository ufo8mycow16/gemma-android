package app.gemma.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vision's address and key must survive the app being closed. CI runs these as separate
 * instrumentation runs with `am force-stop` between them, so the second really is a new process
 * reading only what was written to disk:
 *   1. firstRunSavesWhenTheTestSucceeds  (fresh install: set up, Test connection, leave without pressing Save)
 *   2. settingsSurviveARealRestart        (new process: straight to the conversation, connected)
 * The server is the real server/app.py with a stand-in model (tools/ci_server.py), at 10.0.2.2:7862.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shown(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, substring: Boolean = false, seconds: Int = 40) =
        rule.waitUntil(seconds * 1000L) { shown(text, substring) }

    @Test
    fun firstRunSavesWhenTheTestSucceeds() {
        waitFor("Settings")
        rule.onNodeWithTag("address").performTextReplacement("10.0.2.2:7862")
        rule.onNodeWithTag("key").performTextReplacement("not-the-right-key-0000")
        rule.onNodeWithText("Test connection").performClick()
        waitFor("Vision needs a valid access key.", substring = true)
        assertFalse("a failed test saves nothing", Storage(rule.activity).serverAddress.isNotBlank())

        // Pasted keys often carry a space or line break; it must still work.
        rule.onNodeWithTag("key").performTextReplacement(" emulator-key-0123456789\n")
        rule.onNodeWithText("Test connection").performClick()
        waitFor("Saved.", substring = true)
        val stored = Storage(rule.activity)
        assertTrue("address on disk", stored.serverAddress == "http://10.0.2.2:7862")
        assertTrue("key on disk, readable", stored.accessKey == "emulator-key-0123456789")
        Shots.take("persist-1-tested-and-saved")
        // No Save pressed: the process is now killed by CI (am force-stop).
    }

    @Test
    fun settingsSurviveARealRestart() {
        // A new process: it must go straight to the conversation, connected, with no setup asked for.
        waitFor("● Connected", seconds = 60)
        assertFalse("not sent back to Settings", shown("Test connection"))
        Shots.take("persist-2-after-restart")
    }
}

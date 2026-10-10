package gemma.core

import org.junit.Assume
import org.junit.Test

/** Lets `./gradlew :core:test` run the same checks that can also be run directly. */
class GradleTests {
    @Test fun clientLogic() {
        println("core checks passed: " + CoreChecks.runAll())
    }

    /** Runs only when GEMMA_TEST_URL and GEMMA_TEST_KEY point at a running Gemma server. */
    @Test fun againstARealServer() {
        val url = System.getenv("GEMMA_TEST_URL")
        val key = System.getenv("GEMMA_TEST_KEY")
        Assume.assumeTrue("GEMMA_TEST_URL and GEMMA_TEST_KEY not set", !url.isNullOrBlank() && !key.isNullOrBlank())
        IntegrationChecks.run(url!!, key!!, System.getenv("GEMMA_TEST_LONG")).forEach { println("OK  $it") }
    }
}

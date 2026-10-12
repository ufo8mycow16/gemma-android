package app.gemma.android

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Screenshots of the real screens, saved where CI collects them (the app's own external files folder). */
object Shots {
    fun take(name: String) {
        try {
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.waitForIdleSync()
            Thread.sleep(400)
            val bmp = inst.uiAutomation.takeScreenshot() ?: return
            val dir = File(inst.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
        }
    }
}

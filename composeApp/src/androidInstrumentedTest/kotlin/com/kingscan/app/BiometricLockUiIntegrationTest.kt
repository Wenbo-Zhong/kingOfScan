package com.kingscan.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.kingscan.app.core.document.EncodedPage
import com.kingscan.app.core.document.FilterType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Checks real Compose windows and Activity lifecycle; grants are test fixtures, not biometric evidence. */
@RunWith(AndroidJUnit4::class)
class BiometricLockUiIntegrationTest {
    @Test
    fun appAndFolderLocksHideRetainedDialogsAndBlockSystemBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val root = File(context.filesDir, "biometric-ui-${System.currentTimeMillis()}")
        AppGraph.init(root.absolutePath)
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        bitmap.recycle()
        val folder = AppGraph.repository.createFolder("生物锁验收")
        val document = AppGraph.repository.create("仅解锁后可见的文档", listOf(EncodedPage(bytes, 48, 64, FilterType.Original)))
        AppGraph.repository.organize(document.id, folder.id)
        instrumentation.runOnMainSync {
            AppGraph.configureFolderLock(folder.id, true)
            AppGraph.unlockFolder(folder.id)
        }
        fun foreground() {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
        }
        fun awaitText(text: String) =
            assertTrue("Missing UI text: $text", device.wait(Until.hasObject(By.text(text)), 10_000))
        try {
            foreground()
            awaitText(document.title)
            device.findObject(By.text(document.title)).click()
            awaitText("重命名")
            device.findObject(By.text("重命名")).click()
            assertNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000))
            device.pressHome()
            device.waitForIdle()
            foreground()
            awaitText("文件夹已锁定")
            assertFalse(device.hasObject(By.text(document.title)))
            assertFalse(device.hasObject(By.clazz("android.widget.EditText")))
            device.pressBack()
            awaitText("新建文件夹")
            assertFalse(device.hasObject(By.text(document.title)))

            instrumentation.runOnMainSync { AppGraph.configureDeviceLock(true) }
            device.pressHome()
            device.waitForIdle()
            foreground()
            awaitText("应用已锁定")
            device.pressBack()
            awaitText("应用已锁定")
            assertTrue(AppGraph.locked)
            assertFalse(device.hasObject(By.text("新建文件夹")))
            // A current authentication grant reveals the app, while the folder remains protected.
            instrumentation.runOnMainSync { AppGraph.unlock() }
            awaitText("新建文件夹")
            assertFalse(device.hasObject(By.text(document.title)))
        } finally {
            instrumentation.runOnMainSync {
                AppGraph.unlock()
                AppGraph.configureDeviceLock(false)
                AppGraph.configureFolderLock(folder.id, false)
            }
            AppGraph.repository.delete(document.id)
            AppGraph.repository.purge(document.id)
            AppGraph.repository.deleteFolder(folder.id)
            device.pressHome()
        }
    }
}

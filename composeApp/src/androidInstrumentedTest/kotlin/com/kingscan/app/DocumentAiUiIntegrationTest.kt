package com.kingscan.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real production UI, using only the five isolated documents installed by install_ui_fixtures.py. */
@RunWith(AndroidJUnit4::class)
class DocumentAiUiIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val output = File(context.filesDir, "ai-ui-verification").apply { mkdirs() }
    private val passes = mutableListOf<String>()
    private val models = listOf(
        Triple("docres", "智能高清", "SmartHd"),
        Triple("handwriting", "去黑色手写", "Handwriting"),
        Triple("fingers", "去手指", "Fingers"),
        Triple("watermark", "去水印", "Watermark"),
        Triple("esdnet", "去摩尔纹", "Demoire"),
    )

    private fun control(label: String): UiObject2? {
        var node = device.findObject(By.text(label)) ?: device.findObject(By.desc(label)) ?: return null
        while (!node.isClickable) node = node.parent ?: return node
        return node
    }

    private fun waitFor(label: String, enabled: Boolean? = null, timeout: Long = 600_000): UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = control(label)
            val processing = label == "保存" && device.hasObject(By.textStartsWith("正在处理"))
            if (node != null && !processing && (enabled == null || node.isEnabled == enabled)) return node
            SystemClock.sleep(250)
        }
        device.dumpWindowHierarchy(File(output, "failure-ui.xml"))
        capture("failure")
        error("Missing UI: $label, enabled=$enabled")
    }

    private fun tap(label: String) { waitFor(label, true).click() }
    private fun capture(name: String) { assertTrue(device.takeScreenshot(File(output, "$name.png"))) }
    private fun pass(message: String) {
        passes += "PASS $message"
        File(output, "progress.txt").writeText(passes.joinToString("\n"))
        println("KMP_DOCUMENT_AI_UI ${passes.last()}")
    }
    private fun metadata(name: String) = JSONObject(
        File(context.filesDir, "kingscan/documents/localaiverification$name/meta.json").readText()
    )
    private fun page(name: String) = metadata(name).getJSONArray("pages").getJSONObject(0)
    private fun bytes(name: String, file: String) =
        File(context.filesDir, "kingscan/documents/localaiverification$name/pages/$file").readBytes()

    private fun action(label: String, family: List<String>) {
        repeat(8) {
            val node = control(label)
            if (node != null && node.isEnabled && node.visibleBounds.width() > 70) {
                node.click()
                return
            }
            val visible = family.firstNotNullOfOrNull { control(it)?.visibleBounds?.takeIf { b -> b.width() > 70 } }
                ?: error("Missing action row for $label")
            device.swipe(device.displayWidth - 80, visible.centerY(), 80, visible.centerY(), 20)
        }
        error("Offscreen action missing: $label")
    }

    private fun openEditor(name: String) {
        repeat(4) {
            if (control("更多") != null) return@repeat
            device.pressBack()
            SystemClock.sleep(300)
        }
        waitFor("更多", timeout = 60_000)
        var opened = false
        repeat(12) {
            if (!opened) {
                val card = control("AI-$name")
                if (card != null && card.visibleBounds.height() > 45) {
                    card.click()
                    opened = true
                } else device.swipe(device.displayWidth / 3, device.displayHeight * 3 / 4,
                    device.displayWidth / 3, device.displayHeight / 3, 30)
            }
        }
        check(opened) { "Missing document card: AI-$name" }
        waitFor("重命名", timeout = 60_000)
        action("擦除与修复", listOf("页面管理", "拼版与拼接", "识别文字", "批注签名", "重新编辑"))
        waitFor("保存", true)
    }

    @Test fun modelButtonsCancelUndoCompareSaveAndReopen() {
        // Animated progress need not become idle before reading the current accessibility tree.
        val configuration = Configurator.getInstance()
        val idleTimeout = configuration.waitForIdleTimeout
        val selectorTimeout = configuration.waitForSelectorTimeout
        configuration.setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0)
        try {
            device.wakeUp()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("更多", timeout = 60_000)
            for ((name, label, kind) in models) {
                println("KMP_DOCUMENT_AI_UI START $name")
                val originalPage = page(name)
                val originalBytes = bytes(name, originalPage.getString("originalFileName"))
                val sourceBytes = bytes(name, originalPage.getString("fileName"))
                openEditor(name)
                if (name == "docres") {
                    tap(label)
                    tap("取消本次处理")
                    waitFor("保存", true)
                    capture("cancelled")
                    tap("保存")
                    waitFor("重命名", timeout = 60_000)
                    assertEquals(0, page(name).getJSONObject("edit").optJSONArray("retouches")?.length() ?: 0)
                    pass("actual in-flight cancellation saves no model edit")
                    openEditor(name)
                }
                action(label, models.map { it.second })
                capture("$name-processing")
                waitFor("保存", true)
                capture("$name-result")
                tap("对比原图")
                waitFor("查看结果", timeout = 30_000)
                capture("$name-original")
                tap("查看结果")
                if (name == "docres") {
                    tap("撤销")
                    waitFor("保存", true)
                    capture("undo")
                    tap("重做")
                    waitFor("保存", true)
                    capture("redo")
                    pass("model undo and redo produce ready preview")
                }
                tap("保存")
                waitFor("重命名", timeout = 60_000)
                val saved = page(name)
                val edits = saved.getJSONObject("edit").getJSONArray("retouches")
                assertEquals(1, edits.length())
                val edit = edits.getJSONObject(0)
                assertEquals(kind, edit.getString("kind"))
                assertEquals("kingscan-document-ai-20261123-v1", edit.getString("modelVersion"))
                assertEquals(20261123, edit.getInt("seed"))
                assertEquals(0, edit.getInt("orientation"))
                assertEquals(.5, edit.getDouble("strength"), 0.0)
                assertArrayEquals(originalBytes, bytes(name, saved.getString("originalFileName")))
                val result = bytes(name, saved.getString("fileName"))
                assertFalse(sourceBytes.contentEquals(result))
                assertEquals(originalPage.getInt("width"), saved.getInt("width"))
                assertEquals(originalPage.getInt("height"), saved.getInt("height"))
                File(output, "$name-saved.jpg").writeBytes(result)
                File(output, "$name-meta.json").writeText(metadata(name).toString(2))
                openEditor(name)
                waitFor("保存", true)
                capture("$name-reopened")
                assertEquals(edits.toString(), page(name).getJSONObject("edit").getJSONArray("retouches").toString())
                assertArrayEquals(originalBytes, bytes(name, saved.getString("originalFileName")))
                pass("$name button, compare, save, explicit parameters, original bytes and reopened preview")
            }
            File(output, "result.txt").writeText(passes.joinToString("\n"))
        } finally {
            configuration.setWaitForIdleTimeout(idleTimeout).setWaitForSelectorTimeout(selectorTimeout)
        }
    }
}

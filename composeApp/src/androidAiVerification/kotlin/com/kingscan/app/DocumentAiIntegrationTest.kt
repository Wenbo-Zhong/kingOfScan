package com.kingscan.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kingscan.app.imaging.AndroidDocumentModels
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentAiIntegrationTest {
    @Test fun actualModelsAndOriginalPreservation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppGraph.init(File(context.filesDir, "kingscan").absolutePath)
        AndroidDocumentModels.context = context.applicationContext
        verifyDocumentAi(File(context.filesDir, "ai-verification").absolutePath)
        Unit
    }
}

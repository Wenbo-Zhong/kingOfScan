package com.kingscan.app

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kingscan.app.core.document.ImportFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.io.File
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = File(filesDir, "kingscan").apply { mkdirs() }.absolutePath
        AppGraph.init(root, com.kingscan.app.core.platform.supportsSystemBiometrics(this))
        com.kingscan.app.core.imaging.AndroidDocumentModels.context = applicationContext
        PDFBoxResourceLoader.init(applicationContext)
        com.kingscan.app.core.pdf.AndroidPdfResources.context = applicationContext
        setContent { App() }
        if (savedInstanceState == null) importSharedFiles(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importSharedFiles(intent)
    }

    @Suppress("DEPRECATION")
    private fun importSharedFiles(incoming: Intent) {
        val streams: List<Uri> = when (incoming.action) {
            Intent.ACTION_SEND -> listOfNotNull(incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return
        }.ifEmpty { (0 until (incoming.clipData?.itemCount ?: 0)).mapNotNull { incoming.clipData?.getItemAt(it)?.uri } }
        lifecycleScope.launch {
            val staged = mutableListOf<ImportFile>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    require(streams.isNotEmpty() && streams.size <= 100) { "请分享 1–100 个图片或 PDF 文件" }
                    streams.forEach { uri ->
                        require(uri.scheme == "content") { "不支持这个文件地址" }
                        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: "外部文件"
                        val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                require(output.size() + read <= 128 * 1024 * 1024) { "$name 超过 128 MB" }
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        } ?: error("无法读取 $name")
                        val pdf = bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".encodeToByteArray())
                        staged += AppGraph.repository.stageImport(bytes, name, pdf)
                    }
                    staged.toList()
                }
            }
            result.fold({ AppGraph.externalImports.send(ExternalImport(files = it)) }, {
                staged.forEach(AppGraph.repository::finishImport)
                AppGraph.externalImports.send(ExternalImport(error = it.message ?: "外部导入失败"))
            })
        }
    }
}

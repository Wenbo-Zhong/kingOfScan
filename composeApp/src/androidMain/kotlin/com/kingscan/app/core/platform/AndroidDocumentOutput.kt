package com.kingscan.app.core.platform

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors

@Composable
actual fun rememberGallerySaver(onResult: (String) -> Unit): (List<String>) -> Unit {
    val context = LocalContext.current
    val report by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<String>>(emptyList()) }
    fun save(paths: List<String>) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { saveImagesToGallery(context, paths, com.kingscan.app.AppGraph.repository::requirePathAccess) }
            }
            result.fold({ report("已保存 $it 张到相册") }, { report(it.message ?: "保存相册失败") })
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val paths = pending; pending = emptyList()
        if (granted) save(paths) else report("需要相册写入权限才能保存")
    }
    return { paths ->
        if (paths.isNotEmpty()) {
            if (Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pending = paths.toList()
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else save(paths.toList())
        }
    }
}

/** Publish only after the whole image has been copied; discard failed entries. */
internal fun saveImagesToGallery(context: Context, paths: List<String>, checkAccess: (String) -> Unit = {}): Int {
    var saved = 0
    for (path in paths) {
        checkAccess(path)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "KingOfScan_${System.currentTimeMillis()}_${saved + 1}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KingOfScan")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("相册无法创建文件（已保存 $saved 张）")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                File(path).inputStream().use { it.copyTo(output) }
            } ?: error("相册文件无法写入")
            checkAccess(path)
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            saved++
        } catch (failure: Exception) {
            context.contentResolver.delete(uri, null, null)
            error("相册保存中断，已保存 $saved 张：${failure.message.orEmpty()}")
        }
    }
    return saved
}

@Composable
actual fun rememberPrintLauncher(onResult: (String) -> Unit): (String, String) -> Unit {
    val context = LocalContext.current
    val report by rememberUpdatedState(onResult)
    return { path, title ->
        runCatching {
            val output = com.kingscan.app.AppGraph.repository.prepareOutput(path)
            val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            manager.print(title, LocalPdfPrintAdapter(File(output), title), PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        }.onFailure { report(it.message ?: "无法打开系统打印") }
    }
}

internal class LocalPdfPrintAdapter(private val source: File, private val title: String) : PrintDocumentAdapter() {
    private var pageCount = 0
    private val worker = Executors.newSingleThreadExecutor()

    override fun onFinish() { worker.shutdown() }

    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
        worker.execute { runCatching {
            pageCount = printPageCount(source)
            PrintDocumentInfo.Builder(title).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pageCount).build()
        }.fold({
            if (cancellationSignal.isCanceled) callback.onLayoutCancelled()
            else callback.onLayoutFinished(it, oldAttributes != newAttributes)
        }, { callback.onLayoutFailed(it.message) }) }
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        if (cancellationSignal.isCanceled) { callback.onWriteCancelled(); return }
        worker.execute { runCatching {
            FileOutputStream(destination.fileDescriptor).use {
                writePrintPages(source, pages, it, cancellationSignal)
            }
        }.fold({
            if (cancellationSignal.isCanceled) callback.onWriteCancelled() else callback.onWriteFinished(pages)
        }, {
            if (cancellationSignal.isCanceled) callback.onWriteCancelled() else callback.onWriteFailed(it.message)
        }) }
    }
}

internal fun printPageCount(source: File): Int =
    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }

internal fun writePrintPages(source: File, pages: Array<out PageRange>, destination: OutputStream, cancellation: CancellationSignal) {
    cancellation.throwIfCanceled()
    PDDocument.load(source).use { input ->
        PDDocument().use { output ->
            val selected = (0 until input.numberOfPages).filter { index -> pages.any { index in it.start..it.end } }
            require(selected.isNotEmpty()) { "没有选中打印页面" }
            selected.forEach { index ->
                cancellation.throwIfCanceled()
                output.importPage(input.getPage(index))
            }
            output.save(destination)
        }
    }
}

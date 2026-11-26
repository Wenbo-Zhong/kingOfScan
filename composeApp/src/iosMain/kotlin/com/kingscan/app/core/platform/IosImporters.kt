@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kingscan.app.core.platform

import com.kingscan.app.core.imaging.toByteArray
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.kingscan.app.core.document.ImportFile
import com.kingscan.app.AppGraph
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerConfigurationSelectionOrdered
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfURL
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberImagesImporter(
    onImages: (List<ImportFile>) -> Unit,
    onError: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
): () -> Unit {
    val latest = rememberUpdatedState(onImages)
    val latestError = rememberUpdatedState(onError)
    val latestStart = rememberUpdatedState(onStart)
    val latestCancel = rememberUpdatedState(onCancel)
    val delegate = remember {
        GalleryDelegate(
            onImages = { latest.value.invoke(it) },
            onError = { latestError.value.invoke(it) },
            onCancel = { latestCancel.value.invoke() },
        )
    }
    return { latestStart.value.invoke(); delegate.present() }
}

@Composable
actual fun rememberFileImporter(
    onFiles: (List<ImportFile>) -> Unit,
    onError: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
): () -> Unit {
    val latest = rememberUpdatedState(onFiles)
    val latestError = rememberUpdatedState(onError)
    val latestCancel = rememberUpdatedState(onCancel)
    val delegate = remember { FileDelegate({ latest.value(it) }, { latestError.value(it) }, { latestCancel.value() }) }
    return { onStart(); delegate.present() }
}

private class GalleryDelegate(
    private val onImages: (List<ImportFile>) -> Unit,
    private val onError: (String) -> Unit,
    private val onCancel: () -> Unit,
) : platform.darwin.NSObject(), PHPickerViewControllerDelegateProtocol {
    fun present() {
        val presenter = topViewController() ?: run { onError("无法打开相册"); return }
        val configuration = PHPickerConfiguration().apply {
            selectionLimit = 50
            selection = PHPickerConfigurationSelectionOrdered
            filter = PHPickerFilter.imagesFilter
        }
        val controller = PHPickerViewController(configuration)
        controller.delegate = this
        retainedGallery = this
        presenter.presentViewController(controller, true, null)
    }

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, null)
        val results = didFinishPicking.mapNotNull { it as? PHPickerResult }
        if (results.isEmpty()) { retainedGallery = null; onCancel(); return }
        val staged = mutableListOf<ImportFile>()
        fun load(index: Int) {
            if (index == results.size) {
                dispatch_async(dispatch_get_main_queue()) { retainedGallery = null; onImages(staged) }
                return
            }
            val provider = results[index].itemProvider
            provider.loadDataRepresentationForTypeIdentifier("public.image") { data, error ->
                val result = runCatching {
                    require(error == null && data != null) { "无法读取第 ${index + 1} 张图片" }
                    AppGraph.repository.stageImport(data!!.toByteArray(), provider.suggestedName ?: "相册 ${index + 1}")
                }
                result.fold({
                    staged += it
                    load(index + 1)
                }, {
                    staged.forEach(AppGraph.repository::finishImport)
                    dispatch_async(dispatch_get_main_queue()) { retainedGallery = null; onError(it.message ?: "无法读取图片") }
                })
            }
        }
        load(0)
    }
}

private var retainedGallery: GalleryDelegate? = null

private class FileDelegate(
    private val onFiles: (List<ImportFile>) -> Unit,
    private val onError: (String) -> Unit,
    private val onCancel: () -> Unit,
) : platform.darwin.NSObject(), UIDocumentPickerDelegateProtocol {
    fun present() {
        val presenter = topViewController() ?: run { onError("无法打开文件选择器"); return }
        val controller = UIDocumentPickerViewController(
            documentTypes = listOf("com.adobe.pdf", "public.image"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        controller.allowsMultipleSelection = true
        controller.delegate = this
        retainedFiles = this
        presenter.presentViewController(controller, true, null)
    }

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val urls = didPickDocumentsAtURLs.mapNotNull { it as? NSURL }
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val staged = mutableListOf<ImportFile>()
            val result = runCatching {
                urls.forEach { url ->
                    val bytes = NSData.dataWithContentsOfURL(url)?.toByteArray() ?: error("无法读取文件")
                    val pdf = bytes.take(5).toByteArray().contentEquals("%PDF-".encodeToByteArray())
                    staged += AppGraph.repository.stageImport(bytes, url.lastPathComponent ?: "导入文件", pdf)
                }
                staged.toList()
            }
            dispatch_async(dispatch_get_main_queue()) {
                retainedFiles = null
                result.fold({ if (it.isEmpty()) onCancel() else onFiles(it) }, {
                    staged.forEach(AppGraph.repository::finishImport)
                    onError(it.message ?: "导入失败")
                })
            }
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        retainedFiles = null
        onCancel()
    }
}

private var retainedFiles: FileDelegate? = null

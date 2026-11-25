@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kingscan.app.ui

import com.kingscan.app.imaging.toByteArray

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import com.kingscan.app.ui.ScanButton as Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.unit.dp
import com.kingscan.app.imaging.CaptureFrame
import com.kingscan.app.imaging.CapturePreviewAnalyzer
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.imaging.ScanLimits
import com.kingscan.app.imaging.argb
import com.kingscan.app.model.ImportFile
import com.kingscan.app.AppGraph
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.QuartzCore.CACurrentMediaTime
import platform.darwin.dispatch_queue_create
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.fileDataRepresentation
import platform.AVFoundation.hasTorch
import platform.AVFoundation.requestAccessForMediaType
import platform.AVFoundation.torchMode
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

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
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun ScannerCamera(
    torchEnabled: Boolean,
    modifier: Modifier,
    onBind: (capture: () -> Unit) -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onError: (String) -> Unit,
    onDetection: (CaptureFrame) -> Unit,
) {
    val latestPhoto = rememberUpdatedState(onPhoto)
    val latestBind = rememberUpdatedState(onBind)
    val latestError = rememberUpdatedState(onError)
    val latestDetection = rememberUpdatedState(onDetection)
    var denied by remember { mutableStateOf(false) }
    val cameraView = remember {
        IosCameraView(
            onPhoto = { latestPhoto.value.invoke(it) },
            onDenied = { denied = true },
            onFailure = { latestError.value.invoke(it) },
            onDetection = { latestDetection.value.invoke(it) },
        )
    }
    DisposableEffect(cameraView) {
        latestBind.value.invoke { cameraView.capture() }
        cameraView.start()
        onDispose { cameraView.stop() }
    }
    androidx.compose.runtime.LaunchedEffect(torchEnabled) {
        cameraView.setTorch(torchEnabled)
    }
    if (denied) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Button(onClick = { cameraView.start() }, modifier = Modifier.padding(24.dp)) {
                Text("需要相机权限，请在系统设置中允许")
            }
        }
    } else {
        UIKitView(
            factory = { cameraView },
            modifier = modifier,
            update = { view -> view.setTorch(torchEnabled) },
        )
    }
}

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

@Composable
actual fun rememberShareLauncher(): (String, String, String) -> Unit {
    return remember {
        { path, _, _ ->
            val presenter = topViewController() ?: return@remember
            val controller = UIActivityViewController(
                activityItems = listOf(NSURL.fileURLWithPath(path)),
                applicationActivities = null,
            )
            controller.popoverPresentationController?.sourceView = presenter.view
            controller.popoverPresentationController?.sourceRect = presenter.view.bounds
            presenter.presentViewController(controller, true, null)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosCameraView(
    private val onPhoto: (ByteArray) -> Unit,
    private val onDenied: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val onDetection: (CaptureFrame) -> Unit,
) : UIView(frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 1.0, 1.0)) {
    private val session = AVCaptureSession()
    private val photoOutput = AVCapturePhotoOutput()
    private val videoOutput = AVCaptureVideoDataOutput()
    private val sessionQueue = dispatch_queue_create("com.kingscan.camera.session", null)
    private val frameQueue = dispatch_queue_create("com.kingscan.camera.frames", null)
    private val videoDelegate = VideoDelegate { frame ->
        dispatch_async(dispatch_get_main_queue()) { if (active) onDetection(frame) }
    }
    private var previewLayer: AVCaptureVideoPreviewLayer? = null
    private var photoDelegate: PhotoDelegate? = null
    private var configured = false
    private var active = false

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
    }

    fun start() {
        active = true
        val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        when (status) {
            AVAuthorizationStatusAuthorized -> configureAndRun()
            AVAuthorizationStatusNotDetermined -> {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (active) { if (granted) configureAndRun() else onDenied() }
                    }
                }
            }
            else -> onDenied()
        }
    }

    fun stop() {
        active = false
        dispatch_async(sessionQueue) {
            if (session.running) session.stopRunning()
        }
    }

    fun capture() {
        if (!configured) {
            onFailure("相机还没准备好")
            return
        }
        val delegate = PhotoDelegate({ if (active) onPhoto(it) }, { if (active) onFailure(it) })
        photoDelegate = delegate
        photoOutput.capturePhotoWithSettings(AVCapturePhotoSettings.photoSettings(), delegate)
    }

    fun setTorch(enabled: Boolean) {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return
        if (!device.hasTorch) return
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!device.lockForConfiguration(error.ptr)) return@memScoped
            device.torchMode = if (enabled) AVCaptureTorchModeOn else AVCaptureTorchModeOff
            device.unlockForConfiguration()
        }
    }

    private fun configureAndRun() {
        if (configured) {
            dispatch_async(sessionQueue) { if (active && !session.running) session.startRunning() }
            return
        }
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        if (device == null) {
            onFailure("这台设备没有可用的相机")
            return
        }
        val input = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVCaptureDeviceInput.deviceInputWithDevice(device, error.ptr)
        }
        if (input == null) {
            onFailure("无法打开相机")
            return
        }
        session.beginConfiguration()
        session.sessionPreset = AVCaptureSessionPresetPhoto
        if (!session.canAddInput(input) || !session.canAddOutput(photoOutput)) {
            session.commitConfiguration()
            onFailure("无法配置相机")
            return
        }
        session.addInput(input)
        session.addOutput(photoOutput)
        videoOutput.videoSettings = mapOf(kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA)
        videoOutput.alwaysDiscardsLateVideoFrames = true
        if (session.canAddOutput(videoOutput)) {
            session.addOutput(videoOutput)
            videoOutput.setSampleBufferDelegate(videoDelegate, frameQueue)
        } else onFailure("相机不支持实时检测，可手动拍摄")
        listOfNotNull(photoOutput.connectionWithMediaType(AVMediaTypeVideo), videoOutput.connectionWithMediaType(AVMediaTypeVideo)).forEach {
            if (it.supportsVideoOrientation) it.videoOrientation = AVCaptureVideoOrientationPortrait
        }
        session.commitConfiguration()
        val layer = AVCaptureVideoPreviewLayer(session = session)
        layer.videoGravity = AVLayerVideoGravityResizeAspect
        layer.connection?.let { if (it.supportsVideoOrientation) it.videoOrientation = AVCaptureVideoOrientationPortrait }
        layer.frame = bounds
        this.layer.addSublayer(layer)
        previewLayer = layer
        configured = true
        dispatch_async(sessionQueue) {
            if (active) session.startRunning()
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
    }
}

private class VideoDelegate(private val onFrame: (CaptureFrame) -> Unit) :
    platform.darwin.NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    private var lastAt = 0L
    private val analyzer = CapturePreviewAnalyzer()

    override fun captureOutput(output: AVCaptureOutput, didOutputSampleBuffer: CMSampleBufferRef?, fromConnection: AVCaptureConnection) {
        val now = (CACurrentMediaTime() * 1000).toLong()
        if (now - lastAt < 120L) return
        lastAt = now
        val buffer = didOutputSampleBuffer?.let { CMSampleBufferGetImageBuffer(it) } ?: return
        if (CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly) != 0) return
        val image = try {
            val base = CVPixelBufferGetBaseAddress(buffer)?.reinterpret<UByteVar>() ?: return
            val sourceWidth = CVPixelBufferGetWidth(buffer).toInt()
            val sourceHeight = CVPixelBufferGetHeight(buffer).toInt()
            val stride = CVPixelBufferGetBytesPerRow(buffer).toInt()
            val scale = minOf(1f, ScanLimits.DETECT_EDGE.toFloat() / maxOf(sourceWidth, sourceHeight))
            val width = (sourceWidth * scale).toInt().coerceAtLeast(4)
            val height = (sourceHeight * scale).toInt().coerceAtLeast(4)
            val pixels = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val offset = (y * sourceHeight / height) * stride + (x * sourceWidth / width) * 4
                pixels[y * width + x] = argb(255, base[offset + 2].toInt(), base[offset + 1].toInt(), base[offset].toInt())
            }
            RgbaImage(width, height, pixels)
        } finally { CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly) }
        runCatching { analyzer.analyze(image, now) }.onSuccess(onFrame)
    }
}

private class PhotoDelegate(
    private val onPhoto: (ByteArray) -> Unit,
    private val onFailure: (String) -> Unit,
) : platform.darwin.NSObject(), AVCapturePhotoCaptureDelegateProtocol {
    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        if (error != null) {
            dispatch_async(dispatch_get_main_queue()) { onFailure("拍摄失败") }
            return
        }
        val data = didFinishProcessingPhoto.fileDataRepresentation() ?: run {
            dispatch_async(dispatch_get_main_queue()) { onFailure("没有拿到照片") }
            return
        }
        val bytes = data.toByteArray()
        dispatch_async(dispatch_get_main_queue()) {
            onPhoto(bytes)
        }
    }
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

    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
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

@OptIn(ExperimentalForeignApi::class)
internal fun topViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    val window = application.keyWindow
        ?: application.windows.mapNotNull { it as? platform.UIKit.UIWindow }.firstOrNull { it.isKeyWindow() }
    var controller = window?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}

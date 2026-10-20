@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kingscan.app.ui

import com.kingscan.app.imaging.toByteArray

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
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

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun ScannerCamera(
    torchEnabled: Boolean,
    modifier: Modifier,
    onBind: (capture: () -> Unit) -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onError: (String) -> Unit,
) {
    val latestPhoto = rememberUpdatedState(onPhoto)
    val latestBind = rememberUpdatedState(onBind)
    val latestError = rememberUpdatedState(onError)
    var denied by remember { mutableStateOf(false) }
    val cameraView = remember {
        IosCameraView(
            onPhoto = { latestPhoto.value.invoke(it) },
            onDenied = { denied = true },
            onFailure = { latestError.value.invoke(it) },
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
actual fun rememberImageImporter(
    onImage: (ByteArray) -> Unit,
    onError: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
): () -> Unit {
    val latest = rememberUpdatedState(onImage)
    val latestError = rememberUpdatedState(onError)
    val latestStart = rememberUpdatedState(onStart)
    val latestCancel = rememberUpdatedState(onCancel)
    val delegate = remember {
        GalleryDelegate(
            onImage = { latest.value.invoke(it) },
            onError = { latestError.value.invoke(it) },
            onCancel = { latestCancel.value.invoke() },
        )
    }
    return { latestStart.value.invoke(); delegate.present() }
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
) : UIView(frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 1.0, 1.0)) {
    private val session = AVCaptureSession()
    private val photoOutput = AVCapturePhotoOutput()
    private var previewLayer: AVCaptureVideoPreviewLayer? = null
    private var photoDelegate: PhotoDelegate? = null
    private var configured = false

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
    }

    fun start() {
        val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        when (status) {
            AVAuthorizationStatusAuthorized -> configureAndRun()
            AVAuthorizationStatusNotDetermined -> {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (granted) configureAndRun() else onDenied()
                    }
                }
            }
            else -> onDenied()
        }
    }

    fun stop() {
        if (session.running) {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                session.stopRunning()
            }
        }
    }

    fun capture() {
        if (!configured) {
            onFailure("相机还没准备好")
            return
        }
        val delegate = PhotoDelegate(onPhoto, onFailure)
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
            if (!session.running) session.startRunning()
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
        if (session.canAddInput(input)) session.addInput(input)
        if (session.canAddOutput(photoOutput)) session.addOutput(photoOutput)
        session.commitConfiguration()
        val layer = AVCaptureVideoPreviewLayer(session = session)
        layer.videoGravity = AVLayerVideoGravityResizeAspect
        layer.frame = bounds
        this.layer.addSublayer(layer)
        previewLayer = layer
        configured = true
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            session.startRunning()
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
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
    private val onImage: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onCancel: () -> Unit,
) : platform.darwin.NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    private var picker: UIImagePickerController? = null

    fun present() {
        val presenter = topViewController() ?: run { onError("无法打开相册"); return }
        val controller = UIImagePickerController()
        controller.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
        controller.delegate = this
        picker = controller
        retainedGallery = this
        presenter.presentViewController(controller, true, null)
    }

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        val data = image?.let { UIImageJPEGRepresentation(it, 0.92) }
        if (data != null) onImage(data.toByteArray()) else onError("无法读取所选图片")
        picker.dismissViewControllerAnimated(true, null)
        retainedGallery = null
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, null)
        retainedGallery = null
        onCancel()
    }
}

private var retainedGallery: GalleryDelegate? = null

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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kingscan.app.core.codes

import com.kingscan.app.core.imaging.*
import kotlinx.cinterop.*
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.*
import platform.Foundation.*
import platform.UIKit.*
import platform.Vision.*

actual fun createBarcodeCodec(): BarcodeCodec = IosBarcodeCodec()

private class IosBarcodeCodec : BarcodeCodec {
    override fun generate(text: String): RgbaImage {
        validateQrText(text)
        val filter = CIFilter.filterWithName("CIQRCodeGenerator",
            withInputParameters = mapOf("inputMessage" to text.encodeToByteArray().toNSData(),
                "inputCorrectionLevel" to "M")) ?: error("无法生成二维码")
        val image = filter.outputImage ?: error("无法生成二维码")
        val cgImage = CIContext.contextWithOptions(null).createCGImage(image, image.extent) ?: error("无法生成二维码")
        val modules = try {
            val bytes = UIImagePNGRepresentation(UIImage.imageWithCGImage(cgImage))!!.toByteArray()
            IosImageCodec().decode(bytes, 512)
        } finally { CGImageRelease(cgImage) }
        return renderQr(modules)
    }

    override fun decode(image: RgbaImage): List<BarcodeValue> = memScoped {
        val request = VNDetectBarcodesRequest()
        request.revision = VNDetectBarcodesRequestRevision1
        val handler = VNImageRequestHandler(data = IosImageCodec().encodeJpeg(image, 100).toNSData(), options = emptyMap<Any?, Any>())
        val error = alloc<ObjCObjectVar<NSError?>>()
        check(handler.performRequests(listOf(request), error.ptr)) { error.value?.localizedDescription ?: "条码识别失败" }
        request.results.orEmpty().filterIsInstance<VNBarcodeObservation>().mapNotNull {
            it.payloadStringValue?.let { text -> BarcodeValue(text, it.symbology ?: "条码") }
        }.distinct()
    }
}

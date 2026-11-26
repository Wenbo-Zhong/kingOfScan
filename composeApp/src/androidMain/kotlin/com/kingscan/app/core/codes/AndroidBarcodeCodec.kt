package com.kingscan.app.core.codes

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.kingscan.app.core.imaging.RgbaImage

actual fun createBarcodeCodec(): BarcodeCodec = AndroidBarcodeCodec()

private class AndroidBarcodeCodec : BarcodeCodec {
    override fun generate(text: String): RgbaImage {
        validateQrText(text)
        val matrix = Encoder.encode(text, ErrorCorrectionLevel.M,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
        return renderQr(RgbaImage(matrix.width, matrix.height,
            IntArray(matrix.width * matrix.height) { if (matrix[it % matrix.width, it / matrix.width].toInt() == 1) 0xFF000000.toInt() else -1 }))
    }

    override fun decode(image: RgbaImage): List<BarcodeValue> {
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, image.pixels)))
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        return try {
            GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(bitmap, hints)
                .map { BarcodeValue(it.text, it.barcodeFormat.name) }.distinct()
        } catch (_: NotFoundException) { emptyList() }
    }
}

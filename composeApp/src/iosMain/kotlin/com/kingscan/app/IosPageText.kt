@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kingscan.app.imaging

import com.kingscan.app.model.PageMark
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGContextRotateCTM
import platform.Foundation.NSString
import platform.Foundation.create
import platform.UIKit.*

actual fun drawPageText(source: RgbaImage, marks: List<PageMark>): RgbaImage {
    if (marks.isEmpty()) return source
    val codec = IosImageCodec()
    val image = UIImage.imageWithData(codec.encodeJpeg(source, 100).toNSData()) ?: error("无法读取页面")
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(source.width.toDouble(), source.height.toDouble()), true, 1.0)
    try {
        image.drawInRect(CGRectMake(0.0, 0.0, source.width.toDouble(), source.height.toDouble()))
        marks.forEach { mark ->
            val point = mark.points.firstOrNull() ?: return@forEach
            val color = mark.color.toInt()
            val context = UIGraphicsGetCurrentContext()
            CGContextSaveGState(context)
            CGContextTranslateCTM(context, point.x * source.width.toDouble(), point.y * source.height.toDouble())
            CGContextRotateCTM(context, mark.quarterTurns.mod(4) * kotlin.math.PI / 2)
            NSString.create(string = mark.text).drawAtPoint(CGPointMake(0.0, 0.0),
                withAttributes = mapOf(
                    NSFontAttributeName to UIFont.systemFontOfSize(mark.strokeWidth * minOf(source.width, source.height).toDouble()),
                    NSForegroundColorAttributeName to UIColor.colorWithRed(color.red() / 255.0, color.green() / 255.0,
                        color.blue() / 255.0, mark.opacity.toDouble()),
                ))
            CGContextRestoreGState(context)
        }
        val result = UIGraphicsGetImageFromCurrentImageContext() ?: error("无法绘制水印")
        return codec.decode(UIImageJPEGRepresentation(result, 1.0)?.toByteArray() ?: error("无法保存水印"), maxOf(source.width, source.height))
    } finally { UIGraphicsEndImageContext() }
}

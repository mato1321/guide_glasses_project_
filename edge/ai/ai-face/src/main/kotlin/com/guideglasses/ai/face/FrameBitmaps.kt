package com.guideglasses.ai.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.guideglasses.core.domain.face.DetectedFace
import com.guideglasses.core.domain.glasses.CameraFrame
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal fun CameraFrame.toBitmapOrNull(): Bitmap? = when (format) {
    CameraFrame.Format.JPEG ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    CameraFrame.Format.RGBA_8888 ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        }

    CameraFrame.Format.NV21 -> null
}

/**
 * 從整張畫面裁出臉部區域。
 *
 * 邊界外擴一圈：特徵模型看的不只是五官，還包含臉型輪廓與髮際線。
 * 只裁 ML Kit 給的框會切掉下巴與額頭，特徵品質會明顯下降。
 */
internal fun Bitmap.cropFace(face: DetectedFace, marginRatio: Float = FACE_CROP_MARGIN): Bitmap? {
    val cx = face.centerX * width
    val cy = (face.top + face.height / 2f) * height
    val halfW = face.width * width * (1f + marginRatio) / 2f
    val halfH = face.height * height * (1f + marginRatio) / 2f

    val left = (cx - halfW).roundToInt().coerceIn(0, width - 1)
    val top = (cy - halfH).roundToInt().coerceIn(0, height - 1)
    val right = (cx + halfW).roundToInt().coerceIn(left + 1, width)
    val bottom = (cy + halfH).roundToInt().coerceIn(top + 1, height)

    val cropWidth = right - left
    val cropHeight = bottom - top
    if (cropWidth <= 0 || cropHeight <= 0) return null

    return Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
}

/** 臉部裁切時向外多留 20%，避免切掉下巴與額頭。 */
internal const val FACE_CROP_MARGIN = 0.2f

package io.github.kichikuou.xsystem4

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Full-screen halftone dot overlay: a 12% white dot grid plus an offset
 * 14% neon pink dot grid. It is purely decorative and never consumes touches.
 *
 * The pattern is rendered as a small tiled bitmap shader so drawing the
 * overlay costs a single drawRect() call.
 */
class HalftoneOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tile: Bitmap? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun buildTile(): Bitmap {
        val density = resources.displayMetrics.density
        val step = (16f * density).toInt().coerceAtLeast(2)
        val radius = 1.5f * density

        val bitmap = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)

        // Grid A: 12% white dots at every 16dp node. Drawing the four corners
        // makes the circles seamless once the tile is repeated.
        dot.color = Color.argb(31, 255, 255, 255)
        canvas.drawCircle(0f, 0f, radius, dot)
        canvas.drawCircle(step.toFloat(), 0f, radius, dot)
        canvas.drawCircle(0f, step.toFloat(), radius, dot)
        canvas.drawCircle(step.toFloat(), step.toFloat(), radius, dot)

        // Grid B: 14% neon pink dots, offset by half a step.
        dot.color = Color.argb(36, 255, 42, 133)
        canvas.drawCircle(step / 2f, step / 2f, radius, dot)

        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        if (paint.shader == null) {
            val bitmap = tile ?: buildTile().also { tile = it }
            paint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Drop the shader but do not recycle the tiny tile: letting the GC free it
        // avoids any chance of drawing a recycled bitmap during a view transition.
        paint.shader = null
        tile = null
    }
}

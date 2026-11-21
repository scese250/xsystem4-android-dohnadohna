package io.github.kichikuou.xsystem4

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.TextView

/**
 * TextView that reproduces the "DOHNA DOHNA ANDROID VER." header from the mockup:
 * bright pink fill (#FF2DB8), crisp thick black outer outline, black 3D drop shadow,
 * soft magenta outer glow, and a horizontal -3deg skew (keeping the baseline level).
 *
 * Every layer is rendered via `layout.draw(canvas)` using the TextView's own TextPaint
 * so `textAllCaps`, `letterSpacing`, and `gravity` align identically across stroke and fill.
 */
class StrokedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    init {
        // Reserve internal room for the 3dp stroke, 3.5dp 3D shadow, magenta glow, and -3deg skew.
        val horizontal = (14f * density + 0.5f).toInt()
        val vertical = (10f * density + 0.5f).toInt()
        setPadding(
            paddingLeft + horizontal,
            paddingTop + vertical,
            paddingRight + horizontal,
            paddingBottom + vertical
        )
    }

    /**
     * The mockup renders the title on a single horizontal line. On narrow landscape phones the
     * default 22sp can exceed the center column width, so shrink text size until it fits on
     * one line (down to 14sp) instead of wrapping mid-phrase.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (isInEditMode || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            return
        }
        val initialLayout = layout ?: return
        if (initialLayout.lineCount <= 1 || text.isEmpty()) {
            return
        }

        val minSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            14f,
            resources.displayMetrics
        )
        var size = textSize
        while (size > minSize) {
            size = (size - density).coerceAtLeast(minSize)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val fitted = layout
            if (fitted == null || fitted.lineCount <= 1) {
                return
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        if (textLayout == null || text.isEmpty()) {
            super.onDraw(canvas)
            return
        }

        val textPaint = paint
        val origStyle = textPaint.style
        val origStrokeWidth = textPaint.strokeWidth
        val origStrokeCap = textPaint.strokeCap
        val origStrokeJoin = textPaint.strokeJoin
        val origColor = textPaint.color
        val fillColor = currentTextColor

        val verticalExtra = (height - extendedPaddingTop - extendedPaddingBottom - textLayout.height)
            .coerceAtLeast(0) / 2f
        val xOffset = compoundPaddingLeft.toFloat()
        val yOffset = extendedPaddingTop.toFloat() + verticalExtra
        val cx = width / 2f
        val cy = height / 2f

        canvas.save()
        try {
            // CSS transform: skew(-3deg) keeps the baseline 100% horizontal.
            canvas.translate(cx, cy)
            canvas.skew(SKEW_X, 0f)
            canvas.translate(-cx, -cy)
            canvas.translate(xOffset, yOffset)

            textPaint.isAntiAlias = true
            textPaint.strokeCap = Paint.Cap.ROUND
            textPaint.strokeJoin = Paint.Join.ROUND

            // Layer 1: Soft magenta outer glow
            textPaint.style = Paint.Style.FILL_AND_STROKE
            textPaint.strokeWidth = 6f * density
            textPaint.color = COLOR_MAGENTA_GLOW
            textPaint.setShadowLayer(16f * density, 0f, 0f, COLOR_MAGENTA_GLOW)
            textLayout.draw(canvas)

            // Layer 2: Hard black 3D drop shadow (+3.5dp, +3.5dp)
            textPaint.clearShadowLayer()
            textPaint.style = Paint.Style.FILL_AND_STROKE
            textPaint.strokeWidth = 6f * density
            textPaint.color = Color.BLACK
            canvas.save()
            canvas.translate(3.5f * density, 3.5f * density)
            textLayout.draw(canvas)
            canvas.restore()

            // Layer 3: Crisp thick black outer outline
            textPaint.clearShadowLayer()
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = 6f * density
            textPaint.color = Color.BLACK
            textLayout.draw(canvas)

            // Layer 4: Bright pink fill (#FF2DB8)
            textPaint.clearShadowLayer()
            textPaint.style = Paint.Style.FILL
            textPaint.strokeWidth = 0f
            textPaint.color = fillColor
            textLayout.draw(canvas)
        } finally {
            textPaint.clearShadowLayer()
            textPaint.style = origStyle
            textPaint.strokeWidth = origStrokeWidth
            textPaint.strokeCap = origStrokeCap
            textPaint.strokeJoin = origStrokeJoin
            textPaint.color = origColor
            canvas.restore()
        }
    }

    private companion object {
        const val SKEW_X = -0.0524f // -tan(3 deg)
        val COLOR_MAGENTA_GLOW = Color.argb(210, 255, 0, 180)
    }
}

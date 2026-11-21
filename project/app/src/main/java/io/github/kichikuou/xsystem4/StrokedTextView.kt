package io.github.kichikuou.xsystem4

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.TextView

/**
 * TextView that reproduces the "Dohna Dohna Android Ver." logo lettering:
 * hot pink fill, neon lime inner outline, thick black outer outline and a
 * hard black 3D drop shadow. The magenta glow comes from the regular
 * TextView shadow layer configured in the layout.
 *
 * Paint order (bottom to top):
 *   1. black 3D drop shadow, offset by +5dp / +5dp
 *   2. black outer outline (3dp on each side)
 *   3. neon lime inner outline (2dp on each side)
 *   4. the normal TextView drawing (pink fill + glow)
 */
class StrokedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    init {
        // Keep room inside the view bounds for the strokes, the 3D shadow and the
        // glow so that neither the outline nor the -3 degree rotation gets clipped.
        val horizontal = (10f * density + 0.5f).toInt()
        val vertical = (10f * density + 0.5f).toInt()
        setPadding(
            paddingLeft + horizontal,
            paddingTop + vertical,
            paddingRight + horizontal,
            paddingBottom + vertical
        )
    }

    /**
     * The mockup renders the title on a single line. On narrow landscape phones the
     * default 22sp is wider than the center column, so shrink the text size just enough
     * to keep it on one line (down to 15sp) instead of letting it wrap mid-phrase.
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
            15f,
            resources.displayMetrics
        )
        var size = textSize
        while (size > minSize) {
            size = (size - density).coerceAtLeast(minSize)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            // Re-measure so the next check uses the real, letter-spaced line layout.
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

        // Copy the TextView's own TextPaint. This preserves the typeface, text size
        // and the native letter spacing state, so the strokes line up exactly with
        // the filled glyphs that super.onDraw() renders.
        strokePaint.set(paint)
        strokePaint.clearShadowLayer()
        strokePaint.isAntiAlias = true
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND

        val offset = 5f * density

        // Layer 1: 3D drop shadow. FILL_AND_STROKE makes the shadow follow the
        // outer outline instead of the thin glyph shape.
        strokePaint.style = Paint.Style.FILL_AND_STROKE
        strokePaint.strokeWidth = 6f * density
        strokePaint.color = Color.BLACK
        drawTextLines(canvas, textLayout, offset, offset)

        // Layer 2: black outer outline (3dp each side).
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 6f * density
        strokePaint.color = Color.BLACK
        drawTextLines(canvas, textLayout, 0f, 0f)

        // Layer 3: neon lime inner outline (2dp each side).
        strokePaint.strokeWidth = 4f * density
        strokePaint.color = LIME
        drawTextLines(canvas, textLayout, 0f, 0f)

        // Layer 4: pink fill and magenta glow.
        super.onDraw(canvas)
    }

    private fun drawTextLines(canvas: Canvas, textLayout: Layout, dx: Float, dy: Float) {
        val content = text
        val xOffset = compoundPaddingLeft + dx
        val yOffset = extendedPaddingTop + dy
        for (i in 0 until textLayout.lineCount) {
            val start = textLayout.getLineStart(i)
            val end = textLayout.getLineEnd(i)
            if (end <= start) continue
            val x = textLayout.getLineLeft(i) + xOffset
            val baseline = textLayout.getLineBaseline(i) + yOffset
            canvas.drawText(content.subSequence(start, end).toString(), x, baseline, strokePaint)
        }
    }

    private companion object {
        val LIME = 0xFFB8FF00.toInt()
    }
}

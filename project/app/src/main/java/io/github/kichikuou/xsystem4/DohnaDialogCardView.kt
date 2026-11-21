package io.github.kichikuou.xsystem4

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * Custom comic-styled dialog card matching `.dohna-dialog` in `New Interface/dialog.html`:
 * - Subtle -4deg horizontal skew on the card geometry while keeping text crisp
 * - Dual solid 3D offset shadows (+7dp black, +11dp #FF2DB8 neon pink)
 * - #181226 body with 8% white halftone dot pattern and subtle inner border
 * - #FBEE03 yellow header bar with thick 3.5dp black bottom divider and outer border
 */
class DohnaDialogCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val insetLeft = 14f * density
    private val insetTop = 4f * density
    private val insetRight = 24f * density
    private val insetBottom = 15f * density
    private val cornerRadius = 14f * density
    private val borderWidth = 3.5f * density
    private val innerBorderWidth = 1.5f * density
    private val shadowBlackOffset = 7f * density
    private val shadowAccentOffset = 11f * density

    private val cardRect = RectF()
    private val innerRect = RectF()
    private val workRect = RectF()
    private val cardClipPath = Path()

    private val accentShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_NEON_PINK
    }
    private val blackShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_BODY_BG
    }
    private val halftonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val innerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = innerBorderWidth
        color = COLOR_INNER_BORDER
    }
    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_HEADER_YELLOW
    }
    private val headerDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = Color.BLACK
    }
    private val outerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = Color.BLACK
    }

    private var halftoneTile: Bitmap? = null

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        setPadding(
            (insetLeft + borderWidth).roundToInt(),
            (insetTop + borderWidth).roundToInt(),
            (insetRight + borderWidth).roundToInt(),
            (insetBottom + borderWidth).roundToInt()
        )
    }

    fun setAccentShadowColor(color: Int) {
        if (accentShadowPaint.color != color) {
            accentShadowPaint.color = color
            invalidate()
        }
    }

    private fun ensureHalftoneShader() {
        if (halftonePaint.shader != null) return
        val step = (10f * density).roundToInt().coerceAtLeast(2)
        val dotRadius = 1.2f * density
        val bitmap = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(20, 255, 255, 255)
        }
        canvas.drawCircle(step / 2f, step / 2f, dotRadius, dotPaint)
        halftoneTile = bitmap
        halftonePaint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availWidth = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        if (availWidth > 0 && widthMode != MeasureSpec.EXACTLY) {
            val horizontalReserve = paddingLeft + paddingRight
            val maxCardBody = (470f * density).roundToInt()
            val minCardBody = (300f * density).roundToInt()
            val targetWidth = (maxCardBody + horizontalReserve)
                .coerceAtMost(availWidth)
                .coerceAtLeast(minCardBody.coerceAtMost(availWidth))
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                heightMeasureSpec
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        cardRect.set(insetLeft, insetTop, w - insetRight, h - insetBottom)
        if (cardRect.width() <= 0f || cardRect.height() <= 0f) {
            super.onDraw(canvas)
            return
        }

        ensureHalftoneShader()

        val halfStroke = borderWidth / 2f
        innerRect.set(cardRect)
        innerRect.inset(halfStroke, halfStroke)
        val innerRadius = (cornerRadius - halfStroke).coerceAtLeast(0f)

        cardClipPath.reset()
        cardClipPath.addRoundRect(innerRect, innerRadius, innerRadius, Path.Direction.CW)

        val cx = cardRect.centerX()
        val cy = cardRect.centerY()

        canvas.save()
        canvas.translate(cx, cy)
        canvas.skew(SKEW_X, 0f)
        canvas.translate(-cx, -cy)

        // 1. Offset neon pink shadow (+11dp, +11dp)
        workRect.set(cardRect)
        workRect.offset(shadowAccentOffset, shadowAccentOffset)
        canvas.drawRoundRect(workRect, cornerRadius, cornerRadius, accentShadowPaint)

        // 2. Offset solid black shadow (+7dp, +7dp)
        workRect.set(cardRect)
        workRect.offset(shadowBlackOffset, shadowBlackOffset)
        canvas.drawRoundRect(workRect, cornerRadius, cornerRadius, blackShadowPaint)

        // 3. Dark purple-charcoal body (#181226)
        canvas.drawRoundRect(innerRect, innerRadius, innerRadius, bodyPaint)

        // 4. Clipped interior: halftone dot pattern, subtle inner border, and yellow header
        canvas.save()
        canvas.clipPath(cardClipPath)

        canvas.drawRect(cardRect, halftonePaint)

        workRect.set(cardRect)
        val innerStrokeInset = borderWidth + innerBorderWidth / 2f
        workRect.inset(innerStrokeInset, innerStrokeInset)
        val subtleRadius = (cornerRadius - innerStrokeInset).coerceAtLeast(0f)
        canvas.drawRoundRect(workRect, subtleRadius, subtleRadius, innerBorderPaint)

        val headerView = if (childCount > 0) getChildAt(0) else null
        if (headerView != null && headerView.visibility == View.VISIBLE) {
            val headerBottom = headerView.bottom.toFloat().coerceIn(cardRect.top, cardRect.bottom)
            canvas.drawRect(cardRect.left, cardRect.top, cardRect.right, headerBottom, headerBgPaint)
            canvas.drawLine(cardRect.left, headerBottom, cardRect.right, headerBottom, headerDividerPaint)
        }

        canvas.restore()

        // 5. Crisp 3.5dp black outer comic border
        canvas.drawRoundRect(innerRect, innerRadius, innerRadius, outerBorderPaint)

        canvas.restore()

        super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        halftonePaint.shader = null
        halftoneTile = null
    }

    private companion object {
        const val SKEW_X = -0.0699268f // -tan(4 deg)
        const val COLOR_NEON_PINK = 0xFFFF2DB8.toInt()
        const val COLOR_BODY_BG = 0xFF181226.toInt()
        const val COLOR_HEADER_YELLOW = 0xFFFBEE03.toInt()
        const val COLOR_INNER_BORDER = 0x29FFFFFF
    }
}

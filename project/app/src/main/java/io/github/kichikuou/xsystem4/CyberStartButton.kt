package io.github.kichikuou.xsystem4

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Custom cyberpunk "START GAME" button faithfully matching `.cyber-btn-start` from the mockup:
 * - Pulsing outer neon green (#46F772) and cyan (#00F0FF) glow
 * - Hard 3D magenta/pink (#FF007F) drop shadow pulsing between 2dp and 4dp
 * - -3deg horizontal skew on the button body while keeping the play icon & label upright
 * - Diagonal #D4FF00 -> #46F772 -> #00F0FF gradient (top-left to bottom-right)
 * - Crisp 2dp solid white border with 16dp rounded corners
 * - Tactile press scale feedback
 */
class CyberStartButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val glowPadH = 18f * density
    private val glowPadV = 14f * density
    private val cornerRadius = 16f * density
    private val borderWidth = 2f * density

    private val pillRect = RectF()
    private val workRect = RectF()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(15f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_PINK_SHADOW
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = Color.WHITE
    }

    private var normalShader: Shader? = null
    private var pressedShader: Shader? = null

    private var pulseProgress = 0f
    private var pulseAnimator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        // Software layer ensures BlurMaskFilter renders identically across API 21..34.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        val padH = (glowPadH + 30f * density + 0.5f).toInt()
        val padV = (glowPadV + 14f * density + 0.5f).toInt()
        setPadding(padH, padV, padH, padV)

        val iconSize = (22f * density + 0.5f).toInt()
        val iconGap = (10f * density + 0.5f).toInt()
        val playIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_play)
            contentDescription = null
            layoutParams = LayoutParams(iconSize, iconSize).apply {
                marginEnd = iconGap
            }
        }
        val label = TextView(context).apply {
            setText(R.string.start_game)
            setTextColor(COLOR_TEXT_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            isAllCaps = true
            letterSpacing = 0.1f
            includeFontPadding = false
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        addView(playIcon)
        addView(label)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pillRect.set(glowPadH, glowPadV, w - glowPadH, h - glowPadV)
        if (pillRect.width() > 0f && pillRect.height() > 0f) {
            normalShader = LinearGradient(
                pillRect.left,
                pillRect.top,
                pillRect.right,
                pillRect.bottom,
                intArrayOf(COLOR_LIME_START, COLOR_GREEN_MID, COLOR_CYAN_END),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            pressedShader = LinearGradient(
                pillRect.left,
                pillRect.top,
                pillRect.right,
                pillRect.bottom,
                intArrayOf(COLOR_LIME_PRESSED, COLOR_GREEN_PRESSED, COLOR_CYAN_PRESSED),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                animate()
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(80L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().cancel()
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160L)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (pillRect.width() <= 0f || pillRect.height() <= 0f) {
            super.onDraw(canvas)
            return
        }

        val cx = pillRect.centerX()
        val cy = pillRect.centerY()

        canvas.save()
        canvas.translate(cx, cy)
        canvas.skew(SKEW_X, 0f)
        canvas.translate(-cx, -cy)

        val spread = 3f * density * pulseProgress

        // 1. Pulsing cyan/lime outer ambient glow
        workRect.set(pillRect)
        workRect.inset(-spread, -spread)
        glowPaint.color = if (pulseProgress < 0.5f) COLOR_LIME_START else COLOR_CYAN_END
        glowPaint.alpha = (75 + 100 * pulseProgress).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(workRect, cornerRadius, cornerRadius, glowPaint)

        // 2. Pulsing green lower glow (0 6px 25px rgba(70, 247, 114, 0.5..0.85))
        workRect.set(pillRect)
        workRect.offset(0f, 3f * density)
        workRect.inset(-spread * 0.6f, -spread * 0.6f)
        glowPaint.color = COLOR_GREEN_MID
        glowPaint.alpha = (125 + 90 * pulseProgress).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(workRect, cornerRadius, cornerRadius, glowPaint)

        // 3. Hard 3D pink/magenta shadow pulsing between 2dp and 4dp
        val shadowOffset = (2f + 2f * pulseProgress) * density
        workRect.set(pillRect)
        workRect.offset(shadowOffset, shadowOffset)
        canvas.drawRoundRect(workRect, cornerRadius, cornerRadius, shadowPaint)

        // 4. Diagonal gradient body (#D4FF00 -> #46F772 -> #00F0FF)
        bodyPaint.shader = if (isPressed) pressedShader else normalShader
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bodyPaint)

        // 5. Crisp 2dp white border
        workRect.set(pillRect)
        val halfStroke = borderWidth / 2f
        workRect.inset(halfStroke, halfStroke)
        canvas.drawRoundRect(workRect, cornerRadius - halfStroke, cornerRadius - halfStroke, borderPaint)

        canvas.restore()

        super.onDraw(canvas)
    }

    fun startPulseAnimation() {
        if (pulseAnimator != null) {
            return
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                pulseProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseProgress = 0f
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopPulseAnimation()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SKEW_X = -0.0524f // -tan(3 deg)
        const val COLOR_LIME_START = 0xFFD4FF00.toInt()
        const val COLOR_GREEN_MID = 0xFF46F772.toInt()
        const val COLOR_CYAN_END = 0xFF00F0FF.toInt()
        const val COLOR_LIME_PRESSED = 0xFFB4DB00.toInt()
        const val COLOR_GREEN_PRESSED = 0xFF3AD965.toInt()
        const val COLOR_CYAN_PRESSED = 0xFF00CCE0.toInt()
        const val COLOR_PINK_SHADOW = 0xFFFF007F.toInt()
        const val COLOR_TEXT_DARK = 0xFF080512.toInt()
    }
}

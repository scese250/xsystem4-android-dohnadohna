package io.github.kichikuou.xsystem4

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dohna Dohna styled confirmation / alert dialog matching `New Interface/dialog.html`.
 * Supports both native SDL_ShowMessageBox calls (1..3 buttons, SDL flags, physical keys)
 * and launcher alert/confirmation dialogs without requiring AndroidX or Material.
 */
class DohnaConfirmDialog private constructor(
    private val hostActivity: Activity,
    private val badgeText: String,
    private val message: CharSequence,
    private val buttons: List<ButtonSpec>,
    private val onResult: (Int) -> Unit
) : Dialog(hostActivity) {

    data class ButtonSpec(
        val id: Int,
        val text: String,
        val flags: Int = 0
    )

    private val density = hostActivity.resources.displayMetrics.density

    private lateinit var scrimView: View
    private lateinit var focusGlowView: View
    private lateinit var cardView: DohnaDialogCardView
    private lateinit var badgeIconView: ImageView
    private lateinit var badgeTextView: TextView
    private lateinit var closeButton: ImageButton
    private lateinit var questionView: TextView
    private lateinit var warningPillView: LinearLayout
    private lateinit var warningIconView: ImageView
    private lateinit var warningTextView: TextView
    private lateinit var actionsContainer: LinearLayout

    private var glowAnimator: ValueAnimator? = null
    private var warningBlinkAnimator: ValueAnimator? = null
    private var resultDispatched = false

    private val actionButtonViews = ArrayList<Pair<View, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_dohna_confirm)

        setCancelable(false)
        setCanceledOnTouchOutside(false)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        scrimView = findViewById(R.id.dohna_dialog_scrim)
        focusGlowView = findViewById(R.id.dohna_dialog_focus_glow)
        cardView = findViewById(R.id.dohna_dialog_card)
        badgeIconView = findViewById(R.id.dohna_dialog_badge_icon)
        badgeTextView = findViewById(R.id.dohna_dialog_badge_text)
        closeButton = findViewById(R.id.dohna_dialog_close)
        questionView = findViewById(R.id.dohna_dialog_question)
        warningPillView = findViewById(R.id.dohna_dialog_warning_pill)
        warningIconView = findViewById(R.id.dohna_dialog_warning_icon)
        warningTextView = findViewById(R.id.dohna_dialog_warning_text)
        actionsContainer = findViewById(R.id.dohna_dialog_actions)

        badgeIconView.imageTintList = ColorStateList.valueOf(COLOR_NEON_LIME)
        warningIconView.imageTintList = ColorStateList.valueOf(COLOR_NEON_YELLOW)

        badgeTextView.text = badgeText.uppercase(Locale.ROOT)
        bindMessage(message)
        buildActionButtons()

        closeButton.setOnClickListener {
            dispatchAndDismiss(resolveCancelId() ?: RESULT_DISMISSED)
        }
    }

    private fun bindMessage(raw: CharSequence) {
        if (raw is Spanned) {
            val trimmed = trimTrailingWhitespace(raw)
            questionView.apply {
                text = trimmed
                gravity = Gravity.START
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT
                setTextColor(COLOR_RICH_TEXT)
                setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
            warningPillView.visibility = View.GONE
            return
        }

        val lines = raw.toString()
            .trim()
            .split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        questionView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(1f * density, 2f * density, 2f * density, Color.BLACK)
        }

        when {
            lines.isEmpty() -> {
                questionView.text = ""
                warningPillView.visibility = View.GONE
            }
            lines.size == 1 -> {
                questionView.text = lines[0]
                warningPillView.visibility = View.GONE
            }
            else -> {
                questionView.text = lines[0]
                warningTextView.text = lines.drop(1).joinToString("\n")
                warningPillView.visibility = View.VISIBLE
            }
        }
    }

    private fun buildActionButtons() {
        actionsContainer.removeAllViews()
        actionButtonViews.clear()

        val effectiveButtons = if (buttons.isNotEmpty()) {
            buttons
        } else {
            listOf(ButtonSpec(id = 0, text = hostActivity.getString(R.string.ok), flags = SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT))
        }

        val maxRowWidth = (380f * density).roundToInt()
        val estimatedBodyInnerWidth = (hostActivity.resources.displayMetrics.widthPixels - 124f * density).roundToInt()
        val targetRowWidth = if (effectiveButtons.size >= 2 && estimatedBodyInnerWidth > maxRowWidth) {
            maxRowWidth
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        actionsContainer.layoutParams = LinearLayout.LayoutParams(
            targetRowWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (18f * density).roundToInt()
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val gapPx = (14f * density).roundToInt()
        for (i in effectiveButtons.indices) {
            val spec = effectiveButtons[i]
            val primary = isPrimaryButton(i, effectiveButtons)
            val buttonView = createStyledButton(spec, primary)

            val lp = if (effectiveButtons.size == 1) {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = gapPx
                }
            }
            buttonView.layoutParams = lp
            if (effectiveButtons.size == 1) {
                buttonView.minimumWidth = (176f * density).roundToInt()
            }

            buttonView.setOnClickListener {
                dispatchAndDismiss(spec.id)
            }

            actionsContainer.addView(buttonView)
            actionButtonViews.add(buttonView to spec.id)
        }
    }

    private fun createStyledButton(spec: ButtonSpec, primary: Boolean): LinearLayout {
        val ctx: Context = hostActivity
        val padH = (16f * density).roundToInt()
        val padTop = (10f * density).roundToInt()
        val padBottom = (13f * density).roundToInt() // Extra 3dp accounts for bottom 3D black shadow
        val iconSize = (14f * density).roundToInt()
        val iconGap = (6f * density).roundToInt()

        val textColor = if (primary) COLOR_OK_TEXT_DARK else Color.WHITE

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minimumHeight = (46f * density).roundToInt()
            setPadding(padH, padTop, padH, padBottom)
            setBackgroundResource(
                if (primary) R.drawable.dohna_dialog_btn_ok else R.drawable.dohna_dialog_btn_cancel
            )
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().cancel()
                        v.animate()
                            .scaleX(0.97f)
                            .scaleY(0.97f)
                            .setDuration(70L)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().cancel()
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(140L)
                            .setInterpolator(OvershootInterpolator(1.6f))
                            .start()
                    }
                }
                false
            }
        }

        val icon = ImageView(ctx).apply {
            setImageResource(if (primary) R.drawable.ic_play else R.drawable.ic_dialog_close)
            imageTintList = ColorStateList.valueOf(textColor)
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = iconGap
            }
        }

        val label = TextView(ctx).apply {
            text = spec.text.ifEmpty { if (primary) ctx.getString(R.string.ok) else ctx.getString(R.string.cancel) }
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            isAllCaps = true
            letterSpacing = 0.06f
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(icon)
        container.addView(label)
        return container
    }

    override fun show() {
        val w = window
        if (w != null) {
            // Prevent status/navigation bars from flashing into view when the dialog window opens.
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            applyImmersiveFlags(w)
        }

        super.show()

        if (w != null) {
            applyImmersiveFlags(w)
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            val decor = w.decorView
            if (decor.isAttachedToWindow) {
                try {
                    hostActivity.windowManager.updateViewLayout(decor, w.attributes)
                } catch (_: IllegalArgumentException) {
                    // Window already detached or not yet added; safe to ignore.
                }
            }
        }

        startAnimations()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window?.let { applyImmersiveFlags(it) }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                dispatchAndDismiss(resolveCancelId() ?: RESULT_DISMISSED)
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER
        ) {
            val focused = currentFocus
            val focusedIsAction = focused === closeButton || actionButtonViews.any { it.first === focused }
            if (!focusedIsAction) {
                val confirmId = resolveConfirmId()
                if (confirmId != null) {
                    if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                        dispatchAndDismiss(confirmId)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        stopAnimations()
        super.onStop()
    }

    override fun dismiss() {
        stopAnimations()
        super.dismiss()
    }

    private fun startAnimations() {
        stopAnimations()

        scrimView.alpha = 0f
        scrimView.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()

        cardView.alpha = 0f
        cardView.scaleX = 0.82f
        cardView.scaleY = 0.82f
        cardView.translationY = 12f * density
        cardView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val s = 0.92f + 0.18f * t
                focusGlowView.scaleX = s
                focusGlowView.scaleY = s
                focusGlowView.alpha = 0.65f + 0.35f * t
            }
            start()
        }

        if (warningPillView.visibility == View.VISIBLE) {
            warningBlinkAnimator = ValueAnimator.ofFloat(1f, 0.5f).apply {
                duration = 650L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    warningIconView.alpha = anim.animatedValue as Float
                }
                start()
            }
        }
    }

    private fun stopAnimations() {
        scrimView.animate().cancel()
        cardView.animate().cancel()
        glowAnimator?.cancel()
        glowAnimator = null
        warningBlinkAnimator?.cancel()
        warningBlinkAnimator = null
    }

    private fun dispatchAndDismiss(selectedId: Int) {
        if (resultDispatched) return
        resultDispatched = true
        onResult(selectedId)
        if (isShowing) {
            dismiss()
        }
    }

    private fun resolveConfirmId(): Int? {
        buttons.firstOrNull { (it.flags and SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT) != 0 }?.let {
            return it.id
        }
        if (buttons.size == 1) {
            return buttons[0].id
        }
        return null
    }

    private fun resolveCancelId(): Int? {
        buttons.firstOrNull { (it.flags and SDL_MESSAGEBOX_BUTTON_ESCAPEKEY_DEFAULT) != 0 }?.let {
            return it.id
        }
        if (buttons.size >= 2) {
            buttons.drop(1).firstOrNull { isCancelText(it.text) }?.let { return it.id }
        }
        buttons.firstOrNull { isCancelText(it.text) }?.let { return it.id }
        return null
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveFlags(w: Window) {
        w.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    companion object {
        const val RESULT_DISMISSED = -1
        const val SDL_MESSAGEBOX_ERROR = 0x00000010
        const val SDL_MESSAGEBOX_WARNING = 0x00000020
        const val SDL_MESSAGEBOX_INFORMATION = 0x00000040
        const val SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT = 0x00000001
        const val SDL_MESSAGEBOX_BUTTON_ESCAPEKEY_DEFAULT = 0x00000002

        private const val COLOR_NEON_LIME = 0xFFB8FF00.toInt()
        private const val COLOR_NEON_YELLOW = 0xFFFBEE03.toInt()
        private const val COLOR_OK_TEXT_DARK = 0xFF0D0914.toInt()
        private const val COLOR_RICH_TEXT = 0xFFEAE4F8.toInt()

        fun fromSdlMessageBox(
            activity: Activity,
            sdlFlags: Int,
            title: String?,
            message: String,
            buttons: List<ButtonSpec>,
            onResult: (Int) -> Unit
        ): DohnaConfirmDialog {
            val badge = when {
                (sdlFlags and SDL_MESSAGEBOX_ERROR) != 0 -> "ERROR"
                buttons.size <= 1 -> "NOTICE"
                else -> "CONFIRM"
            }
            val effectiveMessage = message.ifBlank { title ?: "" }
            return DohnaConfirmDialog(
                hostActivity = activity,
                badgeText = badge,
                message = effectiveMessage,
                buttons = buttons,
                onResult = onResult
            )
        }

        fun showNotice(
            activity: Activity,
            badgeText: String = "NOTICE",
            message: CharSequence,
            okText: String = activity.getString(R.string.ok),
            onOk: (() -> Unit)? = null
        ): DohnaConfirmDialog {
            val dialog = DohnaConfirmDialog(
                hostActivity = activity,
                badgeText = badgeText,
                message = message,
                buttons = listOf(
                    ButtonSpec(
                        id = 1,
                        text = okText,
                        flags = SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT or SDL_MESSAGEBOX_BUTTON_ESCAPEKEY_DEFAULT
                    )
                ),
                onResult = { selectedId ->
                    if (selectedId == 1) {
                        onOk?.invoke()
                    }
                }
            )
            dialog.show()
            return dialog
        }

        fun showError(
            activity: Activity,
            message: CharSequence,
            okText: String = activity.getString(R.string.ok),
            onOk: (() -> Unit)? = null
        ): DohnaConfirmDialog {
            return showNotice(
                activity = activity,
                badgeText = "ERROR",
                message = message,
                okText = okText,
                onOk = onOk
            )
        }

        fun showConfirm(
            activity: Activity,
            badgeText: String = "CONFIRM",
            message: CharSequence,
            okText: String = activity.getString(R.string.ok),
            cancelText: String = activity.getString(R.string.cancel),
            onConfirm: () -> Unit,
            onCancel: (() -> Unit)? = null
        ): DohnaConfirmDialog {
            val dialog = DohnaConfirmDialog(
                hostActivity = activity,
                badgeText = badgeText,
                message = message,
                buttons = listOf(
                    ButtonSpec(
                        id = 1,
                        text = okText,
                        flags = SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT
                    ),
                    ButtonSpec(
                        id = 0,
                        text = cancelText,
                        flags = SDL_MESSAGEBOX_BUTTON_ESCAPEKEY_DEFAULT
                    )
                ),
                onResult = { selectedId ->
                    if (selectedId == 1) {
                        onConfirm()
                    } else {
                        onCancel?.invoke()
                    }
                }
            )
            dialog.show()
            return dialog
        }

        private fun isPrimaryButton(index: Int, buttons: List<ButtonSpec>): Boolean {
            val spec = buttons[index]
            if ((spec.flags and SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT) != 0) {
                return true
            }
            if ((spec.flags and SDL_MESSAGEBOX_BUTTON_ESCAPEKEY_DEFAULT) != 0) {
                return false
            }
            if (isCancelText(spec.text)) {
                return false
            }
            if (buttons.size == 1) {
                return true
            }
            val anyHasReturnFlag = buttons.any {
                (it.flags and SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT) != 0
            }
            return !anyHasReturnFlag && index == 0
        }

        private fun isCancelText(text: String): Boolean {
            val normalized = text.trim().lowercase(Locale.ROOT)
            return normalized == "cancel" ||
                normalized == "no" ||
                normalized == "stay" ||
                normalized == "close" ||
                normalized == "dismiss" ||
                normalized == "back" ||
                normalized == "キャンセル" ||
                normalized == "いいえ" ||
                normalized == "閉じる" ||
                normalized == "戻る" ||
                normalized == "取消" ||
                normalized == "否"
        }

        private fun trimTrailingWhitespace(source: Spanned): CharSequence {
            var end = source.length
            while (end > 0 && Character.isWhitespace(source[end - 1])) {
                end--
            }
            var start = 0
            while (start < end && Character.isWhitespace(source[start])) {
                start++
            }
            if (start == 0 && end == source.length) {
                return source
            }
            return SpannableStringBuilder(source, start, end)
        }
    }
}

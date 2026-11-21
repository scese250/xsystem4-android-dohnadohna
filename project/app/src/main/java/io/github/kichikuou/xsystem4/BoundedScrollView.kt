package io.github.kichikuou.xsystem4

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * ScrollView that wraps its content height up to a configurable maximum height
 * so long messages or HTML help text scroll without pushing dialog buttons off-screen.
 */
class BoundedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = (resources.displayMetrics.heightPixels * 0.40f).toInt()
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cappedHeightSpec = if (maxHeightPx > 0) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val bounded = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
            MeasureSpec.makeMeasureSpec(bounded, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, cappedHeightSpec)
    }
}

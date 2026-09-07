package care.primary.sphere360.util

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** ImageView dont la hauteur est dérivée de la largeur (ratio 16:10 par défaut). */
class AspectRatioImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ImageView(context, attrs, defStyle) {

    var ratio: Float = 10f / 16f
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * ratio).toInt()
        setMeasuredDimension(w, h)
    }
}

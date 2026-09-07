package care.primary.sphere360.util

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.Toast

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.toast(msg: CharSequence) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

private fun topInset(insets: WindowInsets): Int =
    if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top
    else @Suppress("DEPRECATION") insets.systemWindowInsetTop

private fun bottomInset(insets: WindowInsets): Int =
    if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom
    else @Suppress("DEPRECATION") insets.systemWindowInsetBottom

/** Ajoute la hauteur de la barre de statut au padding haut (écrans edge-to-edge). */
fun View.padTopWithStatusBar() {
    val base = paddingTop
    setOnApplyWindowInsetsListener { v, insets ->
        v.setPadding(v.paddingLeft, base + topInset(insets), v.paddingRight, v.paddingBottom)
        insets
    }
    requestApplyInsets()
}

/** Ajoute la hauteur de la barre de navigation au padding bas. */
fun View.padBottomWithNavBar() {
    val base = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + bottomInset(insets))
        insets
    }
    requestApplyInsets()
}

/** Callback avec les insets système (haut, bas) en pixels. */
fun View.onSystemInsets(block: (top: Int, bottom: Int) -> Unit) {
    setOnApplyWindowInsetsListener { _, insets ->
        block(topInset(insets), bottomInset(insets))
        insets
    }
    requestApplyInsets()
}

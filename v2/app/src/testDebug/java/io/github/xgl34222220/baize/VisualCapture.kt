package io.github.xgl34222220.baize

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

/** Draw the real laid-out View tree; host Robolectric has no PixelCopy surface. */
internal fun captureActivityContent(activity: Activity): Bitmap {
    val content = activity.findViewById<View>(android.R.id.content)
    require(content.width > 0 && content.height > 0)
    return Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888).also {
        content.draw(Canvas(it))
    }
}

package app.seniorremote.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import app.seniorremote.data.AndroidTvImageStore
import app.seniorremote.domain.SavedTv
import app.seniorremote.ui.RemoteIcons

val LocalTvImages = staticCompositionLocalOf<AndroidTvImageStore?> { null }

/** The picture a family member chose for this TV, or the plain TV icon. */
@Composable
fun TvThumbnail(tv: SavedTv, size: Dp, tint: Color) {
    val images = LocalTvImages.current
    val picture by produceState<ImageBitmap?>(initialValue = null, tv.imageFile, images) {
        value = tv.imageFile?.let { images?.load(it)?.asImageBitmap() }
    }
    val bitmap = picture
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(size / 6)),
        )
    } else {
        Icon(RemoteIcons.Tv, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

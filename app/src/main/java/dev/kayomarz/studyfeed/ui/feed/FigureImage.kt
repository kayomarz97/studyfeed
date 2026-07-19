package dev.kayomarz.studyfeed.ui.feed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.kayomarz.studyfeed.feed.FeedCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders a figure attached to a card (BUILD_PROMPT §8 A2; plan D12).
 *
 * **`BitmapFactory.decodeFile`, deliberately NOT Coil/Glide.** The figures are local PNGs in the
 * imported pack directory, so an image loader's whole value — HTTP fetch, network caching — is moot.
 * Worse, Coil's standard artifact depends on OkHttp, which `CheckDependencyDenylistTask` would fail the
 * build over, correctly: an image loader that *can* fetch a URL has no business in an app that must
 * never touch a network. So we decode local files directly and cache the results ourselves.
 *
 * Decoding happens off the main thread (a full-res PNG decode would jank the pager) and the bitmap is
 * downsampled toward the display width, because a captioned clinical photo does not need its full
 * source resolution to be read on a phone.
 */
private object FigureCache {
    // ~24MB of decoded bitmaps is plenty for a feed that shows one figure at a time and pages forward.
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bmp: Bitmap) { cache.put(key, bmp) }

    /** Decode downsampled to at most [reqWidth] px wide. Returns null on any failure (missing/corrupt). */
    fun decode(path: String, reqWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }
}

@Composable
fun FigureImage(figure: FeedCard.Figure, modifier: Modifier = Modifier) {
    var bitmap by remember(figure.path) { mutableStateOf(FigureCache.get(figure.path)) }

    LaunchedEffect(figure.path) {
        if (bitmap == null) {
            val decoded = withContext(Dispatchers.IO) { FigureCache.decode(figure.path, reqWidth = 1080) }
            if (decoded != null) { FigureCache.put(figure.path, decoded); bitmap = decoded }
        }
    }

    val bmp = bitmap ?: return // no bitmap yet, or the file was missing/corrupt → render nothing (D5:
    //                            a referenced-but-absent figure degrades silently, never a broken slot)
    Column(modifier.fillMaxWidth()) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = figure.captionMd ?: "figure",
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
        figure.captionMd?.takeIf { it.isNotBlank() }?.let { caption ->
            Text(
                text = PackMarkdown.inline(caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

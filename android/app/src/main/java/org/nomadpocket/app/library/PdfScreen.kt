package org.nomadpocket.app.library

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nomadpocket.library.BookShelf

/** An open PDF and its page sizes. PdfRenderer allows one page open at a time, so renders take [lock]. */
private class OpenPdf(val file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(fd)
    val lock = Mutex()
    val aspect: List<Float> = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { it.width.toFloat() / it.height.coerceAtLeast(1) } }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        file.delete()
    }
}

/**
 * A PDF stored inside a book, shown with Android's own PdfRenderer (no extra
 * library, works offline). Pages render as they scroll into view; pinch-zoom is
 * not supported yet.
 */
@Composable
fun PdfScreen(modifier: Modifier, bookId: String, path: String) {
    val context = LocalContext.current
    val pdf by produceState<Result<OpenPdf>?>(null, bookId, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val content = BookShelf.get(context).archive(bookId)?.content(path) ?: error("This file isn't in the book.")
                val file = File(context.cacheDir, "pdf/${System.nanoTime()}.pdf").apply { parentFile?.mkdirs(); writeBytes(content.data) }
                OpenPdf(file)
            }
        }
    }
    // Close the PDF this effect was started for (not whatever `pdf` holds when it is disposed).
    val opened = pdf?.getOrNull()
    DisposableEffect(opened) { onDispose { opened?.close() } }
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        when {
            pdf == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            pdf!!.isFailure -> Text("Couldn't open this PDF: ${pdf!!.exceptionOrNull()?.message}", Modifier.padding(24.dp))
            else -> PdfPages(pdf!!.getOrThrow())
        }
    }
}

@Composable
private fun PdfPages(pdf: OpenPdf) {
    BoxWithConstraints {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pdf.aspect.size) { i -> PdfPage(pdf, i, widthPx) }
        }
    }
}

@Composable
private fun PdfPage(pdf: OpenPdf, index: Int, widthPx: Int) {
    var bitmap by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, widthPx) {
        bitmap = withContext(Dispatchers.IO) {
            pdf.lock.withLock {
                runCatching {
                    pdf.renderer.openPage(index).use { page ->
                        val w = widthPx.coerceIn(1, 2000)
                        val h = (w / pdf.aspect[index]).toInt().coerceIn(1, 4000)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
            }
        }
    }
    val b = bitmap
    val ratio = pdf.aspect[index].coerceAtLeast(0.1f)
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxWidth().aspectRatio(ratio))
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(androidx.compose.ui.graphics.Color.White))
    }
}

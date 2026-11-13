package dev.wonderdesk.wayfinder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wayfinder's own viewer: photos (pinch and double-tap to zoom, swipe between them), video and audio,
 * text and code, and PDFs. Used for the Safe folder (whose files never leave Wayfinder) and whenever
 * the matching Wonderdesk app isn't installed.
 */
object Preview {
    data class Request(val files: List<File>, val start: Int, val private: Boolean)
    var current by mutableStateOf<Request?>(null)
    fun canShow(f: File) = f.kind() in setOf(Kind.Image, Kind.Video, Kind.Audio, Kind.Text, Kind.Code, Kind.Pdf)
    fun show(files: List<File>, start: Int, private: Boolean = false) { current = Request(files.filter(::canShow).ifEmpty { files }, start.coerceAtLeast(0), private) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewer(req: Preview.Request, onClose: () -> Unit) {
    val context = LocalContext.current
    val files = req.files
    val pager = rememberPagerState(req.start.coerceIn(0, (files.size - 1).coerceAtLeast(0))) { files.size }
    var chrome by remember { mutableStateOf(true) }
    var fadeOut by remember { mutableFloatStateOf(1f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(180)) }
    BackHandler { onClose() }
    WonderKitTheme {
        val c = rememberPlayerChrome()
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.viewerBackdrop(c, fadeOut)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondBoundsPageCount = 1, key = { files[it].path }) { i ->
                val f = files[i]
                when (f.kind()) {
                    Kind.Image -> ZoomImage(f, onTap = { chrome = !chrome }, onDismiss = onClose, onFade = { fadeOut = 1f - it * .85f; if (it > .05f) chrome = false })
                    Kind.Video, Kind.Audio -> WonderVideo(android.net.Uri.fromFile(f), active = pager.currentPage == i && !pager.isScrollInProgress, controls = chrome,
                        onToggleControls = { chrome = it }, audioOnly = f.kind() == Kind.Audio)
                    Kind.Pdf -> PdfPages(f)
                    Kind.Text, Kind.Code -> TextPage(f)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (req.private) "No preview for this file. Move it out of the Safe folder to open it in another app." else "No preview for this file. Open it with another app from the ⋮ menu in Browse.",
                            Modifier.padding(24.dp).mediaGlass(c).padding(18.dp), color = c.ink, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                val f = files.getOrNull(pager.currentPage)
                ViewerTopBar(f?.name.orEmpty(), listOfNotNull(f?.let { formatBytes(it.length()) }, if (files.size > 1) "${pager.currentPage + 1} of ${files.size}" else null, if (req.private) "Safe folder" else null).joinToString(" · "),
                    onBack = onClose) {
                    if (!req.private && f != null) HudButton(WonderIcons.Share, "Share", c, 44.dp) { context.share(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f), f.mime()) {} }
                }
            }
        }
    }
}

/** Downsampled to the screen, then pinch, drag and double-tap to zoom; swipe up or down to close. */
@Composable
private fun ZoomImage(f: File, onTap: () -> Unit, onDismiss: () -> Unit, onFade: (Float) -> Unit) {
    val context = LocalContext.current
    val screen = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
    val bmp by produceState<Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
                var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= screen * 1.5) s *= 2
                val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
                val deg = runCatching { when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 } }.getOrDefault(0)
                if (deg == 0 || b == null) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
            }.getOrNull()
        }
    }
    val img = remember(bmp) { bmp?.asImageBitmap() }
    ZoomableImage(img, f.name, onTap = onTap, onDismiss = onDismiss, onDismissProgress = onFade)
}

@Composable
private fun TextPage(f: File) {
    val text by produceState<String?>(null, f) { value = withContext(Dispatchers.IO) { runCatching { f.bufferedReader().use { r -> val b = CharArray(400_000); val n = r.read(b); if (n <= 0) "" else String(b, 0, n) } }.getOrDefault("Couldn't read this file") } }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 72.dp, start = 10.dp, end = 10.dp, bottom = 10.dp).mediaGlass(rememberPlayerChrome())) {
        SelectionContainer {
            Text(text ?: "", Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
                color = Color(0xFFE8EDF2), fontFamily = if (f.kind() == Kind.Code) FontFamily.Monospace else FontFamily.Default, fontSize = 14.sp)
        }
    }
}

@Composable
private fun PdfPages(f: File) {
    val context = LocalContext.current
    val width = context.resources.displayMetrics.widthPixels
    val renderer = remember(f) { runCatching { PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Couldn't open this PDF", color = Color.White) }; return }
    LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(top = 96.dp, bottom = 24.dp, start = 10.dp, end = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(renderer.pageCount) { i ->
            val page by produceState<Bitmap?>(null, i) {
                value = withContext(Dispatchers.IO) {
                    runCatching { synchronized(renderer) { renderer.openPage(i).use { p ->
                        val h = (width.toFloat() * p.height / p.width).toInt()
                        Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    } } }.getOrNull()
                }
            }
            val img = remember(page) { page?.asImageBitmap() }
            if (img != null) Image(img, "Page ${i + 1}", Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.FillWidth)
            else Box(Modifier.fillMaxWidth().aspectRatio(.7f).background(Color(0xFF1C2128)))
        }
    }
}

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
    val pager = rememberPagerState(files.indexOfFirst { true }.let { req.start.coerceIn(0, (files.size - 1).coerceAtLeast(0)) }) { files.size }
    var chrome by remember { mutableStateOf(true) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(180)) }
    BackHandler { onClose() }
    WonderKitTheme {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color(0xFF0B0E12))) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondBoundsPageCount = 1, key = { files[it].path }) { i ->
                val f = files[i]
                when (f.kind()) {
                    Kind.Image -> ZoomImage(f, onTap = { chrome = !chrome })
                    Kind.Video, Kind.Audio -> Player(f, active = pager.currentPage == i)
                    Kind.Pdf -> PdfPages(f)
                    Kind.Text, Kind.Code -> TextPage(f)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState("No preview for this file", if (req.private) "Move it out of the Safe folder to open it in another app." else "Open it with another app from the ⋮ menu in Browse.", icon = WonderIcons.InsertDriveFile)
                    }
                }
            }
            if (chrome) {
                val f = files.getOrNull(pager.currentPage)
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).statusBarsPadding().padding(4.dp, 4.dp, 12.dp, 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(WonderIcons.ArrowBack, "Back", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(f?.name.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(f?.let { formatBytes(it.length()) }, if (files.size > 1) "${pager.currentPage + 1} of ${files.size}" else null, if (req.private) "Safe folder" else null).joinToString(" · "),
                            color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                    }
                    if (!req.private && f != null) IconButton(onClick = { context.share(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f), f.mime()) {} }) {
                        Icon(WonderIcons.Share, "Share", tint = Color.White)
                    }
                }
            }
        }
    }
}

/** Downsampled to the screen, then pinch, drag and double-tap to zoom. */
@Composable
private fun ZoomImage(f: File, onTap: () -> Unit) {
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
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoomScope = rememberCoroutineScope()
    var boxSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    // Keeps the picture on screen however far you pan.
    fun clamp(o: Offset, s: Float) = Offset(o.x.coerceIn(-(s - 1) * boxSize.width / 2, (s - 1) * boxSize.width / 2), o.y.coerceIn(-(s - 1) * boxSize.height / 2, (s - 1) * boxSize.height / 2))
    fun zoomTo(ts: Float, to: Offset) { val fs = scale; val fo = offset; val tt = clamp(to, ts)
        zoomScope.launch { androidx.compose.animation.core.animate(0f, 1f, animationSpec = androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { t, _ -> scale = fs + (ts - fs) * t; offset = fo + (tt - fo) * t } } }
    val img = remember(bmp) { bmp?.asImageBitmap() }
    Box(Modifier.fillMaxSize().onSizeChanged { boxSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
        .pointerInput(f) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { p -> if (scale > 1.1f) zoomTo(1f, Offset.Zero) else zoomTo(2.5f, (Offset(size.width / 2f, size.height / 2f) - p) * 1.5f) }) }
        // At normal size, one-finger drags go to the pager (swipe to the next photo); pinch zooms.
        .then(if (scale > 1.01f) Modifier.pointerInput(f) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset = if (scale <= 1.01f) Offset.Zero else clamp(offset + pan, scale) } }
              else Modifier.pointerInput(f) { detectTransformGestures(panZoomLock = true) { _, _, zoom, _ -> if (zoom != 1f) scale = (scale * zoom).coerceIn(1f, 6f) } }), contentAlignment = Alignment.Center) {
        if (img == null) CircularProgressIndicator(color = Color.White)
        else Image(img, f.name, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
    }
}

/** Video and audio with the standard player controls; pauses when you swipe away. */
@Composable
private fun Player(f: File, active: Boolean) {
    val context = LocalContext.current
    val player = remember(f) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f))); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(active) { player.playWhenReady = active; if (!active) player.pause() }
    val resumed = rememberIsResumed()
    LaunchedEffect(resumed) { if (!resumed) player.pause() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView({ c -> PlayerView(c).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false); setBackgroundColor(android.graphics.Color.TRANSPARENT) } },
            Modifier.fillMaxSize().navigationBarsPadding())
        if (f.kind() == Kind.Audio) Icon(WonderIcons.MusicNote, null, Modifier.size(120.dp).align(Alignment.Center), tint = Color.White.copy(alpha = .25f))
    }
}

@Composable
private fun TextPage(f: File) {
    val text by produceState<String?>(null, f) { value = withContext(Dispatchers.IO) { runCatching { f.bufferedReader().use { r -> val b = CharArray(400_000); val n = r.read(b); if (n <= 0) "" else String(b, 0, n) } }.getOrDefault("Couldn't read this file") } }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp).background(Color(0xFF14181E))) {
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 80.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            if (img != null) Image(img, "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            else Box(Modifier.fillMaxWidth().aspectRatio(.7f).background(Color(0xFF1C2128)))
        }
    }
}

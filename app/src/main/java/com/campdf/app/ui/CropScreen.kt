package com.campdf.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.campdf.app.AppViewModel
import com.campdf.app.Imaging
import com.campdf.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val INSET = floatArrayOf(0.08f, 0.08f, 0.92f, 0.08f, 0.92f, 0.92f, 0.08f, 0.92f)
private val FULL = floatArrayOf(0.03f, 0.03f, 0.97f, 0.03f, 0.97f, 0.97f, 0.03f, 0.97f)

@Composable
fun CropScreen(vm: AppViewModel) {
    val path = vm.cropSourcePath ?: return
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val img by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) { Imaging.loadOriented(path, 1800)?.asImageBitmap() }
    }
    val pts = remember(path) {
        mutableStateListOf(Offset(0.05f, 0.05f), Offset(0.95f, 0.05f), Offset(0.95f, 0.95f), Offset(0.05f, 0.95f))
    }
    var ready by remember(path) { mutableStateOf(false) }
    var autoFailed by remember(path) { mutableStateOf(false) }
    val editCorners = vm.cropEditIndex?.let { vm.pages.getOrNull(it)?.corners }

    fun setCorners(a: FloatArray) {
        if (a.size < 8) return
        for (i in 0 until 4) pts[i] = Offset(a[2 * i].coerceIn(0f, 1f), a[2 * i + 1].coerceIn(0f, 1f))
    }

    fun validQuad(candidate: List<Offset>): Boolean {
        var sign = 0
        var area2 = 0f
        for (i in 0 until 4) {
            val a = candidate[i]
            val b = candidate[(i + 1) % 4]
            val d = candidate[(i + 2) % 4]
            val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            if (abs(cross) < 0.0005f) return false
            val nextSign = if (cross > 0) 1 else -1
            if (sign != 0 && sign != nextSign) return false
            sign = nextSign
            area2 += a.x * b.y - b.x * a.y
        }
        return abs(area2) * 0.5f >= 0.025f
    }

    LaunchedEffect(path) {
        ready = false
        val init: FloatArray = when {
            editCorners != null -> editCorners.toFloatArray()
            vm.autoDetect -> {
                val d = withContext(Dispatchers.Default) { Imaging.detectCornersOrNull(path) }
                autoFailed = d == null
                d ?: INSET
            }
            else -> FULL
        }
        setCorners(init)
        ready = true
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenTopBar(stringResource(R.string.crop_title), onBack = { vm.cancelCrop() })

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val im = img
            if (im == null || !ready) {
                CircularProgressIndicator()
            } else {
                var boxSize by remember(path) { mutableStateOf(IntSize.Zero) }
                var active by remember(path) { mutableIntStateOf(-1) }
                val hitPx = with(density) { 56.dp.toPx() }
                val primary = c.primary
                Box(
                    Modifier
                        .aspectRatio(im.width.toFloat() / im.height.toFloat())
                        .onSizeChanged { boxSize = it }
                        .pointerInput(path) {
                            detectDragGestures(
                                onDragStart = { o ->
                                    var best = -1
                                    var bd = hitPx
                                    for (i in 0 until 4) {
                                        val p = Offset(pts[i].x * boxSize.width, pts[i].y * boxSize.height)
                                        val d = (p - o).getDistance()
                                        if (d < bd) { bd = d; best = i }
                                    }
                                    active = best
                                },
                                onDragEnd = { active = -1 },
                                onDragCancel = { active = -1 },
                                onDrag = { change, drag ->
                                    if (active >= 0 && boxSize.width > 0 && boxSize.height > 0) {
                                        change.consume()
                                        val current = pts.toList()
                                        val p = current[active]
                                        val proposed = current.toMutableList().apply {
                                            this[active] = Offset(
                                                (p.x + drag.x / boxSize.width).coerceIn(0f, 1f),
                                                (p.y + drag.y / boxSize.height).coerceIn(0f, 1f)
                                            )
                                        }
                                        // Do not let handles cross or create a degenerate crop.
                                        if (validQuad(proposed)) pts[active] = proposed[active]
                                    }
                                }
                            )
                        }
                ) {
                    Image(im, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    Canvas(Modifier.fillMaxSize()) {
                        val p = pts.map { Offset(it.x * size.width, it.y * size.height) }
                        val quad = Path().apply {
                            moveTo(p[0].x, p[0].y)
                            lineTo(p[1].x, p[1].y)
                            lineTo(p[2].x, p[2].y)
                            lineTo(p[3].x, p[3].y)
                            close()
                        }
                        val shade = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(0f, 0f, size.width, size.height))
                            addPath(quad)
                        }
                        drawPath(shade, Color.Black.copy(alpha = 0.48f))
                        // Rule-of-thirds guides make alignment and page margins easier to judge.
                        for (t in 1..2) {
                            val f = t / 3f
                            val left = Offset(p[0].x + (p[3].x - p[0].x) * f, p[0].y + (p[3].y - p[0].y) * f)
                            val right = Offset(p[1].x + (p[2].x - p[1].x) * f, p[1].y + (p[2].y - p[1].y) * f)
                            val top = Offset(p[0].x + (p[1].x - p[0].x) * f, p[0].y + (p[1].y - p[0].y) * f)
                            val bottom = Offset(p[3].x + (p[2].x - p[3].x) * f, p[3].y + (p[2].y - p[3].y) * f)
                            drawLine(Color.White.copy(alpha = 0.55f), left, right, 1.dp.toPx())
                            drawLine(Color.White.copy(alpha = 0.55f), top, bottom, 1.dp.toPx())
                        }
                        drawPath(quad, Color.Black.copy(alpha = 0.55f), style = Stroke(width = 5.dp.toPx()))
                        drawPath(quad, primary, style = Stroke(width = 2.5.dp.toPx()))
                        p.forEach {
                            drawCircle(Color.Black.copy(alpha = 0.45f), 19.dp.toPx(), it)
                            drawCircle(Color.White, 15.dp.toPx(), it)
                            drawCircle(primary, 11.dp.toPx(), it)
                            drawCircle(Color.White, 3.dp.toPx(), it)
                        }
                    }
                    // Magnifier: the finger hides the corner being dragged, so show it 3x zoomed.
                    if (active >= 0 && boxSize.width > 0 && boxSize.height > 0) {
                        Canvas(Modifier.fillMaxSize()) {
                            val cp = pts[active]
                            val r = 56.dp.toPx()
                            val zoom = 3f
                            val px = cp.x * size.width
                            val py = cp.y * size.height
                            val lx = px.coerceIn(r, (size.width - r).coerceAtLeast(r))
                            var ly = py - r * 2.1f
                            if (ly - r < 0f) ly = py + r * 2.1f
                            ly = ly.coerceIn(r, (size.height - r).coerceAtLeast(r))
                            val srcW = ((2f * r / zoom) * im.width / size.width).coerceAtMost(im.width.toFloat())
                            val srcH = ((2f * r / zoom) * im.height / size.height).coerceAtMost(im.height.toFloat())
                            val srcX = (cp.x * im.width - srcW / 2f).coerceIn(0f, im.width - srcW)
                            val srcY = (cp.y * im.height - srcH / 2f).coerceIn(0f, im.height - srcH)
                            val circle = Path().apply { addOval(Rect(lx - r, ly - r, lx + r, ly + r)) }
                            drawCircle(Color.Black, r + 3.dp.toPx(), Offset(lx, ly))
                            clipPath(circle) {
                                drawImage(
                                    im,
                                    srcOffset = IntOffset(srcX.roundToInt(), srcY.roundToInt()),
                                    srcSize = IntSize(srcW.roundToInt().coerceAtLeast(1), srcH.roundToInt().coerceAtLeast(1)),
                                    dstOffset = IntOffset((lx - r).roundToInt(), (ly - r).roundToInt()),
                                    dstSize = IntSize((2f * r).roundToInt(), (2f * r).roundToInt())
                                )
                                // crosshair on the exact corner position
                                val hx = lx - r + ((cp.x * im.width - srcX) / srcW) * 2f * r
                                val hy = ly - r + ((cp.y * im.height - srcY) / srcH) * 2f * r
                                drawLine(primary, Offset(hx - 14.dp.toPx(), hy), Offset(hx + 14.dp.toPx(), hy), 1.5.dp.toPx())
                                drawLine(primary, Offset(hx, hy - 14.dp.toPx()), Offset(hx, hy + 14.dp.toPx()), 1.5.dp.toPx())
                            }
                            drawCircle(Color.White, r, Offset(lx, ly), style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                }
            }
        }

        if (autoFailed && ready) {
            Text(
                stringResource(R.string.auto_failed_hint),
                color = c.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SecondaryButton(stringResource(R.string.auto), Modifier.weight(1f), enabled = ready) {
                scope.launch {
                    val a = withContext(Dispatchers.Default) { Imaging.detectCornersOrNull(path) }
                    autoFailed = a == null
                    setCorners(a ?: INSET)
                }
            }
            SecondaryButton(stringResource(R.string.full_page), Modifier.weight(1f), enabled = ready) { setCorners(FULL) }
            PrimaryButton(stringResource(R.string.confirm), Modifier.weight(1.4f), enabled = ready) {
                val out = FloatArray(8)
                for (i in 0 until 4) {
                    out[2 * i] = pts[i].x
                    out[2 * i + 1] = pts[i].y
                }
                vm.confirmCrop(out)
            }
        }
    }
}

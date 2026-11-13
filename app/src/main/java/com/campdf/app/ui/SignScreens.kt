package com.campdf.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campdf.app.AppViewModel
import com.campdf.app.R
import com.campdf.app.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val INK = Color(0xFF0B1F6B)

@Composable
fun SignPadScreen(vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { 4.dp.toPx() }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenTopBar(stringResource(R.string.sig_pad_title), onBack = { vm.onBack() })
        Box(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDragEnd = {
                            if (current.isNotEmpty()) strokes.add(current)
                            current = emptyList()
                        },
                        onDragCancel = {
                            if (current.isNotEmpty()) strokes.add(current)
                            current = emptyList()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            current = current + change.position
                        }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val all = strokes.toList() + listOf(current)
                for (s in all) {
                    if (s.size > 1) {
                        val p = Path()
                        p.moveTo(s[0].x, s[0].y)
                        for (k in 1 until s.size) p.lineTo(s[k].x, s[k].y)
                        drawPath(p, INK, style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    } else if (s.size == 1) {
                        drawCircle(INK, strokeWidthPx / 2f, s[0])
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecondaryButton(stringResource(R.string.clear), Modifier.weight(1f), enabled = strokes.isNotEmpty()) {
                strokes.clear()
                current = emptyList()
            }
            PrimaryButton(stringResource(R.string.use_signature), Modifier.weight(1.4f), enabled = strokes.isNotEmpty()) {
                val bmp = renderSignature(strokes.toList(), strokeWidthPx)
                if (bmp != null) vm.saveSignatureAndPlace(bmp)
            }
        }
    }
}

/** Renders strokes into a tightly cropped transparent bitmap (about 900 px wide). */
private fun renderSignature(strokes: List<List<Offset>>, strokePx: Float): Bitmap? {
    val pts = strokes.flatten()
    if (pts.isEmpty()) return null
    val pad = strokePx * 3f
    val minX = pts.minOf { it.x } - pad
    val maxX = pts.maxOf { it.x } + pad
    val minY = pts.minOf { it.y } - pad
    val maxY = pts.maxOf { it.y } + pad
    val w = (maxX - minX).coerceAtLeast(1f)
    val h = (maxY - minY).coerceAtLeast(1f)
    val scale = 900f / w
    val bw = 900
    val bh = (h * scale).roundToInt().coerceIn(1, 3000)
    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(11, 31, 107)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = strokePx * scale
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(11, 31, 107)
        style = android.graphics.Paint.Style.FILL
    }
    for (s in strokes) {
        if (s.size > 1) {
            val p = android.graphics.Path()
            p.moveTo((s[0].x - minX) * scale, (s[0].y - minY) * scale)
            for (k in 1 until s.size) p.lineTo((s[k].x - minX) * scale, (s[k].y - minY) * scale)
            canvas.drawPath(p, paint)
        } else if (s.size == 1) {
            canvas.drawCircle((s[0].x - minX) * scale, (s[0].y - minY) * scale, strokePx * scale / 2f, dot)
        }
    }
    return bmp
}

@Composable
fun SignPlaceScreen(vm: AppViewModel) {
    val page = vm.pages.getOrNull(vm.selected) ?: return
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val preview by rememberPageImage(page, 1400)
    val sig by produceState<ImageBitmap?>(initialValue = null, vm.signatureVersion) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(vm.repo.signatureFile.absolutePath)?.asImageBitmap()
        }
    }
    var fx by remember { mutableFloatStateOf(0.7f) }
    var fy by remember { mutableFloatStateOf(0.85f) }
    var fw by remember { mutableFloatStateOf(0.3f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenTopBar(stringResource(R.string.place_signature), onBack = { vm.onBack() })
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val im = preview
            if (im == null) {
                CircularProgressIndicator()
            } else {
                Box(
                    Modifier
                        .aspectRatio(im.width.toFloat() / im.height.toFloat())
                        .onSizeChanged { boxSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                if (boxSize.width > 0 && boxSize.height > 0) {
                                    fx = (fx + drag.x / boxSize.width).coerceIn(0f, 1f)
                                    fy = (fy + drag.y / boxSize.height).coerceIn(0f, 1f)
                                }
                            }
                        }
                ) {
                    Image(im, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    val s = sig
                    if (s != null && boxSize.width > 0) {
                        val wPx = fw * boxSize.width
                        val hPx = wPx * s.height / s.width
                        Image(
                            s,
                            null,
                            Modifier
                                .offset { IntOffset((fx * boxSize.width - wPx / 2f).roundToInt(), (fy * boxSize.height - hPx / 2f).roundToInt()) }
                                .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() }),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.sig_drag_hint), color = c.onSurfaceVariant, fontSize = 12.sp)
            Slider(value = fw, onValueChange = { fw = it }, valueRange = 0.1f..0.6f)
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecondaryButton(stringResource(R.string.redraw), Modifier.weight(1f)) { vm.screen = Screen.SignPad }
            PrimaryButton(stringResource(R.string.apply), Modifier.weight(1.4f), enabled = sig != null) {
                vm.applySignature(fx, fy, fw)
            }
        }
    }
}

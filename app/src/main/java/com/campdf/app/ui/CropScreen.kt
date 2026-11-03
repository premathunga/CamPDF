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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.campdf.app.AppViewModel
import com.campdf.app.Imaging
import com.campdf.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FULL = floatArrayOf(0.03f, 0.03f, 0.97f, 0.03f, 0.97f, 0.97f, 0.03f, 0.97f)

@Composable
fun CropScreen(vm: AppViewModel) {
    val path = vm.cropSourcePath ?: return
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val img by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) { Imaging.loadOriented(path, 1600)?.asImageBitmap() }
    }
    val pts = remember(path) {
        mutableStateListOf(Offset(0.05f, 0.05f), Offset(0.95f, 0.05f), Offset(0.95f, 0.95f), Offset(0.05f, 0.95f))
    }
    var ready by remember(path) { mutableStateOf(false) }
    val editCorners = vm.cropEditIndex?.let { vm.pages.getOrNull(it)?.corners }

    fun setCorners(a: FloatArray) {
        for (i in 0 until 4) pts[i] = Offset(a[2 * i], a[2 * i + 1])
    }

    LaunchedEffect(path) {
        val init: FloatArray = when {
            editCorners != null -> editCorners.toFloatArray()
            vm.autoDetect -> withContext(Dispatchers.Default) { Imaging.detectCorners(path) }
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
                val hitPx = with(density) { 48.dp.toPx() }
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
                                        val p = pts[active]
                                        pts[active] = Offset(
                                            (p.x + drag.x / boxSize.width).coerceIn(0f, 1f),
                                            (p.y + drag.y / boxSize.height).coerceIn(0f, 1f)
                                        )
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
                        drawPath(quad, primary.copy(alpha = 0.16f))
                        drawPath(quad, primary, style = Stroke(width = 2.dp.toPx()))
                        p.forEach {
                            drawCircle(Color.White, 14.dp.toPx(), it)
                            drawCircle(primary, 11.dp.toPx(), it)
                            drawCircle(Color.White, 3.5.dp.toPx(), it)
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SecondaryButton(
                stringResource(R.string.auto),
                Modifier.weight(1f),
                enabled = ready
            ) {
                scope.launch {
                    val a = withContext(Dispatchers.Default) { Imaging.detectCorners(path) }
                    setCorners(a)
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

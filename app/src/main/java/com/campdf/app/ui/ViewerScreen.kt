package com.campdf.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campdf.app.AppViewModel
import com.campdf.app.PdfHolder
import com.campdf.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** In-app PDF viewer: scrolls through all pages, zoom with the buttons or by double-tapping a page. */
@Composable
fun ViewerScreen(vm: AppViewModel) {
    val doc = vm.viewerDoc ?: return
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val config = LocalConfiguration.current

    val holder = remember(doc.file.absolutePath, doc.file.lastModified()) {
        runCatching { PdfHolder(doc.file) }.getOrNull()
    }
    DisposableEffect(holder) { onDispose { holder?.close() } }

    var zoom by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()
    val screenPx = with(density) { config.screenWidthDp.dp.toPx() }
    val widthPx = (screenPx * zoom).toInt().coerceIn(200, 2400)
    val widthDp = with(density) { widthPx.toDp() }

    Column(Modifier.fillMaxSize().background(c.surfaceContainerLowest)) {
        ScreenTopBar(doc.name, onBack = { vm.onBack() }) {
            if (holder != null) {
                Text(
                    "${listState.firstVisibleItemIndex + 1}/${holder.pageCount}",
                    color = c.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            IconButton(onClick = { sharePdf(ctx, vm, doc.file) }) { Icon(Icons.Rounded.Share, null) }
            IconButton(onClick = { vm.editDoc(doc) }) { Icon(Icons.Rounded.Edit, null, tint = c.primary) }
            IconButton(onClick = { openPdf(ctx, vm, doc.file) }) { Icon(Icons.Rounded.OpenInNew, null) }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (holder == null) {
                Text(
                    stringResource(R.string.viewer_error),
                    color = c.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
            } else {
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.width(widthDp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(holder.pageCount) { index ->
                            PdfPageView(holder, index, widthPx) { zoom = if (zoom > 1f) 1f else 2f }
                        }
                    }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RoundBtn(Icons.Rounded.ZoomIn) { zoom = (zoom + 0.5f).coerceAtMost(2.5f) }
                    RoundBtn(Icons.Rounded.ZoomOut) { zoom = (zoom - 0.5f).coerceAtLeast(1f) }
                }
            }
        }
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(c.surfaceContainerHigh)
            .border(1.dp, c.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = c.onSurface)
    }
}

@Composable
private fun PdfPageView(holder: PdfHolder, index: Int, widthPx: Int, onDoubleTap: () -> Unit) {
    val img by produceState<ImageBitmap?>(initialValue = null, holder, index, widthPx) {
        value = withContext(Dispatchers.IO) { holder.render(index, widthPx)?.asImageBitmap() }
    }
    val base = Modifier
        .fillMaxWidth()
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
    val im = img
    if (im != null) {
        Image(im, null, base.background(Color.White), contentScale = ContentScale.FillWidth)
    } else {
        Box(base.aspectRatio(0.707f).background(Color.White), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}

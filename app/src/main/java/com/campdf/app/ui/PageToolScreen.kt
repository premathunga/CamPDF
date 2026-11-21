package com.campdf.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campdf.app.AppViewModel
import com.campdf.app.PageImages
import com.campdf.app.PageToolMode
import com.campdf.app.PageSource
import com.campdf.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v3.6 Extract pages / Reorder pages on a saved document.
 *  - Extract: tap the pages to copy into a new document.
 *  - Reorder: tap the pages in their new order (1, 2, 3...); pages not tapped keep their order at the end.
 */
@Composable
fun PageToolScreen(vm: AppViewModel) {
    val st = vm.pageTool ?: return
    val c = MaterialTheme.colorScheme
    val extract = st.mode == PageToolMode.EXTRACT
    var picked by remember(st) { mutableStateOf(listOf<Int>()) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenTopBar(stringResource(if (extract) R.string.tool_extract else R.string.tool_reorder), onBack = { vm.onBack() }) {
            if (picked.isNotEmpty()) {
                TextButton(onClick = { picked = emptyList() }) { Text(stringResource(R.string.clear)) }
            } else if (extract) {
                TextButton(onClick = { picked = (0 until st.pages.count).toList() }) { Text(stringResource(R.string.select_all)) }
            }
        }
        Text(
            st.doc.name + "  ·  " + stringResource(if (extract) R.string.extract_hint else R.string.reorder_hint),
            color = c.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(st.pages.count) { i ->
                val pos = picked.indexOf(i)
                PageCell(st.pages, i, pos, extract) {
                    picked = if (pos >= 0) picked - i else picked + i
                }
            }
        }
        val n = picked.size
        PrimaryButton(
            if (extract) stringResource(R.string.extract_n, n) else stringResource(R.string.save_order),
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            icon = if (extract) Icons.Rounded.ContentCut else Icons.Rounded.SwapVert,
            enabled = n > 0
        ) {
            if (extract) vm.extractPages(picked)
            else vm.reorderPages(picked + (0 until st.pages.count).filter { it !in picked })
        }
    }
}

@Composable
private fun PageCell(src: PageSource, index: Int, pos: Int, extract: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    // v3.9: each thumbnail reads only its own page from the file
    val img by produceState<ImageBitmap?>(null, src, index) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                PageImages.decode(src.page(index), 300)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val box = remember(src, index) { runCatching { src.box(index) }.getOrNull() }
    val aspect = if (box != null && box[1] > 0f) box[0] / box[1] else 0.707f
    val shape = RoundedCornerShape(10.dp)
    val on = pos >= 0
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.4f, 2.5f))
                .clip(shape)
                .background(androidx.compose.ui.graphics.Color.White)
                .border(if (on) 3.dp else 1.dp, if (on) c.primary else c.outlineVariant, shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val im = img
            if (im != null) Image(im, null, Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Fit)
            else CircularProgressIndicator(Modifier.size(22.dp))
            if (on) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(26.dp).clip(CircleShape).background(c.primary),
                    contentAlignment = Alignment.Center
                ) {
                    if (extract) androidx.compose.material3.Icon(Icons.Rounded.Check, null, tint = c.onPrimary, modifier = Modifier.size(16.dp))
                    else Text("${pos + 1}", color = c.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.padding(top = 3.dp)) {
            Text("${index + 1}", fontSize = 11.sp, color = c.onSurfaceVariant)
        }
    }
}

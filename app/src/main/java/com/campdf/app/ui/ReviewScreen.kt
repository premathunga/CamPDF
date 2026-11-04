package com.campdf.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campdf.app.AppViewModel
import com.campdf.app.FilterMode
import com.campdf.app.PageItem
import com.campdf.app.R

@Composable
fun ReviewScreen(vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    val pages = vm.pages
    val sel = vm.selected.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val page = pages.getOrNull(sel)
    val preview by rememberPageImage(page, 1400)
    var filterOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenTopBar(stringResource(R.string.review_title), onBack = { vm.onBack() }) {
            Text(
                stringResource(R.string.page_x_of_y, sel + 1, pages.size),
                color = c.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 12.dp)
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(c.surfaceContainer)
                .border(1.dp, c.outlineVariant, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            val im = preview
            if (im != null) {
                Image(im, null, Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
            } else {
                CircularProgressIndicator()
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { index, p ->
                Thumb(p, index, index == sel) { vm.selected = index }
            }
            item {
                Box(
                    Modifier
                        .size(64.dp, 86.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, c.outline, RoundedCornerShape(12.dp))
                        .clickable { vm.startScan() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AddAPhoto, null, tint = c.primary, modifier = Modifier.size(28.dp))
                }
            }
        }

        if (filterOpen && page != null) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(FilterMode.values().toList()) { mode ->
                    FilterThumb(page, mode, page.filter == mode) { vm.setFilter(mode) }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            ToolButton(Icons.Rounded.AddAPhoto, stringResource(R.string.add_page)) { vm.startScan() }
            ToolButton(Icons.Rounded.RotateRight, stringResource(R.string.rotate)) { vm.rotateSelected() }
            ToolButton(Icons.Rounded.Crop, stringResource(R.string.crop)) { vm.editCrop() }
            ToolButton(
                Icons.Rounded.AutoFixHigh,
                stringResource(R.string.filter),
                tint = if (filterOpen) c.primary else c.onSurface
            ) { filterOpen = !filterOpen }
            ToolButton(Icons.Rounded.Edit, stringResource(R.string.sign)) { vm.openSign() }
            ToolButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.move_left), enabled = sel > 0) { vm.moveSelected(-1) }
            ToolButton(
                Icons.AutoMirrored.Rounded.ArrowForward,
                stringResource(R.string.move_right),
                enabled = sel < pages.size - 1
            ) { vm.moveSelected(1) }
            ToolButton(Icons.Rounded.Delete, stringResource(R.string.delete), tint = c.error) { vm.deleteSelected() }
        }

        PrimaryButton(
            if (vm.editingDoc != null) stringResource(R.string.save_changes) else stringResource(R.string.create_pdf, pages.size),
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            icon = Icons.Rounded.PictureAsPdf,
            enabled = pages.isNotEmpty()
        ) { vm.createPdf() }
    }

    if (vm.showDiscard) {
        AlertDialog(
            onDismissRequest = { vm.showDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_msg)) },
            confirmButton = {
                TextButton(onClick = { vm.finish() }) { Text(stringResource(R.string.discard), color = c.error) }
            },
            dismissButton = {
                TextButton(onClick = { vm.showDiscard = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun filterLabel(mode: FilterMode): String = stringResource(
    when (mode) {
        FilterMode.ORIGINAL -> R.string.filter_original
        FilterMode.LIGHTEN -> R.string.filter_lighten
        FilterMode.ENHANCE -> R.string.filter_enhance
        FilterMode.NO_SHADOW -> R.string.filter_noshadow
        FilterMode.BW -> R.string.filter_bw
    }
)

@Composable
private fun FilterThumb(page: PageItem, mode: FilterMode, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val img by rememberPageImage(page.copy(filter = mode), 200)
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(64.dp, 84.dp)
                .clip(shape)
                .background(c.surfaceContainerHigh)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.outlineVariant, shape)
        ) {
            val im = img
            if (im != null) Image(im, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            filterLabel(mode),
            fontSize = 11.sp,
            color = if (selected) c.primary else c.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun Thumb(page: PageItem, index: Int, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val img by rememberPageImage(page, 220)
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(64.dp, 86.dp)
            .clip(shape)
            .background(c.surfaceContainerHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.outlineVariant, shape)
            .clickable(onClick = onClick)
    ) {
        val im = img
        if (im != null) Image(im, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Text(
            "${index + 1}",
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(CircleShape)
                .background(if (selected) c.primary else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

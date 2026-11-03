package com.campdf.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campdf.app.AppViewModel
import com.campdf.app.Imaging
import com.campdf.app.PageItem
import com.campdf.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    var m = modifier
        .clip(shape)
        .background(c.surfaceContainer)
        .border(1.dp, c.outlineVariant, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m, content = content)
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White
        )
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, c.outlineVariant),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = c.surfaceContainerHigh,
            contentColor = c.onSurface
        )
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val color = if (enabled) tint else tint.copy(alpha = 0.38f)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(min = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, fontSize = 12.sp, color = color, maxLines = 1)
    }
}

@Composable
fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        actions()
    }
}

@Composable
fun rememberPageImage(page: PageItem?, maxDim: Int, withFilter: Boolean = true): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, page, maxDim, withFilter) {
        value = if (page == null) null else withContext(Dispatchers.Default) {
            Imaging.pageBitmap(page, maxDim, withFilter)?.asImageBitmap()
        }
    }

@Composable
fun rememberFileImage(file: File?, maxDim: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, file, file?.lastModified()) {
        value = if (file == null) null else withContext(Dispatchers.Default) {
            Imaging.loadOriented(file.absolutePath, maxDim)?.asImageBitmap()
        }
    }

fun openPdf(ctx: Context, vm: AppViewModel, file: File) {
    val uri = vm.repo.uri(file)
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, R.string.no_viewer, Toast.LENGTH_SHORT).show()
    }
}

fun sharePdf(ctx: Context, vm: AppViewModel, file: File) {
    val uri = vm.repo.uri(file)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, null))
}

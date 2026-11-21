package com.campdf.app.ui

import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campdf.app.AppViewModel
import com.campdf.app.DocItem
import com.campdf.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * v3.11 "PDF created" screen, redesigned: success header (glowing check over a soft wave), a document card
 * (thumbnail with PDF badge, name with rename, pages / size / date), three quality tiles, and the actions -
 * Share, Save to device, Open (in CamPDF's own viewer - the file does not leave the app), Lock with password.
 */
@Composable
fun ExportScreen(vm: AppViewModel) {
    val doc = vm.exported ?: return
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var lockOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    LockPdfHost(vm, if (lockOpen) doc else null) { lockOpen = false }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openOutputStream(uri)!!.use { out -> doc.file.inputStream().use { it.copyTo(out) } }
                    }.isSuccess
                }
                Toast.makeText(ctx, ctx.getString(if (ok) R.string.saved_ok else R.string.error_generic), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        WaveBackdrop(220.dp)
        Column(Modifier.fillMaxSize()) {
            // top row: back to the pages (left), Done (right)
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { vm.onBack() },
                    modifier = Modifier.clip(CircleShape).background(c.surfaceContainerHigh.copy(alpha = 0.8f))
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = c.onSurface) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.finish() }) {
                    Text(stringResource(R.string.done), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            // v3.12: the details scroll (only if the phone is small); the actions are fixed at the bottom so
            // Share / Save / Open / Lock are always on screen, above the navigation bar
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SuccessBadge()
                Text(
                    stringResource(R.string.pdf_created_ok),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = c.onBackground,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.export_ready), fontSize = 14.sp, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                DocCard(doc, onRename = { renameOpen = true })
                Spacer(Modifier.height(10.dp))
                QualityCard(vm.exportQuality) { vm.changeQuality(it) }
                Spacer(Modifier.height(10.dp))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.background)
                    .navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp)
            ) {
                Button(
                    onClick = { sharePdf(ctx, vm, doc.file) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)
                ) {
                    Icon(Icons.Rounded.Share, null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.share), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(Icons.Rounded.Save, stringResource(R.string.save_to_phone), Modifier.weight(1f)) {
                        saver.launch(vm.repo.sanitize(doc.name) + ".pdf")
                    }
                    ActionTile(Icons.Rounded.OpenInNew, stringResource(R.string.open), Modifier.weight(1f)) { vm.viewExported() }
                    ActionTile(Icons.Rounded.Lock, stringResource(R.string.lock_title), Modifier.weight(1f)) { lockOpen = true }
                }
            }
        }
    }

    if (renameOpen) RenameDialog(doc.name, onDismiss = { renameOpen = false }) { n ->
        renameOpen = false
        if (n.isNotBlank() && n != doc.name) vm.renameExported(n)
    }
}

/** Big check in a glowing circle with a few sparkles. */
@Composable
private fun SuccessBadge() {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(c.primary.copy(alpha = 0.45f), Color.Transparent), center, size.minDimension / 2f),
                size.minDimension / 2f
            )
            drawCircle(c.primary.copy(alpha = 0.35f), size.minDimension * 0.36f, center, style = Stroke(3.dp.toPx()))
        }
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c.primary, c.primaryContainer), Offset(0f, 0f), Offset(250f, 250f))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(42.dp))
        }
        Icon(Icons.Rounded.AutoAwesome, null, tint = c.primary.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).size(17.dp))
        Icon(Icons.Rounded.AutoAwesome, null, tint = c.primary.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 14.dp, start = 4.dp).size(12.dp))
    }
}

@Composable
private fun DocCard(doc: DocItem, onRename: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val thumb by rememberFileImage(doc.thumb, 400)
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(74.dp, 96.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                val t = thumb
                if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Rounded.PictureAsPdf, null, tint = c.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
                Text(
                    "PDF",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(topStart = 8.dp))
                        .background(c.primary)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(Modifier.width(1.dp).height(80.dp).background(c.outlineVariant))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        doc.name + ".pdf",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = c.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Edit, stringResource(R.string.rename), tint = c.primary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                val pages = if (doc.pages == 1) stringResource(R.string.page_one) else stringResource(R.string.pages_many, doc.pages)
                InfoLine(Icons.Rounded.Layers, pages)
                InfoLine(Icons.Rounded.Storage, Formatter.formatShortFileSize(ctx, doc.size))
                val d = Date(doc.modified)
                InfoLine(Icons.Rounded.Event, DateFormat.getMediumDateFormat(ctx).format(d) + " · " + DateFormat.getTimeFormat(ctx).format(d))
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.onSurfaceVariant, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QualityCard(selected: Int, onPick: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, tint = c.onSurface, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.export_quality), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.onSurface)
                    Text(stringResource(R.string.export_quality_d), fontSize = 12.sp, color = c.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityTile(Icons.Rounded.Diamond, stringResource(R.string.quality_high), stringResource(R.string.q_high_sub), selected == 0, Modifier.weight(1f)) { onPick(0) }
                QualityTile(Icons.Rounded.Balance, stringResource(R.string.quality_bal), stringResource(R.string.q_bal_sub), selected == 1, Modifier.weight(1f)) { onPick(1) }
                QualityTile(Icons.Rounded.Bolt, stringResource(R.string.quality_compact), stringResource(R.string.q_compact_sub), selected == 2, Modifier.weight(1f)) { onPick(2) }
            }
        }
    }
}

@Composable
private fun QualityTile(icon: ImageVector, title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (on) c.primary.copy(alpha = 0.18f) else c.surfaceContainerHigh)
                .border(if (on) 2.dp else 1.dp, if (on) c.primary else c.outlineVariant, shape)
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = if (on) c.primary else c.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 11.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        if (on) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(c.primary),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Check, null, tint = c.onPrimary, modifier = Modifier.size(14.dp)) }
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    // v3.12: icon above a two-line label, so three fit side by side (Save, Open, Lock)
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(c.surfaceContainer)
            .border(1.dp, c.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = c.onSurface, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = c.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text(stringResource(R.string.file_name)) },
                singleLine = true
            )
        },
        confirmButton = { TextButton(onClick = { onOk(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

package com.campdf.app.ui

import android.content.ContentValues
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.twotone.AddPhotoAlternate
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.Badge
import androidx.compose.material.icons.twotone.Collections
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.FileCopy
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Gesture
import androidx.compose.material.icons.twotone.Print
import androidx.compose.material.icons.twotone.SwapVert
import androidx.compose.material.icons.twotone.UnfoldLess
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campdf.app.AppViewModel
import com.campdf.app.DocItem
import com.campdf.app.PageToolMode
import com.campdf.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** One tool of the home screen's Tools row; [color] = the tool's own accent (icon + tinted circle). */
class ToolItem(val icon: ImageVector, val label: Int, val color: Color, val section: Int = R.string.sec_edit, val onClick: () -> Unit)

/**
 * v3.5.2 tool accents: one colour per tool, readable on the dark and the light theme. The icon is drawn in the
 * colour (two-tone Material icon: the filled part is a lighter shade of the same colour) inside a circle tinted
 * with it - our own style, no icons copied from other apps.
 */
object ToolColors {
    val Teal = Color(0xFF19C3A6)
    val Blue = Color(0xFF4C8DF6)
    val Green = Color(0xFF34C759)
    val Coral = Color(0xFFFF7A59)
    val Amber = Color(0xFFF5B83D)
    val Violet = Color(0xFF9B7BF7)
    val Sky = Color(0xFF29B6F6)
    val Slate = Color(0xFF8FA3BF)
}

/** One tool: round badge tinted with the tool's colour (soft ring around it), name below (up to two lines). */
@Composable
fun ToolTile(t: ToolItem, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = t.onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // v3.12: a glowing ring + gradient-filled circle in the tool's colour (the new design)
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().clip(CircleShape).background(t.color.copy(alpha = 0.10f)))
            Box(
                Modifier
                    .size(47.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(t.color.copy(alpha = 0.42f), t.color.copy(alpha = 0.14f))))
                    .border(1.5.dp, t.color.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(t.icon, null, tint = t.color, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(t.label),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            color = c.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(28.dp)
        )
    }
}

/** Tiles in rows of [cols] equal columns (the last row is left-aligned). */
@Composable
private fun ToolRows(tools: List<ToolItem>, cols: Int = 4) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        tools.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { ToolTile(it, Modifier.weight(1f)) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * v3.8 home screen tools: two rows of four, like CamScanner - the first seven tools and "All", which opens the
 * Tools page with every tool.
 */
@Composable
fun ToolsGrid(tools: List<ToolItem>, onAll: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.tools), Icons.Rounded.GridView)
        val shown = if (tools.size <= 8) tools
            else tools.take(7) + ToolItem(Icons.TwoTone.Apps, R.string.tools_all, ToolColors.Slate, onClick = onAll)
        ToolRows(shown)
    }
}

/** v3.8 Tools page: every tool, grouped. */
@Composable
fun ToolsScreen(vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    val tools = rememberTools(vm, vm.sortedDocs(vm.docs))
    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenTopBar(stringResource(R.string.tools), onBack = { vm.onBack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            listOf(R.string.sec_scan, R.string.sec_convert, R.string.sec_edit, R.string.sec_util).forEach { sec ->
                val group = tools.filter { it.section == sec }
                if (group.isNotEmpty()) {
                    // v3.12: each section is a card with an icon header (the new design)
                    Spacer(Modifier.height(10.dp))
                    SectionHeader(
                        stringResource(sec),
                        when (sec) {
                            R.string.sec_scan -> Icons.Rounded.CameraAlt
                            R.string.sec_convert -> Icons.Rounded.SwapHoriz
                            R.string.sec_edit -> Icons.Rounded.Edit
                            else -> Icons.Rounded.Build
                        }
                    )
                    AppCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Box(Modifier.padding(vertical = 8.dp)) { ToolRows(group) }
                    }
                }
            }
        }
    }
}

/** What a tool does with the document the user picks. */
private enum class DocAction { SIGN, LOCK, EXTRACT, REORDER, COMPRESS, PRINT, IMAGES }

/**
 * v3.6 all tools of the home screen, in this order: ID card, Import images, Import files, PDF to images, Sign,
 * Merge, Extract pages, Reorder pages, Lock, Compress, Print. Also shows the dialogs the tools need (document
 * picker, merge picker, compress options, images ready) - call it once from the screen.
 */
@Composable
fun rememberTools(vm: AppViewModel, docs: List<DocItem>): List<ToolItem> {
    val ctx = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    var action by remember { mutableStateOf<DocAction?>(null) }
    var mergeOpen by remember { mutableStateOf(false) }
    var lockDoc by remember { mutableStateOf<DocItem?>(null) }
    var compressDoc by remember { mutableStateOf<DocItem?>(null) }
    var images by remember { mutableStateOf<List<File>>(emptyList()) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importPdfs(uris)
    }

    fun needDocs(a: DocAction) {
        if (docs.isEmpty()) Toast.makeText(ctx, R.string.empty_title, Toast.LENGTH_SHORT).show() else action = a
    }

    action?.let { a ->
        DocPickerDialog(docs, onPick = { d ->
            action = null
            when (a) {
                DocAction.SIGN -> vm.signDoc(d)
                DocAction.LOCK -> lockDoc = d
                DocAction.EXTRACT -> vm.openPageTool(d, PageToolMode.EXTRACT)
                DocAction.REORDER -> vm.openPageTool(d, PageToolMode.REORDER)
                DocAction.COMPRESS -> compressDoc = d
                DocAction.PRINT -> printPdf(ctx, d.file, d.name)
                DocAction.IMAGES -> vm.pdfToImages(d) { images = it }
            }
        }, onDismiss = { action = null })
    }
    if (mergeOpen) {
        MergePickerDialog(docs, onMerge = { mergeOpen = false; vm.mergeDocs(it) }, onDismiss = { mergeOpen = false })
    }
    compressDoc?.let { d -> CompressDialog(d, onPick = { level -> compressDoc = null; vm.compressDoc(d, level) }, onDismiss = { compressDoc = null }) }
    if (images.isNotEmpty()) ImagesReadyDialog(vm, images) { images = emptyList() }
    LockPdfHost(vm, lockDoc) { lockDoc = null }

    return listOf(
        ToolItem(Icons.TwoTone.Badge, R.string.tool_id_card, ToolColors.Teal, R.string.sec_scan) { vm.startIdCard() },
        ToolItem(Icons.TwoTone.AddPhotoAlternate, R.string.tool_import_images, ToolColors.Sky, R.string.sec_scan) { imagePicker.launch("image/*") },
        ToolItem(Icons.TwoTone.FolderOpen, R.string.tool_import_files, ToolColors.Blue, R.string.sec_scan) { pdfPicker.launch(arrayOf("application/pdf")) },
        ToolItem(Icons.TwoTone.Collections, R.string.tool_pdf_images, ToolColors.Teal, R.string.sec_convert) { needDocs(DocAction.IMAGES) },
        ToolItem(Icons.TwoTone.Gesture, R.string.tool_sign, ToolColors.Green) { needDocs(DocAction.SIGN) },
        ToolItem(Icons.TwoTone.FileCopy, R.string.tool_merge, ToolColors.Blue) {
            if (docs.size < 2) Toast.makeText(ctx, R.string.merge_need_two, Toast.LENGTH_SHORT).show() else mergeOpen = true
        },
        ToolItem(Icons.TwoTone.ContentCut, R.string.tool_extract, ToolColors.Violet) { needDocs(DocAction.EXTRACT) },
        ToolItem(Icons.TwoTone.SwapVert, R.string.tool_reorder, ToolColors.Blue) { needDocs(DocAction.REORDER) },
        ToolItem(Icons.TwoTone.Lock, R.string.tool_password, ToolColors.Green) { needDocs(DocAction.LOCK) },
        ToolItem(Icons.TwoTone.UnfoldLess, R.string.tool_compress, ToolColors.Amber) { needDocs(DocAction.COMPRESS) },
        ToolItem(Icons.TwoTone.Print, R.string.tool_print, ToolColors.Coral, R.string.sec_util) { needDocs(DocAction.PRINT) }
    )
}

/** Merge: tap documents in the order they should follow each other (numbers show the order). */
@Composable
private fun MergePickerDialog(docs: List<DocItem>, onMerge: (List<DocItem>) -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var order by remember { mutableStateOf(listOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_merge)) },
        text = {
            Column {
                Text(stringResource(R.string.merge_hint), fontSize = 13.sp, color = c.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(docs, key = { it.file.name }) { d ->
                        val k = d.file.name
                        val pos = order.indexOf(k)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (pos >= 0) c.primaryContainer else Color.Transparent)
                                .clickable { order = if (pos >= 0) order - k else order + k }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape)
                                    .background(if (pos >= 0) c.primary else c.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                if (pos >= 0) Text("${pos + 1}", color = c.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(d.name, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMerge(order.mapNotNull { k -> docs.firstOrNull { it.file.name == k } }) }, enabled = order.size >= 2) {
                Text(stringResource(R.string.merge_n, order.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Compress: two levels, with the current size shown. */
@Composable
private fun CompressDialog(doc: DocItem, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var level by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.TwoTone.UnfoldLess, null, tint = ToolColors.Amber) },
        title = { Text(stringResource(R.string.tool_compress)) },
        text = {
            Column {
                Text(
                    doc.name + "  ·  " + android.text.format.Formatter.formatShortFileSize(ctx, doc.size),
                    color = c.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                listOf(R.string.compress_share to R.string.compress_share_d, R.string.compress_min to R.string.compress_min_d)
                    .forEachIndexed { i, (t, d) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { level = i }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = level == i, onClick = { level = i })
                            Column {
                                Text(stringResource(t), color = c.onSurface)
                                Text(stringResource(d), color = c.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                    }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.compress_note), fontSize = 12.sp, color = c.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(level) }) { Text(stringResource(R.string.tool_compress)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** PDF to images: the JPEGs are ready - share them, or save them to the phone's gallery (Pictures/CamPDF). */
@Composable
private fun ImagesReadyDialog(vm: AppViewModel, files: List<File>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.TwoTone.Collections, null, tint = ToolColors.Teal) },
        title = { Text(stringResource(R.string.images_ready, files.size)) },
        text = { Text(stringResource(R.string.images_ready_d)) },
        confirmButton = {
            TextButton(onClick = { shareImages(ctx, vm, files); onClose() }) { Text(stringResource(R.string.share)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    TextButton(onClick = {
                        val n = saveImagesToGallery(ctx, files)
                        Toast.makeText(ctx, ctx.getString(R.string.images_saved, n), Toast.LENGTH_SHORT).show()
                        onClose()
                    }) { Text(stringResource(R.string.save_to_gallery)) }
                }
            }
        }
    )
}

private fun shareImages(ctx: Context, vm: AppViewModel, files: List<File>) {
    val uris = ArrayList(files.map { vm.repo.uri(it) })
    val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/jpeg"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        val clip = ClipData.newRawUri(files[0].name, uris[0])
        for (k in 1 until uris.size) clip.addItem(ClipData.Item(uris[k]))
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, null))
}

/** Android 10+: into Pictures/CamPDF through MediaStore (no storage permission needed). Returns how many. */
private fun saveImagesToGallery(ctx: Context, files: List<File>): Int {
    if (android.os.Build.VERSION.SDK_INT < 29) return 0
    var n = 0
    for (f in files) {
        runCatching {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CamPDF")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            n++
        }
    }
    return n
}

/** Print: hands the PDF to Android's print screen (the user chooses the printer or "Save as PDF"). */
fun printPdf(ctx: Context, file: File, name: String) {
    val pm = ctx.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.LayoutResultCallback, extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder("$name.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true
            )
        }

        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.WriteResultCallback
        ) {
            try {
                FileInputStream(file).use { i -> FileOutputStream(destination.fileDescriptor).use { i.copyTo(it) } }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
    try {
        pm.print(name, adapter, null)
    } catch (_: Exception) {
        Toast.makeText(ctx, R.string.error_generic, Toast.LENGTH_SHORT).show()
    }
}

/** Lets the user pick one of the saved documents (Tools -> Password). */
@Composable
fun DocPickerDialog(docs: List<DocItem>, onPick: (DocItem) -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_doc)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(docs, key = { it.file.name }) { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(d) }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PictureAsPdf, null, tint = c.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(d.name, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/**
 * v3.5 "Lock with password". Keep this composed on the screen (it owns the "save to phone" launcher);
 * the dialog shows while [doc] is not null. The locked copy is shared or saved; the original stays as it is.
 */
@Composable
fun LockPdfHost(vm: AppViewModel, doc: DocItem?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pendingSave
        pendingSave = null
        if (uri != null && f != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                    }.isSuccess
                }
                Toast.makeText(ctx, ctx.getString(if (ok) R.string.locked_saved else R.string.error_generic), Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (doc == null) return

    val c = MaterialTheme.colorScheme
    var pw by remember(doc.file.name) { mutableStateOf("") }
    var pw2 by remember(doc.file.name) { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val tooShort = pw.length < 4
    val mismatch = pw2.isNotEmpty() && pw != pw2
    val ok = !tooShort && pw == pw2
    val vt = if (show) VisualTransformation.None else PasswordVisualTransformation()

    fun go(save: Boolean) {
        if (!ok) return
        val password = pw
        onClose()
        vm.lockPdf(doc, password) { f ->
            if (save) {
                pendingSave = f
                saver.launch(doc.file.name)
            } else {
                sharePdf(ctx, vm, f)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Rounded.Lock, null, tint = c.primary) },
        title = { Text(stringResource(R.string.lock_title)) },
        text = {
            Column {
                Text(doc.name, fontWeight = FontWeight.SemiBold, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it.take(60) },
                    label = { Text(stringResource(R.string.lock_pw)) },
                    singleLine = true,
                    visualTransformation = vt,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.show_password))
                        }
                    },
                    supportingText = { if (pw.isNotEmpty() && tooShort) Text(stringResource(R.string.lock_short)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pw2,
                    onValueChange = { pw2 = it.take(60) },
                    label = { Text(stringResource(R.string.lock_pw2)) },
                    singleLine = true,
                    visualTransformation = vt,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    supportingText = { if (mismatch) Text(stringResource(R.string.lock_mismatch)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.lock_note), fontSize = 12.sp, color = c.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { go(save = false) }, enabled = ok) {
                Icon(Icons.Rounded.Share, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.lock_share))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { go(save = true) }, enabled = ok) {
                    Icon(Icons.Rounded.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.lock_save))
                }
            }
        }
    )
}

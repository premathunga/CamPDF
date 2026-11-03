package com.campdf.app.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.campdf.app.Screen
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var renameDoc by remember { mutableStateOf<DocItem?>(null) }
    var deleteDoc by remember { mutableStateOf<DocItem?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }

    val all = vm.docs
    val list = if (query.isBlank()) all else all.filter { it.name.contains(query, ignoreCase = true) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.all_documents),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = c.onBackground,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { vm.screen = Screen.Settings }) {
                    Icon(Icons.Rounded.Settings, null, tint = c.onSurface)
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceContainer,
                    unfocusedContainerColor = c.surfaceContainer,
                    unfocusedBorderColor = c.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (list.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.Description, null, tint = c.onSurfaceVariant, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(if (all.isEmpty()) R.string.empty_title else R.string.no_results),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.onSurface
                    )
                    if (all.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.empty_desc),
                            color = c.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(list, key = { it.file.name }) { doc ->
                        DocRow(
                            doc,
                            onOpen = { openPdf(ctx, vm, doc.file) },
                            onShare = { sharePdf(ctx, vm, doc.file) },
                            onRename = { renameDoc = doc },
                            onDelete = { deleteDoc = doc }
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrimaryButton(
                stringResource(R.string.scan),
                Modifier.weight(1.2f).height(60.dp),
                icon = Icons.Rounded.CameraAlt,
                onClick = { vm.startScan() }
            )
            SecondaryButton(
                stringResource(R.string.import_image),
                Modifier.weight(1f).height(60.dp),
                icon = Icons.Rounded.PhotoLibrary,
                onClick = { picker.launch("image/*") }
            )
        }
    }

    renameDoc?.let { doc ->
        var text by remember(doc.file.name) { mutableStateOf(doc.name) }
        AlertDialog(
            onDismissRequest = { renameDoc = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) vm.renameDoc(doc, text)
                    renameDoc = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameDoc = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    deleteDoc?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleteDoc = null },
            title = { Text(stringResource(R.string.delete_doc_title)) },
            text = { Text(stringResource(R.string.delete_doc_msg, doc.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDoc(doc)
                    deleteDoc = null
                }) { Text(stringResource(R.string.delete), color = c.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteDoc = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun DocRow(
    doc: DocItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val thumb by rememberFileImage(doc.thumb, 240)
    var menu by remember { mutableStateOf(false) }
    val date = remember(doc.modified) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(doc.modified))
    }
    val pagesText = when {
        doc.pages <= 0 -> ""
        doc.pages == 1 -> stringResource(R.string.page_one)
        else -> stringResource(R.string.pages_many, doc.pages)
    }
    val meta = listOf(date, pagesText, Formatter.formatShortFileSize(ctx, doc.size))
        .filter { it.isNotEmpty() }
        .joinToString(" • ")

    AppCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp, 74.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                val t = thumb
                if (t != null) {
                    Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Rounded.PictureAsPdf, null, tint = c.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    doc.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(meta, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 2)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, null, tint = c.onSurfaceVariant) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = { menu = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = c.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.error) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

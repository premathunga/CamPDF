package com.campdf.app.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
/**
 * v3.12 home (brand header, tools, the 5 newest documents + "See all") and, with [full], the All documents screen
 * (every document, sort, back button). Search and multi-select work on both.
 */
fun HomeScreen(vm: AppViewModel, full: Boolean = false) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var renameDoc by remember { mutableStateOf<DocItem?>(null) }
    var deleteDoc by remember { mutableStateOf<DocItem?>(null) }
    // v3.4 multi-select: long-press a document to start, tap to add / remove, then share or delete them together
    val selected = remember { mutableStateListOf<String>() }
    var deleteSelected by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected.clear() }
    fun toggle(doc: DocItem) {
        val k = doc.file.name
        if (k in selected) selected.remove(k) else selected.add(k)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }

    val all = vm.sortedDocs(vm.docs)
    var sortMenu by remember { mutableStateOf(false) }
    // v3.5 tools: password lock (document chosen from the Tools row or from a document's menu)
    var lockDoc by remember { mutableStateOf<DocItem?>(null) }
    // v3.6: all tools (and the dialogs they need)
    val tools = rememberTools(vm, all)
    // v3.12: home shows the newest few; "See all" opens the full list. A search always looks through everything.
    val recent = remember(vm.docs) { vm.docs.sortedByDescending { it.modified }.take(RECENT_COUNT) }
    val list = when {
        query.isNotBlank() -> all.filter { it.name.contains(query, ignoreCase = true) }
        full -> all
        else -> recent
    }
    // forget selections of documents that no longer exist (deleted / renamed)
    LaunchedEffect(all) { val names = all.map { it.file.name }.toSet(); selected.retainAll { it in names } }
    val selectedDocs = all.filter { it.file.name in selected }

    Box(Modifier.fillMaxSize().background(c.background)) {
        if (!full) WaveBackdrop(230.dp)
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                // selection bar: close, "N selected", select all, share, delete
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selected.clear() }) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel), tint = c.onSurface) }
                    Text(
                        stringResource(R.string.n_selected, selected.size),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val keys = list.map { it.file.name }
                        if (keys.all { it in selected }) selected.removeAll(keys) else keys.forEach { if (it !in selected) selected.add(it) }
                    }) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all), tint = c.onSurface) }
                    if (selected.size >= 2) {
                        // v3.6: merge the selected documents (in list order) into a new one
                        IconButton(onClick = { vm.mergeDocs(selectedDocs); selected.clear() }) {
                            Icon(Icons.Rounded.FileCopy, stringResource(R.string.tool_merge), tint = c.onSurface)
                        }
                    }
                    // v3.8: print the selected documents (several are printed as one job)
                    IconButton(onClick = { vm.printDocs(selectedDocs) { f, name -> printPdf(ctx, f, name) } }) {
                        Icon(Icons.Rounded.Print, stringResource(R.string.tool_print), tint = c.onSurface)
                    }
                    IconButton(onClick = { sharePdfs(ctx, vm, selectedDocs.map { it.file }) }) {
                        Icon(Icons.Rounded.Share, stringResource(R.string.share), tint = c.primary)
                    }
                    IconButton(onClick = { deleteSelected = true }) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.delete), tint = c.error)
                    }
                }
            } else if (full) {
                ScreenTopBar(stringResource(R.string.all_documents), onBack = { vm.onBack() }) {
                // v3.4.3: sort the list by date / name / size (remembered)
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort_by), tint = c.onSurface)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf(
                            R.string.sort_newest, R.string.sort_oldest, R.string.sort_name_az,
                            R.string.sort_name_za, R.string.sort_largest, R.string.sort_smallest
                        ).forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(label),
                                        color = if (vm.sortMode == i) c.primary else c.onSurface,
                                        fontWeight = if (vm.sortMode == i) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                trailingIcon = {
                                    if (vm.sortMode == i) Icon(Icons.Rounded.Check, null, tint = c.primary)
                                },
                                onClick = { vm.updateSortMode(i); sortMenu = false }
                            )
                        }
                    }
                }
                }
            } else {
                // v3.12 brand header: logo + CamPDF, settings; then the page title and a short subtitle
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrandMark(Modifier.weight(1f))
                    IconButton(
                        onClick = { vm.screen = Screen.Settings },
                        modifier = Modifier.clip(CircleShape).background(c.surfaceContainerHigh.copy(alpha = 0.7f))
                    ) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.settings), tint = c.onSurface)
                    }
                }
                Text(
                    stringResource(R.string.all_documents),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = c.onBackground,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
                )
                Text(
                    stringResource(R.string.home_subtitle),
                    fontSize = 14.sp,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp)
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceContainer,
                    unfocusedContainerColor = c.surfaceContainer,
                    unfocusedBorderColor = c.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            // v3.9: the tools scroll away with the list (before they stayed fixed and left little room for the
            // documents on small screens)
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (!selecting && !full) {
                    item(key = "tools") { ToolsGrid(tools) { vm.screen = Screen.Tools } }
                }
                if (!full && all.isNotEmpty()) {
                    item(key = "recent") {
                        SectionHeader(
                            stringResource(if (query.isBlank()) R.string.recent_documents else R.string.search_results),
                            Icons.Rounded.History,
                            action = stringResource(R.string.see_all),
                            onAction = { query = ""; selected.clear(); vm.screen = Screen.Documents }
                        )
                    }
                }
                if (list.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
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
                    }
                }
                items(list, key = { it.file.name }) { doc ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        DocRow(
                            doc,
                            selecting = selecting,
                            isSelected = doc.file.name in selected,
                            onToggle = { toggle(doc) },
                            onOpen = { if (selecting) toggle(doc) else vm.openViewer(doc) },
                            onEdit = { vm.editDoc(doc) },
                            onShare = { sharePdf(ctx, vm, doc.file) },
                            onLock = { lockDoc = doc },
                            onPrint = { printPdf(ctx, doc.file, doc.name) },
                            onRename = { renameDoc = doc },
                            onDelete = { deleteDoc = doc }
                        )
                    }
                }
            }
        }

        if (!selecting) Row(
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
                stringResource(R.string.import_short),
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

    if (deleteSelected) {
        val n = selectedDocs.size
        AlertDialog(
            onDismissRequest = { deleteSelected = false },
            title = { Text(stringResource(R.string.delete_docs_title, n)) },
            text = { Text(stringResource(R.string.delete_docs_msg, n)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDocs(selectedDocs)
                    selected.clear()
                    deleteSelected = false
                }) { Text(stringResource(R.string.delete), color = c.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSelected = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    LockPdfHost(vm, lockDoc) { lockDoc = null }

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
    selecting: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onLock: () -> Unit,
    onPrint: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val thumb by rememberFileImage(doc.thumb, 240)
    var menu by remember { mutableStateOf(false) }
    val date = remember(doc.modified) {
        val d = Date(doc.modified)
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(d) + " • " + DateFormat.getTimeInstance(DateFormat.SHORT).format(d)
    }
    val pagesText = when {
        doc.pages <= 0 -> ""
        doc.pages == 1 -> stringResource(R.string.page_one)
        else -> stringResource(R.string.pages_many, doc.pages)
    }
    val meta = listOf(date, pagesText, Formatter.formatShortFileSize(ctx, doc.size))
        .filter { it.isNotEmpty() }
        .joinToString(" • ")

    AppCard(Modifier.fillMaxWidth(), onClick = onOpen, onLongClick = onToggle, selected = isSelected) {
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
            // v3.8: the checkbox is always there - tap it to select (no need to know about long-press)
            IconButton(onClick = onToggle) {
                Icon(
                    if (isSelected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    stringResource(R.string.select),
                    tint = if (isSelected) c.primary else c.onSurfaceVariant
                )
            }
            if (!selecting) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options), tint = c.onSurfaceVariant) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = { menu = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lock_title)) },
                        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                        onClick = { menu = false; onLock() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tool_print)) },
                        leadingIcon = { Icon(Icons.Rounded.Print, null) },
                        onClick = { menu = false; onPrint() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_pages)) },
                        leadingIcon = { Icon(Icons.Rounded.Description, null) },
                        onClick = { menu = false; onEdit() }
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

private const val RECENT_COUNT = 5

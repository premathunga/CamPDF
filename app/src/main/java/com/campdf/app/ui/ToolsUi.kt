package com.campdf.app.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
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
import com.campdf.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One tool of the home screen's Tools row. */
class ToolItem(val icon: ImageVector, val label: Int, val onClick: () -> Unit)

/**
 * v3.5 Tools row on the home screen: icon + name tiles in one line that scrolls sideways,
 * so more tools can be added later without taking more room from the document list.
 */
@Composable
fun ToolsRow(tools: List<ToolItem>) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.tools),
            color = c.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tools) { t ->
                val shape = RoundedCornerShape(18.dp)
                Column(
                    Modifier
                        .width(84.dp)
                        .clip(shape)
                        .background(c.surfaceContainer)
                        .border(1.dp, c.outlineVariant, shape)
                        .clickable(onClick = t.onClick)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(c.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(t.icon, null, tint = c.onPrimaryContainer, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(t.label),
                        fontSize = 12.sp,
                        color = c.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** The standard tools; [onPassword] asks which document to lock. */
fun standardTools(vm: AppViewModel, onPassword: () -> Unit): List<ToolItem> = listOf(
    ToolItem(Icons.Rounded.Badge, R.string.tool_id_card) { vm.startIdCard() },
    ToolItem(Icons.Rounded.Lock, R.string.tool_password, onPassword)
)

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
                            Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
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

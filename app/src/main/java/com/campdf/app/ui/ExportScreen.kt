package com.campdf.app.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campdf.app.AppViewModel
import com.campdf.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExportScreen(vm: AppViewModel) {
    val doc = vm.exported ?: return
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember(doc.file.name) { mutableStateOf(doc.name) }
    val thumb by rememberFileImage(doc.thumb, 400)

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                            doc.file.inputStream().use { it.copyTo(out) }
                        }
                    }.isSuccess
                }
                Toast.makeText(
                    ctx,
                    ctx.getString(if (ok) R.string.saved_ok else R.string.error_generic),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenTopBar(stringResource(R.string.export_title), onBack = { vm.onBack() })
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(c.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Check, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(46.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.pdf_created), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = c.onBackground)
            Spacer(Modifier.height(18.dp))

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.file_name)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { if (name.isNotBlank()) vm.renameExported(name) },
                            enabled = name.isNotBlank() && name != doc.name
                        ) { Icon(Icons.Rounded.Check, null, tint = c.primary) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(72.dp, 96.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            val t = thumb
                            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Icon(Icons.Rounded.PictureAsPdf, null, tint = c.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            val pagesText = if (doc.pages == 1) stringResource(R.string.page_one)
                            else stringResource(R.string.pages_many, doc.pages)
                            Text(pagesText, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text(Formatter.formatShortFileSize(ctx, doc.size), color = c.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = vm.exportQuality == 0, onClick = { vm.changeQuality(0) }, label = { Text(stringResource(R.string.quality_high)) })
                        FilterChip(selected = vm.exportQuality == 1, onClick = { vm.changeQuality(1) }, label = { Text(stringResource(R.string.quality_bal)) })
                        FilterChip(selected = vm.exportQuality == 2, onClick = { vm.changeQuality(2) }, label = { Text(stringResource(R.string.quality_compact)) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    stringResource(R.string.share),
                    Modifier.weight(1f),
                    icon = Icons.Rounded.Share
                ) { sharePdf(ctx, vm, doc.file) }
                SecondaryButton(
                    stringResource(R.string.save_to_phone),
                    Modifier.weight(1f),
                    icon = Icons.Rounded.Save
                ) { saver.launch(vm.repo.sanitize(name) + ".pdf") }
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                stringResource(R.string.open),
                Modifier.fillMaxWidth(),
                icon = Icons.Rounded.OpenInNew
            ) { openPdf(ctx, vm, doc.file) }
            Spacer(Modifier.height(16.dp))
        }
        PrimaryButton(
            stringResource(R.string.done),
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
        ) { vm.finish() }
    }
}

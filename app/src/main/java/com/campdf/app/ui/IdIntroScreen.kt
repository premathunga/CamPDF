package com.campdf.app.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campdf.app.AppViewModel
import com.campdf.app.IdCard
import com.campdf.app.IdKind
import com.campdf.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun idKindLabel(k: IdKind): Int = when (k) {
    IdKind.NIC -> R.string.id_kind_nic
    IdKind.LICENSE -> R.string.id_kind_license
    IdKind.PASSPORT -> R.string.id_kind_passport
    IdKind.CERTIFICATE -> R.string.id_kind_cert
}

private fun idKindDesc(k: IdKind): Int = when (k) {
    IdKind.NIC, IdKind.LICENSE -> R.string.id_desc_card
    IdKind.PASSPORT -> R.string.id_desc_passport
    IdKind.CERTIFICATE -> R.string.id_desc_cert
}

/**
 * v3.7 ID copy screen (Tools -> ID card): choose what is copied, see where it lands on the A4 sheet (our own
 * drawing of the real layout), and add a "for ... only" watermark with today's date. Then the camera opens.
 */
@Composable
fun IdIntroScreen(vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    val kinds = IdKind.values().toList()
    var kind by remember { mutableStateOf(kinds.getOrElse(vm.prefs.idKind) { IdKind.NIC }) }
    var wmOn by remember { mutableStateOf(vm.prefs.idWatermarkOn) }
    var purpose by remember { mutableStateOf(vm.prefs.idPurpose) }
    val date = remember { SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date()) }
    val wmText = if (purpose.isBlank()) stringResource(R.string.wm_copy_only, date)
        else stringResource(R.string.wm_format, purpose.trim(), date)

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenTopBar(stringResource(R.string.tool_id_card), onBack = { vm.onBack() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // A4 example
            Box(Modifier.height(300.dp).aspectRatio(IdCard.A4_W_MM / IdCard.A4_H_MM)) {
                SheetPreview(kind, if (wmOn) wmText else null)
                Text(
                    stringResource(R.string.a4_example),
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                kinds.forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(stringResource(idKindLabel(k))) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(idKindDesc(kind)), fontSize = 13.sp, color = c.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))

            // watermark
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.wm_title), fontWeight = FontWeight.SemiBold, color = c.onSurface)
                            Text(stringResource(R.string.wm_desc), fontSize = 12.sp, color = c.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = wmOn, onCheckedChange = { wmOn = it })
                    }
                    if (wmOn) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = purpose,
                            onValueChange = { purpose = it.take(50) },
                            label = { Text(stringResource(R.string.wm_purpose)) },
                            placeholder = { Text(stringResource(R.string.wm_purpose_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("“$wmText”", fontSize = 13.sp, color = c.primary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        PrimaryButton(
            stringResource(R.string.id_start),
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            icon = Icons.Rounded.CameraAlt
        ) {
            vm.prefs.idKind = kind.ordinal
            vm.prefs.idWatermarkOn = wmOn
            vm.prefs.idPurpose = purpose.trim()
            vm.startIdScan(kind, if (wmOn) wmText else null)
        }
    }
}

/** The A4 sheet with placeholder cards drawn where the real scans will go (same layout code as the PDF). */
@Composable
private fun SheetPreview(kind: IdKind, watermark: String?) {
    Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))) {
        drawRect(Color.White)
        val mm = size.width / IdCard.A4_W_MM
        val aspects = when {
            kind.fullPage -> listOf(0.72f)
            else -> List(kind.perSheet) { kind.wMm / kind.hMm }
        }
        IdCard.layout(kind, aspects).forEachIndexed { i, r ->
            val tl = Offset(r.left * mm, r.top * mm)
            val sz = Size(r.width() * mm, r.height() * mm)
            if (kind.fullPage) drawCertificate(tl, sz) else drawCard(tl, sz, back = i == 1, passport = kind == IdKind.PASSPORT)
            if (watermark != null) {
                drawIntoCanvas { cv ->
                    IdCard.drawWatermark(cv.nativeCanvas, RectF(tl.x, tl.y, tl.x + sz.width, tl.y + sz.height), watermark, mm)
                }
            }
        }
        drawRect(Color(0x33000000), style = Stroke(1.dp.toPx()))
    }
}

private fun DrawScope.drawCard(tl: Offset, sz: Size, back: Boolean, passport: Boolean) {
    val r = CornerRadius(sz.height * 0.06f)
    drawRoundRect(if (passport) Color(0xFFE7EEF8) else Color(0xFFEAF3EE), tl, sz, r)
    drawRoundRect(Color(0x55000000), tl, sz, r, style = Stroke(1f))
    val pad = sz.height * 0.1f
    val line = Color(0xFFB9C4CF)
    if (!back) {
        // photo + text lines
        val ph = Size(sz.height * 0.55f, sz.height * 0.7f)
        drawRoundRect(Color(0xFFC3CEDA), Offset(tl.x + pad, tl.y + (sz.height - ph.height) / 2f), ph, CornerRadius(3f))
        val x0 = tl.x + pad * 2 + ph.width
        for (k in 0 until 4) {
            val y = tl.y + pad + k * sz.height * 0.18f
            drawRect(line, Offset(x0, y), Size((tl.x + sz.width - pad - x0) * (if (k == 0) 1f else 0.75f), sz.height * 0.06f))
        }
    } else {
        // back: code strip + lines
        drawRect(Color(0xFF9AA6B2), Offset(tl.x + pad, tl.y + pad), Size(sz.width - 2 * pad, sz.height * 0.18f))
        for (k in 0 until 3) {
            drawRect(line, Offset(tl.x + pad, tl.y + sz.height * (0.45f + k * 0.15f)), Size((sz.width - 2 * pad) * 0.7f, sz.height * 0.06f))
        }
    }
}

private fun DrawScope.drawCertificate(tl: Offset, sz: Size) {
    drawRect(Color(0xFFF6F3EA), tl, sz)
    drawRect(Color(0xFFC9B98A), tl, sz, style = Stroke(2f))
    val pad = sz.width * 0.1f
    drawRect(Color(0xFFB9C4CF), Offset(tl.x + sz.width * 0.25f, tl.y + pad), Size(sz.width * 0.5f, sz.height * 0.03f))
    for (k in 0 until 9) {
        drawRect(Color(0xFFD0D6DC), Offset(tl.x + pad, tl.y + pad * 2 + k * sz.height * 0.06f), Size(sz.width - 2 * pad, sz.height * 0.012f))
    }
    drawCircle(Color(0xFFE2B8B8), sz.width * 0.08f, Offset(tl.x + sz.width * 0.75f, tl.y + sz.height * 0.82f))
}

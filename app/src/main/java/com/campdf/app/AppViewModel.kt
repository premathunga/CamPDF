package com.campdf.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    val repo = DocRepo(app)
    private val cache: File = app.cacheDir

    var screen by mutableStateOf(if (prefs.welcomeSeen) Screen.Home else Screen.Welcome)

    // scan session
    val pages = mutableStateListOf<PageItem>()
    var selected by mutableIntStateOf(0)
    val cropQueue = mutableStateListOf<String>()
    var cropEditIndex by mutableStateOf<Int?>(null)
    private var fromCamera = false
    /** v3.5: ID card session - pages are card sides, the PDF puts two per A4 sheet at real size. */
    var idMode by mutableStateOf(false)

    // library
    var docs by mutableStateOf<List<DocItem>>(emptyList())
    var exported by mutableStateOf<DocItem?>(null)

    // ui state
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var showDiscard by mutableStateOf(false)
    var exportQuality by mutableIntStateOf(prefs.quality)
    var signatureVersion by mutableIntStateOf(0)
    var viewerDoc by mutableStateOf<DocItem?>(null)
    var editingDoc by mutableStateOf<DocItem?>(null)

    // settings
    var themeMode by mutableIntStateOf(prefs.theme)
    var defaultQuality by mutableIntStateOf(prefs.quality)
    var autoDetect by mutableStateOf(prefs.autoDetect)
    var autoCapture by mutableStateOf(prefs.autoCapture)
    var removeFingers by mutableStateOf(prefs.removeFingers)
    var appLock by mutableStateOf(prefs.appLock)
    var sortMode by mutableIntStateOf(prefs.sortMode)

    private var idCounter = System.currentTimeMillis()

    init {
        Imaging.removeFingers = prefs.removeFingers
        // v3.5: password-locked copies are only kept until the next start (they were shared / saved already)
        viewModelScope.launch(Dispatchers.IO) { lockedDir().listFiles()?.forEach { it.delete() } }
        refreshDocs()
        // v3.0: optional page-finding network (assets/pagenet.bin, trained with tools/ml). Without the file the
        // app works exactly as before (straight-edge detection only).
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.assets.open("pagenet.bin").use { PageNet.load(it) } }
        }
    }

    private fun err() = getApplication<Application>().getString(R.string.error_generic)

    val cropSourcePath: String?
        get() {
            val e = cropEditIndex
            return if (e != null) pages.getOrNull(e)?.rawPath else cropQueue.firstOrNull()
        }

    val hasSignature: Boolean get() = repo.signatureFile.exists()

    // ---------- navigation ----------

    fun completeWelcome() {
        prefs.welcomeSeen = true
        screen = Screen.Home
    }

    fun onBack() {
        when (screen) {
            Screen.Camera -> {
                if (pages.isEmpty()) { clearSession(); screen = Screen.Home } else screen = Screen.Review
            }
            Screen.Crop -> cancelCrop()
            Screen.Review -> if (pages.isEmpty()) screen = Screen.Home else showDiscard = true
            Screen.SignPad, Screen.SignPlace -> screen = Screen.Review
            Screen.Export -> screen = Screen.Review
            Screen.Viewer -> screen = Screen.Home
            Screen.Settings -> screen = Screen.Home
            else -> Unit
        }
    }

    // ---------- viewer / edit existing document ----------

    /** Re-opens a saved PDF for editing: every page is rendered back into an editable page. */
    fun editDoc(doc: DocItem) {
        clearSession()
        viewModelScope.launch {
            busy = true
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val result = ArrayList<PageItem>()
                    val pfd = ParcelFileDescriptor.open(doc.file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    try {
                        for (i in 0 until renderer.pageCount) {
                            renderer.openPage(i).use { page ->
                                val w = 2000
                                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bm.eraseColor(android.graphics.Color.WHITE)
                                page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val id = idCounter++
                                val f = File(cache, "page_$id.jpg")
                                Imaging.saveJpeg(bm, f, 92)
                                bm.recycle()
                                result.add(PageItem(id, f.absolutePath, f.absolutePath, null, 0, FilterMode.ORIGINAL))
                            }
                        }
                    } finally {
                        runCatching { renderer.close() }
                        runCatching { pfd.close() }
                    }
                    result
                }.getOrNull()
            }
            busy = false
            if (loaded.isNullOrEmpty()) { message = err(); return@launch }
            pages.addAll(loaded)
            editingDoc = doc
            selected = 0
            screen = Screen.Review
        }
    }

    // ---------- library ----------

    fun openViewer(doc: DocItem) {
        viewerDoc = doc
        screen = Screen.Viewer
    }

    fun refreshDocs() {
        viewModelScope.launch { docs = withContext(Dispatchers.IO) { repo.list() } }
    }

    fun deleteDoc(doc: DocItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(doc.file) }
            refreshDocs()
        }
    }

    /** v3.4: deletes several documents at once (multi-select on the home screen). */
    fun deleteDocs(list: List<DocItem>) {
        if (list.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { list.forEach { repo.delete(it.file) } }
            refreshDocs()
        }
    }

    fun renameDoc(doc: DocItem, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.rename(doc.file, name) }
            refreshDocs()
        }
    }

    // ---------- capture / import ----------

    fun startScan() {
        if (pages.isEmpty()) clearSession()
        screen = Screen.Camera
    }

    /** v3.5 Tools -> ID card: scan the front, then the back; both go on one A4 sheet at real size. */
    fun startIdCard() {
        clearSession()
        idMode = true
        screen = Screen.Camera
    }

    fun onCaptured(path: String) {
        fromCamera = true
        cropEditIndex = null
        cropQueue.add(path)
        screen = Screen.Crop
    }

    fun importUris(uris: List<Uri>) {
        if (screen == Screen.Home) clearSession()
        viewModelScope.launch {
            busy = true
            val files = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, u ->
                    runCatching {
                        val f = File(cache, "imp_${System.currentTimeMillis()}_$i.jpg")
                        getApplication<Application>().contentResolver.openInputStream(u)!!.use { input ->
                            FileOutputStream(f).use { out -> input.copyTo(out) }
                        }
                        f.absolutePath
                    }.getOrNull()
                }
            }
            busy = false
            if (files.isEmpty()) { message = err(); return@launch }
            fromCamera = false
            cropEditIndex = null
            cropQueue.addAll(files)
            screen = Screen.Crop
        }
    }

    // ---------- crop ----------

    fun editCrop() {
        if (pages.isEmpty()) return
        cropEditIndex = selected
        screen = Screen.Crop
    }

    fun cancelCrop() {
        if (cropEditIndex != null) {
            cropEditIndex = null
            screen = Screen.Review
            return
        }
        if (cropQueue.isNotEmpty()) cropQueue.removeAt(0)
        if (cropQueue.isNotEmpty()) return
        screen = when {
            pages.isNotEmpty() -> Screen.Review
            fromCamera -> Screen.Camera
            else -> Screen.Home
        }
    }

    fun confirmCrop(c: FloatArray) {
        val raw = cropSourcePath ?: return
        val editIdx = cropEditIndex
        viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val src = Imaging.loadOriented(raw, 3600) ?: error("load")
                    val warped = try { Imaging.warp(src, c, 3508) } finally { src.recycle() }
                    val id = idCounter++
                    val f = File(cache, "page_$id.jpg")
                    try { Imaging.saveJpeg(warped, f, 94) } finally { warped.recycle() }
                    Pair(id, f.absolutePath)
                }.getOrNull()
            }
            busy = false
            if (result == null) { message = err(); return@launch }
            val (id, basePath) = result
            val corners = c.toList()
            if (editIdx != null && editIdx in pages.indices) {
                val old = pages[editIdx]
                pages[editIdx] = old.copy(basePath = basePath, corners = corners, rev = old.rev + 1)
                cropEditIndex = null
                screen = Screen.Review
            } else {
                // ID cards keep their colours (photo, emblem): Enhance instead of the paper-whitening Magic
                pages.add(PageItem(id, raw, basePath, corners, filter = if (idMode) FilterMode.ENHANCE else FilterMode.MAGIC))
                if (cropQueue.isNotEmpty()) cropQueue.removeAt(0)
                if (cropQueue.isEmpty()) {
                    selected = pages.lastIndex
                    if (idMode && pages.size == 1 && fromCamera) {
                        // front done -> straight back to the camera for the back side
                        message = getApplication<Application>().getString(R.string.id_scan_back)
                        screen = Screen.Camera
                    } else {
                        screen = Screen.Review
                    }
                }
            }
        }
    }

    // ---------- page tools ----------

    fun rotateSelected() {
        val i = selected
        pages.getOrNull(i)?.let { pages[i] = it.copy(rotation = (it.rotation + 1) % 4) }
    }

    fun setFilter(mode: FilterMode) {
        val i = selected
        pages.getOrNull(i)?.let { pages[i] = it.copy(filter = mode) }
    }

    /** v3.4.3: gives every page of the scan the filter of the current page (one tap for a 10-page document). */
    fun applyFilterToAll() {
        val mode = pages.getOrNull(selected)?.filter ?: return
        for (i in pages.indices) if (pages[i].filter != mode) pages[i] = pages[i].copy(filter = mode)
        message = getApplication<Application>().getString(R.string.filter_applied_all, pages.size)
    }

    fun deleteSelected() {
        if (pages.isEmpty()) return
        pages.removeAt(selected.coerceIn(0, pages.lastIndex))
        if (pages.isEmpty()) {
            clearSession()
            screen = Screen.Home
        } else {
            selected = selected.coerceAtMost(pages.lastIndex)
        }
    }

    fun moveSelected(delta: Int) {
        val j = selected + delta
        if (selected in pages.indices && j in pages.indices) {
            val p = pages.removeAt(selected)
            pages.add(j, p)
            selected = j
        }
    }

    // ---------- signature ----------

    fun openSign() {
        screen = if (hasSignature) Screen.SignPlace else Screen.SignPad
    }

    fun saveSignatureAndPlace(bm: Bitmap) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                FileOutputStream(repo.signatureFile).use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bm.recycle()
            }
            signatureVersion++
            screen = Screen.SignPlace
        }
    }

    /**
     * v2.8.0: the signature is kept as a stamp on the page (drawn after the filter), not burnt into the
     * scan. A private copy of the current signature is used, so redrawing the signature later does not
     * change pages that were already signed.
     */
    fun applySignature(fx: Float, fy: Float, fw: Float) {
        val idx = selected
        val page = pages.getOrNull(idx) ?: return
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(cache, "page_sig_${idCounter++}.png")
                    repo.signatureFile.copyTo(f, overwrite = true)
                    f.absolutePath
                }.getOrNull()
            }
            if (path == null) { message = err(); return@launch }
            val cur = pages.getOrNull(idx) ?: page
            pages[idx] = cur.copy(stamps = cur.stamps + SigStamp(path, fx, fy, fw), rev = cur.rev + 1)
            screen = Screen.Review
        }
    }

    /** Removes the last signature placed on the selected page. */
    fun undoSignature() {
        val i = selected
        val p = pages.getOrNull(i) ?: return
        if (p.stamps.isEmpty()) return
        pages[i] = p.copy(stamps = p.stamps.dropLast(1), rev = p.rev + 1)
    }

    // ---------- PDF ----------

    /**
     * High: 3508 px / quality 94 for A4-scale printing; Balanced: 3000 px / quality 92;
     * Compact: 2000 px / quality 82. The higher JPEG floors preserve small text and thin strokes.
     */
    private fun buildPdf(target: File, list: List<PageItem>, quality: Int) {
        val (maxDim, jpegQuality) = when (quality) {
            0 -> 3508 to 94
            1 -> 3000 to 92
            else -> 2000 to 82
        }
        if (idMode) {
            // v3.5: two card sides per A4 sheet, true size (300 / 250 / 200 dpi by quality)
            val dpi = when (quality) { 0 -> 300; 1 -> 250; else -> 200 }
            val sheets = list.chunked(2)
            PdfWriter.write(target, sheets.size) { i ->
                val bm = IdCard.sheet(sheets[i], dpi) ?: error("render")
                try {
                    PdfPage(Imaging.toJpeg(bm, jpegQuality), bm.width, bm.height, exactA4 = true)
                } finally {
                    bm.recycle()
                }
            }
            return
        }
        PdfWriter.write(target, list.size) { i ->
            val bm = Imaging.pageBitmap(list[i], maxDim) ?: error("render")
            try {
                PdfPage(Imaging.toJpeg(bm, jpegQuality), bm.width, bm.height)
            } finally {
                bm.recycle()
            }
        }
    }

    /** Write beside the destination, then replace it only after a complete PDF was generated. */
    private fun writePdfSafely(target: File, list: List<PageItem>, quality: Int) {
        val parent = target.parentFile ?: repo.dir
        if (!parent.exists() && !parent.mkdirs()) error("Cannot create PDF directory")
        val temp = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            buildPdf(temp, list, quality)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun createPdf() {
        if (pages.isEmpty()) return
        val snapshot = pages.toList()
        val quality = defaultQuality
        val editing = editingDoc
        viewModelScope.launch {
            busy = true
            val doc = withContext(Dispatchers.IO) {
                runCatching {
                    val file = editing?.file ?: repo.newFile(if (idMode) "ID_" + repo.defaultName().removePrefix("Scan_") else repo.defaultName())
                    writePdfSafely(file, snapshot, quality)
                    val nPages = if (idMode) (snapshot.size + 1) / 2 else snapshot.size
                    val thumbBitmap = if (idMode) IdCard.sheet(snapshot.take(2), 40) else Imaging.pageBitmap(snapshot[0], 360)
                    try { repo.writeMeta(file, nPages, thumbBitmap) } finally { thumbBitmap?.recycle() }
                    DocItem(
                        file, file.nameWithoutExtension, nPages, file.length(), file.lastModified(),
                        File(repo.dir, file.name + ".jpg").takeIf { it.exists() }
                    )
                }.getOrNull()
            }
            busy = false
            if (doc == null) { message = err(); return@launch }
            exportQuality = quality
            exported = doc
            screen = Screen.Export
            refreshDocs()
        }
    }

    // ---------- v3.5 password lock ----------

    private fun lockedDir() = File(getApplication<Application>().filesDir, "locked").apply { mkdirs() }

    /**
     * Makes an AES-256 password-locked copy of [doc] (the original in CamPDF stays unlocked) and hands it to
     * [then] on the main thread - to share it or save it to the phone. The password is not stored anywhere.
     */
    fun lockPdf(doc: DocItem, password: String, then: (File) -> Unit) {
        viewModelScope.launch {
            busy = true
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = lockedDir()
                    dir.listFiles()?.forEach { it.delete() }
                    val dst = File(dir, doc.file.name)
                    PdfCrypt.lock(doc.file, dst, password)
                    dst
                }.getOrNull()
            }
            busy = false
            if (f == null) { message = err(); return@launch }
            then(f)
        }
    }

    fun changeQuality(q: Int) {
        val doc = exported ?: return
        if (q == exportQuality) return
        val snapshot = pages.toList()
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching { writePdfSafely(doc.file, snapshot, q) }.isSuccess
            }
            busy = false
            if (ok) {
                exportQuality = q
                exported = doc.copy(size = doc.file.length(), modified = doc.file.lastModified())
                refreshDocs()
            } else {
                message = err()
            }
        }
    }

    fun renameExported(name: String) {
        val d = exported ?: return
        viewModelScope.launch {
            val nf = withContext(Dispatchers.IO) { repo.rename(d.file, name) }
            exported = d.copy(
                file = nf,
                name = nf.nameWithoutExtension,
                thumb = File(repo.dir, nf.name + ".jpg").takeIf { it.exists() }
            )
            refreshDocs()
        }
    }

    fun finish() {
        clearSession()
        exported = null
        showDiscard = false
        screen = Screen.Home
        refreshDocs()
    }

    private fun clearSession() {
        idMode = false
        pages.clear()
        cropQueue.clear()
        cropEditIndex = null
        editingDoc = null
        selected = 0
        cache.listFiles()?.forEach {
            if (it.name.startsWith("raw_") || it.name.startsWith("imp_") || it.name.startsWith("page_")) it.delete()
        }
    }

    // ---------- settings ----------

    fun setTheme(v: Int) { themeMode = v; prefs.theme = v }
    fun updateDefaultQuality(v: Int) { defaultQuality = v; prefs.quality = v }
    fun updateAutoDetect(v: Boolean) { autoDetect = v; prefs.autoDetect = v }
    fun updateAutoCapture(v: Boolean) { autoCapture = v; prefs.autoCapture = v }
    fun updateRemoveFingers(v: Boolean) {
        removeFingers = v
        prefs.removeFingers = v
        Imaging.removeFingers = v
    }
    fun updateAppLock(v: Boolean) { appLock = v; prefs.appLock = v }
    fun updateSortMode(v: Int) { sortMode = v; prefs.sortMode = v }

    /** v3.4.3: the document list in the order chosen on the home screen. */
    fun sortedDocs(list: List<DocItem>): List<DocItem> = when (sortMode) {
        1 -> list.sortedBy { it.modified }
        2 -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        3 -> list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
        4 -> list.sortedByDescending { it.size }
        5 -> list.sortedBy { it.size }
        else -> list.sortedByDescending { it.modified }
    }

    /**
     * v3.4.3: images shared to CamPDF from another app (WhatsApp, Gallery, Files: "Share -> CamPDF").
     * They go through the normal import (auto edge detection -> crop -> review) as a new scan.
     */
    fun importShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!prefs.welcomeSeen) prefs.welcomeSeen = true
        clearSession()
        screen = Screen.Home
        importUris(uris)
    }
}

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
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    var idKind by mutableStateOf<IdKind?>(null)
    val idMode: Boolean get() = idKind != null
    /** v3.7: "for ... only" text drawn across each ID copy (null = no watermark). */
    var idWatermark by mutableStateOf<String?>(null)

    // library
    var docs by mutableStateOf<List<DocItem>>(emptyList())
    var exported by mutableStateOf<DocItem?>(null)

    // ui state
    var busy by mutableStateOf(false)
    /** v3.9: the lock screen covers the app (the camera must not capture underneath it). */
    var uiLocked by mutableStateOf(false)
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
        viewModelScope.launch(Dispatchers.IO) {
            lockedDir().listFiles()?.forEach { it.delete() }
            exportDir().listFiles()?.forEach { it.delete() }
        }
        refreshDocs()
        restoreSession()
        if (pages.isEmpty()) {
            // nothing to restore: page images left behind by an earlier crash are removed (private photos)
            val started = System.currentTimeMillis()
            viewModelScope.launch(Dispatchers.IO) {
                cache.listFiles()?.forEach {
                    // only files from before this start (a photo shared to CamPDF right now is being copied)
                    if (it.lastModified() < started - 2000) if (it.name.startsWith("raw_") || it.name.startsWith("imp_") || it.name.startsWith("page_") ||
                        it.name == "CamPDF_print.pdf") it.delete()
                }
            }
        }
        viewModelScope.launch {
            // v3.9: the open scan is written to a small file after every change (debounced), so it survives
            // Android closing the app in the background (low-memory phones) - see restoreSession()
            snapshotFlow { sessionSnapshot() }.collectLatest { snap ->
                delay(400)
                withContext(Dispatchers.IO) { runCatching { writeSession(snap) } }
            }
        }
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
            Screen.Viewer -> { val back = if (viewerFromExport) Screen.Export else viewerReturn; viewerFromExport = false; screen = back }
            Screen.Settings -> screen = Screen.Home
            Screen.PageTool -> { closePageTool(); screen = Screen.Home }
            Screen.IdIntro -> screen = Screen.Home
            Screen.Tools -> screen = Screen.Home
            Screen.Documents -> screen = Screen.Home
            else -> Unit
        }
    }

    // ---------- viewer / edit existing document ----------

    /** Re-opens a saved PDF for editing: every page is rendered back into an editable page. */
    fun editDoc(doc: DocItem, thenSign: Boolean = false) {
        clearSession()
        viewModelScope.launch {
            busy = true
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val result = ArrayList<PageItem>()
                    // v3.6: CamPDF's own files give back their original JPEG pages (no re-render, no quality loss)
                    PdfDoc.open(doc.file)?.use { own ->
                        for (i in 0 until own.count) {
                            val id = idCounter++
                            val pg = own.page(i)
                            val (bytes, ext) = PageImages.file(pg)
                            val f = File(cache, "page_$id.$ext")
                            f.writeBytes(bytes)
                            // v3.9: an ID copy sheet (full A4, true size) stays exactly A4 when saved again;
                            // v3.10: a 1-bit page keeps the B&W filter, so it is saved as 1-bit again
                            result.add(PageItem(id, f.absolutePath, f.absolutePath, null, 0,
                                when { pg.bitonal -> FilterMode.BW; pg.mask != null -> FilterMode.MAGIC; else -> FilterMode.ORIGINAL },
                                exactA4 = PdfDoc.isFullA4(own.box(i))))
                        }
                        return@runCatching result
                    }
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
            if (thenSign) openSign() // Tools -> Sign: straight to the signature
        }
    }

    // ---------- library ----------

    fun openViewer(doc: DocItem) {
        viewerDoc = doc
        viewerFromExport = false
        // v3.12: back from the viewer returns to the list it was opened from (home or all documents)
        viewerReturn = if (screen == Screen.Documents) Screen.Documents else Screen.Home
        screen = Screen.Viewer
    }

    /** v3.11: "Open" on the PDF-created screen shows the PDF in CamPDF's own viewer; back returns to that screen. */
    private var viewerFromExport = false
    private var viewerReturn = Screen.Home

    fun viewExported() {
        val d = exported ?: return
        viewerDoc = d
        viewerFromExport = true
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
        screen = Screen.IdIntro
    }

    /** v3.8.1 ID screen -> import: the chosen kind from photos already on the phone (front and back, in order). */
    fun startIdImport(kind: IdKind, watermark: String?, uris: List<Uri>) {
        if (uris.isEmpty()) return
        clearSession()
        idKind = kind
        idWatermark = watermark?.takeIf { it.isNotBlank() }
        importUris(uris)
    }

    /** v3.7 ID screen -> start: scan the chosen kind (sides as needed), watermark text or null. */
    fun startIdScan(kind: IdKind, watermark: String?) {
        clearSession()
        idKind = kind
        idWatermark = watermark?.takeIf { it.isNotBlank() }
        screen = Screen.Camera
    }

    fun onCaptured(path: String) {
        fromCamera = true
        cropEditIndex = null
        cropQueue.add(path)
        screen = Screen.Crop
    }

    fun importUris(uris: List<Uri>) {
        if (screen == Screen.Home || screen == Screen.Tools || screen == Screen.Documents) clearSession()
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
        var sideways = false
        viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val src = Imaging.loadOriented(raw, 3600) ?: error("load")
                    val warped = try { Imaging.warp(src, c, 3508) } finally { src.recycle() }
                    val id = idCounter++
                    val f = File(cache, "page_$id.jpg")
                    // v3.10: the filter is chosen for the page (cards / colour pages: Lighten, documents: Magic)
                    val auto = try {
                        Imaging.saveJpeg(warped, f, 94)
                        // v3.10: a page lying sideways is pointed out (the user turns it with Rotate)
                        if (runCatching { Imaging.looksSideways(warped) }.getOrDefault(false)) sideways = true
                        runCatching { Imaging.autoFilter(warped) }.getOrDefault(FilterMode.MAGIC)
                    } finally { warped.recycle() }
                    Triple(id, f.absolutePath, auto)
                }.getOrNull()
            }
            busy = false
            if (result == null) { message = err(); return@launch }
            val (id, basePath, auto) = result
            if (sideways) message = str(R.string.page_sideways)
            val corners = c.toList()
            if (editIdx != null && editIdx in pages.indices) {
                val old = pages[editIdx]
                pages[editIdx] = old.copy(basePath = basePath, corners = corners, rev = old.rev + 1)
                cropEditIndex = null
                screen = Screen.Review
            } else {
                // v3.8.1 ID copies: Lighten - a plain, colour-true brightening. Magic / Enhance re-balance the light and
                // turned the security print of a laminated NIC into strong orange / yellow (phone test); Lighten kept
                // the card as it looks.
                pages.add(PageItem(id, raw, basePath, corners, filter = if (idMode) FilterMode.LIGHTEN else auto))
                if (cropQueue.isNotEmpty()) cropQueue.removeAt(0)
                if (cropQueue.isEmpty()) {
                    selected = pages.lastIndex
                    if ((idKind?.sides ?: 1) == 2 && pages.size == 1 && fromCamera) {
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

    /**
     * v3.10 Book mode: splits the selected page (a photographed two-page spread) at the book's spine into a left
     * and a right page. The spine is found from the gutter shadow / the gap between the text blocks; text lines
     * that curve towards the spine are straightened (Imaging.dewarpLines).
     */
    fun splitSelected() {
        val i = selected
        val page = pages.getOrNull(i) ?: return
        viewModelScope.launch {
            busy = true
            val made = withContext(Dispatchers.Default) {
                runCatching {
                    var bm = Imaging.loadOriented(page.basePath, 3508) ?: error("load")
                    bm = Imaging.rotate(bm, page.rotation)
                    try {
                        val cut = (Imaging.findSpine(bm) * bm.width).toInt().coerceIn(1, bm.width - 1)
                        listOf(0 to cut, cut to bm.width).map { (x0, x1) ->
                            val cut0 = android.graphics.Bitmap.createBitmap(bm, x0, 0, x1 - x0, bm.height)
                            // the page curls towards the spine: its text lines are straightened (if they bend)
                            val part = runCatching { Imaging.dewarpLines(cut0) }.getOrDefault(cut0)
                            if (part !== cut0 && cut0 !== bm) cut0.recycle()
                            val id = idCounter++
                            val f = File(cache, "page_$id.jpg")
                            try { Imaging.saveJpeg(part, f, 94) } finally { if (part !== bm) part.recycle() }
                            PageItem(id, f.absolutePath, f.absolutePath, null, 0, page.filter)
                        }
                    } finally { bm.recycle() }
                }.getOrNull()
            }
            busy = false
            if (made == null) { message = err(); return@launch }
            pages.removeAt(i)
            pages.addAll(i, made)
            selected = i
            message = str(R.string.split_done)
        }
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
        val kind = idKind
        if (kind != null) {
            // v3.5 / v3.7: ID copies at true size on A4 (300 / 250 / 200 dpi by quality), watermark if chosen
            val dpi = when (quality) { 0 -> 300; 1 -> 250; else -> 200 }
            val sheets = list.chunked(kind.perSheet)
            val wm = idWatermark
            PdfWriter.write(target, sheets.size) { i ->
                val bm = IdCard.sheet(sheets[i], dpi, kind, wm) ?: error("render")
                try {
                    PdfPage(Imaging.toJpeg(bm, jpegQuality), bm.width, bm.height, exactA4 = true)
                } finally {
                    bm.recycle()
                }
            }
            return
        }
        PdfWriter.write(target, list.size) { i ->
            val item = list[i]
            val bm = Imaging.pageBitmap(item, maxDim) ?: error("render")
            try {
                // v3.10: black-and-white pages (no signature in colour on them) are stored with 1 bit per pixel
                when {
                    item.filter == FilterMode.BW && item.stamps.isEmpty() -> PageImages.bitonal(bm, item.exactA4)
                    // v3.10 Compact: documents as MRC (sharp black text + small background), ~2.5x smaller
                    quality == 2 && item.filter == FilterMode.MAGIC && item.stamps.isEmpty() && !item.exactA4 ->
                        PageImages.mrc(bm, 60) ?: PdfPage(Imaging.toJpeg(bm, jpegQuality), bm.width, bm.height, exactA4 = item.exactA4)
                    else -> PdfPage(Imaging.toJpeg(bm, jpegQuality), bm.width, bm.height, exactA4 = item.exactA4)
                }
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
                    val kind = idKind
                    val file = editing?.file ?: repo.newFile(if (kind != null) kind.prefix + repo.defaultName().removePrefix("Scan_") else repo.defaultName())
                    writePdfSafely(file, snapshot, quality)
                    val nPages = if (kind != null) (snapshot.size + kind.perSheet - 1) / kind.perSheet else snapshot.size
                    val thumbBitmap = if (kind != null) IdCard.sheet(snapshot.take(kind.perSheet), 40, kind, idWatermark)
                        else Imaging.pageBitmap(snapshot[0], 360)
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
            // v3.9: further changes (back to Review, Create again) update this file instead of making a copy
            editingDoc = doc
            screen = Screen.Export
            refreshDocs()
        }
    }

    // ---------- v3.6 document tools ----------

    var pageTool by mutableStateOf<PageToolState?>(null)

    private fun exportDir() = File(getApplication<Application>().filesDir, "export").apply { mkdirs() }

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    /**
     * v3.9 pages of a PDF other apps made, rendered at ~200 dpi into JPEG pages one at a time when asked
     * (password-protected files throw SecurityException when opened).
     */
    private class RenderSource(file: File) : PageSource {
        private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        private val renderer = try { PdfRenderer(pfd) } catch (e: Throwable) { pfd.close(); throw e }
        override val count: Int = renderer.pageCount
        private val sizes = arrayOfNulls<FloatArray>(count)

        @Synchronized
        override fun box(i: Int): FloatArray {
            sizes[i]?.let { return it }
            val b = renderer.openPage(i).use { pg -> floatArrayOf(pg.width.toFloat(), pg.height.toFloat()) }
                .let { (pw, ph) -> floatArrayOf(pw, ph, 0f, 0f, pw, ph) }
            sizes[i] = b
            return b
        }

        @Synchronized
        override fun page(i: Int): PdfPage = renderer.openPage(i).use { pg ->
            val pw = pg.width.toFloat() // points
            val ph = pg.height.toFloat()
            val scale = minOf(200f / 72f, 2480f / maxOf(pw, ph))
            val w = (pw * scale).toInt().coerceAtLeast(1)
            val h = (ph * scale).toInt().coerceAtLeast(1)
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bm.eraseColor(android.graphics.Color.WHITE)
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val jpeg = try { Imaging.toJpeg(bm, 90) } finally { bm.recycle() }
            PdfPage(jpeg, w, h, box = floatArrayOf(pw, ph, 0f, 0f, pw, ph))
        }

        override fun close() {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    /** v3.9 pages of a saved document, read one at a time: CamPDF files losslessly, other PDFs rendered. */
    private fun openPages(file: File): PageSource = PdfDoc.open(file) ?: RenderSource(file)

    /**
     * Writes [src] as a document (a new file, or over [target]) with its sidecars; returns it. IO thread.
     * Pages are written one at a time (v3.9: long documents no longer need to fit into memory).
     */
    private fun saveDoc(name: String, src: PageSource, target: File? = null): DocItem {
        require(src.count > 0)
        val file = target ?: repo.newFile(name)
        val parent = file.parentFile ?: repo.dir
        val temp = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        var first: PdfPage? = null
        try {
            PdfWriter.write(temp, src.count) { i -> src.page(i).also { if (i == 0) first = it } }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        val thumb = first?.let { PageImages.decode(it, 360) }
        try { repo.writeMeta(file, src.count, thumb) } finally { thumb?.recycle() }
        return DocItem(
            file, file.nameWithoutExtension, src.count, file.length(), file.lastModified(),
            File(repo.dir, file.name + ".jpg").takeIf { it.exists() }
        )
    }

    /** Runs a document tool off the main thread with the busy overlay; failure = error message. */
    private fun <T> tool(work: () -> T, done: (T) -> Unit) {
        viewModelScope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { runCatching(work) }
            busy = false
            refreshDocs()
            r.onSuccess(done).onFailure {
                message = when (it) {
                    is SecurityException -> str(R.string.pdf_locked_cannot)
                    is OutOfMemoryError -> str(R.string.too_large)
                    else -> err()
                }
            }
        }
    }

    /** Tools -> Import files: PDFs from the phone become CamPDF documents (others' PDFs are rendered to pages). */
    fun importPdfs(uris: List<Uri>) {
        val app = getApplication<Application>()
        tool({
            var n = 0
            for (u in uris) {
                val name = runCatching {
                    app.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull()?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: repo.defaultName()
                val tmp = File(cache, "imp_${System.nanoTime()}.pdf")
                try {
                    app.contentResolver.openInputStream(u)!!.use { i -> FileOutputStream(tmp).use { i.copyTo(it) } }
                    val own = PdfDoc.open(tmp)
                    if (own != null) {
                        // already a CamPDF file: keep it byte for byte
                        own.use { src ->
                            val f = repo.newFile(name)
                            tmp.copyTo(f)
                            val thumb = PageImages.decode(src.page(0), 360)
                            try { repo.writeMeta(f, src.count, thumb) } finally { thumb?.recycle() }
                        }
                    } else {
                        RenderSource(tmp).use { saveDoc(name, it) }
                    }
                    n++
                } finally {
                    tmp.delete()
                }
            }
            n
        }) { n -> message = str(R.string.pdf_imported, n) }
    }

    /** Tools -> Merge files: one new document with all pages of [list], in that order (lossless). */
    fun mergeDocs(list: List<DocItem>) {
        if (list.size < 2) return
        tool({
            ConcatSource(list.map { openPages(it.file) }).use { src ->
                saveDoc("Merged_" + repo.defaultName().removePrefix("Scan_"), src)
            }
        }) { d -> message = str(R.string.merged_ok, list.size, d.pages) }
    }

    /** Tools -> Extract pages / Reorder pages: opens the document's pages and the page picker. */
    fun openPageTool(doc: DocItem, mode: PageToolMode) {
        tool({ openPages(doc.file) }) { src ->
            closePageTool()
            pageTool = PageToolState(doc, mode, src)
            screen = Screen.PageTool
        }
    }

    fun closePageTool() {
        pageTool?.let { st -> viewModelScope.launch(Dispatchers.IO) { runCatching { st.pages.close() } } }
        pageTool = null
    }

    /** New document with the chosen pages (indices in document order). */
    fun extractPages(indices: List<Int>) {
        val st = pageTool ?: return
        if (indices.isEmpty()) return
        val sorted = indices.sorted()
        tool({ saveDoc(st.doc.name + " (" + sorted.joinToString(",") { "${it + 1}" }.take(40) + ")", PickSource(st.pages, sorted)) }) { d ->
            message = str(R.string.extracted_ok, d.pages)
            closePageTool()
            screen = Screen.Home
        }
    }

    /** Rewrites the document with its pages in [order] (indices of the old pages). */
    fun reorderPages(order: List<Int>) {
        val st = pageTool ?: return
        if (order.size != st.pages.count) return
        tool({ saveDoc(st.doc.name, PickSource(st.pages, order), target = st.doc.file) }) {
            message = str(R.string.reordered_ok)
            closePageTool()
            screen = Screen.Home
        }
    }

    /** A source whose pages are made smaller on the fly (compress). */
    private inner class SmallSource(private val src: PageSource, private val maxDim: Int, private val q: Int) : PageSource {
        override val count: Int get() = src.count
        override fun box(i: Int) = src.box(i)
        override fun page(i: Int): PdfPage {
            val p = src.page(i)
            if (p.bitonal || p.mask != null) return p // already tiny (1-bit page / MRC layers)
            val bm = PageImages.decode(p, maxDim * 2) ?: error("decode")
            val s = minOf(1f, maxDim.toFloat() / maxOf(bm.width, bm.height))
            val sc = if (s < 1f) Bitmap.createScaledBitmap(bm, (bm.width * s).toInt().coerceAtLeast(1), (bm.height * s).toInt().coerceAtLeast(1), true) else bm
            if (sc !== bm) bm.recycle()
            val sw = sc.width
            val sh = sc.height
            val jpeg = try { Imaging.toJpeg(sc, q) } finally { sc.recycle() }
            // the page keeps its exact place and size on the sheet (box); an already small page stays as it was
            return if (jpeg.size < p.data.size) PdfPage(jpeg, sw, sh, exactA4 = p.exactA4, box = p.box) else p
        }
        override fun close() = src.close()
    }

    /**
     * Tools -> Compress: a smaller copy. level 0 = for sharing (longest side 1600 px, JPEG 70),
     * level 1 = smallest (1200 px, JPEG 55). Page layout stays exactly the same.
     */
    fun compressDoc(doc: DocItem, level: Int) {
        val (maxDim, q) = if (level == 0) 1600 to 70 else 1200 to 55
        tool({
            val before = doc.file.length()
            val d = SmallSource(openPages(doc.file), maxDim, q).use { saveDoc(doc.name + " (small)", it) }
            before to d
        }) { (before, d) ->
            val ctx = getApplication<Application>()
            message = str(R.string.compressed_ok,
                android.text.format.Formatter.formatShortFileSize(ctx, before),
                android.text.format.Formatter.formatShortFileSize(ctx, d.size))
        }
    }

    /** Tools -> PDF to images: one JPEG per page (export folder, cleared at the next start) for [then]. */
    fun pdfToImages(doc: DocItem, then: (List<File>) -> Unit) {
        tool({
            val dir = exportDir()
            dir.listFiles()?.forEach { it.delete() }
            val base = repo.sanitize(doc.name).replace(' ', '_')
            openPages(doc.file).use { src ->
                List(src.count) { i ->
                    val (bytes, ext) = PageImages.file(src.page(i))
                    File(dir, "${base}_${i + 1}.$ext").also { it.writeBytes(bytes) }
                }
            }
        }, then)
    }

    /**
     * v3.8 Print from the list: one document is printed as it is; several are joined (losslessly, page by page)
     * into one temporary file so they go to the printer as one job. [then] gets the file and a job name.
     */
    fun printDocs(list: List<DocItem>, then: (File, String) -> Unit) {
        if (list.isEmpty()) return
        if (list.size == 1) { then(list[0].file, list[0].name); return }
        tool({
            val f = File(cache, "CamPDF_print.pdf")
            ConcatSource(list.map { openPages(it.file) }).use { PdfDoc.write(f, it) }
            f
        }) { f -> then(f, "CamPDF (${list.size})") }
    }

    /**
     * v3.9 Settings -> Export all documents: copies every PDF into a folder the user picks (phone storage, SD card,
     * USB stick). Documents live only inside CamPDF, so this is the backup before changing or resetting the phone;
     * Tools -> Import files brings them back.
     */
    fun exportAll(tree: Uri) {
        val app = getApplication<Application>()
        tool({
            val cr = app.contentResolver
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
            )
            var n = 0
            for (d in repo.list()) {
                val u = android.provider.DocumentsContract.createDocument(cr, parent, "application/pdf", d.file.name) ?: continue
                cr.openOutputStream(u)!!.use { out -> d.file.inputStream().use { it.copyTo(out) } }
                n++
            }
            n
        }) { n -> message = str(R.string.exported_all, n) }
    }

    /** Tools -> Sign: opens the document's pages for editing and goes straight to the signature. */
    fun signDoc(doc: DocItem) = editDoc(doc, thenSign = true)

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
        idKind = null
        idWatermark = null
        pages.clear()
        cropQueue.clear()
        cropEditIndex = null
        editingDoc = null
        selected = 0
        cache.listFiles()?.forEach {
            if (it.name.startsWith("raw_") || it.name.startsWith("imp_") || it.name.startsWith("page_")) it.delete()
        }
    }

    // ---------- v3.9 unsaved scan survives the app being closed ----------

    private val sessionFile: File get() = File(getApplication<Application>().filesDir, "session.json")

    /** The whole open scan as JSON text (read on the main thread; "" = nothing open). */
    private fun sessionSnapshot(): String {
        if (pages.isEmpty()) return ""
        val o = JSONObject()
        idKind?.let { o.put("idKind", it.name) }
        idWatermark?.let { o.put("idWatermark", it) }
        editingDoc?.let { o.put("editing", it.file.absolutePath) }
        o.put("selected", selected)
        val arr = JSONArray()
        for (p in pages) {
            val j = JSONObject()
            j.put("id", p.id); j.put("raw", p.rawPath); j.put("base", p.basePath)
            p.corners?.let { c -> j.put("corners", JSONArray().apply { c.forEach { put(it.toDouble()) } }) }
            j.put("rot", p.rotation); j.put("filter", p.filter.name); j.put("rev", p.rev); j.put("exactA4", p.exactA4)
            val st = JSONArray()
            for (s in p.stamps) st.put(JSONObject().put("p", s.sigPath).put("fx", s.fx.toDouble()).put("fy", s.fy.toDouble()).put("fw", s.fw.toDouble()))
            j.put("stamps", st)
            arr.put(j)
        }
        o.put("pages", arr)
        return o.toString()
    }

    private fun writeSession(snap: String) {
        val f = sessionFile
        if (snap.isEmpty()) { f.delete(); return }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(snap)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /**
     * Reopens a scan that was not saved yet when Android closed the app (its page images are still in the
     * app's cache): the Review screen comes back with the pages, filters, signatures and ID settings.
     */
    private fun restoreSession() {
        val f = sessionFile
        if (!f.exists() || !prefs.welcomeSeen) return
        runCatching {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("pages")
            val restored = ArrayList<PageItem>()
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val base = j.getString("base")
                if (!File(base).exists()) continue
                val corners = j.optJSONArray("corners")?.let { c -> List(c.length()) { c.getDouble(it).toFloat() } }
                val st = j.optJSONArray("stamps")
                val stamps = if (st == null) emptyList() else List(st.length()) {
                    val s = st.getJSONObject(it)
                    SigStamp(s.getString("p"), s.getDouble("fx").toFloat(), s.getDouble("fy").toFloat(), s.getDouble("fw").toFloat())
                }.filter { File(it.sigPath).exists() }
                restored.add(
                    PageItem(
                        j.getLong("id"), j.getString("raw"), base, corners, j.optInt("rot"),
                        runCatching { FilterMode.valueOf(j.getString("filter")) }.getOrDefault(FilterMode.MAGIC),
                        j.optInt("rev"), stamps, j.optBoolean("exactA4")
                    )
                )
            }
            if (restored.isEmpty()) { f.delete(); return }
            idKind = o.optString("idKind").takeIf { it.isNotEmpty() }?.let { runCatching { IdKind.valueOf(it) }.getOrNull() }
            idWatermark = o.optString("idWatermark").takeIf { it.isNotEmpty() }
            editingDoc = o.optString("editing").takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }?.let {
                DocItem(it, it.nameWithoutExtension, 0, it.length(), it.lastModified(), null)
            }
            pages.addAll(restored)
            selected = o.optInt("selected").coerceIn(0, pages.lastIndex)
            idCounter = maxOf(idCounter, restored.maxOf { it.id } + 1)
            screen = Screen.Review
            message = str(R.string.session_restored, pages.size)
        }.onFailure { f.delete() }
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

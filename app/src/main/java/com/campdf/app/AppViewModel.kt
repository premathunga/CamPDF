package com.campdf.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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

    // library
    var docs by mutableStateOf<List<DocItem>>(emptyList())
    var exported by mutableStateOf<DocItem?>(null)

    // ui state
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var showDiscard by mutableStateOf(false)
    var exportQuality by mutableIntStateOf(prefs.quality)
    var signatureVersion by mutableIntStateOf(0)

    // settings
    var themeMode by mutableIntStateOf(prefs.theme)
    var defaultQuality by mutableIntStateOf(prefs.quality)
    var autoDetect by mutableStateOf(prefs.autoDetect)
    var appLock by mutableStateOf(prefs.appLock)

    private var idCounter = System.currentTimeMillis()

    init { refreshDocs() }

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
            Screen.Settings -> screen = Screen.Home
            else -> Unit
        }
    }

    // ---------- library ----------

    fun refreshDocs() {
        viewModelScope.launch { docs = withContext(Dispatchers.IO) { repo.list() } }
    }

    fun deleteDoc(doc: DocItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(doc.file) }
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
                    val warped = Imaging.warp(src, c, 3508)
                    src.recycle()
                    val id = idCounter++
                    val f = File(cache, "page_$id.jpg")
                    Imaging.saveJpeg(warped, f, 92)
                    warped.recycle()
                    Pair(id, f.absolutePath)
                }.getOrNull()
            }
            busy = false
            if (result == null) { message = err(); return@launch }
            val (id, basePath) = result
            val corners = c.toList()
            if (editIdx != null && editIdx in pages.indices) {
                val old = pages[editIdx]
                pages[editIdx] = old.copy(basePath = basePath, corners = corners, rotation = 0, rev = old.rev + 1)
                cropEditIndex = null
                screen = Screen.Review
            } else {
                pages.add(PageItem(id, raw, basePath, corners))
                if (cropQueue.isNotEmpty()) cropQueue.removeAt(0)
                if (cropQueue.isEmpty()) {
                    selected = pages.lastIndex
                    screen = Screen.Review
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

    fun applySignature(fx: Float, fy: Float, fw: Float) {
        val idx = selected
        val page = pages.getOrNull(idx) ?: return
        viewModelScope.launch {
            busy = true
            val path = withContext(Dispatchers.Default) {
                runCatching {
                    val base = Imaging.pageBitmap(page, 3508, withFilter = false) ?: error("page")
                    val sig = BitmapFactory.decodeFile(repo.signatureFile.absolutePath) ?: error("sig")
                    val out = Imaging.stamp(base, sig, fx, fy, fw)
                    base.recycle()
                    sig.recycle()
                    val f = File(cache, "page_${idCounter++}.jpg")
                    Imaging.saveJpeg(out, f, 92)
                    out.recycle()
                    f.absolutePath
                }.getOrNull()
            }
            busy = false
            if (path == null) { message = err(); return@launch }
            pages[idx] = page.copy(basePath = path, rotation = 0, rev = page.rev + 1)
            screen = Screen.Review
        }
    }

    // ---------- PDF ----------

    private fun buildPdf(target: File, list: List<PageItem>, quality: Int) {
        val (maxDim, jq) = when (quality) {
            0 -> 3508 to 88
            1 -> 2339 to 80
            else -> 1754 to 70
        }
        PdfWriter.write(target, list.size) { i ->
            val bm = Imaging.pageBitmap(list[i], maxDim) ?: error("render")
            val bytes = Imaging.toJpeg(bm, jq)
            val page = PdfPage(bytes, bm.width, bm.height)
            bm.recycle()
            page
        }
    }

    fun createPdf() {
        if (pages.isEmpty()) return
        val snapshot = pages.toList()
        val quality = defaultQuality
        viewModelScope.launch {
            busy = true
            val doc = withContext(Dispatchers.IO) {
                runCatching {
                    val file = repo.newFile(repo.defaultName())
                    buildPdf(file, snapshot, quality)
                    repo.writeMeta(file, snapshot.size, Imaging.pageBitmap(snapshot[0], 360))
                    DocItem(
                        file, file.nameWithoutExtension, snapshot.size, file.length(), file.lastModified(),
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

    fun changeQuality(q: Int) {
        val doc = exported ?: return
        if (q == exportQuality) return
        val snapshot = pages.toList()
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { runCatching { buildPdf(doc.file, snapshot, q) }.isSuccess }
            busy = false
            if (ok) {
                exportQuality = q
                exported = doc.copy(size = doc.file.length())
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
        pages.clear()
        cropQueue.clear()
        cropEditIndex = null
        selected = 0
        cache.listFiles()?.forEach {
            if (it.name.startsWith("raw_") || it.name.startsWith("imp_") || it.name.startsWith("page_")) it.delete()
        }
    }

    // ---------- settings ----------

    fun setTheme(v: Int) { themeMode = v; prefs.theme = v }
    fun updateDefaultQuality(v: Int) { defaultQuality = v; prefs.quality = v }
    fun updateAutoDetect(v: Boolean) { autoDetect = v; prefs.autoDetect = v }
    fun updateAppLock(v: Boolean) { appLock = v; prefs.appLock = v }
}

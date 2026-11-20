package com.campdf.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.campdf.app.AppViewModel
import com.campdf.app.DocDetect
import com.campdf.app.LiveTracker
import com.campdf.app.R
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // v3.5.1: while the photo picker is open the camera keeps running behind it (the picker is a sheet over this
    // screen), so live detection is paused - before, auto-capture took whatever the camera saw meanwhile.
    var galleryOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris) // stay paused: we are leaving the camera for the crop screen
        else galleryOpen = false
    }
    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraBody(vm, galleryOpen, onGallery = { galleryOpen = true; picker.launch("image/*") })
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.camera_perm), color = Color.White, textAlign = TextAlign.Center, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(stringResource(R.string.grant), onClick = { permLauncher.launch(Manifest.permission.CAMERA) })
                Spacer(Modifier.height(12.dp))
                SecondaryButton(stringResource(R.string.cancel), onClick = { vm.onBack() })
            }
        }
    }
}

@Composable
private fun CameraBody(vm: AppViewModel, galleryOpen: Boolean, onGallery: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val c = MaterialTheme.colorScheme

    val previewView = remember {
        PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    // v3.3 live page detection: the outline of the page found in the preview, and how long it has been steady
    val liveOn = vm.autoDetect
    var liveQuad by remember { mutableStateOf<FloatArray?>(null) }
    var frameAspect by remember { mutableStateOf(3f / 4f) } // width / height of the upright analysis frame
    var steady by remember { mutableStateOf(0f) }
    var pageLike by remember { mutableStateOf(false) }
    val tracker = remember { LiveTracker() }
    val paused = remember { AtomicBoolean(false) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor { r -> Thread(r, "CamPDF-live").apply { isDaemon = true } } }

    DisposableEffect(lifecycleOwner, liveOn) {
        val future = ProcessCameraProvider.getInstance(ctx)
        val main = ContextCompat.getMainExecutor(ctx)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                // v3.3.1: ask explicitly for the camera's full 4:3 resolution. With the live-detection stream bound as
                // well, some phones otherwise handed back a much smaller photo (first test: page 1210 px wide instead
                // of ~1900 px, with colour fringes around the ink).
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                    .build()
                // small 4:3 frames (same field of view as the 4:3 preview and photo) for the page detector
                val analysis = if (!liveOn) null else ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { a ->
                        a.setAnalyzer(analysisExecutor) { proxy ->
                            try {
                                if (!paused.get()) {
                                    val frame = proxy.toBitmap()
                                    val rot = proxy.imageInfo.rotationDegrees
                                    val s = 320f / maxOf(frame.width, frame.height)
                                    val m = Matrix().apply { postScale(s, s); postRotate(rot.toFloat()) }
                                    val upright = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
                                    if (upright !== frame) frame.recycle()
                                    val aspect = upright.width.toFloat() / upright.height
                                    // v3.3.1: fast network-only detection (~3x cheaper than the full detector)
                                    val q = runCatching { DocDetect.detectLive(upright) }.getOrNull()
                                    val like = q != null && DocDetect.lastLiveAuto
                                    upright.recycle()
                                    val st = tracker.update(q, SystemClock.elapsedRealtime(), like)
                                    main.execute {
                                        if (!paused.get()) {
                                            liveQuad = st.quad
                                            steady = st.progress
                                            pageLike = st.pageLike
                                            frameAspect = aspect
                                        }
                                    }
                                }
                            } catch (_: Throwable) {
                                // a bad frame is skipped; the next one is analysed
                            } finally {
                                proxy.close()
                            }
                        }
                    }
                provider.unbindAll()
                val boundCamera = try {
                    if (analysis != null) provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                    else provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                } catch (e: Exception) {
                    // some cameras cannot run three streams at once: keep preview + photo, no live outline
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }
                camera = boundCamera
                imageCapture = capture
                // Let the camera settle focus and exposure around the center of the document guide.
                val point = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
                boundCamera.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(point)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                )
            } catch (e: Exception) {
                // camera unavailable; the user can still import images from the gallery
            }
        }, main)
        onDispose {
            runCatching { future.get().unbindAll() }
        }
    }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    // v3.5.1: no live detection / auto-capture while this screen is not in front (picker, another app, screen off)
    // or while the picker is open; on return the steadiness starts from zero.
    var resumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> resumed = false
                Lifecycle.Event.ON_RESUME -> resumed = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // the camera may only take a picture by itself 1.5 s after it opened (time to bring the page into view)
    var openedAt by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    val active = resumed && !galleryOpen && !vm.busy && !vm.uiLocked
    LaunchedEffect(active) {
        if (!capturing) paused.set(!active)
        tracker.reset()
        steady = 0f
        liveQuad = null
        if (active) openedAt = SystemClock.elapsedRealtime()
    }

    // v2.8.0: the activity is locked to portrait, so without this every photo taken with the phone held
    // sideways came out sideways. The orientation sensor tells CameraX how the phone is held; it writes
    // the matching EXIF orientation and loadOriented() turns the page upright. When the phone lies flat
    // over the page the sensor reports UNKNOWN and the last known orientation is kept.
    DisposableEffect(imageCapture) {
        val ic = imageCapture
        val listener = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(orientation: Int) {
                if (ic == null || orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val rot = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (ic.targetRotation != rot) ic.targetRotation = rot
            }
        }
        if (ic != null && listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(torch, camera) {
        camera?.cameraControl?.enableTorch(torch)
    }

    fun capture() {
        val ic = imageCapture ?: return
        if (capturing) return
        capturing = true
        paused.set(true)
        val f = File(ctx.cacheDir, "raw_${System.currentTimeMillis()}.jpg")
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    tracker.reset()
                    vm.onCaptured(f.absolutePath)
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    tracker.reset()
                    paused.set(false)
                    f.delete()
                }
            }
        )
    }

    // auto-capture: the page has been held steady long enough
    val autoCapture = vm.autoCapture
    LaunchedEffect(steady >= 1f, autoCapture, pageLike, active) {
        fun ok() = liveOn && autoCapture && steady >= 1f && !capturing && liveQuad != null && pageLike && active
        if (!ok()) return@LaunchedEffect
        val wait = 1500L - (SystemClock.elapsedRealtime() - openedAt)
        if (wait > 0) delay(wait) // just opened: give the user a moment, then capture if still steady
        if (ok()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            capture()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.onBack() }) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = Color.White) }
            Box(Modifier.weight(1f))
            if (liveOn) {
                // auto-capture switch
                Text(
                    stringResource(R.string.auto),
                    color = if (autoCapture) Color.Black else Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (autoCapture) c.tertiary else Color.White.copy(alpha = 0.18f))
                        .clickable { vm.updateAutoCapture(!autoCapture) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            IconButton(onClick = { torch = !torch }) {
                Icon(
                    if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    stringResource(R.string.flash),
                    tint = if (torch) c.tertiary else Color.White
                )
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            val q = liveQuad
            val primary = c.primary
            Canvas(Modifier.fillMaxSize()) {
                if (q != null) {
                    // the page found in the live preview; it turns green while it is held steady.
                    // The preview is drawn FIT_CENTER: map frame coordinates into the area the picture really fills.
                    val boxAspect = size.width / size.height
                    val pw: Float
                    val ph: Float
                    if (frameAspect > boxAspect) { pw = size.width; ph = size.width / frameAspect } else { ph = size.height; pw = size.height * frameAspect }
                    val ox = (size.width - pw) / 2f
                    val oy = (size.height - ph) / 2f
                    val p = List(4) { Offset(ox + q[2 * it] * pw, oy + q[2 * it + 1] * ph) }
                    val path = Path().apply {
                        moveTo(p[0].x, p[0].y); lineTo(p[1].x, p[1].y); lineTo(p[2].x, p[2].y); lineTo(p[3].x, p[3].y); close()
                    }
                    val col = if (steady > 0f) Color(0xFF34C759) else primary
                    drawPath(path, col.copy(alpha = 0.18f + 0.17f * steady))
                    drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(width = 5.dp.toPx()))
                    drawPath(path, col, style = Stroke(width = 3.dp.toPx()))
                    p.forEach { drawCircle(Color.White, 6.dp.toPx(), it); drawCircle(col, 4.dp.toPx(), it) }
                    return@Canvas
                }
                // No page found (or live detection off): a portrait-page guide with corner brackets and thirds.
                val guideW = size.width * 0.82f
                val guideH = size.height * 0.78f
                val left = (size.width - guideW) / 2f
                val top = (size.height - guideH) / 2f
                val right = left + guideW
                val bottom = top + guideH
                val radius = 14.dp.toPx()
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.32f),
                    topLeft = Offset(left, top),
                    size = Size(guideW, guideH),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
                )
                val corner = 24.dp.toPx()
                val thick = 3.dp.toPx()
                val guideColor = Color.White.copy(alpha = 0.95f)
                drawLine(guideColor, Offset(left, top + corner), Offset(left, top), thick)
                drawLine(guideColor, Offset(left, top), Offset(left + corner, top), thick)
                drawLine(guideColor, Offset(right - corner, top), Offset(right, top), thick)
                drawLine(guideColor, Offset(right, top), Offset(right, top + corner), thick)
                drawLine(guideColor, Offset(left, bottom - corner), Offset(left, bottom), thick)
                drawLine(guideColor, Offset(left, bottom), Offset(left + corner, bottom), thick)
                drawLine(guideColor, Offset(right - corner, bottom), Offset(right, bottom), thick)
                drawLine(guideColor, Offset(right, bottom - corner), Offset(right, bottom), thick)
                for (fraction in listOf(1f / 3f, 2f / 3f)) {
                    val y = top + guideH * fraction
                    val x = left + guideW * fraction
                    drawLine(Color.White.copy(alpha = 0.28f), Offset(left + 8.dp.toPx(), y), Offset(right - 8.dp.toPx(), y), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.28f), Offset(x, top + 8.dp.toPx()), Offset(x, bottom - 8.dp.toPx()), 1.dp.toPx())
                }
            }
            val hint = when {
                !liveOn -> R.string.camera_hint
                capturing -> R.string.hint_capturing
                q == null -> R.string.hint_searching
                autoCapture && pageLike -> R.string.hint_hold_steady
                else -> R.string.hint_page_found
            }
            val kind = vm.idKind
            val idSide = when {
                kind == null -> ""
                kind.sides == 2 -> stringResource(
                    R.string.id_side_fmt, stringResource(idKindLabel(kind)),
                    stringResource(if (vm.pages.isEmpty()) R.string.id_side_front else R.string.id_side_back)
                ) + "  ·  "
                else -> stringResource(idKindLabel(kind)) + "  ·  "
            }
            Text(
                idSide + stringResource(hint),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.58f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onGallery, modifier = Modifier.size(56.dp), enabled = !capturing) {
                Icon(Icons.Rounded.PhotoLibrary, stringResource(R.string.import_image), tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                val shutterLabel = stringResource(R.string.take_photo)
                Box(
                    Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(7.dp)
                        .clip(CircleShape)
                        .background(c.primary)
                        .clickable(enabled = !capturing, onClickLabel = shutterLabel) { capture() }
                        .semantics { contentDescription = shutterLabel },
                    contentAlignment = Alignment.Center
                ) {
                    if (capturing) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(30.dp))
                }
                // auto-capture countdown around the shutter
                if (liveOn && autoCapture && steady > 0f && !capturing) {
                    val green = Color(0xFF34C759)
                    Canvas(Modifier.size(78.dp)) {
                        val sw = 4.dp.toPx()
                        drawArc(
                            color = green,
                            startAngle = -90f,
                            sweepAngle = 360f * steady,
                            useCenter = false,
                            topLeft = Offset(sw / 2, sw / 2),
                            size = Size(size.width - sw, size.height - sw),
                            style = Stroke(width = sw, cap = StrokeCap.Round)
                        )
                    }
                }
            }
            Box(Modifier.size(56.dp))
        }
    }
}

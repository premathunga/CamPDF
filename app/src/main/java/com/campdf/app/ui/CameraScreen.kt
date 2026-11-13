package com.campdf.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.campdf.app.AppViewModel
import com.campdf.app.R
import java.io.File
import java.util.concurrent.TimeUnit

@Composable
fun CameraScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraBody(vm, onGallery = { picker.launch("image/*") })
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
private fun CameraBody(vm: AppViewModel, onGallery: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val c = MaterialTheme.colorScheme

    val previewView = remember {
        PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
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
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { future.get().unbindAll() }
        }
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
        val f = File(ctx.cacheDir, "raw_${System.currentTimeMillis()}.jpg")
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    vm.onCaptured(f.absolutePath)
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    f.delete()
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.onBack() }) { Icon(Icons.Rounded.Close, null, tint = Color.White) }
            Box(Modifier.weight(1f))
            IconButton(onClick = { torch = !torch }) {
                Icon(
                    if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    null,
                    tint = if (torch) c.tertiary else Color.White
                )
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                // A portrait-page guide with corner brackets and thirds, rather than a generic
                // dashed rectangle, gives a more useful framing reference for document capture.
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
            Text(
                stringResource(R.string.camera_hint),
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
                Icon(Icons.Rounded.PhotoLibrary, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Box(
                Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(c.primary)
                    .clickable(enabled = !capturing) { capture() },
                contentAlignment = Alignment.Center
            ) {
                if (capturing) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(30.dp))
            }
            Box(Modifier.size(56.dp))
        }
    }
}

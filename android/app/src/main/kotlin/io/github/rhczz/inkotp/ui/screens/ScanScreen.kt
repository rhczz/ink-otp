package io.github.rhczz.inkotp.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.rhczz.inkotp.platform.copyLuminancePlane
import io.github.rhczz.inkotp.platform.decodeQrFrame
import io.github.rhczz.inkotp.platform.rotateLuminance
import io.github.rhczz.inkotp.ui.components.InkPrimaryButton
import io.github.rhczz.inkotp.ui.components.InkSecondaryButton
import io.github.rhczz.inkotp.ui.components.InkTextButton
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onDetected: (String) -> Unit,
    onGallery: () -> Unit,
    onManual: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = LocalInkColors.current
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val detected = remember { AtomicBoolean(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted && !permissionRequested) {
            permissionRequested = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ScreenScaffold(title = "扫码添加", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(vertical = AppTokens.Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.l),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .height(AppTokens.Size.scanFrame + AppTokens.Spacing.xxxl)
                    .clip(RoundedCornerShape(AppTokens.Radius.card))
                    .background(colors.surfaceSunken),
                contentAlignment = Alignment.Center,
            ) {
                if (permissionGranted && cameraError == null) {
                    CameraQrPreview(
                        attempt = attempt,
                        detected = detected,
                        onDetected = onDetected,
                        onFailure = { cameraError = "无法启动相机，请重试或从图片导入" },
                    )
                    Box(
                        Modifier.size(AppTokens.Size.scanFrame).border(
                            AppTokens.Border.focus,
                            AppTokens.Light.surface,
                            RoundedCornerShape(AppTokens.Radius.card),
                        ),
                    )
                } else {
                    Text(
                        text = cameraError ?: "需要相机权限才能扫码\n也可以从图片导入或手动添加",
                        style = AppType.body,
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(AppTokens.Spacing.xl),
                    )
                }
            }
            Text(
                text = "对准服务提供的二维码，识别过程完全离线。",
                style = AppType.caption,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
            )
            if (!permissionGranted) {
                InkPrimaryButton(text = "重试相机权限", onClick = { permission.launch(Manifest.permission.CAMERA) })
                InkTextButton(
                    text = "前往系统设置",
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                )
            } else if (cameraError != null) {
                InkPrimaryButton(text = "重试相机", onClick = { cameraError = null; attempt++ })
            }
            InkSecondaryButton(text = "从图片导入", onClick = onGallery)
            InkTextButton(text = "手动输入", onClick = onManual)
            Spacer(Modifier.height(AppTokens.Spacing.l))
        }
    }
}

@Composable
private fun CameraQrPreview(
    attempt: Int,
    detected: AtomicBoolean,
    onDetected: (String) -> Unit,
    onFailure: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val latestDetected by rememberUpdatedState(onDetected)
    val latestFailure by rememberUpdatedState(onFailure)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    DisposableEffect(context, lifecycleOwner, rotation, attempt) {
        val disposed = AtomicBoolean(false)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().setTargetRotation(rotation).build()
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var cameraProvider: ProcessCameraProvider? = null
        var lastAnalysisNanos = 0L
        preview.setSurfaceProvider(mainExecutor) { request ->
            if (disposed.get()) request.willNotProvideSurface() else surfaceRequest = request
        }
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val now = SystemClock.elapsedRealtimeNanos()
                if (!disposed.get() && !detected.get() && now - lastAnalysisNanos >= FRAME_INTERVAL_NANOS) {
                    lastAnalysisNanos = now
                    val plane = image.planes.firstOrNull()
                    if (plane != null) {
                        val crop = image.cropRect
                        val frame = copyLuminancePlane(
                            plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride,
                            crop.left, crop.top, crop.width(), crop.height(),
                        )
                        val rotated = rotateLuminance(frame, image.imageInfo.rotationDegrees)
                        val result = try {
                            decodeQrFrame(rotated)
                        } finally {
                            frame.bytes.fill(0)
                            if (rotated !== frame) rotated.bytes.fill(0)
                        }
                        if (result != null && detected.compareAndSet(false, true)) {
                            mainExecutor.execute {
                                if (!disposed.get() && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                    latestDetected(result)
                                } else {
                                    detected.set(false)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // A malformed camera frame is skipped; never include QR data in logs.
            } finally {
                image.close()
            }
        }
        try {
            val providerFuture = ProcessCameraProvider.getInstance(context.applicationContext)
            providerFuture.addListener({
                if (!disposed.get()) {
                    try {
                        val provider = providerFuture.get()
                        cameraProvider = provider
                        // Permission may have been revoked since the composable last checked.
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        } else {
                            latestFailure()
                        }
                    } catch (_: Exception) {
                        if (!disposed.get()) latestFailure()
                    }
                }
            }, mainExecutor)
        } catch (_: Exception) {
            latestFailure()
        }

        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            // This page owns these two use cases only; do not call unbindAll().
            cameraProvider?.unbind(preview, analysis)
            surfaceRequest?.willNotProvideSurface()
            surfaceRequest = null
            analysisExecutor.shutdown()
        }
    }
    surfaceRequest?.let { request ->
        CameraXViewfinder(surfaceRequest = request, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

private const val FRAME_INTERVAL_NANOS = 180_000_000L

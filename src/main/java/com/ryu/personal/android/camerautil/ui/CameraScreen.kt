package com.ryu.personal.android.camerautil.ui

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateSizeAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    backgroundColor: Color = Color.Black,
    previewAlignment: Alignment = Alignment.TopCenter,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val ratio by viewModel.cameraRatio.collectAsState()


    var previewSize by remember { mutableStateOf(DpSize.Zero) }
    val animatedPreviewSize by animateSizeAsState(
        targetValue = with(density) { previewSize.toSize() },
        label = "previewSize",
        finishedListener = {
            viewModel.finishTextureViewSizeChanged()
        }
    )

    DisposableEffect(Unit) {
        viewModel.startPreview()
        onDispose { viewModel.stopPreview() }
    }

    BoxWithConstraints(
        modifier = modifier.background(backgroundColor),
        contentAlignment = previewAlignment
    ) {
        viewModel.getTextureViewSize(context, DpSize(maxWidth, maxHeight), ratio)?.let { size ->
            previewSize = size
            AndroidView(
                modifier =  Modifier
                    .size(with(density) { animatedPreviewSize.toDpSize() })
                    .blur(radius = if (viewModel.onShowPreview.value) 0.dp else 50.dp),
                factory = { viewModel.textureView.value }
            )
        }
        if (!viewModel.onShowPreview.value
            && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
            )
        }
    }
}
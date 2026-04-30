package de.aarondietz.beetmeister.ui.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

internal val BeetMeisterLogoDark = Color(0xFF46663E)
internal val BeetMeisterLogoCenter = Color(0xFFD2E5B6)
internal val BeetMeisterLogoRing = Color(0xFFB7D487)

@Composable
internal fun BeetMeisterLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawCircle(BeetMeisterLogoDark)
        drawCircle(BeetMeisterLogoCenter, radius = size.minDimension * 0.3f)
        drawCircle(
            color = BeetMeisterLogoRing,
            radius = size.minDimension * 0.45f,
            style = Stroke(width = size.minDimension * (8f / 120f)),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFE8F1E3)
@Composable
private fun BeetMeisterLogoPreview() {
    Box(
        modifier = Modifier
            .background(Brush.verticalGradient(colors = listOf(Color(0xFFE8F1E3), Color(0xFFF7F3E8))))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        BeetMeisterLogo(modifier = Modifier.size(160.dp))
    }
}

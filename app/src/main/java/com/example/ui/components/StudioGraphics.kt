package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.DownloaderMode
import com.example.ui.theme.BrandFacebookBlue
import com.example.ui.theme.BrandInstagramPink
import com.example.ui.theme.BrandTikTokBlack
import com.example.ui.theme.BrandYouTubeRed
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonIndigo
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonVioletLight
import com.example.ui.theme.StudioViolet

/**
 * Renders the exact MediaSave gradient Play Triangle + Music Note emblem from Screen 1 & Screen 12.
 */
@Composable
fun MediaSaveBrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 84.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        // Soft outer glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(NeonPurple.copy(alpha = 0.38f), ElectricBlue.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = w * 0.58f
            ),
            radius = w * 0.58f
        )

        // Outer rounded play triangle (Purple -> Blue -> Cyan)
        val outerPlay = Path().apply {
            moveTo(w * 0.18f, h * 0.12f)
            lineTo(w * 0.88f, h * 0.50f)
            lineTo(w * 0.18f, h * 0.88f)
            close()
        }
        drawPath(
            path = outerPlay,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFA855F7), Color(0xFF6366F1), Color(0xFF06B6D4)),
                start = Offset(w * 0.15f, h * 0.1f),
                end = Offset(w * 0.85f, h * 0.9f)
            )
        )

        // Inner golden-orange play triangle facet
        val innerFacet = Path().apply {
            moveTo(w * 0.34f, h * 0.30f)
            lineTo(w * 0.68f, h * 0.50f)
            lineTo(w * 0.34f, h * 0.70f)
            close()
        }
        drawPath(
            path = innerFacet,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFBBF24), Color(0xFFF97316), Color(0xFFEC4899)),
                start = Offset(w * 0.34f, h * 0.30f),
                end = Offset(w * 0.68f, h * 0.70f)
            )
        )

        // Music note stem & head at bottom-right of triangle
        val noteBrush = Brush.linearGradient(
            colors = listOf(Color(0xFF38BDF8), Color(0xFF6366F1)),
            start = Offset(w * 0.5f, h * 0.45f),
            end = Offset(w * 0.75f, h * 0.85f)
        )
        drawCircle(
            brush = noteBrush,
            radius = w * 0.11f,
            center = Offset(w * 0.58f, h * 0.76f)
        )
        drawLine(
            brush = noteBrush,
            start = Offset(w * 0.66f, h * 0.76f),
            end = Offset(w * 0.66f, h * 0.44f),
            strokeWidth = w * 0.065f,
            cap = StrokeCap.Round
        )
        drawLine(
            brush = noteBrush,
            start = Offset(w * 0.66f, h * 0.44f),
            end = Offset(w * 0.79f, h * 0.50f),
            strokeWidth = w * 0.065f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Renders the gradient "MediaSave" title text (Purple "Media" + Cyan/Blue "Save") from Screen 2.
 */
@Composable
fun MediaSaveGradientTitle(
    fontSize: TextUnit = 28.sp,
    modifier: Modifier = Modifier
) {
    val annotated = buildAnnotatedString {
        withStyle(
            SpanStyle(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFC084FC), Color(0xFF818CF8), Color(0xFF38BDF8))
                ),
                fontWeight = FontWeight.ExtraBold,
                fontSize = fontSize
            )
        ) {
            append("MediaSave")
        }
    }
    Text(text = annotated, modifier = modifier)
}

/**
 * Glowing concentric neon rings with Link icon for Screen 3 ("Analyzing Link").
 */
@Composable
fun AnalyzingLinkNeonRing(
    modifier: Modifier = Modifier,
    size: Dp = 210.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "analyzing_ring")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rot"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val center = Offset(w / 2f, w / 2f)

            // Outer ambient glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NeonPurple.copy(alpha = 0.28f), ElectricBlue.copy(alpha = 0.1f), Color.Transparent),
                    center = center,
                    radius = w * 0.48f
                ),
                radius = w * 0.48f
            )

            // Static subtle outer track ring
            drawCircle(
                color = Color(0xFF1E293B),
                radius = w * 0.40f,
                style = Stroke(width = 4.dp.toPx())
            )

            // Rotating neon sweep ring 1
            rotate(degrees = rotation, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            NeonPurple,
                            ElectricBlue,
                            ElectricCyan,
                            Color.Transparent
                        ),
                        center = center
                    ),
                    startAngle = 0f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(w * 0.10f, w * 0.10f),
                    size = Size(w * 0.80f, w * 0.80f),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Counter-rotating inner neon ring
            rotate(degrees = -rotation * 1.3f, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            NeonVioletLight,
                            ElectricBlue,
                            Color.Transparent
                        ),
                        center = center
                    ),
                    startAngle = 45f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(w * 0.20f, w * 0.20f),
                    size = Size(w * 0.60f, w * 0.60f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Icon(
            imageVector = Icons.Default.Link,
            contentDescription = "Analyzing Link",
            tint = ElectricBlue,
            modifier = Modifier.size(54.dp)
        )
    }
}

/**
 * Platform shortcut icon badge matching Screen 2 (YouTube, Facebook, Instagram, TikTok).
 */
@Composable
fun PlatformBrandIconItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    when (name.lowercase()) {
                        "youtube" -> Brush.linearGradient(listOf(BrandYouTubeRed, Color(0xFFCC0000)))
                        "facebook" -> Brush.linearGradient(listOf(BrandFacebookBlue, Color(0xFF0D5BD9)))
                        "instagram" -> Brush.linearGradient(
                            listOf(Color(0xFFFEDA77), Color(0xFFF58529), BrandInstagramPink, Color(0xFF8134AF))
                        )
                        else -> Brush.linearGradient(listOf(BrandTikTokBlack, Color(0xFF1F2937)))
                    }
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            when (name.lowercase()) {
                "youtube" -> {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = name,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                "facebook" -> {
                    Text(
                        text = "f",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                "instagram" -> {
                    Canvas(modifier = Modifier.size(24.dp)) {
                        drawRoundRect(
                            color = Color.White,
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                            style = Stroke(width = 2.2.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 5.dp.toPx(),
                            style = Stroke(width = 2.2.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 1.6.dp.toPx(),
                            center = Offset(size.width * 0.76f, size.height * 0.24f)
                        )
                    }
                }
                else -> {
                    // TikTok neon music note
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = name,
                        tint = ElectricCyan,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Rich procedural artwork thumbnail that renders vivid scenery or album art matching Screens 2, 4, 5, 6, 7, 8, 10, 11.
 */
@Composable
fun MediaThumbnailArtwork(
    title: String,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    showPlayOverlay: Boolean = false,
    durationBadge: String? = null
) {
    val lower = title.lowercase()
    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            when {
                lower.contains("nature") || lower.contains("horizon") || lower.contains("mountain") -> {
                    // Alpine mountain & emerald lake reflection (matches "Beautiful Nature 4K")
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF1D4ED8), Color(0xFF38BDF8), Color(0xFF065F46), Color(0xFF047857))
                        )
                    )
                    // Sun glow
                    drawCircle(
                        color = Color(0xFFFDE047).copy(alpha = 0.45f),
                        radius = w * 0.18f,
                        center = Offset(w * 0.75f, h * 0.28f)
                    )
                    // Snow-capped mountain peaks
                    val peak1 = Path().apply {
                        moveTo(0f, h * 0.65f)
                        lineTo(w * 0.32f, h * 0.22f)
                        lineTo(w * 0.65f, h * 0.65f)
                        close()
                    }
                    drawPath(peak1, color = Color(0xFF334155))
                    val peak2 = Path().apply {
                        moveTo(w * 0.35f, h * 0.65f)
                        lineTo(w * 0.72f, h * 0.18f)
                        lineTo(w, h * 0.65f)
                        close()
                    }
                    drawPath(peak2, color = Color(0xFF1E293B))
                    // Turquoise lake
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF0284C7), Color(0xFF0F766E)),
                            startY = h * 0.65f,
                            endY = h
                        ),
                        topLeft = Offset(0f, h * 0.65f),
                        size = Size(w, h * 0.35f)
                    )
                }

                lower.contains("cat") || lower.contains("funny") -> {
                    // Warm golden amber playful backdrop
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFFF59E0B), Color(0xFFD97706), Color(0xFF78350F))
                        )
                    )
                    drawCircle(
                        color = Color(0xFFFDE68A).copy(alpha = 0.4f),
                        radius = h * 0.32f,
                        center = Offset(w * 0.35f, h * 0.52f)
                    )
                    drawCircle(
                        color = Color(0xFFFCD34D).copy(alpha = 0.4f),
                        radius = h * 0.30f,
                        center = Offset(w * 0.68f, h * 0.54f)
                    )
                }

                lower.contains("travel") || lower.contains("vlog") -> {
                    // Sunset coastal horizon
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF7C2D12), Color(0xFFEA580C), Color(0xFFFACC15), Color(0xFF1E3A8A))
                        )
                    )
                    drawCircle(
                        color = Color(0xFFFEF08A),
                        radius = h * 0.16f,
                        center = Offset(w * 0.5f, h * 0.48f)
                    )
                }

                lower.contains("cook") || lower.contains("recipe") -> {
                    // Rich culinary crimson/warm orange
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF991B1B), Color(0xFFDC2626), Color(0xFFF97316))
                        )
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.22f),
                        radius = h * 0.34f,
                        center = Offset(w * 0.5f, h * 0.5f)
                    )
                }

                lower.contains("alan walker") || lower.contains("faded") -> {
                    // Iconic dark cyan/teal silhouette with glowing 'AW' monogram
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF0891B2), Color(0xFF0F172A), Color(0xFF020617)),
                            center = Offset(w * 0.5f, h * 0.45f),
                            radius = w * 0.7f
                        )
                    )
                    drawCircle(
                        color = ElectricCyan.copy(alpha = 0.25f),
                        radius = minOf(w, h) * 0.38f,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                lower.contains("imagine") || lower.contains("believer") -> {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF0F172A), Color(0xFF0284C7), Color(0xFF312E81))
                        )
                    )
                }

                lower.contains("coldplay") || lower.contains("paradise") -> {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFFBE185D), Color(0xFFEC4899), Color(0xFF4C1D95))
                        )
                    )
                }

                else -> {
                    val c1 = if (isVideo) NeonPurple else Color(0xFF0EA5E9)
                    val c2 = if (isVideo) Color(0xFF0284C7) else NeonPurple
                    drawRect(brush = Brush.linearGradient(listOf(c1, c2, Color(0xFF0F172A))))
                }
            }
        }

        if (lower.contains("alan walker") || lower.contains("faded")) {
            Text(
                text = "AW",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )
        }

        if (showPlayOverlay) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        if (!durationBadge.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = durationBadge,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun StudioHeroBackdrop(
    mode: DownloaderMode,
    modifier: Modifier = Modifier
) {
    // Kept for backward compatibility
    Box(modifier = modifier)
}

@Composable
fun EmptyLibraryArtwork(
    isVideo: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(96.dp), contentAlignment = Alignment.Center) {
        MediaSaveBrandLogo(size = 76.dp)
    }
}

@Composable
fun TechBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .border(
                width = 1.dp,
                color = contentColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
    }
}

fun formatDurationReadable(durationMs: Long): String {
    if (durationMs <= 0L) return "03:32"
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(1L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

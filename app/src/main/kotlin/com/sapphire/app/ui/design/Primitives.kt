package com.sapphire.app.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import kotlin.random.Random

/**
 * Small monospace eyebrow label — the "research-terminal" accent over sections.
 * Renders uppercase with wide tracking, a leading accent dot, and a faint rule.
 */
@Composable
fun SectionEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = true,
) {
    val palette = LocalSapphirePalette.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (accent) palette.Accent else palette.OnInkFaint),
        )
        Text(
            text.uppercase(),
            style = SapphireMono.Eyebrow,
            color = if (accent) palette.Accent else palette.OnInkFaint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Platform origin badge. UNREAD → brand color outline + label; READ → monochrome mid-gray
 * per PRD §3.3 visual state matrix. Mono label, tight pill, 1px stroke only (no fill)
 * so badges read as data tags, not buttons.
 */
@Composable
fun PlatformBadge(
    tag: String,
    read: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSapphirePalette.current
    val brand = Color(PlatformColors.forTag(tag))
    val color = if (read) palette.OnInkFaint else brand
    Row(
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(4.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color),
        )
        Text(
            PlatformLabels.forTag(tag),
            style = SapphireMono.Label,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * AI agent provenance badge — the `[✨ AI Search Agent]` / `[🤖 Agent: …]` tag (PRD §3.6/§3.7).
 * Distinct from platform badges: accent-tinted fill (not outline) so synth items pop as
 * machine-curated against organic feed items.
 */
@Composable
fun AIAgentBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSapphirePalette.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(palette.Accent.copy(alpha = 0.14f))
            .border(1.dp, palette.Accent.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("✦", color = palette.AccentBright, fontSize = 9.sp)
        Text(
            label.uppercase(),
            style = SapphireMono.Label,
            color = palette.AccentBright,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Soft radial accent glow placed behind a hero element (onboarding title, active card).
 * Uses [Modifier.drawWithCache] to build the radial gradient once per size change.
 */
fun Modifier.accentGlow(
    color: Color,
    radiusFraction: Float = 1.4f,
    alpha: Float = 0.35f,
): Modifier = this.drawWithCache {
    val radius = size.maxDimension * radiusFraction
    val brush = Brush.radialGradient(
        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
        center = Offset(size.width * 0.5f, size.height * 0.35f),
        radius = radius,
    )
    onDrawWithContent {
        drawRect(brush)
        drawContent()
    }
}

/**
 * Grain / noise texture overlay. Adds analog warmth to flat charcoal backgrounds so the
 * dark surfaces don't read as a flat black screen. Deterministic per-call seed.
 *
 * Implementation: draws a field of low-alpha specks into a cached layer; compositing
 * strategy Offload keeps it cheap under alpha animation.
 */
@Composable
fun Modifier.grainOverlay(
    alpha: Float = 0.04f,
    seed: Long = 7L,
): Modifier {
    val palette = LocalSapphirePalette.current
    val speck = palette.OnInk
    val rng = Random(seed)
    return this.drawWithCache {
        val count = (size.width * size.height / 220f).toInt().coerceIn(400, 2600)
        onDrawWithContent {
            drawContent()
            repeat(count) {
                val x = rng.nextFloat() * size.width
                val y = rng.nextFloat() * size.height
                val a = (rng.nextFloat() * 0.6f + 0.1f) * alpha
                drawCircle(
                    color = speck.copy(alpha = a),
                    radius = rng.nextFloat() * 0.9f + 0.3f,
                    center = Offset(x, y),
                )
            }
        }
    }
}

/**
 * Shimmer placeholder block (PRD §3.5 shimmer loading). A diagonal gradient sweep that
 * pans across the target bounds; used by macro/classification loading slots.
 */
@Composable
fun Modifier.shimmerSweep(
    periodMs: Int = 1300,
): Modifier {
    val palette = LocalSapphirePalette.current
    val transition = rememberInfiniteTransition(label = "shimmer-sweep")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Restart),
        label = "shimmer-progress",
    )
    val base = palette.InkRaised
    val highlight = palette.OnInk.copy(alpha = 0.10f)
    return this.drawWithCache {
        val w = size.width
        onDrawWithContent {
            drawRect(base)
            val sweepWidth = w * 0.7f
            val start = -sweepWidth + progress * (w + sweepWidth * 2)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(start, 0f),
                    end = Offset(start + sweepWidth, size.height),
                ),
            )
            drawContent()
        }
    }
}

/** A pre-shaped shimmer chip used to occupy the macro toolbar before classification. */
@Composable
fun ShimmerBlock(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp = 30.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .shimmerSweep(),
    )
}

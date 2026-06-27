package com.sapphire.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Sapphire shapes. Tight radii — the editorial/research tone wants crisp, confident
 * corners, not pillowy Material defaults. Cards use 14dp; bottom sheet 20dp at the
 * grabbing edge; inputs nearly square.
 */
val SapphireShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

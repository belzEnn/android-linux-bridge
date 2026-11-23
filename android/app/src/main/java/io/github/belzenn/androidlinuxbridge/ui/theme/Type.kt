package io.github.belzenn.androidlinuxbridge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val MaterialTypography = Typography()

internal val Typography = MaterialTypography.copy(
    headlineSmall = MaterialTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = MaterialTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = MaterialTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = MaterialTypography.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = MaterialTypography.bodyMedium.copy(letterSpacing = 0.sp),
)

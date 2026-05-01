package com.tactilelens.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tactilelens.app.R

/**
 * Type system — IBM Plex Sans for UI, IBM Plex Mono for metrics.
 *
 * Plex chosen for three reasons:
 *  1. Free + open (SIL OFL), bundled in `res/font/` so no Play Services
 *     dependency at runtime. Demo-grade reliability.
 *  2. Distinctive technical character without being trendy. Avoids the
 *     Inter / Roboto / Geometric Humanist clichés.
 *  3. Mono variant pairs natively for metric readouts — latency pill,
 *     axis pole labels, confidence value all live in mono.
 *
 * APK cost: ~880 KB across 5 TTFs. Negligible against current 155 MB APK.
 */

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/**
 * Six-style scale, mapped onto Material 3 typography slots so MaterialTheme
 * components read the right family / weight / size automatically.
 *
 *  | Style    | M3 slot       | Use                                    |
 *  |----------|---------------|----------------------------------------|
 *  | Display  | displayMedium | Material name on Results               |
 *  | Title    | titleLarge    | Section titles                         |
 *  | Body     | bodyMedium    | Instructions / paragraphs              |
 *  | Label    | labelMedium   | Axis labels, status labels (uppercase) |
 *  | MetricL  | headlineSmall | Confidence value                       |
 *  | MetricS  | labelSmall    | Latency pill, session counter          |
 */
val Typography = Typography(
    displayMedium = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.96).sp, // -0.02em at 48sp
    ),
    titleLarge = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.32.sp, // 0.12em at 11sp — for ALL CAPS usage
    ),
    headlineSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.56).sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

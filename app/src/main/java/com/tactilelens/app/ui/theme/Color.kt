package com.tactilelens.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Instrument-grade palette. Reference frame: laboratory equipment, oscilloscope,
 * pro-audio plugin — not "AI app." Three commitments encoded here:
 *
 *  1. Near-black canvas, no blue tint. Cyan-on-deep-blue is the AI dashboard
 *     cliche; we move to neutral [Carbon] so the only chroma in the UI is
 *     intentional and load-bearing.
 *  2. Single chromatic accent ([Pulse]), used ONLY for live signal cues —
 *     confidence indicator dot, axis-bar end-cap, tap ripple, NPU-active dot
 *     on the latency pill. A Snapdragon-adjacent red that hat-tips the chip
 *     without literal branding.
 *  3. Bone-on-Carbon contrast (not white-on-white-tinted-blue). The dot grid
 *     finally reads.
 *
 * See specs/plans/2026-05-01-groundwork-android-compose-camera-ml-ui-redesign.md
 * for the full design system rationale.
 */

// Canvas + raised surfaces (darkest → mid-dark)
val Carbon = Color(0xFF0A0A0B)
val Slate = Color(0xFF131316)
val Iron = Color(0xFF1F1F24)
val Graphite = Color(0xFF3A3A42)

// Text (low → high emphasis)
val Mercury = Color(0xFF9BA0A8)
val Bone = Color(0xFFE5E7EB)
val Snow = Color(0xFFFAFAFA)

// Sole chromatic accent — Snapdragon-adjacent red. Used only for live signal.
val Pulse = Color(0xFFFF4040)

// ------------------------------------------------------------------
// Legacy palette — preserved as Deprecated typealiases for one-phase
// transition compatibility. Phase 2 will sweep call sites to the
// semantic tokens above and these will be deleted.
// ------------------------------------------------------------------
@Deprecated("Use Carbon", ReplaceWith("Carbon"))
val DeepSpace = Carbon

@Deprecated("Use Slate", ReplaceWith("Slate"))
val NebulaBlue = Slate

@Deprecated("Use Pulse for live-signal accents; Bone for primary text", ReplaceWith("Pulse"))
val GlowCyan = Pulse

@Deprecated("Use Pulse", ReplaceWith("Pulse"))
val VividBlue = Pulse

@Deprecated("Use Slate", ReplaceWith("Slate"))
val PanelInk = Slate

@Deprecated("Use Bone", ReplaceWith("Bone"))
val MistWhite = Bone

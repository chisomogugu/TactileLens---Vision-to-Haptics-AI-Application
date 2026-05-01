package com.tactilelens.app.data.model

/**
 * The six canonical materials TactileLens hand-tunes.
 *
 * Anything outside this set arrives as `material: Material? = null` in
 * [AnalysisResult], which routes the renderers through the axis-driven
 * procedural path. See ARCHITECTURE.md §4 (data model) and §10 (locked
 * decisions) for the routing fork.
 *
 * PAPER occupies the smooth + flat corner of the axis space at slightly
 * softer hardness than GLASS, with a distinct crinkling foley character.
 *
 * FABRIC fills the soft + draggy + flat corner of the four-axis space
 * (low hardness, low density, high friction) that nothing else covers.
 * DTD labels `knitted`, `woven`, and `lacy` route here at high confidence.
 *
 * GLASS re-enabled per locked decision Q6-B so the canonical set matches
 * the ML team's 6-material training centroids one-to-one. Audio + haptic
 * polish for GLASS lands in Phase 6 of the LiteRT integration plan.
 */
enum class Material(val display: String) {
    WOOD("Wood"),
    PAPER("Paper"),
    ROCKS("Rocks"),
    SAND("Sand"),
    FABRIC("Fabric"),
    GLASS("Glass"),
}

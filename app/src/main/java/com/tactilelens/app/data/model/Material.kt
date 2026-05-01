package com.tactilelens.app.data.model

/**
 * The five canonical materials TactileLens hand-tunes.
 *
 * Anything outside this set arrives as `material: Material? = null` in
 * [AnalysisResult], which routes the renderers through the axis-driven
 * procedural path. See ARCHITECTURE.md §4 (data model) and §10 (locked
 * decisions) for the routing fork.
 *
 * PAPER replaced GLASS as the 5th canonical because the available CC0
 * glass-rub sources had ambiguous fluty/bell character that did not read
 * as "glass surface" in listen testing. Paper occupies the same smooth +
 * flat corner of the axis space at slightly softer hardness, with a
 * distinct crinkling foley character.
 *
 * FABRIC fills the soft + draggy + flat corner of the four-axis space
 * (low hardness, low flatBumpy, high friction) that nothing else covers.
 * DTD labels `knitted`, `woven`, and `lacy` route here at high confidence.
 */
enum class Material(val display: String) {
    WOOD("Wood"),
    PAPER("Paper"),
    ROCKS("Rocks"),
    SAND("Sand"),
    FABRIC("Fabric"),
}

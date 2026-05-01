package com.tactilelens.app.data.model

/**
 * The four canonical materials TactileLens hand-tunes.
 *
 * Anything outside this set arrives as `material: Material? = null` in
 * [AnalysisResult], which routes the renderers through the axis-driven
 * procedural path. See ARCHITECTURE.md §4 (data model) and §10 (locked
 * decisions) for the routing fork.
 */
enum class Material(val display: String) {
    WOOD("Wood"),
    GLASS("Glass"),
    ROCKS("Rocks"),
    SAND("Sand"),
}

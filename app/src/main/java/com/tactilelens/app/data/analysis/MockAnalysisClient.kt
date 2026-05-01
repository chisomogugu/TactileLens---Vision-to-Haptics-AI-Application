package com.tactilelens.app.data.analysis

import android.content.Context
import android.net.Uri
import com.tactilelens.app.data.model.AnalysisResult
import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes
import kotlinx.coroutines.delay

/**
 * Mock analysis client.
 *
 * Returns a canned [AnalysisResult] cycling through the four canonical
 * materials, with reasonable axis values per material. Adds a 1.5 s delay
 * to simulate inference latency (the original frontend used a 7 s mock
 * that was tied to the scan reveal animation; here we go faster because
 * the dropdown lets the user override anyway).
 *
 * Replace this with the real ML client once the team's payload is settled.
 * See ARCHITECTURE.md §10 (locked decision 2: ML contract).
 */
class MockAnalysisClient(@Suppress("UNUSED_PARAMETER") context: Context) : AnalysisClient {

    private var counter: Int = 0

    override suspend fun analyze(uri: Uri): AnalysisResult {
        delay(1500)
        val material = MATERIAL_CYCLE[counter % MATERIAL_CYCLE.size]
        counter++
        return resultFor(material)
    }

    /** Build an [AnalysisResult] for a known material with sensible axis defaults. */
    fun resultFor(material: Material?): AnalysisResult = when (material) {
        Material.WOOD -> AnalysisResult(
            axes = TextureAxes(roughness = 0.55f, density = 0.45f, friction = 0.50f, hardness = 0.70f),
            material = Material.WOOD,
            confidence = 0.82f,
            label = "wood",
        )
        Material.GLASS -> AnalysisResult(
            axes = TextureAxes(roughness = 0.05f, density = 0.05f, friction = 0.10f, hardness = 0.95f),
            material = Material.GLASS,
            confidence = 0.91f,
            label = "glass",
        )
        Material.ROCKS -> AnalysisResult(
            axes = TextureAxes(roughness = 0.85f, density = 0.85f, friction = 0.55f, hardness = 0.85f),
            material = Material.ROCKS,
            confidence = 0.78f,
            label = "rocks",
        )
        Material.SAND -> AnalysisResult(
            axes = TextureAxes(roughness = 0.95f, density = 0.30f, friction = 0.40f, hardness = 0.30f),
            material = Material.SAND,
            confidence = 0.88f,
            label = "sand",
        )
        null -> AnalysisResult(
            axes = TextureAxes.Neutral,
            material = null,
            confidence = 0.45f,
            label = "fibrous",
        )
    }

    private companion object {
        private val MATERIAL_CYCLE = listOf(
            Material.SAND,
            Material.WOOD,
            Material.GLASS,
            Material.ROCKS,
        )
    }
}

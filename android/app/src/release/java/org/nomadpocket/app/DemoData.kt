package org.nomadpocket.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.nomadpocket.mesh.CoverageData
import org.nomadpocket.mesh.MapNode

/** Release builds have no demo data; see src/debug for the screenshot loader. */
object DemoData {
    val nodesFlow: StateFlow<List<MapNode>> = MutableStateFlow(emptyList())
    val coverageFlow: StateFlow<CoverageData?> = MutableStateFlow(null)

    val camera: Triple<Double, Double, Double>? = null
    val sharedCells: List<org.nomadpocket.mesh.CoverageShare.Cell>? = null

    @Suppress("UNUSED_PARAMETER")
    fun load(context: Context, screen: String?) {}
}

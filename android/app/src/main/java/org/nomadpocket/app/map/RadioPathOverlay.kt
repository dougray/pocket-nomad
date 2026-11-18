// RadioPathOverlay.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// The radio path you last checked, kept on the map as a line coloured by the verdict
// (green clear, orange tight, red blocked), until you clear it. Port of RadioPathOverlay.swift.

package org.nomadpocket.app.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PathOverlay(val from: ShapePoint, val to: ShapePoint, val verdict: PathVerdict, val label: String)

object RadioPathOverlay {
    private val _state = MutableStateFlow<PathOverlay?>(null)
    val state: StateFlow<PathOverlay?> = _state
    fun show(o: PathOverlay) { _state.value = o }
    fun clear() { _state.value = null }
}

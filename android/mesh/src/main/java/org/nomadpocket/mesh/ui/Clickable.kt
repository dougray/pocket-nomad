package org.nomadpocket.mesh.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier

/** Tap, plus long-press for the "Block" menu (the iOS context menu). */
internal fun Modifier.meshClickable(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

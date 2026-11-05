package org.nomadpocket.app

import android.content.Context

/** Release builds have no model self-test; see src/debug. */
object ModelSelfTest {
    @Suppress("UNUSED_PARAMETER")
    fun runIfRequested(context: Context, extra: String?) {}
}

// DriveLog.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// A plain-text diary of each drive: when drive mode started and stopped (and
// why), radio links coming and going, pings and their answers, and a heartbeat
// each minute. It never leaves the phone; it is there so a drive that maps
// nothing can be explained afterwards. Locations are never written to it.
// Ported from App/Sources/Mesh/DriveLog.swift.

package org.nomadpocket.mesh

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DriveLog(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        /** Oldest lines are dropped past this many. */
        const val MAX_LINES = 4000
        private val STAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
    }

    private val lines: MutableList<String> =
        runCatching { file.readLines().filter { it.isNotEmpty() }.toMutableList() }.getOrElse { mutableListOf() }

    val text: String get() = lines.joinToString("\n")

    fun note(event: String) {
        lines += "${STAMP.format(Instant.ofEpochSecond(now() / 1000))} $event"
        if (lines.size > MAX_LINES) lines.subList(0, lines.size - MAX_LINES).clear()
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }
}

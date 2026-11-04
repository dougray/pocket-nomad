package org.nomadpocket.app.tools

/** The Survive tools. A port of App/Sources/Tools/ToolKind.swift. */
enum class ToolKind(val id: String, val title: String, val symbol: String) {
    COMPASS("compass", "Compass & Position", "location.north.circle.fill"),
    SOS("sos", "SOS Light", "flashlight.on.fill"),
    MORSE("morse", "Morse Code", "dot.radiowaves.left.and.right"),
    WATER("water", "Water Purifier", "drop.fill"),
    MEDICAL_ID("medicalID", "Medical ID", "heart.text.square.fill"),
    CHECKLISTS("checklists", "Checklists", "checklist"),
    SUN("sun", "Daylight", "sun.horizon.fill");

    companion object {
        fun from(id: String): ToolKind? = entries.firstOrNull { it.id == id }

        /** The tool that helps with a question, if one obviously does. */
        fun related(question: String): ToolKind? {
            val q = question.lowercase()
            val rules = listOf(
                WATER to listOf("bleach", "purif", "disinfect water", "boil water", "drinking water", "safe to drink", "gallon of water"),
                SOS to listOf("sos", "signal", "rescue", "flashlight"),
                MORSE to listOf("morse"),
                COMPASS to listOf("compass", "which way is north", "coordinates", "mgrs", "my location", "where am i"),
                SUN to listOf("sunset", "sunrise", "daylight", "dark"),
                MEDICAL_ID to listOf("medical id", "blood type"),
            )
            return rules.firstOrNull { (_, words) -> words.any { q.contains(it) } }?.first
        }
    }
}

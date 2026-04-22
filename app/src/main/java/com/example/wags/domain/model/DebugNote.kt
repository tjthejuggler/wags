package com.example.wags.domain.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a single debug note created by the user on a specific screen.
 * Written to [debug_wags.json] so a programmer LLM can quickly locate
 * relevant source files.
 */
data class DebugNote(
    val id: String = System.currentTimeMillis().toString(),
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
    val screenRoute: String,
    val screenLabel: String,
    val sourceFile: String,
    val sourceFunctions: String,
    val noteType: NoteType,
    val noteText: String
)

enum class NoteType(val label: String) {
    BUG("Bug"),
    FEATURE("Feature"),
    NOTE("Note")
}

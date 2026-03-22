package com.example.wags.domain.model

/**
 * Describes the broadest category for which a new personal best was set.
 *
 * Categories are ordered from most specific (exact settings) to broadest (global).
 * The celebratory dialog shows the **broadest** category that was beaten, since
 * beating a broader category strictly implies beating all narrower ones that
 * include the current settings.
 *
 * Example: settings = (MORNING, FULL, NO_PREP)
 *   • EXACT          → "Personal best for Morning · Full · No Prep"
 *   • TWO_SETTINGS   → e.g. "Personal best for Morning · Full" (any prep)
 *   • ONE_SETTING    → e.g. "Personal best for Morning" (any lung volume, any prep)
 *   • GLOBAL         → "Personal best across all settings!"
 */
enum class PersonalBestCategory {
    /** New PB for the exact 3-setting combination only. */
    EXACT,
    /** New PB for a 2-setting combination (one setting relaxed). */
    TWO_SETTINGS,
    /** New PB for a single setting (two settings relaxed). */
    ONE_SETTING,
    /** New PB across ALL settings — the absolute best ever. */
    GLOBAL
}

/**
 * Result of the broader personal-best check after a free hold completes.
 *
 * @property durationMs   The duration of the hold that set the new PB.
 * @property category     The broadest category that was beaten.
 * @property description  A human-readable label for the broadest beaten category
 *                        (e.g. "Morning · Full", "Morning", "All settings").
 */
data class PersonalBestResult(
    val durationMs: Long,
    val category: PersonalBestCategory,
    val description: String
)

/**
 * Describes a single PB badge for a record — one per category level the record
 * qualifies for.
 *
 * @property description  Human-readable label (e.g. "Morning", "Morning · Full", "All settings").
 * @property isCurrent    True if this record is **still** the best for this category.
 *                        False if a newer record has since surpassed it.
 */
data class RecordPbBadge(
    val category: PersonalBestCategory,
    val description: String,
    val isCurrent: Boolean
)

/**
 * Returns the trophy count (1–4) for the broadest [PersonalBestCategory].
 *
 * - EXACT        → 1 🏆
 * - TWO_SETTINGS → 2 🏆🏆
 * - ONE_SETTING  → 3 🏆🏆🏆
 * - GLOBAL       → 4 🏆🏆🏆🏆
 */
fun PersonalBestCategory.trophyCount(): Int = when (this) {
    PersonalBestCategory.EXACT        -> 1
    PersonalBestCategory.TWO_SETTINGS -> 2
    PersonalBestCategory.ONE_SETTING  -> 3
    PersonalBestCategory.GLOBAL       -> 4
}

/**
 * Returns a string of trophy emojis for the given category.
 */
fun PersonalBestCategory.trophyEmojis(): String = "🏆".repeat(trophyCount())

/**
 * A single row in the Personal Bests screen.
 *
 * @property trophyCount  Number of trophies (1–4).
 * @property label        Human-readable label (e.g. "All settings", "Morning", "Morning · Full").
 * @property recordId     ID of the best record for this combination, or null if no records exist.
 * @property durationMs   Duration of the best record, or null.
 * @property timestamp    Epoch-ms timestamp of the best record, or null.
 */
data class PersonalBestEntry(
    val trophyCount: Int,
    val label: String,
    val recordId: Long?,
    val durationMs: Long?,
    val timestamp: Long?
)

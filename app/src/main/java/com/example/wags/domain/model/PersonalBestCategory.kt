package com.example.wags.domain.model

/**
 * Describes the broadest category for which a new personal best was set.
 *
 * Categories are ordered from most specific (exact settings) to broadest (global).
 * The celebratory dialog shows the **broadest** category that was beaten, since
 * beating a broader category strictly implies beating all narrower ones that
 * include the current settings.
 *
 * With 5 settings (timeOfDay, lungVolume, prepType, posture, audio) there are 6 levels:
 *
 * Example: settings = (MORNING, FULL, NO_PREP, LAYING, SILENCE)
 *   • EXACT           → "Personal best for Morning · Full · No Prep · Laying · Silence"
 *   • FOUR_SETTINGS   → e.g. "Personal best for Morning · Full · No Prep · Laying" (any audio)
 *   • THREE_SETTINGS  → e.g. "Personal best for Morning · Full · No Prep" (any posture, any audio)
 *   • TWO_SETTINGS    → e.g. "Personal best for Morning · Full" (any prep, any posture, any audio)
 *   • ONE_SETTING     → e.g. "Personal best for Morning" (any other settings)
 *   • GLOBAL          → "Personal best across all settings!"
 */
enum class PersonalBestCategory {
    /** New PB for the exact 5-setting combination only. */
    EXACT,
    /** New PB for a 4-setting combination (one setting relaxed). */
    FOUR_SETTINGS,
    /** New PB for a 3-setting combination (two settings relaxed). */
    THREE_SETTINGS,
    /** New PB for a 2-setting combination (three settings relaxed). */
    TWO_SETTINGS,
    /** New PB for a single setting (four settings relaxed). */
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
 * Returns the trophy count (1–6) for the broadest [PersonalBestCategory].
 *
 * - EXACT           → 1 🏆
 * - FOUR_SETTINGS   → 2 🏆🏆
 * - THREE_SETTINGS  → 3 🏆🏆🏆
 * - TWO_SETTINGS    → 4 🏆🏆🏆🏆
 * - ONE_SETTING     → 5 🏆🏆🏆🏆🏆
 * - GLOBAL          → 6 🏆🏆🏆🏆🏆🏆
 */
fun PersonalBestCategory.trophyCount(): Int = when (this) {
    PersonalBestCategory.EXACT           -> 1
    PersonalBestCategory.FOUR_SETTINGS   -> 2
    PersonalBestCategory.THREE_SETTINGS  -> 3
    PersonalBestCategory.TWO_SETTINGS    -> 4
    PersonalBestCategory.ONE_SETTING     -> 5
    PersonalBestCategory.GLOBAL          -> 6
}

/**
 * Returns a string of trophy emojis for the given category.
 */
fun PersonalBestCategory.trophyEmojis(): String = "🏆".repeat(trophyCount())

/**
 * A single row in the Personal Bests screen.
 *
 * @property trophyCount  Number of trophies (1–6).
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

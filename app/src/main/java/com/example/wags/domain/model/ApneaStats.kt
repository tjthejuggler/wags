package com.example.wags.domain.model

/**
 * Aggregated statistics for the Apnea screen stats section.
 *
 * Each nullable value field is paired with a nullable [recordId] field that
 * identifies the specific record where that extreme was set — used to navigate
 * to the detail screen when the user taps the stat row.
 *
 * All nullable fields are null when no data exists for that metric
 * (e.g. no oximeter was connected during any session, so SpO2 is unavailable).
 */
data class ApneaStats(
    // ── Activity counts ───────────────────────────────────────────────────────
    val freeHoldCount: Int = 0,
    val o2TableCount: Int = 0,
    val co2TableCount: Int = 0,
    val progressiveO2Count: Int = 0,
    val minBreathCount: Int = 0,
    val wonkaContractionCount: Int = 0,
    val wonkaEnduranceCount: Int = 0,

    // ── Total times (ms) — per drill type ─────────────────────────────────────
    val freeHoldTotalHoldMs: Long = 0L,
    val o2TableTotalHoldMs: Long = 0L,
    val co2TableTotalHoldMs: Long = 0L,
    val progressiveO2TotalHoldMs: Long = 0L,
    val minBreathTotalHoldMs: Long = 0L,
    val freeHoldTotalSessionMs: Long = 0L,
    val o2TableTotalSessionMs: Long = 0L,
    val co2TableTotalSessionMs: Long = 0L,
    val progressiveO2TotalSessionMs: Long = 0L,
    val minBreathTotalSessionMs: Long = 0L,

    // ── Overall HR / SpO2 extremes (across entire hold) ───────────────────────
    val maxHrEver: Float? = null,
    val maxHrEverRecordId: Long? = null,
    val minHrEver: Float? = null,
    val minHrEverRecordId: Long? = null,
    val lowestSpO2Ever: Int? = null,
    val lowestSpO2EverRecordId: Long? = null,

    // ── Session-start extremes (first telemetry sample of each hold) ──────────
    val maxStartHr: Int? = null,
    val maxStartHrRecordId: Long? = null,
    val minStartHr: Int? = null,
    val minStartHrRecordId: Long? = null,
    val maxStartSpO2: Int? = null,
    val maxStartSpO2RecordId: Long? = null,
    val minStartSpO2: Int? = null,
    val minStartSpO2RecordId: Long? = null,

    // ── Session-end extremes (last telemetry sample of each hold) ────────────
    val maxEndHr: Int? = null,
    val maxEndHrRecordId: Long? = null,
    val minEndHr: Int? = null,
    val minEndHrRecordId: Long? = null,
    val maxEndSpO2: Int? = null,
    val maxEndSpO2RecordId: Long? = null,
    val minEndSpO2: Int? = null,
    val minEndSpO2RecordId: Long? = null,
) {
    /** Total hold time across all drill types (ms). */
    val totalHoldMs: Long get() =
        freeHoldTotalHoldMs + o2TableTotalHoldMs + co2TableTotalHoldMs +
        progressiveO2TotalHoldMs + minBreathTotalHoldMs

    /** Total session time across all drill types (ms). */
    val totalSessionMs: Long get() =
        freeHoldTotalSessionMs + o2TableTotalSessionMs + co2TableTotalSessionMs +
        progressiveO2TotalSessionMs + minBreathTotalSessionMs
}

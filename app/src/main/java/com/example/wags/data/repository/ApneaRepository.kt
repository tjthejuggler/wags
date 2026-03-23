package com.example.wags.data.repository

import com.example.wags.data.db.dao.ApneaRecordDao
import com.example.wags.data.db.dao.FreeHoldTelemetryDao
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.domain.model.ApneaStats
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.PersonalBestEntry
import com.example.wags.domain.model.PersonalBestResult
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.RecordPbBadge
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApneaRepository @Inject constructor(
    private val dao: ApneaRecordDao,
    private val telemetryDao: FreeHoldTelemetryDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    fun getLatestRecords(limit: Int = 20): Flow<List<ApneaRecordEntity>> =
        dao.getLatest(limit)

    fun getByType(type: String): Flow<List<ApneaRecordEntity>> =
        dao.getByType(type)

    /** All records matching the current settings combination (for history / recent records). */
    fun getBySettings(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<List<ApneaRecordEntity>> =
        dao.getBySettings(lungVolume, prepType, timeOfDay, posture)

    /**
     * The [limit] most recent records for a given settings combination, across ALL event types.
     * Used by the Recent Records section on the main Apnea screen.
     */
    fun getRecentBySettings(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        limit: Int = 10
    ): Flow<List<ApneaRecordEntity>> =
        dao.getRecentBySettings(lungVolume, prepType, timeOfDay, posture, limit)

    /** Best free-hold duration for the current settings combination. */
    fun getBestFreeHold(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?> =
        dao.getBestFreeHold(lungVolume, prepType, timeOfDay, posture)

    /** One-shot (suspend) best free-hold duration for a given settings combination. */
    suspend fun getBestFreeHoldOnce(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldOnce(lungVolume, prepType, timeOfDay, posture) }

    /** recordId of the best free-hold for the current settings combination. */
    fun getBestFreeHoldRecordId(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<Long?> =
        dao.getBestFreeHoldRecordId(lungVolume, prepType, timeOfDay, posture)

    // ── Broader personal-best queries (one-shot, for PB celebration) ──────────

    /** Best free-hold across ALL settings (global PB). */
    suspend fun getBestFreeHoldGlobal(): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldGlobal() }

    // Single-setting
    suspend fun getBestFreeHoldByTimeOfDay(timeOfDay: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByTimeOfDay(timeOfDay) }
    suspend fun getBestFreeHoldByLungVolume(lungVolume: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByLungVolume(lungVolume) }
    suspend fun getBestFreeHoldByPrepType(prepType: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByPrepType(prepType) }
    suspend fun getBestFreeHoldByPosture(posture: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByPosture(posture) }

    // Two-setting
    suspend fun getBestFreeHoldByTimeOfDayAndLungVolume(timeOfDay: String, lungVolume: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByTimeOfDayAndLungVolume(timeOfDay, lungVolume) }
    suspend fun getBestFreeHoldByTimeOfDayAndPrepType(timeOfDay: String, prepType: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByTimeOfDayAndPrepType(timeOfDay, prepType) }
    suspend fun getBestFreeHoldByTimeOfDayAndPosture(timeOfDay: String, posture: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByTimeOfDayAndPosture(timeOfDay, posture) }
    suspend fun getBestFreeHoldByLungVolumeAndPrepType(lungVolume: String, prepType: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByLungVolumeAndPrepType(lungVolume, prepType) }
    suspend fun getBestFreeHoldByLungVolumeAndPosture(lungVolume: String, posture: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByLungVolumeAndPosture(lungVolume, posture) }
    suspend fun getBestFreeHoldByPrepTypeAndPosture(prepType: String, posture: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByPrepTypeAndPosture(prepType, posture) }

    // Three-setting
    suspend fun getBestFreeHoldByTodLvPt(timeOfDay: String, lungVolume: String, prepType: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByTodLvPt(timeOfDay, lungVolume, prepType) }
    suspend fun getBestFreeHoldByTodLvPos(timeOfDay: String, lungVolume: String, posture: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByTodLvPos(timeOfDay, lungVolume, posture) }
    suspend fun getBestFreeHoldByTodPtPos(timeOfDay: String, prepType: String, posture: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByTodPtPos(timeOfDay, prepType, posture) }
    suspend fun getBestFreeHoldByLvPtPos(lungVolume: String, prepType: String, posture: String): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldByLvPtPos(lungVolume, prepType, posture) }

    /**
     * Determines the **broadest** personal-best category that a new hold of
     * [durationMs] beats, given the hold's settings.
     *
     * Must be called **before** the new record is saved to the database so
     * the queries compare against prior records only.
     *
     * Returns null when the hold is not even a PB for the exact settings combo.
     */
    suspend fun checkBroaderPersonalBest(
        durationMs: Long,
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String
    ): PersonalBestResult? = withContext(ioDispatcher) {
        // ── Exact settings (4 constraints) ─────────────────────────────────
        val exactBest = dao.getBestFreeHoldOnce(lungVolume, prepType, timeOfDay, posture)
        val isExactPb = exactBest == null || durationMs > exactBest
        if (!isExactPb) return@withContext null   // not even a PB for exact settings

        // Helper to format a setting name for display
        fun fmt(s: String): String = s.lowercase().replace('_', ' ')
            .replaceFirstChar { it.uppercase() }

        // ── Global (0 constraints) ─────────────────────────────────────────
        val globalBest = dao.getBestFreeHoldGlobal()
        if (globalBest == null || durationMs > globalBest) {
            return@withContext PersonalBestResult(
                durationMs  = durationMs,
                category    = PersonalBestCategory.GLOBAL,
                description = "all settings"
            )
        }

        // ── Single-setting categories (1 constraint, 3 relaxed) ────────────
        val todBest = dao.getBestFreeHoldByTimeOfDay(timeOfDay)
        if (todBest == null || durationMs > todBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.ONE_SETTING, fmt(timeOfDay))
        }
        val lvBest = dao.getBestFreeHoldByLungVolume(lungVolume)
        if (lvBest == null || durationMs > lvBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.ONE_SETTING, fmt(lungVolume))
        }
        val ptBest = dao.getBestFreeHoldByPrepType(prepType)
        if (ptBest == null || durationMs > ptBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.ONE_SETTING, fmt(prepType))
        }
        val posBest = dao.getBestFreeHoldByPosture(posture)
        if (posBest == null || durationMs > posBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.ONE_SETTING, fmt(posture))
        }

        // ── Two-setting categories (2 constraints, 2 relaxed) ──────────────
        val todLvBest = dao.getBestFreeHoldByTimeOfDayAndLungVolume(timeOfDay, lungVolume)
        if (todLvBest == null || durationMs > todLvBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.TWO_SETTINGS, "${fmt(timeOfDay)} · ${fmt(lungVolume)}")
        }
        val todPtBest = dao.getBestFreeHoldByTimeOfDayAndPrepType(timeOfDay, prepType)
        if (todPtBest == null || durationMs > todPtBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.TWO_SETTINGS, "${fmt(timeOfDay)} · ${fmt(prepType)}")
        }
        val todPosBest = dao.getBestFreeHoldByTimeOfDayAndPosture(timeOfDay, posture)
        if (todPosBest == null || durationMs > todPosBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.TWO_SETTINGS, "${fmt(timeOfDay)} · ${fmt(posture)}")
        }
        val lvPtBest = dao.getBestFreeHoldByLungVolumeAndPrepType(lungVolume, prepType)
        if (lvPtBest == null || durationMs > lvPtBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.TWO_SETTINGS, "${fmt(lungVolume)} · ${fmt(prepType)}")
        }
        val lvPosBest = dao.getBestFreeHoldByLungVolumeAndPosture(lungVolume, posture)
        if (lvPosBest == null || durationMs > lvPosBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.TWO_SETTINGS, "${fmt(lungVolume)} · ${fmt(posture)}")
        }
        val ptPosBest = dao.getBestFreeHoldByPrepTypeAndPosture(prepType, posture)
        if (ptPosBest == null || durationMs > ptPosBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.TWO_SETTINGS, "${fmt(prepType)} · ${fmt(posture)}")
        }

        // ── Three-setting categories (3 constraints, 1 relaxed) ────────────
        val todLvPtBest = dao.getBestFreeHoldByTodLvPt(timeOfDay, lungVolume, prepType)
        if (todLvPtBest == null || durationMs > todLvPtBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.THREE_SETTINGS, "${fmt(timeOfDay)} · ${fmt(lungVolume)} · ${fmt(prepType)}")
        }
        val todLvPosBest = dao.getBestFreeHoldByTodLvPos(timeOfDay, lungVolume, posture)
        if (todLvPosBest == null || durationMs > todLvPosBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.THREE_SETTINGS, "${fmt(timeOfDay)} · ${fmt(lungVolume)} · ${fmt(posture)}")
        }
        val todPtPosBest = dao.getBestFreeHoldByTodPtPos(timeOfDay, prepType, posture)
        if (todPtPosBest == null || durationMs > todPtPosBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.THREE_SETTINGS, "${fmt(timeOfDay)} · ${fmt(prepType)} · ${fmt(posture)}")
        }
        val lvPtPosBest = dao.getBestFreeHoldByLvPtPos(lungVolume, prepType, posture)
        if (lvPtPosBest == null || durationMs > lvPtPosBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.THREE_SETTINGS, "${fmt(lungVolume)} · ${fmt(prepType)} · ${fmt(posture)}")
        }

        // ── Exact only ─────────────────────────────────────────────────────
        PersonalBestResult(
            durationMs  = durationMs,
            category    = PersonalBestCategory.EXACT,
            description = "${fmt(timeOfDay)} · ${fmt(lungVolume)} · ${fmt(prepType)} · ${fmt(posture)}"
        )
    }

    /**
     * Computes the broadest [PersonalBestCategory] that the current best record
     * for the given settings combination holds **right now**.
     *
     * Returns null if there is no free-hold record for these settings.
     */
    suspend fun getBestRecordTrophyLevel(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String
    ): PersonalBestCategory? = withContext(ioDispatcher) {
        val recordId = dao.getBestFreeHoldRecordIdOnce(lungVolume, prepType, timeOfDay, posture)
            ?: return@withContext null
        computeBroadestCurrentCategory(recordId)
    }

    /**
     * Computes all PB badges for a specific record — both current and former.
     *
     * Returns a list of [RecordPbBadge] from broadest to narrowest.
     * Only includes categories where the record was at least a PB at the time
     * it was recorded.
     */
    suspend fun getRecordPbBadges(recordId: Long): List<RecordPbBadge> = withContext(ioDispatcher) {
        val record = dao.getById(recordId) ?: return@withContext emptyList()
        if (record.tableType != null) return@withContext emptyList() // only free holds

        fun fmt(s: String): String = s.lowercase().replace('_', ' ')
            .replaceFirstChar { it.uppercase() }

        val lv = record.lungVolume
        val pt = record.prepType
        val tod = record.timeOfDay
        val pos = record.posture

        val badges = mutableListOf<RecordPbBadge>()

        // Check from broadest to narrowest — only add if it was a PB at the time
        // Global
        if (dao.wasGlobalBestAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.GLOBAL, "All settings", dao.isGlobalBest(recordId)))
        }

        // Single-setting categories
        if (dao.wasBestForTimeOfDayAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.ONE_SETTING, fmt(tod), dao.isBestForTimeOfDay(recordId)))
        }
        if (dao.wasBestForLungVolumeAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.ONE_SETTING, fmt(lv), dao.isBestForLungVolume(recordId)))
        }
        if (dao.wasBestForPrepTypeAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.ONE_SETTING, fmt(pt), dao.isBestForPrepType(recordId)))
        }
        if (dao.wasBestForPostureAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.ONE_SETTING, fmt(pos), dao.isBestForPosture(recordId)))
        }

        // Two-setting categories
        if (dao.wasBestForTimeOfDayAndLungVolumeAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.TWO_SETTINGS, "${fmt(tod)} · ${fmt(lv)}", dao.isBestForTimeOfDayAndLungVolume(recordId)))
        }
        if (dao.wasBestForTimeOfDayAndPrepTypeAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.TWO_SETTINGS, "${fmt(tod)} · ${fmt(pt)}", dao.isBestForTimeOfDayAndPrepType(recordId)))
        }
        if (dao.wasBestForTimeOfDayAndPostureAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.TWO_SETTINGS, "${fmt(tod)} · ${fmt(pos)}", dao.isBestForTimeOfDayAndPosture(recordId)))
        }
        if (dao.wasBestForLungVolumeAndPrepTypeAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.TWO_SETTINGS, "${fmt(lv)} · ${fmt(pt)}", dao.isBestForLungVolumeAndPrepType(recordId)))
        }
        if (dao.wasBestForLungVolumeAndPostureAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.TWO_SETTINGS, "${fmt(lv)} · ${fmt(pos)}", dao.isBestForLungVolumeAndPosture(recordId)))
        }
        if (dao.wasBestForPrepTypeAndPostureAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.TWO_SETTINGS, "${fmt(pt)} · ${fmt(pos)}", dao.isBestForPrepTypeAndPosture(recordId)))
        }

        // Three-setting categories
        if (dao.wasBestForTodLvPtAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.THREE_SETTINGS, "${fmt(tod)} · ${fmt(lv)} · ${fmt(pt)}", dao.isBestForTodLvPt(recordId)))
        }
        if (dao.wasBestForTodLvPosAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.THREE_SETTINGS, "${fmt(tod)} · ${fmt(lv)} · ${fmt(pos)}", dao.isBestForTodLvPos(recordId)))
        }
        if (dao.wasBestForTodPtPosAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.THREE_SETTINGS, "${fmt(tod)} · ${fmt(pt)} · ${fmt(pos)}", dao.isBestForTodPtPos(recordId)))
        }
        if (dao.wasBestForLvPtPosAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.THREE_SETTINGS, "${fmt(lv)} · ${fmt(pt)} · ${fmt(pos)}", dao.isBestForLvPtPos(recordId)))
        }

        // Exact 4-setting combo
        if (dao.wasBestForExactSettingsAtTime(recordId)) {
            badges.add(RecordPbBadge(PersonalBestCategory.EXACT, "${fmt(tod)} · ${fmt(lv)} · ${fmt(pt)} · ${fmt(pos)}", dao.isBestForExactSettings(recordId)))
        }

        badges
    }

    /**
     * Helper: determines the broadest category for which a given record is
     * currently the best.
     */
    private suspend fun computeBroadestCurrentCategory(recordId: Long): PersonalBestCategory {
        if (dao.isGlobalBest(recordId)) return PersonalBestCategory.GLOBAL

        if (dao.isBestForTimeOfDay(recordId)) return PersonalBestCategory.ONE_SETTING
        if (dao.isBestForLungVolume(recordId)) return PersonalBestCategory.ONE_SETTING
        if (dao.isBestForPrepType(recordId)) return PersonalBestCategory.ONE_SETTING
        if (dao.isBestForPosture(recordId)) return PersonalBestCategory.ONE_SETTING

        if (dao.isBestForTimeOfDayAndLungVolume(recordId)) return PersonalBestCategory.TWO_SETTINGS
        if (dao.isBestForTimeOfDayAndPrepType(recordId)) return PersonalBestCategory.TWO_SETTINGS
        if (dao.isBestForTimeOfDayAndPosture(recordId)) return PersonalBestCategory.TWO_SETTINGS
        if (dao.isBestForLungVolumeAndPrepType(recordId)) return PersonalBestCategory.TWO_SETTINGS
        if (dao.isBestForLungVolumeAndPosture(recordId)) return PersonalBestCategory.TWO_SETTINGS
        if (dao.isBestForPrepTypeAndPosture(recordId)) return PersonalBestCategory.TWO_SETTINGS

        if (dao.isBestForTodLvPt(recordId)) return PersonalBestCategory.THREE_SETTINGS
        if (dao.isBestForTodLvPos(recordId)) return PersonalBestCategory.THREE_SETTINGS
        if (dao.isBestForTodPtPos(recordId)) return PersonalBestCategory.THREE_SETTINGS
        if (dao.isBestForLvPtPos(recordId)) return PersonalBestCategory.THREE_SETTINGS

        return PersonalBestCategory.EXACT
    }

    // ── All Personal Bests (for the Personal Bests screen) ───────────────────

    /**
     * Builds the complete list of personal-best entries across every combination
     * of settings, ordered from broadest (5🏆 global) to narrowest (1🏆 exact).
     *
     * Layout:
     *  • 5🏆  Global (1 entry)
     *  • 4🏆  Single-setting: 3 tod + 3 lv + 3 pt + 2 posture = 11 entries
     *  • 3🏆  Two-setting pairs: C(4,2) groups = 6 groups of varying sizes
     *  • 2🏆  Three-setting combos: C(4,3) groups = 4 groups
     *  • 1🏆  Exact 4-setting combos: 3×3×3×2 = 54 entries
     */
    suspend fun getAllPersonalBests(): List<PersonalBestEntry> = withContext(ioDispatcher) {
        val entries = mutableListOf<PersonalBestEntry>()

        val lungVolumes = listOf("FULL", "PARTIAL", "EMPTY")
        val prepTypes   = PrepType.entries.map { it.name }
        val timesOfDay  = TimeOfDay.entries.map { it.name }
        val postures    = Posture.entries.map { it.name }

        fun String.displayLv() = if (this == "PARTIAL") "Half" else lowercase().replaceFirstChar { it.uppercase() }
        fun String.displayPt() = PrepType.valueOf(this).displayName()
        fun String.displayTod() = TimeOfDay.valueOf(this).displayName()
        fun String.displayPos() = Posture.valueOf(this).displayName()

        // Helper to query and build an entry
        suspend fun entry(trophies: Int, label: String, lv: String, pt: String, tod: String, pos: String): PersonalBestEntry {
            val best = dao.getBestFreeHoldRecord(lv, pt, tod, pos)
            return PersonalBestEntry(
                trophyCount = trophies,
                label       = label,
                recordId    = best?.recordId,
                durationMs  = best?.durationMs,
                timestamp   = best?.timestamp
            )
        }

        // ── 5🏆 Global ──────────────────────────────────────────────────────
        entries += entry(5, "All settings", "", "", "", "")

        // ── 4🏆 Single setting ──────────────────────────────────────────────
        for (tod in timesOfDay) {
            entries += entry(4, tod.displayTod(), "", "", tod, "")
        }
        for (lv in lungVolumes) {
            entries += entry(4, lv.displayLv(), lv, "", "", "")
        }
        for (pt in prepTypes) {
            entries += entry(4, pt.displayPt(), "", pt, "", "")
        }
        for (pos in postures) {
            entries += entry(4, pos.displayPos(), "", "", "", pos)
        }

        // ── 3🏆 Two-setting pairs ──────────────────────────────────────────
        // tod × lv
        for (tod in timesOfDay) for (lv in lungVolumes) {
            entries += entry(3, "${tod.displayTod()} · ${lv.displayLv()}", lv, "", tod, "")
        }
        // tod × pt
        for (tod in timesOfDay) for (pt in prepTypes) {
            entries += entry(3, "${tod.displayTod()} · ${pt.displayPt()}", "", pt, tod, "")
        }
        // tod × pos
        for (tod in timesOfDay) for (pos in postures) {
            entries += entry(3, "${tod.displayTod()} · ${pos.displayPos()}", "", "", tod, pos)
        }
        // lv × pt
        for (lv in lungVolumes) for (pt in prepTypes) {
            entries += entry(3, "${lv.displayLv()} · ${pt.displayPt()}", lv, pt, "", "")
        }
        // lv × pos
        for (lv in lungVolumes) for (pos in postures) {
            entries += entry(3, "${lv.displayLv()} · ${pos.displayPos()}", lv, "", "", pos)
        }
        // pt × pos
        for (pt in prepTypes) for (pos in postures) {
            entries += entry(3, "${pt.displayPt()} · ${pos.displayPos()}", "", pt, "", pos)
        }

        // ── 2🏆 Three-setting combos ────────────────────────────────────────
        // tod × lv × pt
        for (tod in timesOfDay) for (lv in lungVolumes) for (pt in prepTypes) {
            entries += entry(2, "${tod.displayTod()} · ${lv.displayLv()} · ${pt.displayPt()}", lv, pt, tod, "")
        }
        // tod × lv × pos
        for (tod in timesOfDay) for (lv in lungVolumes) for (pos in postures) {
            entries += entry(2, "${tod.displayTod()} · ${lv.displayLv()} · ${pos.displayPos()}", lv, "", tod, pos)
        }
        // tod × pt × pos
        for (tod in timesOfDay) for (pt in prepTypes) for (pos in postures) {
            entries += entry(2, "${tod.displayTod()} · ${pt.displayPt()} · ${pos.displayPos()}", "", pt, tod, pos)
        }
        // lv × pt × pos
        for (lv in lungVolumes) for (pt in prepTypes) for (pos in postures) {
            entries += entry(2, "${lv.displayLv()} · ${pt.displayPt()} · ${pos.displayPos()}", lv, pt, "", pos)
        }

        // ── 1🏆 Exact 4-setting combos ──────────────────────────────────────
        for (tod in timesOfDay) for (lv in lungVolumes) for (pt in prepTypes) for (pos in postures) {
            entries += entry(
                1,
                "${tod.displayTod()} · ${lv.displayLv()} · ${pt.displayPt()} · ${pos.displayPos()}",
                lv, pt, tod, pos
            )
        }

        entries
    }

    // ── Paginated all-records (for the All Records screen) ───────────────────

    /**
     * Fetches a page of records with optional settings filters and an optional list of
     * event types to include. Pass empty [eventTypes] to include everything.
     */
    suspend fun getPagedRecords(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        eventTypes: List<String?>,   // null element = free hold
        pageSize: Int,
        offset: Int
    ): List<ApneaRecordEntity> = withContext(ioDispatcher) {
        val includeAll = eventTypes.isEmpty()
        val includeNullType = includeAll || eventTypes.contains(null)
        val namedTypes = if (includeAll) emptyList() else eventTypes.filterNotNull()

        when {
            includeAll -> {
                dao.getPagedAll(lungVolume, prepType, timeOfDay, posture, pageSize, offset)
            }
            includeNullType && namedTypes.isEmpty() -> {
                dao.getPagedFreeHolds(lungVolume, prepType, timeOfDay, posture, pageSize, offset)
            }
            includeNullType -> {
                val freeHolds = dao.getPagedFreeHolds(lungVolume, prepType, timeOfDay, posture, pageSize, offset)
                val named = namedTypes.flatMap { type ->
                    dao.getPagedByTableType(lungVolume, prepType, timeOfDay, posture, type, pageSize, offset)
                }
                (freeHolds + named).sortedByDescending { it.timestamp }.take(pageSize)
            }
            else -> {
                namedTypes.flatMap { type ->
                    dao.getPagedByTableType(lungVolume, prepType, timeOfDay, posture, type, pageSize, offset)
                }.sortedByDescending { it.timestamp }.take(pageSize)
            }
        }
    }

    /**
     * Fetches a page of free-hold records that were personal bests at the time they happened.
     */
    suspend fun getPagedPersonalBestFreeHolds(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        pageSize: Int,
        offset: Int
    ): List<ApneaRecordEntity> = withContext(ioDispatcher) {
        dao.getPagedPersonalBestFreeHolds(lungVolume, prepType, timeOfDay, posture, pageSize, offset)
    }

    suspend fun getById(recordId: Long): ApneaRecordEntity? =
        dao.getById(recordId)

    suspend fun saveRecord(entity: ApneaRecordEntity): Long =
        dao.insert(entity)

    /** Update an existing record (e.g. after the user edits its settings). */
    suspend fun updateRecord(entity: ApneaRecordEntity) =
        dao.update(entity)

    /** Permanently delete a record; CASCADE removes its telemetry automatically. */
    suspend fun deleteRecord(recordId: Long) =
        dao.deleteById(recordId)

    // ── Free-hold telemetry ───────────────────────────────────────────────

    suspend fun saveTelemetry(samples: List<FreeHoldTelemetryEntity>) =
        telemetryDao.insertAll(samples)

    suspend fun getTelemetryForRecord(recordId: Long): List<FreeHoldTelemetryEntity> =
        telemetryDao.getForRecord(recordId)

    // ── Stats (filtered by settings) ──────────────────────────────────────

    /**
     * Returns a [Flow] of [ApneaStats] that reacts to any DB change.
     */
    fun getStats(lungVolume: String, prepType: String, timeOfDay: String, posture: String): Flow<ApneaStats> {
        // Group A: activity counts (5)
        val groupA = combine(
            dao.countFreeHolds(lungVolume, prepType, timeOfDay, posture),
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, "O2"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, "CO2"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, "PROGRESSIVE_O2"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, "MIN_BREATH"),
        ) { fh, o2, co2, progO2, minB -> listOf<Any?>(fh, o2, co2, progO2, minB) }

        // Group B: activity counts cont. + overall HR extremes (5)
        val groupB = combine(
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, "WONKA_FIRST_CONTRACTION"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, "WONKA_ENDURANCE"),
            dao.getMaxHrEver(lungVolume, prepType, timeOfDay, posture),
            dao.getMaxHrRecordId(lungVolume, prepType, timeOfDay, posture),
            dao.getMinHrEver(lungVolume, prepType, timeOfDay, posture),
        ) { wc, we, maxHr, maxHrId, minHr -> listOf<Any?>(wc, we, maxHr, maxHrId, minHr) }

        // Group C: overall HR/SpO2 extremes cont. (5)
        val groupC = combine(
            dao.getMinHrRecordId(lungVolume, prepType, timeOfDay, posture),
            dao.getLowestSpO2Ever(lungVolume, prepType, timeOfDay, posture),
            dao.getLowestSpO2RecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxStartHr(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxStartHrRecordId(lungVolume, prepType, timeOfDay, posture),
        ) { minHrId, loSpO2, loSpO2Id, mxSHr, mxSHrId -> listOf<Any?>(minHrId, loSpO2, loSpO2Id, mxSHr, mxSHrId) }

        // Group D: start extremes (5)
        val groupD = combine(
            telemetryDao.getMinStartHr(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinStartHrRecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxStartSpO2(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxStartSpO2RecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinStartSpO2(lungVolume, prepType, timeOfDay, posture),
        ) { mnSHr, mnSHrId, mxSSp, mxSSpId, mnSSp -> listOf<Any?>(mnSHr, mnSHrId, mxSSp, mxSSpId, mnSSp) }

        // Group E: start SpO2 cont. + end extremes (5)
        val groupE = combine(
            telemetryDao.getMinStartSpO2RecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxEndHr(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxEndHrRecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinEndHr(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinEndHrRecordId(lungVolume, prepType, timeOfDay, posture),
        ) { mnSSpId, mxEHr, mxEHrId, mnEHr, mnEHrId -> listOf<Any?>(mnSSpId, mxEHr, mxEHrId, mnEHr, mnEHrId) }

        // Group F: end SpO2 extremes (4)
        val groupF = combine(
            telemetryDao.getMaxEndSpO2(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxEndSpO2RecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinEndSpO2(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinEndSpO2RecordId(lungVolume, prepType, timeOfDay, posture),
        ) { mxESp, mxESpId, mnESp, mnESpId -> listOf<Any?>(mxESp, mxESpId, mnESp, mnESpId) }

        // Merge all groups
        return combine(groupA, groupB, groupC, groupD, groupE) { a, b, c, d, e ->
            a + b + c + d + e
        }.combine(groupF) { abcde, f ->
            val v = abcde + f
            ApneaStats(
                freeHoldCount            = v[0] as Int,
                o2TableCount             = v[1] as Int,
                co2TableCount            = v[2] as Int,
                progressiveO2Count       = v[3] as Int,
                minBreathCount           = v[4] as Int,
                wonkaContractionCount    = v[5] as Int,
                wonkaEnduranceCount      = v[6] as Int,
                maxHrEver                = v[7] as? Float,
                maxHrEverRecordId        = v[8] as? Long,
                minHrEver                = v[9] as? Float,
                minHrEverRecordId        = v[10] as? Long,
                lowestSpO2Ever           = v[11] as? Int,
                lowestSpO2EverRecordId   = v[12] as? Long,
                maxStartHr               = v[13] as? Int,
                maxStartHrRecordId       = v[14] as? Long,
                minStartHr               = v[15] as? Int,
                minStartHrRecordId       = v[16] as? Long,
                maxStartSpO2             = v[17] as? Int,
                maxStartSpO2RecordId     = v[18] as? Long,
                minStartSpO2             = v[19] as? Int,
                minStartSpO2RecordId     = v[20] as? Long,
                maxEndHr                 = v[21] as? Int,
                maxEndHrRecordId         = v[22] as? Long,
                minEndHr                 = v[23] as? Int,
                minEndHrRecordId         = v[24] as? Long,
                maxEndSpO2               = v[25] as? Int,
                maxEndSpO2RecordId       = v[26] as? Long,
                minEndSpO2               = v[27] as? Int,
                minEndSpO2RecordId       = v[28] as? Long,
            )
        }
    }

    // ── Stats (all settings combined) ─────────────────────────────────────

    fun getStatsAll(): Flow<ApneaStats> {
        val groupA = combine(
            dao.countFreeHoldsAll(),
            dao.countByTableTypeAll("O2"),
            dao.countByTableTypeAll("CO2"),
            dao.countByTableTypeAll("PROGRESSIVE_O2"),
            dao.countByTableTypeAll("MIN_BREATH"),
        ) { fh, o2, co2, progO2, minB -> listOf<Any?>(fh, o2, co2, progO2, minB) }

        val groupB = combine(
            dao.countByTableTypeAll("WONKA_FIRST_CONTRACTION"),
            dao.countByTableTypeAll("WONKA_ENDURANCE"),
            dao.getMaxHrEverAll(),
            dao.getMaxHrRecordIdAll(),
            dao.getMinHrEverAll(),
        ) { wc, we, maxHr, maxHrId, minHr -> listOf<Any?>(wc, we, maxHr, maxHrId, minHr) }

        val groupC = combine(
            dao.getMinHrRecordIdAll(),
            dao.getLowestSpO2EverAll(),
            dao.getLowestSpO2RecordIdAll(),
            telemetryDao.getMaxStartHrAll(),
            telemetryDao.getMaxStartHrRecordIdAll(),
        ) { minHrId, loSpO2, loSpO2Id, mxSHr, mxSHrId -> listOf<Any?>(minHrId, loSpO2, loSpO2Id, mxSHr, mxSHrId) }

        val groupD = combine(
            telemetryDao.getMinStartHrAll(),
            telemetryDao.getMinStartHrRecordIdAll(),
            telemetryDao.getMaxStartSpO2All(),
            telemetryDao.getMaxStartSpO2RecordIdAll(),
            telemetryDao.getMinStartSpO2All(),
        ) { mnSHr, mnSHrId, mxSSp, mxSSpId, mnSSp -> listOf<Any?>(mnSHr, mnSHrId, mxSSp, mxSSpId, mnSSp) }

        val groupE = combine(
            telemetryDao.getMinStartSpO2RecordIdAll(),
            telemetryDao.getMaxEndHrAll(),
            telemetryDao.getMaxEndHrRecordIdAll(),
            telemetryDao.getMinEndHrAll(),
            telemetryDao.getMinEndHrRecordIdAll(),
        ) { mnSSpId, mxEHr, mxEHrId, mnEHr, mnEHrId -> listOf<Any?>(mnSSpId, mxEHr, mxEHrId, mnEHr, mnEHrId) }

        val groupF = combine(
            telemetryDao.getMaxEndSpO2All(),
            telemetryDao.getMaxEndSpO2RecordIdAll(),
            telemetryDao.getMinEndSpO2All(),
            telemetryDao.getMinEndSpO2RecordIdAll(),
        ) { mxESp, mxESpId, mnESp, mnESpId -> listOf<Any?>(mxESp, mxESpId, mnESp, mnESpId) }

        return combine(groupA, groupB, groupC, groupD, groupE) { a, b, c, d, e ->
            a + b + c + d + e
        }.combine(groupF) { abcde, f ->
            val v = abcde + f
            ApneaStats(
                freeHoldCount            = v[0] as Int,
                o2TableCount             = v[1] as Int,
                co2TableCount            = v[2] as Int,
                progressiveO2Count       = v[3] as Int,
                minBreathCount           = v[4] as Int,
                wonkaContractionCount    = v[5] as Int,
                wonkaEnduranceCount      = v[6] as Int,
                maxHrEver                = v[7] as? Float,
                maxHrEverRecordId        = v[8] as? Long,
                minHrEver                = v[9] as? Float,
                minHrEverRecordId        = v[10] as? Long,
                lowestSpO2Ever           = v[11] as? Int,
                lowestSpO2EverRecordId   = v[12] as? Long,
                maxStartHr               = v[13] as? Int,
                maxStartHrRecordId       = v[14] as? Long,
                minStartHr               = v[15] as? Int,
                minStartHrRecordId       = v[16] as? Long,
                maxStartSpO2             = v[17] as? Int,
                maxStartSpO2RecordId     = v[18] as? Long,
                minStartSpO2             = v[19] as? Int,
                minStartSpO2RecordId     = v[20] as? Long,
                maxEndHr                 = v[21] as? Int,
                maxEndHrRecordId         = v[22] as? Long,
                minEndHr                 = v[23] as? Int,
                minEndHrRecordId         = v[24] as? Long,
                maxEndSpO2               = v[25] as? Int,
                maxEndSpO2RecordId       = v[26] as? Long,
                minEndSpO2               = v[27] as? Int,
                minEndSpO2RecordId       = v[28] as? Long,
            )
        }
    }
}

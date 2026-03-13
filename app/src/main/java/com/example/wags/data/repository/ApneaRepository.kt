package com.example.wags.data.repository

import com.example.wags.data.db.dao.ApneaRecordDao
import com.example.wags.data.db.dao.FreeHoldTelemetryDao
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.domain.model.ApneaStats
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
    fun getBySettings(lungVolume: String, prepType: String, timeOfDay: String): Flow<List<ApneaRecordEntity>> =
        dao.getBySettings(lungVolume, prepType, timeOfDay)

    /**
     * The [limit] most recent records for a given settings combination, across ALL event types.
     * Used by the Recent Records section on the main Apnea screen.
     */
    fun getRecentBySettings(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        limit: Int = 10
    ): Flow<List<ApneaRecordEntity>> =
        dao.getRecentBySettings(lungVolume, prepType, timeOfDay, limit)

    /** Best free-hold duration for the current settings combination. */
    fun getBestFreeHold(lungVolume: String, prepType: String, timeOfDay: String): Flow<Long?> =
        dao.getBestFreeHold(lungVolume, prepType, timeOfDay)

    /** recordId of the best free-hold for the current settings combination. */
    fun getBestFreeHoldRecordId(lungVolume: String, prepType: String, timeOfDay: String): Flow<Long?> =
        dao.getBestFreeHoldRecordId(lungVolume, prepType, timeOfDay)

    // ── Paginated all-records (for the All Records screen) ───────────────────

    /**
     * Fetches a page of records with optional settings filters and an optional list of
     * event types to include. Pass empty [eventTypes] to include everything.
     *
     * [lungVolume], [prepType], [timeOfDay] — pass "" to skip that filter.
     * [eventTypes] — list of tableType strings to include; null entry means "Free Hold" (tableType IS NULL).
     *   Pass null or empty list to include all types.
     */
    suspend fun getPagedRecords(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        eventTypes: List<String?>,   // null element = free hold
        pageSize: Int,
        offset: Int
    ): List<ApneaRecordEntity> = withContext(ioDispatcher) {
        val includeAll = eventTypes.isEmpty()
        val includeNullType = includeAll || eventTypes.contains(null)
        val namedTypes = if (includeAll) emptyList() else eventTypes.filterNotNull()

        when {
            includeAll -> {
                // No type filter — just settings filter
                dao.getPagedAll(lungVolume, prepType, timeOfDay, pageSize, offset)
            }
            includeNullType && namedTypes.isEmpty() -> {
                // Only free holds
                dao.getPagedFreeHolds(lungVolume, prepType, timeOfDay, pageSize, offset)
            }
            includeNullType -> {
                // Free holds + specific named types — fetch both and merge
                val freeHolds = dao.getPagedFreeHolds(lungVolume, prepType, timeOfDay, pageSize, offset)
                val named = namedTypes.flatMap { type ->
                    dao.getPagedByTableType(lungVolume, prepType, timeOfDay, type, pageSize, offset)
                }
                (freeHolds + named).sortedByDescending { it.timestamp }.take(pageSize)
            }
            else -> {
                // Only named types (no free holds)
                namedTypes.flatMap { type ->
                    dao.getPagedByTableType(lungVolume, prepType, timeOfDay, type, pageSize, offset)
                }.sortedByDescending { it.timestamp }.take(pageSize)
            }
        }
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
     * We split the 35 sub-flows into groups of ≤5 (the max combine arity),
     * then merge the groups together.
     */
    fun getStats(lungVolume: String, prepType: String, timeOfDay: String): Flow<ApneaStats> {
        // Group A: activity counts (5)
        val groupA = combine(
            dao.countFreeHolds(lungVolume, prepType, timeOfDay),
            dao.countByTableType(lungVolume, prepType, timeOfDay, "O2"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, "CO2"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, "PROGRESSIVE_O2"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, "MIN_BREATH"),
        ) { fh, o2, co2, progO2, minB -> listOf<Any?>(fh, o2, co2, progO2, minB) }

        // Group B: activity counts cont. + overall HR extremes (5)
        val groupB = combine(
            dao.countByTableType(lungVolume, prepType, timeOfDay, "WONKA_FIRST_CONTRACTION"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, "WONKA_ENDURANCE"),
            dao.getMaxHrEver(lungVolume, prepType, timeOfDay),
            dao.getMaxHrRecordId(lungVolume, prepType, timeOfDay),
            dao.getMinHrEver(lungVolume, prepType, timeOfDay),
        ) { wc, we, maxHr, maxHrId, minHr -> listOf<Any?>(wc, we, maxHr, maxHrId, minHr) }

        // Group C: overall HR/SpO2 extremes cont. (5)
        val groupC = combine(
            dao.getMinHrRecordId(lungVolume, prepType, timeOfDay),
            dao.getLowestSpO2Ever(lungVolume, prepType, timeOfDay),
            dao.getLowestSpO2RecordId(lungVolume, prepType, timeOfDay),
            telemetryDao.getMaxStartHr(lungVolume, prepType, timeOfDay),
            telemetryDao.getMaxStartHrRecordId(lungVolume, prepType, timeOfDay),
        ) { minHrId, loSpO2, loSpO2Id, mxSHr, mxSHrId -> listOf<Any?>(minHrId, loSpO2, loSpO2Id, mxSHr, mxSHrId) }

        // Group D: start extremes (5)
        val groupD = combine(
            telemetryDao.getMinStartHr(lungVolume, prepType, timeOfDay),
            telemetryDao.getMinStartHrRecordId(lungVolume, prepType, timeOfDay),
            telemetryDao.getMaxStartSpO2(lungVolume, prepType, timeOfDay),
            telemetryDao.getMaxStartSpO2RecordId(lungVolume, prepType, timeOfDay),
            telemetryDao.getMinStartSpO2(lungVolume, prepType, timeOfDay),
        ) { mnSHr, mnSHrId, mxSSp, mxSSpId, mnSSp -> listOf<Any?>(mnSHr, mnSHrId, mxSSp, mxSSpId, mnSSp) }

        // Group E: start SpO2 cont. + end extremes (5)
        val groupE = combine(
            telemetryDao.getMinStartSpO2RecordId(lungVolume, prepType, timeOfDay),
            telemetryDao.getMaxEndHr(lungVolume, prepType, timeOfDay),
            telemetryDao.getMaxEndHrRecordId(lungVolume, prepType, timeOfDay),
            telemetryDao.getMinEndHr(lungVolume, prepType, timeOfDay),
            telemetryDao.getMinEndHrRecordId(lungVolume, prepType, timeOfDay),
        ) { mnSSpId, mxEHr, mxEHrId, mnEHr, mnEHrId -> listOf<Any?>(mnSSpId, mxEHr, mxEHrId, mnEHr, mnEHrId) }

        // Group F: end SpO2 extremes (4)
        val groupF = combine(
            telemetryDao.getMaxEndSpO2(lungVolume, prepType, timeOfDay),
            telemetryDao.getMaxEndSpO2RecordId(lungVolume, prepType, timeOfDay),
            telemetryDao.getMinEndSpO2(lungVolume, prepType, timeOfDay),
            telemetryDao.getMinEndSpO2RecordId(lungVolume, prepType, timeOfDay),
        ) { mxESp, mxESpId, mnESp, mnESpId -> listOf<Any?>(mxESp, mxESpId, mnESp, mnESpId) }

        // Merge all groups
        return combine(groupA, groupB, groupC, groupD, groupE) { a, b, c, d, e ->
            a + b + c + d + e
        }.combine(groupF) { abcde, f ->
            val v = abcde + f
            // Indices:
            // 0  freeHoldCount
            // 1  o2TableCount
            // 2  co2TableCount
            // 3  progressiveO2Count
            // 4  minBreathCount
            // 5  wonkaContractionCount
            // 6  wonkaEnduranceCount
            // 7  maxHrEver
            // 8  maxHrEverRecordId
            // 9  minHrEver
            // 10 minHrEverRecordId
            // 11 lowestSpO2Ever
            // 12 lowestSpO2EverRecordId
            // 13 maxStartHr
            // 14 maxStartHrRecordId
            // 15 minStartHr
            // 16 minStartHrRecordId
            // 17 maxStartSpO2
            // 18 maxStartSpO2RecordId
            // 19 minStartSpO2
            // 20 minStartSpO2RecordId
            // 21 maxEndHr
            // 22 maxEndHrRecordId
            // 23 minEndHr
            // 24 minEndHrRecordId
            // 25 maxEndSpO2
            // 26 maxEndSpO2RecordId
            // 27 minEndSpO2
            // 28 minEndSpO2RecordId
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

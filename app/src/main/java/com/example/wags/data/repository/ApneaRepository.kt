package com.example.wags.data.repository

import com.example.wags.data.db.dao.ApneaRecordDao
import com.example.wags.data.db.dao.ApneaSongLogDao
import com.example.wags.data.db.dao.FreeHoldTelemetryDao
import com.example.wags.data.db.dao.buildBestFreeHoldQuery
import com.example.wags.data.db.dao.buildIsBestQuery
import com.example.wags.data.db.dao.buildWasBestAtTimeQuery
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSongLogEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.domain.model.ApneaStats
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.SpotifySong
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
    private val songLogDao: ApneaSongLogDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    fun getLatestRecords(limit: Int = 20): Flow<List<ApneaRecordEntity>> =
        dao.getLatest(limit)

    fun getByType(type: String): Flow<List<ApneaRecordEntity>> =
        dao.getByType(type)

    /** All records matching the current 5-setting combination (for history / recent records). */
    fun getBySettings(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<List<ApneaRecordEntity>> =
        dao.getBySettings(lungVolume, prepType, timeOfDay, posture, audio)

    /** The [limit] most recent records for a given 5-setting combination, across ALL event types. */
    fun getRecentBySettings(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String,
        limit: Int = 10
    ): Flow<List<ApneaRecordEntity>> =
        dao.getRecentBySettings(lungVolume, prepType, timeOfDay, posture, audio, limit)

    /** Best free-hold duration for the current 5-setting combination. */
    fun getBestFreeHold(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Long?> =
        dao.getBestFreeHold(lungVolume, prepType, timeOfDay, posture, audio)

    /** One-shot (suspend) best free-hold duration for a given 5-setting combination. */
    suspend fun getBestFreeHoldOnce(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldOnce(lungVolume, prepType, timeOfDay, posture, audio) }

    /** recordId of the best free-hold for the current 5-setting combination. */
    fun getBestFreeHoldRecordId(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<Long?> =
        dao.getBestFreeHoldRecordId(lungVolume, prepType, timeOfDay, posture, audio)

    // ── Broader personal-best queries (one-shot, for PB celebration) ──────────

    /** Best free-hold across ALL settings (global PB). */
    suspend fun getBestFreeHoldGlobal(): Long? =
        withContext(ioDispatcher) { dao.getBestFreeHoldGlobal() }

    /**
     * Determines the **broadest** personal-best category that a new hold of
     * [durationMs] beats, given the hold's 5 settings.
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
        posture: String,
        audio: String
    ): PersonalBestResult? = withContext(ioDispatcher) {
        // ── Exact settings (5 constraints) ─────────────────────────────────
        val exactBest = dao.getBestFreeHoldOnce(lungVolume, prepType, timeOfDay, posture, audio)
        val isExactPb = exactBest == null || durationMs > exactBest
        if (!isExactPb) return@withContext null   // not even a PB for exact settings

        fun fmt(s: String): String = s.lowercase().replace('_', ' ')
            .replaceFirstChar { it.uppercase() }

        // ── Global (0 constraints) ─────────────────────────────────────────
        val globalBest = dao.getBestFreeHoldGlobal()
        if (globalBest == null || durationMs > globalBest) {
            return@withContext PersonalBestResult(durationMs, PersonalBestCategory.GLOBAL, "all settings")
        }

        // ── Single-setting categories (1 constraint, 4 relaxed) ────────────
        val settings = mapOf(
            "timeOfDay"  to timeOfDay,
            "lungVolume" to lungVolume,
            "prepType"   to prepType,
            "posture"    to posture,
            "audio"      to audio
        )
        for ((key, value) in settings) {
            val best = dao.getBestFreeHoldDynamic(
                buildBestFreeHoldQuery(
                    timeOfDay  = if (key == "timeOfDay")  value else null,
                    lungVolume = if (key == "lungVolume") value else null,
                    prepType   = if (key == "prepType")   value else null,
                    posture    = if (key == "posture")    value else null,
                    audio      = if (key == "audio")      value else null
                )
            )
            if (best == null || durationMs > best) {
                return@withContext PersonalBestResult(durationMs, PersonalBestCategory.ONE_SETTING, fmt(value))
            }
        }

        // ── Two-setting categories (2 constraints, 3 relaxed) ──────────────
        val settingsList = settings.entries.toList()
        for (i in settingsList.indices) {
            for (j in i + 1 until settingsList.size) {
                val a = settingsList[i]; val b = settingsList[j]
                val best = dao.getBestFreeHoldDynamic(
                    buildBestFreeHoldQuery(
                        timeOfDay  = if (a.key == "timeOfDay"  || b.key == "timeOfDay")  timeOfDay  else null,
                        lungVolume = if (a.key == "lungVolume" || b.key == "lungVolume") lungVolume else null,
                        prepType   = if (a.key == "prepType"   || b.key == "prepType")   prepType   else null,
                        posture    = if (a.key == "posture"    || b.key == "posture")    posture    else null,
                        audio      = if (a.key == "audio"      || b.key == "audio")      audio      else null
                    )
                )
                if (best == null || durationMs > best) {
                    return@withContext PersonalBestResult(
                        durationMs, PersonalBestCategory.TWO_SETTINGS,
                        "${fmt(a.value)} · ${fmt(b.value)}"
                    )
                }
            }
        }

        // ── Three-setting categories (3 constraints, 2 relaxed) ────────────
        for (i in settingsList.indices) {
            for (j in i + 1 until settingsList.size) {
                for (k in j + 1 until settingsList.size) {
                    val a = settingsList[i]; val b = settingsList[j]; val c = settingsList[k]
                    val keys = setOf(a.key, b.key, c.key)
                    val best = dao.getBestFreeHoldDynamic(
                        buildBestFreeHoldQuery(
                            timeOfDay  = if ("timeOfDay"  in keys) timeOfDay  else null,
                            lungVolume = if ("lungVolume" in keys) lungVolume else null,
                            prepType   = if ("prepType"   in keys) prepType   else null,
                            posture    = if ("posture"    in keys) posture    else null,
                            audio      = if ("audio"      in keys) audio      else null
                        )
                    )
                    if (best == null || durationMs > best) {
                        return@withContext PersonalBestResult(
                            durationMs, PersonalBestCategory.THREE_SETTINGS,
                            "${fmt(a.value)} · ${fmt(b.value)} · ${fmt(c.value)}"
                        )
                    }
                }
            }
        }

        // ── Four-setting categories (4 constraints, 1 relaxed) ─────────────
        for (i in settingsList.indices) {
            val relaxed = settingsList[i]
            val keys = settings.keys - relaxed.key
            val best = dao.getBestFreeHoldDynamic(
                buildBestFreeHoldQuery(
                    timeOfDay  = if ("timeOfDay"  in keys) timeOfDay  else null,
                    lungVolume = if ("lungVolume" in keys) lungVolume else null,
                    prepType   = if ("prepType"   in keys) prepType   else null,
                    posture    = if ("posture"    in keys) posture    else null,
                    audio      = if ("audio"      in keys) audio      else null
                )
            )
            if (best == null || durationMs > best) {
                val included = settingsList.filter { it.key != relaxed.key }
                return@withContext PersonalBestResult(
                    durationMs, PersonalBestCategory.FOUR_SETTINGS,
                    included.joinToString(" · ") { fmt(it.value) }
                )
            }
        }

        // ── Exact only ─────────────────────────────────────────────────────
        PersonalBestResult(
            durationMs  = durationMs,
            category    = PersonalBestCategory.EXACT,
            description = "${fmt(timeOfDay)} · ${fmt(lungVolume)} · ${fmt(prepType)} · ${fmt(posture)} · ${fmt(audio)}"
        )
    }

    /**
     * Computes the broadest [PersonalBestCategory] that the current best record
     * for the given 5-setting combination holds **right now**.
     *
     * Returns null if there is no free-hold record for these settings.
     */
    suspend fun getBestRecordTrophyLevel(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): PersonalBestCategory? = withContext(ioDispatcher) {
        val recordId = dao.getBestFreeHoldRecordIdOnce(lungVolume, prepType, timeOfDay, posture, audio)
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

        val lv  = record.lungVolume
        val pt  = record.prepType
        val tod = record.timeOfDay
        val pos = record.posture
        val aud = record.audio

        val settings = mapOf(
            "timeOfDay"  to tod,
            "lungVolume" to lv,
            "prepType"   to pt,
            "posture"    to pos,
            "audio"      to aud
        )
        val settingsList = settings.entries.toList()

        val badges = mutableListOf<RecordPbBadge>()

        // ── Global ──────────────────────────────────────────────────────────
        val wasGlobal = dao.wasBestAtTimeDynamic(buildWasBestAtTimeQuery(recordId, null, null, null, null, null)) == 1
        if (wasGlobal) {
            val isGlobal = dao.isBestDynamic(buildIsBestQuery(recordId, null, null, null, null, null)) == 1
            badges.add(RecordPbBadge(PersonalBestCategory.GLOBAL, "All settings", isGlobal))
        }

        // ── Single-setting ──────────────────────────────────────────────────
        for ((key, value) in settingsList) {
            val wasBest = dao.wasBestAtTimeDynamic(
                buildWasBestAtTimeQuery(
                    recordId,
                    timeOfDay  = if (key == "timeOfDay")  value else null,
                    lungVolume = if (key == "lungVolume") value else null,
                    prepType   = if (key == "prepType")   value else null,
                    posture    = if (key == "posture")    value else null,
                    audio      = if (key == "audio")      value else null
                )
            ) == 1
            if (wasBest) {
                val isBest = dao.isBestDynamic(
                    buildIsBestQuery(
                        recordId,
                        timeOfDay  = if (key == "timeOfDay")  value else null,
                        lungVolume = if (key == "lungVolume") value else null,
                        prepType   = if (key == "prepType")   value else null,
                        posture    = if (key == "posture")    value else null,
                        audio      = if (key == "audio")      value else null
                    )
                ) == 1
                badges.add(RecordPbBadge(PersonalBestCategory.ONE_SETTING, fmt(value), isBest))
            }
        }

        // ── Two-setting ─────────────────────────────────────────────────────
        for (i in settingsList.indices) {
            for (j in i + 1 until settingsList.size) {
                val a = settingsList[i]; val b = settingsList[j]
                val keys = setOf(a.key, b.key)
                val wasBest = dao.wasBestAtTimeDynamic(
                    buildWasBestAtTimeQuery(
                        recordId,
                        timeOfDay  = if ("timeOfDay"  in keys) tod else null,
                        lungVolume = if ("lungVolume" in keys) lv  else null,
                        prepType   = if ("prepType"   in keys) pt  else null,
                        posture    = if ("posture"    in keys) pos else null,
                        audio      = if ("audio"      in keys) aud else null
                    )
                ) == 1
                if (wasBest) {
                    val isBest = dao.isBestDynamic(
                        buildIsBestQuery(
                            recordId,
                            timeOfDay  = if ("timeOfDay"  in keys) tod else null,
                            lungVolume = if ("lungVolume" in keys) lv  else null,
                            prepType   = if ("prepType"   in keys) pt  else null,
                            posture    = if ("posture"    in keys) pos else null,
                            audio      = if ("audio"      in keys) aud else null
                        )
                    ) == 1
                    badges.add(RecordPbBadge(PersonalBestCategory.TWO_SETTINGS, "${fmt(a.value)} · ${fmt(b.value)}", isBest))
                }
            }
        }

        // ── Three-setting ───────────────────────────────────────────────────
        for (i in settingsList.indices) {
            for (j in i + 1 until settingsList.size) {
                for (k in j + 1 until settingsList.size) {
                    val a = settingsList[i]; val b = settingsList[j]; val c = settingsList[k]
                    val keys = setOf(a.key, b.key, c.key)
                    val wasBest = dao.wasBestAtTimeDynamic(
                        buildWasBestAtTimeQuery(
                            recordId,
                            timeOfDay  = if ("timeOfDay"  in keys) tod else null,
                            lungVolume = if ("lungVolume" in keys) lv  else null,
                            prepType   = if ("prepType"   in keys) pt  else null,
                            posture    = if ("posture"    in keys) pos else null,
                            audio      = if ("audio"      in keys) aud else null
                        )
                    ) == 1
                    if (wasBest) {
                        val isBest = dao.isBestDynamic(
                            buildIsBestQuery(
                                recordId,
                                timeOfDay  = if ("timeOfDay"  in keys) tod else null,
                                lungVolume = if ("lungVolume" in keys) lv  else null,
                                prepType   = if ("prepType"   in keys) pt  else null,
                                posture    = if ("posture"    in keys) pos else null,
                                audio      = if ("audio"      in keys) aud else null
                            )
                        ) == 1
                        badges.add(RecordPbBadge(PersonalBestCategory.THREE_SETTINGS, "${fmt(a.value)} · ${fmt(b.value)} · ${fmt(c.value)}", isBest))
                    }
                }
            }
        }

        // ── Four-setting ────────────────────────────────────────────────────
        for (i in settingsList.indices) {
            val relaxed = settingsList[i]
            val keys = settings.keys - relaxed.key
            val wasBest = dao.wasBestAtTimeDynamic(
                buildWasBestAtTimeQuery(
                    recordId,
                    timeOfDay  = if ("timeOfDay"  in keys) tod else null,
                    lungVolume = if ("lungVolume" in keys) lv  else null,
                    prepType   = if ("prepType"   in keys) pt  else null,
                    posture    = if ("posture"    in keys) pos else null,
                    audio      = if ("audio"      in keys) aud else null
                )
            ) == 1
            if (wasBest) {
                val isBest = dao.isBestDynamic(
                    buildIsBestQuery(
                        recordId,
                        timeOfDay  = if ("timeOfDay"  in keys) tod else null,
                        lungVolume = if ("lungVolume" in keys) lv  else null,
                        prepType   = if ("prepType"   in keys) pt  else null,
                        posture    = if ("posture"    in keys) pos else null,
                        audio      = if ("audio"      in keys) aud else null
                    )
                ) == 1
                val included = settingsList.filter { it.key != relaxed.key }
                badges.add(RecordPbBadge(PersonalBestCategory.FOUR_SETTINGS, included.joinToString(" · ") { fmt(it.value) }, isBest))
            }
        }

        // ── Exact 5-setting combo ───────────────────────────────────────────
        val wasExact = dao.wasBestAtTimeDynamic(
            buildWasBestAtTimeQuery(recordId, tod, lv, pt, pos, aud)
        ) == 1
        if (wasExact) {
            val isExact = dao.isBestDynamic(buildIsBestQuery(recordId, tod, lv, pt, pos, aud)) == 1
            badges.add(RecordPbBadge(PersonalBestCategory.EXACT, "${fmt(tod)} · ${fmt(lv)} · ${fmt(pt)} · ${fmt(pos)} · ${fmt(aud)}", isExact))
        }

        badges
    }

    /**
     * Helper: determines the broadest category for which a given record is
     * currently the best.
     */
    private suspend fun computeBroadestCurrentCategory(recordId: Long): PersonalBestCategory {
        val record = dao.getById(recordId) ?: return PersonalBestCategory.EXACT
        val tod = record.timeOfDay; val lv = record.lungVolume
        val pt  = record.prepType;  val pos = record.posture; val aud = record.audio

        if (dao.isBestDynamic(buildIsBestQuery(recordId, null, null, null, null, null)) == 1)
            return PersonalBestCategory.GLOBAL

        val settings = mapOf("timeOfDay" to tod, "lungVolume" to lv, "prepType" to pt, "posture" to pos, "audio" to aud)
        val settingsList = settings.entries.toList()

        // ONE_SETTING
        for ((key, value) in settingsList) {
            if (dao.isBestDynamic(buildIsBestQuery(
                    recordId,
                    timeOfDay  = if (key == "timeOfDay")  value else null,
                    lungVolume = if (key == "lungVolume") value else null,
                    prepType   = if (key == "prepType")   value else null,
                    posture    = if (key == "posture")    value else null,
                    audio      = if (key == "audio")      value else null
                )) == 1) return PersonalBestCategory.ONE_SETTING
        }

        // TWO_SETTINGS
        for (i in settingsList.indices) for (j in i + 1 until settingsList.size) {
            val keys = setOf(settingsList[i].key, settingsList[j].key)
            if (dao.isBestDynamic(buildIsBestQuery(
                    recordId,
                    timeOfDay  = if ("timeOfDay"  in keys) tod else null,
                    lungVolume = if ("lungVolume" in keys) lv  else null,
                    prepType   = if ("prepType"   in keys) pt  else null,
                    posture    = if ("posture"    in keys) pos else null,
                    audio      = if ("audio"      in keys) aud else null
                )) == 1) return PersonalBestCategory.TWO_SETTINGS
        }

        // THREE_SETTINGS
        for (i in settingsList.indices) for (j in i + 1 until settingsList.size) for (k in j + 1 until settingsList.size) {
            val keys = setOf(settingsList[i].key, settingsList[j].key, settingsList[k].key)
            if (dao.isBestDynamic(buildIsBestQuery(
                    recordId,
                    timeOfDay  = if ("timeOfDay"  in keys) tod else null,
                    lungVolume = if ("lungVolume" in keys) lv  else null,
                    prepType   = if ("prepType"   in keys) pt  else null,
                    posture    = if ("posture"    in keys) pos else null,
                    audio      = if ("audio"      in keys) aud else null
                )) == 1) return PersonalBestCategory.THREE_SETTINGS
        }

        // FOUR_SETTINGS
        for (i in settingsList.indices) {
            val keys = settings.keys - settingsList[i].key
            if (dao.isBestDynamic(buildIsBestQuery(
                    recordId,
                    timeOfDay  = if ("timeOfDay"  in keys) tod else null,
                    lungVolume = if ("lungVolume" in keys) lv  else null,
                    prepType   = if ("prepType"   in keys) pt  else null,
                    posture    = if ("posture"    in keys) pos else null,
                    audio      = if ("audio"      in keys) aud else null
                )) == 1) return PersonalBestCategory.FOUR_SETTINGS
        }

        return PersonalBestCategory.EXACT
    }

    // ── All Personal Bests (for the Personal Bests screen) ───────────────────

    /**
     * Builds the complete list of personal-best entries across every combination
     * of 5 settings, ordered from broadest (6🏆 global) to narrowest (1🏆 exact).
     */
    suspend fun getAllPersonalBests(): List<PersonalBestEntry> = withContext(ioDispatcher) {
        val entries = mutableListOf<PersonalBestEntry>()

        val lungVolumes = listOf("FULL", "PARTIAL", "EMPTY")
        val prepTypes   = PrepType.entries.map { it.name }
        val timesOfDay  = TimeOfDay.entries.map { it.name }
        val postures    = Posture.entries.map { it.name }
        val audios      = AudioSetting.entries.map { it.name }

        fun String.displayLv()  = if (this == "PARTIAL") "Half" else lowercase().replaceFirstChar { it.uppercase() }
        fun String.displayPt()  = PrepType.valueOf(this).displayName()
        fun String.displayTod() = TimeOfDay.valueOf(this).displayName()
        fun String.displayPos() = Posture.valueOf(this).displayName()
        fun String.displayAud() = AudioSetting.valueOf(this).displayName()

        suspend fun entry(trophies: Int, label: String, lv: String, pt: String, tod: String, pos: String, aud: String): PersonalBestEntry {
            val best = dao.getBestFreeHoldRecord(lv, pt, tod, pos, aud)
            return PersonalBestEntry(
                trophyCount = trophies,
                label       = label,
                recordId    = best?.recordId,
                durationMs  = best?.durationMs,
                timestamp   = best?.timestamp
            )
        }

        // ── 6🏆 Global ──────────────────────────────────────────────────────
        entries += entry(6, "All settings", "", "", "", "", "")

        // ── 5🏆 Single setting ──────────────────────────────────────────────
        for (tod in timesOfDay) entries += entry(5, tod.displayTod(), "", "", tod, "", "")
        for (lv  in lungVolumes) entries += entry(5, lv.displayLv(),  lv, "", "", "", "")
        for (pt  in prepTypes)   entries += entry(5, pt.displayPt(),  "", pt, "", "", "")
        for (pos in postures)    entries += entry(5, pos.displayPos(), "", "", "", pos, "")
        for (aud in audios)      entries += entry(5, aud.displayAud(), "", "", "", "", aud)

        // ── 4🏆 Two-setting pairs ──────────────────────────────────────────
        // tod × lv
        for (tod in timesOfDay) for (lv in lungVolumes)
            entries += entry(4, "${tod.displayTod()} · ${lv.displayLv()}", lv, "", tod, "", "")
        // tod × pt
        for (tod in timesOfDay) for (pt in prepTypes)
            entries += entry(4, "${tod.displayTod()} · ${pt.displayPt()}", "", pt, tod, "", "")
        // tod × pos
        for (tod in timesOfDay) for (pos in postures)
            entries += entry(4, "${tod.displayTod()} · ${pos.displayPos()}", "", "", tod, pos, "")
        // tod × aud
        for (tod in timesOfDay) for (aud in audios)
            entries += entry(4, "${tod.displayTod()} · ${aud.displayAud()}", "", "", tod, "", aud)
        // lv × pt
        for (lv in lungVolumes) for (pt in prepTypes)
            entries += entry(4, "${lv.displayLv()} · ${pt.displayPt()}", lv, pt, "", "", "")
        // lv × pos
        for (lv in lungVolumes) for (pos in postures)
            entries += entry(4, "${lv.displayLv()} · ${pos.displayPos()}", lv, "", "", pos, "")
        // lv × aud
        for (lv in lungVolumes) for (aud in audios)
            entries += entry(4, "${lv.displayLv()} · ${aud.displayAud()}", lv, "", "", "", aud)
        // pt × pos
        for (pt in prepTypes) for (pos in postures)
            entries += entry(4, "${pt.displayPt()} · ${pos.displayPos()}", "", pt, "", pos, "")
        // pt × aud
        for (pt in prepTypes) for (aud in audios)
            entries += entry(4, "${pt.displayPt()} · ${aud.displayAud()}", "", pt, "", "", aud)
        // pos × aud
        for (pos in postures) for (aud in audios)
            entries += entry(4, "${pos.displayPos()} · ${aud.displayAud()}", "", "", "", pos, aud)

        // ── 3🏆 Three-setting combos ────────────────────────────────────────
        // tod × lv × pt
        for (tod in timesOfDay) for (lv in lungVolumes) for (pt in prepTypes)
            entries += entry(3, "${tod.displayTod()} · ${lv.displayLv()} · ${pt.displayPt()}", lv, pt, tod, "", "")
        // tod × lv × pos
        for (tod in timesOfDay) for (lv in lungVolumes) for (pos in postures)
            entries += entry(3, "${tod.displayTod()} · ${lv.displayLv()} · ${pos.displayPos()}", lv, "", tod, pos, "")
        // tod × lv × aud
        for (tod in timesOfDay) for (lv in lungVolumes) for (aud in audios)
            entries += entry(3, "${tod.displayTod()} · ${lv.displayLv()} · ${aud.displayAud()}", lv, "", tod, "", aud)
        // tod × pt × pos
        for (tod in timesOfDay) for (pt in prepTypes) for (pos in postures)
            entries += entry(3, "${tod.displayTod()} · ${pt.displayPt()} · ${pos.displayPos()}", "", pt, tod, pos, "")
        // tod × pt × aud
        for (tod in timesOfDay) for (pt in prepTypes) for (aud in audios)
            entries += entry(3, "${tod.displayTod()} · ${pt.displayPt()} · ${aud.displayAud()}", "", pt, tod, "", aud)
        // tod × pos × aud
        for (tod in timesOfDay) for (pos in postures) for (aud in audios)
            entries += entry(3, "${tod.displayTod()} · ${pos.displayPos()} · ${aud.displayAud()}", "", "", tod, pos, aud)
        // lv × pt × pos
        for (lv in lungVolumes) for (pt in prepTypes) for (pos in postures)
            entries += entry(3, "${lv.displayLv()} · ${pt.displayPt()} · ${pos.displayPos()}", lv, pt, "", pos, "")
        // lv × pt × aud
        for (lv in lungVolumes) for (pt in prepTypes) for (aud in audios)
            entries += entry(3, "${lv.displayLv()} · ${pt.displayPt()} · ${aud.displayAud()}", lv, pt, "", "", aud)
        // lv × pos × aud
        for (lv in lungVolumes) for (pos in postures) for (aud in audios)
            entries += entry(3, "${lv.displayLv()} · ${pos.displayPos()} · ${aud.displayAud()}", lv, "", "", pos, aud)
        // pt × pos × aud
        for (pt in prepTypes) for (pos in postures) for (aud in audios)
            entries += entry(3, "${pt.displayPt()} · ${pos.displayPos()} · ${aud.displayAud()}", "", pt, "", pos, aud)

        // ── 2🏆 Four-setting combos ─────────────────────────────────────────
        // tod × lv × pt × pos
        for (tod in timesOfDay) for (lv in lungVolumes) for (pt in prepTypes) for (pos in postures)
            entries += entry(2, "${tod.displayTod()} · ${lv.displayLv()} · ${pt.displayPt()} · ${pos.displayPos()}", lv, pt, tod, pos, "")
        // tod × lv × pt × aud
        for (tod in timesOfDay) for (lv in lungVolumes) for (pt in prepTypes) for (aud in audios)
            entries += entry(2, "${tod.displayTod()} · ${lv.displayLv()} · ${pt.displayPt()} · ${aud.displayAud()}", lv, pt, tod, "", aud)
        // tod × lv × pos × aud
        for (tod in timesOfDay) for (lv in lungVolumes) for (pos in postures) for (aud in audios)
            entries += entry(2, "${tod.displayTod()} · ${lv.displayLv()} · ${pos.displayPos()} · ${aud.displayAud()}", lv, "", tod, pos, aud)
        // tod × pt × pos × aud
        for (tod in timesOfDay) for (pt in prepTypes) for (pos in postures) for (aud in audios)
            entries += entry(2, "${tod.displayTod()} · ${pt.displayPt()} · ${pos.displayPos()} · ${aud.displayAud()}", "", pt, tod, pos, aud)
        // lv × pt × pos × aud
        for (lv in lungVolumes) for (pt in prepTypes) for (pos in postures) for (aud in audios)
            entries += entry(2, "${lv.displayLv()} · ${pt.displayPt()} · ${pos.displayPos()} · ${aud.displayAud()}", lv, pt, "", pos, aud)

        // ── 1🏆 Exact 5-setting combos ──────────────────────────────────────
        for (tod in timesOfDay) for (lv in lungVolumes) for (pt in prepTypes) for (pos in postures) for (aud in audios)
            entries += entry(1, "${tod.displayTod()} · ${lv.displayLv()} · ${pt.displayPt()} · ${pos.displayPos()} · ${aud.displayAud()}", lv, pt, tod, pos, aud)

        entries
    }

    // ── Paginated all-records (for the All Records screen) ───────────────────

    suspend fun getPagedRecords(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String,
        eventTypes: List<String?>,
        pageSize: Int,
        offset: Int
    ): List<ApneaRecordEntity> = withContext(ioDispatcher) {
        val includeAll = eventTypes.isEmpty()
        val includeNullType = includeAll || eventTypes.contains(null)
        val namedTypes = if (includeAll) emptyList() else eventTypes.filterNotNull()

        when {
            includeAll -> dao.getPagedAll(lungVolume, prepType, timeOfDay, posture, audio, pageSize, offset)
            includeNullType && namedTypes.isEmpty() ->
                dao.getPagedFreeHolds(lungVolume, prepType, timeOfDay, posture, audio, pageSize, offset)
            includeNullType -> {
                val freeHolds = dao.getPagedFreeHolds(lungVolume, prepType, timeOfDay, posture, audio, pageSize, offset)
                val named = namedTypes.flatMap { type ->
                    dao.getPagedByTableType(lungVolume, prepType, timeOfDay, posture, audio, type, pageSize, offset)
                }
                (freeHolds + named).sortedByDescending { it.timestamp }.take(pageSize)
            }
            else -> namedTypes.flatMap { type ->
                dao.getPagedByTableType(lungVolume, prepType, timeOfDay, posture, audio, type, pageSize, offset)
            }.sortedByDescending { it.timestamp }.take(pageSize)
        }
    }

    suspend fun getPagedPersonalBestFreeHolds(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String,
        pageSize: Int,
        offset: Int
    ): List<ApneaRecordEntity> = withContext(ioDispatcher) {
        dao.getPagedPersonalBestFreeHolds(lungVolume, prepType, timeOfDay, posture, audio, pageSize, offset)
    }

    suspend fun getById(recordId: Long): ApneaRecordEntity? =
        dao.getById(recordId)

    suspend fun saveRecord(entity: ApneaRecordEntity): Long =
        dao.insert(entity)

    suspend fun updateRecord(entity: ApneaRecordEntity) =
        dao.update(entity)

    suspend fun deleteRecord(recordId: Long) =
        dao.deleteById(recordId)

    // ── Free-hold telemetry ───────────────────────────────────────────────────

    suspend fun saveTelemetry(samples: List<FreeHoldTelemetryEntity>) =
        telemetryDao.insertAll(samples)

    suspend fun getTelemetryForRecord(recordId: Long): List<FreeHoldTelemetryEntity> =
        telemetryDao.getForRecord(recordId)

    // ── Song log ──────────────────────────────────────────────────────────────

    suspend fun saveSongLog(recordId: Long, songs: List<SpotifySong>) =
        songLogDao.insertAll(songs.map { song ->
            ApneaSongLogEntity(
                recordId     = recordId,
                title        = song.title,
                artist       = song.artist,
                albumArt     = song.albumArt,
                spotifyUri   = song.spotifyUri,
                startedAtMs  = song.startedAtMs,
                endedAtMs    = song.endedAtMs
            )
        })

    suspend fun getSongLogForRecord(recordId: Long): List<SpotifySong> =
        songLogDao.getForRecord(recordId).map { entity ->
            SpotifySong(
                title        = entity.title,
                artist       = entity.artist,
                albumArt     = entity.albumArt,
                spotifyUri   = entity.spotifyUri,
                startedAtMs  = entity.startedAtMs,
                endedAtMs    = entity.endedAtMs
            )
        }

    /**
     * Returns distinct songs that have been played during breath holds.
     * Groups by spotifyUri when available, otherwise by title+artist.
     * Ordered by most recently played.
     */
    suspend fun getDistinctSongs(): List<SpotifySong> =
        songLogDao.getDistinctSongs().map { entity ->
            SpotifySong(
                title      = entity.title,
                artist     = entity.artist,
                albumArt   = entity.albumArt,
                spotifyUri = entity.spotifyUri,
                startedAtMs = entity.startedAtMs,
                endedAtMs  = entity.endedAtMs
            )
        }

    /** Deletes all song log entries (clears the song picker history). */
    suspend fun clearSongHistory() = songLogDao.deleteAll()

    // ── Stats (filtered by 5 settings) ───────────────────────────────────────

    fun getStats(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String
    ): Flow<ApneaStats> {
        val groupA = combine(
            dao.countFreeHolds(lungVolume, prepType, timeOfDay, posture, audio),
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, audio, "O2"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, audio, "CO2"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, audio, "PROGRESSIVE_O2"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, audio, "MIN_BREATH"),
        ) { fh, o2, co2, progO2, minB -> listOf<Any?>(fh, o2, co2, progO2, minB) }

        val groupB = combine(
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, audio, "WONKA_FIRST_CONTRACTION"),
            dao.countByTableType(lungVolume, prepType, timeOfDay, posture, audio, "WONKA_ENDURANCE"),
            dao.getMaxHrEver(lungVolume, prepType, timeOfDay, posture, audio),
            dao.getMaxHrRecordId(lungVolume, prepType, timeOfDay, posture, audio),
            dao.getMinHrEver(lungVolume, prepType, timeOfDay, posture, audio),
        ) { wc, we, maxHr, maxHrId, minHr -> listOf<Any?>(wc, we, maxHr, maxHrId, minHr) }

        val groupC = combine(
            dao.getMinHrRecordId(lungVolume, prepType, timeOfDay, posture, audio),
            dao.getLowestSpO2Ever(lungVolume, prepType, timeOfDay, posture, audio),
            dao.getLowestSpO2RecordId(lungVolume, prepType, timeOfDay, posture, audio),
            telemetryDao.getMaxStartHr(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxStartHrRecordId(lungVolume, prepType, timeOfDay, posture),
        ) { minHrId, loSpO2, loSpO2Id, mxSHr, mxSHrId -> listOf<Any?>(minHrId, loSpO2, loSpO2Id, mxSHr, mxSHrId) }

        val groupD = combine(
            telemetryDao.getMinStartHr(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinStartHrRecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxStartSpO2(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxStartSpO2RecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinStartSpO2(lungVolume, prepType, timeOfDay, posture),
        ) { mnSHr, mnSHrId, mxSSp, mxSSpId, mnSSp -> listOf<Any?>(mnSHr, mnSHrId, mxSSp, mxSSpId, mnSSp) }

        val groupE = combine(
            telemetryDao.getMinStartSpO2RecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxEndHr(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxEndHrRecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinEndHr(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinEndHrRecordId(lungVolume, prepType, timeOfDay, posture),
        ) { mnSSpId, mxEHr, mxEHrId, mnEHr, mnEHrId -> listOf<Any?>(mnSSpId, mxEHr, mxEHrId, mnEHr, mnEHrId) }

        val groupF = combine(
            telemetryDao.getMaxEndSpO2(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMaxEndSpO2RecordId(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinEndSpO2(lungVolume, prepType, timeOfDay, posture),
            telemetryDao.getMinEndSpO2RecordId(lungVolume, prepType, timeOfDay, posture),
        ) { mxESp, mxESpId, mnESp, mnESpId -> listOf<Any?>(mxESp, mxESpId, mnESp, mnESpId) }

        return combine(groupA, groupB, groupC, groupD, groupE) { a, b, c, d, e -> a + b + c + d + e }
            .combine(groupF) { abcde, f ->
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

    // ── Stats (all settings combined) ─────────────────────────────────────────

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

        return combine(groupA, groupB, groupC, groupD, groupE) { a, b, c, d, e -> a + b + c + d + e }
            .combine(groupF) { abcde, f ->
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

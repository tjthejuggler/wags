package com.example.wags.domain.usecase.apnea

import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.ApneaSessionEntity
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TableDifficulty
import com.example.wags.domain.model.TableLength
import com.example.wags.domain.model.TimeOfDay
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

// ── Session type filter ────────────────────────────────────────────────────────

/**
 * The apnea activity types a settings comparison can be scoped to.
 * [FREE_HOLD] maps to records whose `tableType` column is NULL.
 */
enum class ComparisonSessionType(val key: String, val label: String) {
    FREE_HOLD("FREE", "Free Holds"),
    O2_TABLE("O2", "O₂ Tables"),
    CO2_TABLE("CO2", "CO₂ Tables"),
    PROGRESSIVE_O2("PROGRESSIVE_O2", "Progressive O₂"),
    MIN_BREATH("MIN_BREATH", "Min Breath"),
    TILL_CONTRACTION("WONKA_FIRST_CONTRACTION", "Till Contraction"),
    CONTRACTION_COUNT("WONKA_ENDURANCE", "Contraction Count");

    companion object {
        val ALL_KEYS: Set<String> = entries.map { it.key }.toSet()

        /** Record tableType (null for free holds) → filter key. */
        fun keyOf(tableType: String?): String = tableType ?: FREE_HOLD.key

        fun labelOf(key: String): String = entries.firstOrNull { it.key == key }?.label ?: key
    }
}

// ── Result models ──────────────────────────────────────────────────────────────

/** One individual hold, flattened out of any session type. */
data class HoldSample(
    val holdMs: Long,
    /** Apnea record that contained this hold (for drill-down navigation). */
    val recordId: Long,
    val sessionTypeKey: String,
    val lungVolume: String,
    val prepType: String,
    val timeOfDay: String,
    val posture: String,
    val audio: String,
    /** Local hour of day (0–23) the hold started at. */
    val hourOfDay: Int,
)

/** A single extracted hold length plus the record it came from. */
private data class ExtractedHold(val recordId: Long, val holdMs: Long)

/** A single setting option's hold-time performance inside its category. */
data class SettingsOptionResult(
    val key: String,
    val displayName: String,
    val avgMs: Long,
    val bestMs: Long,
    val holdCount: Int,
    /** Percentage above/below the global average hold, e.g. +12.4 or -8.0. */
    val pctVsGlobal: Double,
    /**
     * Smart score: hold time relative to the average hold of the SAME session
     * type (100 = neutral), shrunk toward 100 for small sample sizes. This
     * makes options comparable across categories and across session-type mixes.
     */
    val smartScore: Double,
    val rankInCategory: Int,
    val prevAvgMs: Long?,
    /** % change of avg vs the previous window; null when not comparable. */
    val deltaPctVsPrev: Double?,
    /** True when fewer than [SettingsComparisonCalculator.MIN_RELIABLE_HOLDS] holds back this option. */
    val lowData: Boolean,
    /** Record containing the single best hold of this option in the window. */
    val bestRecordId: Long? = null,
)

/** One settings category (Lung Volume, Prep Type, …) with its ranked options. */
data class SettingsCategoryResult(
    val title: String,
    /** Sorted by average hold time, best first. */
    val options: List<SettingsOptionResult>,
)

/** One row of the cross-category master ranking. */
data class SettingsMasterRankEntry(
    val categoryTitle: String,
    val option: SettingsOptionResult,
    val overallRank: Int,
)

data class SettingsComparisonResult(
    val totalHolds: Int,
    val globalAvgMs: Long,
    val categories: List<SettingsCategoryResult>,
    /** Hour-of-day breakdown (own section + optional master-ranking inclusion). Null when no data. */
    val hourCategory: SettingsCategoryResult? = null,
    val masterRanking: List<SettingsMasterRankEntry>,
    val windowStart: LocalDate?,
    val windowEnd: LocalDate?,
    val prevWindowStart: LocalDate?,
    val prevWindowEnd: LocalDate?,
    val canStepBack: Boolean,
    val canStepForward: Boolean,
)

// ── Calculator ─────────────────────────────────────────────────────────────────

/**
 * Compares apnea hold-time performance across the five record settings
 * (lung volume, prep type, time of day, posture, audio).
 *
 * Per-hold lengths are recovered from every session type:
 *  - Free holds: one record = one hold (`durationMs`).
 *  - O₂/CO₂ tables: the table is deterministic given the PB snapshot and
 *    variant stored on the session row, so per-round hold lengths are
 *    regenerated with [ApneaTableGenerator] for the completed rounds.
 *  - Progressive O₂: `rounds[].actualMs` from the session's params JSON.
 *  - Min Breath: `holds[].durationMs` from the session's params JSON.
 *  - Contraction tables: `rounds[].totalHoldMs` from the session's params JSON.
 */
@Singleton
class SettingsComparisonCalculator @Inject constructor(
    private val tableGenerator: ApneaTableGenerator
) {

    /** Options with fewer holds are flagged as low-data in the UI. */
    val minReliableHolds: Int = MIN_RELIABLE_HOLDS

    fun compute(
        records: List<ApneaRecordEntity>,
        sessions: List<ApneaSessionEntity>,
        sessionTypeKeys: Set<String>,
        windowDays: Int?,
        offset: Int,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        /** When true, hour-of-day options also compete in the master ranking. */
        includeHoursInMaster: Boolean = false
    ): SettingsComparisonResult {
        val sessionByTsType = sessions.associateBy { it.timestamp to it.tableType }

        // ── Window bounds (mirrors the Graphs tab semantics) ────────────────
        val windowEnd: LocalDate?
        val windowStart: LocalDate?
        val prevEnd: LocalDate?
        val prevStart: LocalDate?
        if (windowDays == null) {
            windowEnd = null; windowStart = null; prevEnd = null; prevStart = null
        } else {
            windowEnd = today.minusDays((-offset).toLong() * windowDays)
            windowStart = windowEnd.minusDays(windowDays.toLong())
            prevEnd = windowStart
            prevStart = prevEnd.minusDays(windowDays.toLong())
        }

        // ── Extract per-hold samples for both windows in one pass ──────────
        val currentSamples = mutableListOf<HoldSample>()
        val prevSamples = mutableListOf<HoldSample>()
        var anyBeforeWindow = false

        for (record in records) {
            val typeKey = ComparisonSessionType.keyOf(record.tableType)
            if (typeKey !in sessionTypeKeys) continue
            val zdt = Instant.ofEpochMilli(record.timestamp).atZone(zone)
            val date = zdt.toLocalDate()

            val target = when {
                windowStart == null -> CURRENT
                !date.isBefore(windowStart) && !date.isAfter(windowEnd!!) -> CURRENT
                !date.isBefore(prevStart!!) && !date.isAfter(prevEnd!!) -> PREV
                date.isBefore(prevStart) -> { anyBeforeWindow = true; continue }
                else -> continue
            }
            if (windowStart != null && date.isBefore(windowStart)) anyBeforeWindow = true

            val holds = extractHolds(record, sessionByTsType)
            if (holds.isEmpty()) continue
            val bucket = if (target == CURRENT) currentSamples else prevSamples
            for (hold in holds) {
                bucket.add(
                    HoldSample(
                        holdMs = hold.holdMs,
                        recordId = hold.recordId,
                        sessionTypeKey = typeKey,
                        lungVolume = record.lungVolume,
                        prepType = record.prepType,
                        timeOfDay = record.timeOfDay,
                        posture = record.posture,
                        audio = record.audio,
                        hourOfDay = zdt.hour
                    )
                )
            }
        }

        val globalAvg = if (currentSamples.isEmpty()) 0.0
        else currentSamples.map { it.holdMs.toDouble() }.average()

        // ── Per-session-type means (legacy fallback score normalization) ──
        val typeMeans: Map<String, Double> = currentSamples
            .groupBy { it.sessionTypeKey }
            .mapValues { (_, v) -> v.map { it.holdMs.toDouble() }.average() }
            .filterValues { it > 0.0 }

        // ── Adjusted scores: ridge regression on log hold time with one ────
        // dummy per option of EVERY category (settings + session type + hour
        // of day). Each option's score is its all-else-equal effect, so
        // confounding between settings (e.g. music rarely paired with hyper
        // prep) no longer biases the comparison.
        val adjustedScores = computeAdjustedScores(currentSamples)

        // ── Build per-category results ─────────────────────────────────────
        val categories = CATEGORY_DEFS.map { def ->
            val cur = currentSamples.groupBy(def.selector)
            val prev = prevSamples.groupBy(def.selector)
            SettingsCategoryResult(
                title = def.title,
                options = buildOptions(def.title, cur, prev, def.namer, globalAvg, typeMeans, adjustedScores)
            )
        }.filter { it.options.isNotEmpty() }

        // ── Hour-of-day category (own section below the main categories) ──
        val hourCur = currentSamples.groupBy { it.hourOfDay.toString() }
        val hourPrev = prevSamples.groupBy { it.hourOfDay.toString() }
        val hourCategory = if (hourCur.isEmpty()) null else SettingsCategoryResult(
            title = "Hour of Day",
            options = buildOptions("Hour of Day", hourCur, hourPrev, ::hourName, globalAvg, typeMeans, adjustedScores)
        )

        // ── Master ranking across all categories (smart score) ─────────────
        val masterCategories =
            (if (includeHoursInMaster && hourCategory != null) categories + hourCategory else categories)
        val masterRanking = masterCategories
            .flatMap { cat -> cat.options.map { SettingsMasterRankEntry(cat.title, it, 0) } }
            .sortedWith(
                compareByDescending<SettingsMasterRankEntry> { it.option.smartScore }
                    .thenByDescending { it.option.avgMs }
            )
            .mapIndexed { idx, entry -> entry.copy(overallRank = idx + 1) }

        return SettingsComparisonResult(
            totalHolds = currentSamples.size,
            globalAvgMs = globalAvg.toLong(),
            categories = categories,
            hourCategory = hourCategory,
            masterRanking = masterRanking,
            windowStart = windowStart,
            windowEnd = windowEnd,
            prevWindowStart = prevStart,
            prevWindowEnd = prevEnd,
            canStepBack = windowDays != null && anyBeforeWindow,
            canStepForward = windowDays != null && offset < 0
        )
    }

    // ── Option aggregation ────────────────────────────────────────────────────

    /** Groups → ranked option list (sorted by avg hold, best first). */
    private fun buildOptions(
        categoryTitle: String,
        cur: Map<String, List<HoldSample>>,
        prev: Map<String, List<HoldSample>>,
        namer: (String) -> String,
        globalAvg: Double,
        typeMeans: Map<String, Double>,
        adjustedScores: Map<String, Map<String, Double>>
    ): List<SettingsOptionResult> = cur.map { (key, samples) ->
        buildOptionResult(categoryTitle, key, namer(key), samples, prev[key], globalAvg, typeMeans, adjustedScores)
    }.sortedByDescending { it.avgMs }
        .mapIndexed { idx, opt -> opt.copy(rankInCategory = idx + 1) }

    private fun buildOptionResult(
        categoryTitle: String,
        key: String,
        displayName: String,
        current: List<HoldSample>,
        prev: List<HoldSample>?,
        globalAvg: Double,
        typeMeans: Map<String, Double>,
        adjustedScores: Map<String, Map<String, Double>>
    ): SettingsOptionResult {
        val n = current.size
        val avg = current.map { it.holdMs.toDouble() }.average()
        val bestSample = current.maxByOrNull { it.holdMs }
        val best = bestSample?.holdMs ?: 0L

        // Primary: regression-adjusted, all-else-equal score.
        // Fallback (too little data to fit): session-type-normalized ratio
        // with Bayesian shrinkage toward neutral.
        val smartScore = adjustedScores[categoryTitle]?.get(key) ?: legacyScore(current, typeMeans)

        val prevAvg = prev?.takeIf { it.isNotEmpty() }?.let { it.map { h -> h.holdMs.toDouble() }.average() }
        val deltaPct = if (prevAvg != null && prevAvg > 0.0 && avg > 0.0) {
            (avg / prevAvg - 1.0) * 100.0
        } else null

        return SettingsOptionResult(
            key = key,
            displayName = displayName,
            avgMs = avg.toLong(),
            bestMs = best,
            holdCount = n,
            pctVsGlobal = if (globalAvg > 0.0) (avg / globalAvg - 1.0) * 100.0 else 0.0,
            smartScore = smartScore,
            rankInCategory = 0,
            prevAvgMs = prevAvg?.toLong(),
            deltaPctVsPrev = deltaPct,
            lowData = n < MIN_RELIABLE_HOLDS,
            bestRecordId = bestSample?.recordId
        )
    }

    /** Pre-regression score: hold vs its session type's mean, shrunk to neutral. */
    private fun legacyScore(samples: List<HoldSample>, typeMeans: Map<String, Double>): Double {
        val n = samples.size
        val ratios = samples.mapNotNull { s ->
            val mean = typeMeans[s.sessionTypeKey] ?: return@mapNotNull null
            s.holdMs.toDouble() / mean
        }
        val rawRatio = if (ratios.isNotEmpty()) ratios.average() else 1.0
        return ((n * rawRatio + SHRINKAGE_K) / (n + SHRINKAGE_K)) * 100.0
    }

    // ── Adjusted scoring (ridge regression) ───────────────────────────────────

    /**
     * Fits `ln(holdMs) = Σ optionEffects + noise` by ridge regression over
     * one-hot dummies for EVERY option of every category (lung volume, prep,
     * time of day, posture, audio, session type, hour of day). Because all
     * categories enter the model together, each option's coefficient is its
     * effect *holding every other setting constant* — confounded pairings
     * (e.g. music holds rarely using hyper prep) no longer skew results.
     *
     * The L2 penalty (λ = RIDGE_LAMBDA) shrinks sparse options toward neutral,
     * breaks the dummy collinearity, and keeps the solve stable.
     *
     * Returns `categoryTitle → optionKey → score` where score =
     * `100 * exp(beta)` (100 = average hold, 120 ≈ 20% longer all-else-equal).
     * Empty map when there is too little data to fit (callers fall back to
     * [legacyScore]).
     */
    private fun computeAdjustedScores(samples: List<HoldSample>): Map<String, Map<String, Double>> {
        if (samples.size < MIN_REGRESSION_HOLDS) return emptyMap()

        // Feature columns, created lazily per (category, option) pair.
        data class Col(val category: String, val option: String)
        val cols = mutableListOf<Col>()
        val colIndex = mutableMapOf<Col, Int>()
        fun indexOf(c: Col): Int = colIndex.getOrPut(c) { cols.size.also { cols.add(c) } }

        val rows = samples.map { s ->
            listOf(
                Col("Lung Volume", s.lungVolume),
                Col("Prep Type", s.prepType),
                Col("Time of Day", s.timeOfDay),
                Col("Posture", s.posture),
                Col("Audio", s.audio),
                Col(SESSION_TYPE_COLUMN, s.sessionTypeKey),
                Col(HOUR_COLUMN, s.hourOfDay.toString())
            ).map { indexOf(it) }
        }

        val p = cols.size
        if (samples.size < p * MIN_SAMPLES_PER_FEATURE) return emptyMap()

        // Center the target so the no-intercept dummy encoding is well posed.
        val yMean = samples.map { ln(it.holdMs.toDouble()) }.average()

        // Normal equations for ridge: (XᵀX + λI) β = Xᵀy.
        // X is 0/1, so XᵀX counts co-occurrences and Xᵀy sums centered y.
        val xtx = Array(p) { DoubleArray(p) }
        val xty = DoubleArray(p)
        rows.forEachIndexed { i, idxs ->
            val y = ln(samples[i].holdMs.toDouble()) - yMean
            for (a in idxs) {
                xty[a] += y
                for (b in idxs) xtx[a][b] += 1.0
            }
        }
        for (d in 0 until p) xtx[d][d] += RIDGE_LAMBDA

        val beta = solveLinearSystem(xtx, xty) ?: return emptyMap()

        val out = mutableMapOf<String, MutableMap<String, Double>>()
        cols.forEachIndexed { j, col ->
            // Clamp extreme log-effects (±1.5 ≈ ±350%) for display sanity.
            val score = 100.0 * exp(beta[j].coerceIn(-1.5, 1.5))
            out.getOrPut(col.category) { mutableMapOf() }[col.option] = score
        }
        return out
    }

    /** Solves the small dense system A·x = b by Gaussian elimination with partial pivoting. */
    private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        if (n == 0) return null
        val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) {
                if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            }
            if (abs(m[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val tmp = m[pivot]; m[pivot] = m[col]; m[col] = tmp
            }
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col until n) m[r][c] -= f * m[col][c]
                m[r][n] -= f * m[col][n]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = m[i][n]
            for (j in i + 1 until n) s -= m[i][j] * x[j]
            x[i] = s / m[i][i]
        }
        return x
    }

    // ── Per-hold extraction ───────────────────────────────────────────────────

    private fun extractHolds(
        record: ApneaRecordEntity,
        sessionByTsType: Map<Pair<Long, String>, ApneaSessionEntity>
    ): List<ExtractedHold> {
        fun tagged(holds: List<Long>): List<ExtractedHold> =
            holds.map { ExtractedHold(record.recordId, it) }
        return when (record.tableType) {
            null -> tagged(listOfNotNull(record.durationMs.takeIf { it > 0L }))
            "O2", "CO2" -> tagged(extractTableHolds(record, sessionByTsType))
            "PROGRESSIVE_O2" -> tagged(
                jsonLongs(sessionParams(record, sessionByTsType), "rounds", "actualMs")
                    .ifEmpty { fallbackPerRound(record, sessionByTsType) }
            )
            "MIN_BREATH" -> tagged(
                jsonLongs(sessionParams(record, sessionByTsType), "holds", "durationMs")
                    .ifEmpty { fallbackPerRound(record, sessionByTsType) }
            )
            "WONKA_FIRST_CONTRACTION", "WONKA_ENDURANCE" -> tagged(
                jsonLongs(sessionParams(record, sessionByTsType), "rounds", "totalHoldMs")
                    .ifEmpty { fallbackPerRound(record, sessionByTsType) }
            )
            else -> emptyList()
        }
    }

    private fun sessionParams(
        record: ApneaRecordEntity,
        sessionByTsType: Map<Pair<Long, String>, ApneaSessionEntity>
    ): String? = sessionByTsType[record.timestamp to record.tableType]?.tableParamsJson

    /**
     * O₂/CO₂ tables are deterministic: given the PB snapshot (`pbAtSessionMs`)
     * and the variant ("LENGTH_DIFFICULTY") stored on the session row, the
     * per-round hold lengths can be regenerated exactly.
     */
    private fun extractTableHolds(
        record: ApneaRecordEntity,
        sessionByTsType: Map<Pair<Long, String>, ApneaSessionEntity>
    ): List<Long> {
        val session = sessionByTsType[record.timestamp to record.tableType]
        val pb = session?.pbAtSessionMs ?: 0L
        val rounds = session?.roundsCompleted ?: 0
        if (session == null || pb <= 0L || rounds <= 0) {
            return fallbackPerRound(record, sessionByTsType)
        }
        val parts = session.tableVariant.split("_")
        val length = parts.getOrNull(0)?.let { runCatching { TableLength.valueOf(it) }.getOrNull() }
            ?: return fallbackPerRound(record, sessionByTsType)
        val difficulty = parts.getOrNull(1)?.let { runCatching { TableDifficulty.valueOf(it) }.getOrNull() }
            ?: TableDifficulty.MEDIUM

        val table = if (record.tableType == "O2") {
            tableGenerator.generateO2Table(pb, length, difficulty)
        } else {
            tableGenerator.generateCo2Table(pb, length, difficulty)
        }
        return table.steps.take(rounds).map { it.apneaDurationMs }.filter { it > 0L }
    }

    /**
     * Fallback when per-hold data can't be recovered: spread the record's
     * total hold time evenly across the session's completed rounds.
     */
    private fun fallbackPerRound(
        record: ApneaRecordEntity,
        sessionByTsType: Map<Pair<Long, String>, ApneaSessionEntity>
    ): List<Long> {
        val total = record.durationMs
        if (total <= 0L) return emptyList()
        val session = sessionByTsType[record.timestamp to record.tableType]
        val rounds = session?.roundsCompleted?.takeIf { it > 0 }
            ?: roundsFromVariant(session?.tableVariant)
            ?: return listOf(total)
        return List(rounds) { total / rounds }
    }

    private fun roundsFromVariant(variant: String?): Int? = when {
        variant == null -> null
        variant.startsWith("SHORT") -> 4
        variant.startsWith("MEDIUM") -> 8
        variant.startsWith("LONG") -> 12
        else -> null
    }

    /** Reads a JSON array of round/hold objects and pulls one Long field from each. */
    private fun jsonLongs(json: String?, arrayKey: String, fieldKey: String): List<Long> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONObject(json).optJSONArray(arrayKey) ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                obj.optLong(fieldKey, 0L).takeIf { it > 0L }
            }
        }.getOrDefault(emptyList())
    }

    // ── Category definitions ──────────────────────────────────────────────────

    private data class CategoryDef(
        val title: String,
        val selector: (HoldSample) -> String,
        val namer: (String) -> String
    )

    private companion object {
        const val MIN_RELIABLE_HOLDS = 3
        const val SHRINKAGE_K = 5.0
        const val CURRENT = 0
        const val PREV = 1

        /** Regression column labels (hour title matches the section card). */
        const val SESSION_TYPE_COLUMN = "Session Type"
        const val HOUR_COLUMN = "Hour of Day"

        /** Ridge penalty strength — same feel as the K=5 shrinkage prior. */
        const val RIDGE_LAMBDA = 5.0

        /** Don't attempt a regression fit below this many holds. */
        const val MIN_REGRESSION_HOLDS = 40

        /** Require ≥ this many holds per feature column for a stable fit. */
        const val MIN_SAMPLES_PER_FEATURE = 3

        val CATEGORY_DEFS = listOf(
            CategoryDef("Lung Volume", { it.lungVolume }, ::lungVolumeName),
            CategoryDef("Prep Type", { it.prepType }, ::prepTypeName),
            CategoryDef("Time of Day", { it.timeOfDay }, ::timeOfDayName),
            CategoryDef("Posture", { it.posture }, ::postureName),
            CategoryDef("Audio", { it.audio }, ::audioName)
        )

        fun lungVolumeName(key: String): String = when (key) {
            "FULL" -> "Full"
            "EMPTY" -> "Empty"
            "PARTIAL" -> "Partial"
            else -> key
        }

        fun prepTypeName(key: String): String =
            runCatching { PrepType.valueOf(key) }.getOrNull()?.shortDisplayName() ?: key

        fun timeOfDayName(key: String): String =
            runCatching { TimeOfDay.valueOf(key) }.getOrNull()?.displayName() ?: key

        fun postureName(key: String): String =
            runCatching { Posture.valueOf(key) }.getOrNull()?.displayName() ?: key

        fun audioName(key: String): String =
            runCatching { AudioSetting.valueOf(key) }.getOrNull()?.displayName() ?: key

        fun hourName(key: String): String = "%02d:00".format(key.toIntOrNull() ?: 0)
    }
}

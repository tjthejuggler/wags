package com.example.wags.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.wags.domain.model.TableLength
import com.example.wags.domain.model.TrainingModality
import com.example.wags.domain.usecase.breathing.RfProtocol
import com.example.wags.ui.apnea.AdvancedApneaScreen
import com.example.wags.ui.apnea.AllApneaRecordsScreen
import com.example.wags.ui.apnea.ApneaHistoryScreen
import com.example.wags.ui.apnea.ApneaRecordDetailScreen
import com.example.wags.ui.apnea.ApneaScreen
import com.example.wags.ui.apnea.ApneaTableScreen
import com.example.wags.ui.apnea.FreeHoldActiveScreen
import com.example.wags.ui.apnea.PersonalBestsScreen
import com.example.wags.ui.apnea.SessionAnalyticsHistoryScreen
import com.example.wags.ui.apnea.SessionAnalyticsScreen
import com.example.wags.ui.breathing.AssessmentPickerScreen
import com.example.wags.ui.breathing.AssessmentResultScreen
import com.example.wags.ui.breathing.AssessmentRunScreen
import com.example.wags.ui.breathing.BreathingScreen
import com.example.wags.ui.breathing.ResonanceSessionScreen
import com.example.wags.ui.breathing.RfAssessmentHistoryScreen
import com.example.wags.ui.dashboard.DashboardScreen
import com.example.wags.ui.meditation.MeditationHistoryScreen
import com.example.wags.ui.meditation.MeditationScreen
import com.example.wags.ui.meditation.MeditationSessionDetailScreen
import com.example.wags.ui.readiness.HrvReadinessDetailScreen
import com.example.wags.ui.readiness.HrvReadinessHistoryScreen
import com.example.wags.ui.readiness.ReadinessScreen
import com.example.wags.ui.session.SessionScreen
import com.example.wags.ui.morning.MorningReadinessDetailScreen
import com.example.wags.ui.morning.MorningReadinessHistoryScreen
import com.example.wags.ui.morning.MorningReadinessScreen
import com.example.wags.ui.garmin.GarminScreen
import com.example.wags.ui.settings.SettingsScreen

object WagsRoutes {
    const val DASHBOARD = "dashboard"
    const val READINESS = "readiness"
    const val BREATHING = "breathing"
    const val APNEA_FREE = "apnea_free"
    const val APNEA_TABLE = "apnea_table/{tableType}"
    const val ADVANCED_APNEA = "advanced_apnea/{modality}/{length}"
    const val SESSION = "session/{sessionType}"
    const val MORNING_READINESS = "morning_readiness"
    const val MORNING_READINESS_HISTORY = "morning_readiness_history"
    const val MORNING_READINESS_DETAIL = "morning_readiness_detail/{readingId}"
    const val READINESS_HISTORY = "readiness_history"
    const val HRV_READINESS_DETAIL = "hrv_readiness_detail/{readingId}"
    const val SETTINGS = "settings"
    const val SESSION_ANALYTICS = "session_analytics/{sessionId}"
    const val SESSION_ANALYTICS_HISTORY = "session_analytics_history"
    const val RF_ASSESSMENT_PICKER = "rf_assessment_picker"
    const val RF_ASSESSMENT_RUN = "rf_assessment_run/{protocol}?vibration={vibration}"
    const val RF_ASSESSMENT_RESULT = "rf_assessment_result/{sessionTimestamp}"
    const val RF_ASSESSMENT_HISTORY = "rf_assessment_history"
    const val RESONANCE_SESSION = "resonance_session?vibration={vibration}&duration={duration}&infinity={infinity}"
    const val APNEA_HISTORY = "apnea_history/{lungVolume}/{prepType}/{timeOfDay}/{posture}/{audio}"
    const val APNEA_RECORD_DETAIL = "apnea_record_detail/{recordId}"
    const val APNEA_ALL_RECORDS = "apnea_all_records/{lungVolume}/{prepType}/{timeOfDay}/{posture}/{eventTypes}"
    const val FREE_HOLD_ACTIVE = "free_hold_active/{lungVolume}/{prepType}/{timeOfDay}/{posture}/{showTimer}/{audio}"
    const val PERSONAL_BESTS = "personal_bests"
    // ── Meditation / NSDR ──────────────────────────────────────────────────────
    // ── Garmin Watch ─────────────────────────────────────────────────────────
    const val GARMIN = "garmin"

    const val MEDITATION = "meditation"
    const val MEDITATION_HISTORY = "meditation_history"
    const val MEDITATION_SESSION_DETAIL = "meditation_session_detail/{sessionId}"

    fun apneaTable(type: String) = "apnea_table/$type"
    fun advancedApnea(modality: String, length: String) = "advanced_apnea/$modality/$length"
    fun session(type: String) = "session/$type"
    fun sessionAnalytics(sessionId: Long) = "session_analytics/$sessionId"
    fun rfAssessmentRun(protocol: String, vibration: Boolean = false) =
        "rf_assessment_run/$protocol?vibration=$vibration"
    fun rfAssessmentResult(sessionTimestamp: Long) = "rf_assessment_result/$sessionTimestamp"
    fun apneaHistory(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        audio: String = "SILENCE"
    ) = "apnea_history/$lungVolume/$prepType/$timeOfDay/$posture/$audio"
    fun apneaRecordDetail(recordId: Long) = "apnea_record_detail/$recordId"
    fun hrvReadinessDetail(readingId: Long) = "hrv_readiness_detail/$readingId"
    fun morningReadinessDetail(readingId: Long) = "morning_readiness_detail/$readingId"
    fun freeHoldActive(
        lungVolume: String,
        prepType: String,
        timeOfDay: String,
        posture: String,
        showTimer: Boolean,
        audio: String = "SILENCE"
    ) = "free_hold_active/$lungVolume/$prepType/$timeOfDay/$posture/$showTimer/$audio"
    fun meditationSessionDetail(sessionId: Long) = "meditation_session_detail/$sessionId"
    fun resonanceSession(vibration: Boolean = false, duration: Int = 5, infinity: Boolean = false) =
        "resonance_session?vibration=$vibration&duration=$duration&infinity=$infinity"

    /**
     * Navigate to All Records pre-filtered to the given settings.
     * [eventTypes] is a comma-separated list of tableType values; use "FREE_HOLD" for free holds,
     * or "ALL" to select every event type.
     */
    fun apneaAllRecords(
        lungVolume: String = "",
        prepType: String = "",
        timeOfDay: String = "",
        posture: String = "",
        eventTypes: String = "ALL"
    ) = "apnea_all_records/$lungVolume/$prepType/$timeOfDay/$posture/$eventTypes"
}

@Composable
fun WagsNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = WagsRoutes.DASHBOARD) {
        composable(WagsRoutes.DASHBOARD) {
            DashboardScreen(navController = navController)
        }
        composable(WagsRoutes.READINESS) {
            ReadinessScreen(
                navController = navController,
                onNavigateToHistory = { navController.navigate(WagsRoutes.READINESS_HISTORY) }
            )
        }
        composable(WagsRoutes.READINESS_HISTORY) {
            HrvReadinessHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { readingId ->
                    navController.navigate(WagsRoutes.hrvReadinessDetail(readingId))
                }
            )
        }
        composable(
            route = WagsRoutes.HRV_READINESS_DETAIL,
            arguments = listOf(navArgument("readingId") { type = NavType.LongType })
        ) {
            HrvReadinessDetailScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(WagsRoutes.BREATHING) {
            BreathingScreen(
                navController = navController,
                onNavigateToRfAssessment = { navController.navigate(WagsRoutes.RF_ASSESSMENT_PICKER) },
                onNavigateToHistory = { navController.navigate(WagsRoutes.RF_ASSESSMENT_HISTORY) },
                onNavigateToSession = { vibration, duration, infinity ->
                    navController.navigate(WagsRoutes.resonanceSession(vibration, duration, infinity))
                }
            )
        }
        composable(
            route = WagsRoutes.RESONANCE_SESSION,
            arguments = listOf(
                navArgument("vibration") { type = NavType.BoolType; defaultValue = false },
                navArgument("duration") { type = NavType.IntType; defaultValue = 5 },
                navArgument("infinity") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val vibration = backStackEntry.arguments?.getBoolean("vibration") ?: false
            val duration = backStackEntry.arguments?.getInt("duration") ?: 5
            val infinity = backStackEntry.arguments?.getBoolean("infinity") ?: false
            ResonanceSessionScreen(
                onNavigateBack = { navController.popBackStack() },
                vibrationEnabled = vibration,
                durationMinutes = duration,
                infinityMode = infinity
            )
        }
        composable(WagsRoutes.RF_ASSESSMENT_HISTORY) {
            RfAssessmentHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { sessionTimestamp ->
                    navController.navigate(WagsRoutes.rfAssessmentResult(sessionTimestamp))
                }
            )
        }
        composable(WagsRoutes.APNEA_FREE) {
            ApneaScreen(navController = navController)
        }
        composable(
            route = WagsRoutes.FREE_HOLD_ACTIVE,
            arguments = listOf(
                navArgument("lungVolume") { type = NavType.StringType },
                navArgument("prepType")   { type = NavType.StringType },
                navArgument("timeOfDay")  { type = NavType.StringType },
                navArgument("posture")    { type = NavType.StringType },
                navArgument("showTimer")  { type = NavType.BoolType },
                navArgument("audio")      { type = NavType.StringType; defaultValue = "SILENCE" }
            )
        ) { backStackEntry ->
            val lungVolume = backStackEntry.arguments?.getString("lungVolume") ?: "FULL"
            val prepType   = backStackEntry.arguments?.getString("prepType")   ?: "NO_PREP"
            val timeOfDay  = backStackEntry.arguments?.getString("timeOfDay")  ?: "DAY"
            val posture    = backStackEntry.arguments?.getString("posture")    ?: "LAYING"
            val showTimer  = backStackEntry.arguments?.getBoolean("showTimer") ?: true
            FreeHoldActiveScreen(
                navController = navController,
                lungVolume = lungVolume,
                prepType   = prepType,
                timeOfDay  = timeOfDay,
                posture    = posture,
                showTimer  = showTimer
            )
        }
        composable(WagsRoutes.APNEA_TABLE) { backStackEntry ->
            val tableType = backStackEntry.arguments?.getString("tableType") ?: "O2"
            ApneaTableScreen(navController = navController, tableType = tableType)
        }
        composable(WagsRoutes.ADVANCED_APNEA) { backStackEntry ->
            val modalityStr = backStackEntry.arguments?.getString("modality") ?: "PROGRESSIVE_O2"
            val lengthStr = backStackEntry.arguments?.getString("length") ?: "MEDIUM"
            val modality = runCatching { TrainingModality.valueOf(modalityStr) }
                .getOrDefault(TrainingModality.PROGRESSIVE_O2)
            val length = runCatching { TableLength.valueOf(lengthStr) }
                .getOrDefault(TableLength.MEDIUM)
            AdvancedApneaScreen(
                navController = navController,
                modality = modality,
                length = length
            )
        }
        composable(WagsRoutes.SESSION) { backStackEntry ->
            val sessionType = backStackEntry.arguments?.getString("sessionType") ?: "MEDITATION"
            SessionScreen(navController = navController, sessionType = sessionType)
        }
        composable(WagsRoutes.SETTINGS) {
            SettingsScreen(navController = navController)
        }
        composable(WagsRoutes.MORNING_READINESS) {
            MorningReadinessScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHistory = { navController.navigate(WagsRoutes.MORNING_READINESS_HISTORY) }
            )
        }
        composable(WagsRoutes.MORNING_READINESS_HISTORY) {
            MorningReadinessHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { readingId ->
                    navController.navigate(WagsRoutes.morningReadinessDetail(readingId))
                }
            )
        }
        composable(
            route = WagsRoutes.MORNING_READINESS_DETAIL,
            arguments = listOf(navArgument("readingId") { type = NavType.LongType })
        ) {
            MorningReadinessDetailScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = WagsRoutes.SESSION_ANALYTICS,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            SessionAnalyticsScreen(navController = navController, sessionId = sessionId)
        }
        composable(WagsRoutes.SESSION_ANALYTICS_HISTORY) {
            SessionAnalyticsHistoryScreen(navController = navController)
        }
        composable(
            route = WagsRoutes.APNEA_ALL_RECORDS,
            arguments = listOf(
                navArgument("lungVolume") { type = NavType.StringType; defaultValue = "" },
                navArgument("prepType")   { type = NavType.StringType; defaultValue = "" },
                navArgument("timeOfDay")  { type = NavType.StringType; defaultValue = "" },
                navArgument("posture")    { type = NavType.StringType; defaultValue = "" },
                navArgument("eventTypes") { type = NavType.StringType; defaultValue = "ALL" }
            )
        ) {
            AllApneaRecordsScreen(navController = navController)
        }
        composable(
            route = WagsRoutes.APNEA_HISTORY,
            arguments = listOf(
                navArgument("lungVolume") { type = NavType.StringType },
                navArgument("prepType")   { type = NavType.StringType },
                navArgument("timeOfDay")  { type = NavType.StringType },
                navArgument("posture")    { type = NavType.StringType },
                navArgument("audio")      { type = NavType.StringType; defaultValue = "SILENCE" }
            )
        ) {
            ApneaHistoryScreen(navController = navController)
        }
        composable(
            route = WagsRoutes.APNEA_RECORD_DETAIL,
            arguments = listOf(navArgument("recordId") { type = NavType.LongType })
        ) {
            ApneaRecordDetailScreen(navController = navController)
        }
        composable(WagsRoutes.PERSONAL_BESTS) {
            PersonalBestsScreen(navController = navController)
        }
        composable(WagsRoutes.RF_ASSESSMENT_PICKER) {
            AssessmentPickerScreen(
                onNavigateBack = { navController.popBackStack() },
                onStartAssessment = { protocol, vibration ->
                    navController.navigate(WagsRoutes.rfAssessmentRun(protocol.name, vibration))
                }
            )
        }
        composable(
            route = WagsRoutes.RF_ASSESSMENT_RUN,
            arguments = listOf(
                navArgument("protocol") { type = NavType.StringType },
                navArgument("vibration") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val protocolStr = backStackEntry.arguments?.getString("protocol") ?: RfProtocol.EXPRESS.name
            val protocol = runCatching { RfProtocol.valueOf(protocolStr) }
                .getOrDefault(RfProtocol.EXPRESS)
            val vibration = backStackEntry.arguments?.getBoolean("vibration") ?: false
            AssessmentRunScreen(
                protocol = protocol,
                onNavigateBack = { navController.popBackStack() },
                onSessionComplete = { sessionTimestamp ->
                    navController.navigate(WagsRoutes.rfAssessmentResult(sessionTimestamp)) {
                        popUpTo(WagsRoutes.RF_ASSESSMENT_RUN) { inclusive = true }
                    }
                },
                vibrationEnabled = vibration
            )
        }
        composable(
            route = WagsRoutes.RF_ASSESSMENT_RESULT,
            arguments = listOf(navArgument("sessionTimestamp") { type = NavType.LongType })
        ) { backStackEntry ->
            val sessionTimestamp = backStackEntry.arguments?.getLong("sessionTimestamp") ?: 0L
            AssessmentResultScreen(
                sessionTimestamp = sessionTimestamp,
                onNavigateBack = {
                    navController.popBackStack(WagsRoutes.BREATHING, inclusive = false)
                },
                onRunAgain = {
                    navController.navigate(WagsRoutes.RF_ASSESSMENT_PICKER) {
                        popUpTo(WagsRoutes.RF_ASSESSMENT_RESULT) { inclusive = true }
                    }
                }
            )
        }
        // ── Meditation / NSDR ──────────────────────────────────────────────────
        composable(WagsRoutes.MEDITATION) {
            MeditationScreen(navController = navController)
        }
        composable(WagsRoutes.MEDITATION_HISTORY) {
            MeditationHistoryScreen(navController = navController)
        }
        composable(
            route = WagsRoutes.MEDITATION_SESSION_DETAIL,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
        ) {
            MeditationSessionDetailScreen(navController = navController)
        }
        // ── Garmin Watch ────────────────────────────────────────────────────
        composable(WagsRoutes.GARMIN) {
            GarminScreen(navController = navController)
        }
    }
}

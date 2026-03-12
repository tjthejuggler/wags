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
import com.example.wags.ui.apnea.SessionAnalyticsHistoryScreen
import com.example.wags.ui.apnea.SessionAnalyticsScreen
import com.example.wags.ui.breathing.AssessmentPickerScreen
import com.example.wags.ui.breathing.AssessmentResultScreen
import com.example.wags.ui.breathing.AssessmentRunScreen
import com.example.wags.ui.breathing.BreathingScreen
import com.example.wags.ui.dashboard.DashboardScreen
import com.example.wags.ui.readiness.HrvReadinessHistoryScreen
import com.example.wags.ui.readiness.ReadinessScreen
import com.example.wags.ui.session.SessionScreen
import com.example.wags.ui.morning.MorningReadinessHistoryScreen
import com.example.wags.ui.morning.MorningReadinessScreen
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
    const val READINESS_HISTORY = "readiness_history"
    const val SETTINGS = "settings"
    const val SESSION_ANALYTICS = "session_analytics/{sessionId}"
    const val SESSION_ANALYTICS_HISTORY = "session_analytics_history"
    const val RF_ASSESSMENT_PICKER = "rf_assessment_picker"
    const val RF_ASSESSMENT_RUN = "rf_assessment_run/{protocol}"
    const val RF_ASSESSMENT_RESULT = "rf_assessment_result/{sessionTimestamp}"
    const val APNEA_HISTORY = "apnea_history/{lungVolume}/{prepType}/{timeOfDay}"
    const val APNEA_RECORD_DETAIL = "apnea_record_detail/{recordId}"
    const val APNEA_ALL_RECORDS = "apnea_all_records"

    fun apneaTable(type: String) = "apnea_table/$type"
    fun advancedApnea(modality: String, length: String) = "advanced_apnea/$modality/$length"
    fun session(type: String) = "session/$type"
    fun sessionAnalytics(sessionId: Long) = "session_analytics/$sessionId"
    fun rfAssessmentRun(protocol: String) = "rf_assessment_run/$protocol"
    fun rfAssessmentResult(sessionTimestamp: Long) = "rf_assessment_result/$sessionTimestamp"
    fun apneaHistory(lungVolume: String, prepType: String, timeOfDay: String) =
        "apnea_history/$lungVolume/$prepType/$timeOfDay"
    fun apneaRecordDetail(recordId: Long) = "apnea_record_detail/$recordId"
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
            HrvReadinessHistoryScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(WagsRoutes.BREATHING) {
            BreathingScreen(
                navController = navController,
                onNavigateToRfAssessment = { navController.navigate(WagsRoutes.RF_ASSESSMENT_PICKER) }
            )
        }
        composable(WagsRoutes.APNEA_FREE) {
            ApneaScreen(navController = navController)
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
            MorningReadinessHistoryScreen(onNavigateBack = { navController.popBackStack() })
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
        composable(WagsRoutes.APNEA_ALL_RECORDS) {
            AllApneaRecordsScreen(navController = navController)
        }
        composable(
            route = WagsRoutes.APNEA_HISTORY,
            arguments = listOf(
                navArgument("lungVolume") { type = NavType.StringType },
                navArgument("prepType")   { type = NavType.StringType },
                navArgument("timeOfDay")  { type = NavType.StringType }
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
        composable(WagsRoutes.RF_ASSESSMENT_PICKER) {
            AssessmentPickerScreen(
                onNavigateBack = { navController.popBackStack() },
                onStartAssessment = { protocol ->
                    navController.navigate(WagsRoutes.rfAssessmentRun(protocol.name))
                }
            )
        }
        composable(
            route = WagsRoutes.RF_ASSESSMENT_RUN,
            arguments = listOf(navArgument("protocol") { type = NavType.StringType })
        ) { backStackEntry ->
            val protocolStr = backStackEntry.arguments?.getString("protocol") ?: RfProtocol.EXPRESS.name
            val protocol = runCatching { RfProtocol.valueOf(protocolStr) }
                .getOrDefault(RfProtocol.EXPRESS)
            AssessmentRunScreen(
                protocol = protocol,
                onNavigateBack = { navController.popBackStack() },
                onSessionComplete = { sessionTimestamp ->
                    navController.navigate(WagsRoutes.rfAssessmentResult(sessionTimestamp)) {
                        popUpTo(WagsRoutes.RF_ASSESSMENT_RUN) { inclusive = true }
                    }
                }
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
    }
}

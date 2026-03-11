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
import com.example.wags.ui.apnea.AdvancedApneaScreen
import com.example.wags.ui.apnea.ApneaScreen
import com.example.wags.ui.apnea.ApneaTableScreen
import com.example.wags.ui.apnea.SessionAnalyticsHistoryScreen
import com.example.wags.ui.apnea.SessionAnalyticsScreen
import com.example.wags.ui.breathing.BreathingScreen
import com.example.wags.ui.dashboard.DashboardScreen
import com.example.wags.ui.readiness.ReadinessScreen
import com.example.wags.ui.session.SessionScreen
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
    const val SETTINGS = "settings"
    const val SESSION_ANALYTICS = "session_analytics/{sessionId}"
    const val SESSION_ANALYTICS_HISTORY = "session_analytics_history"

    fun apneaTable(type: String) = "apnea_table/$type"
    fun advancedApnea(modality: String, length: String) = "advanced_apnea/$modality/$length"
    fun session(type: String) = "session/$type"
    fun sessionAnalytics(sessionId: Long) = "session_analytics/$sessionId"
}

@Composable
fun WagsNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = WagsRoutes.DASHBOARD) {
        composable(WagsRoutes.DASHBOARD) {
            DashboardScreen(navController = navController)
        }
        composable(WagsRoutes.READINESS) {
            ReadinessScreen(navController = navController)
        }
        composable(WagsRoutes.BREATHING) {
            BreathingScreen(navController = navController)
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
            MorningReadinessScreen(onNavigateBack = { navController.popBackStack() })
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
    }
}

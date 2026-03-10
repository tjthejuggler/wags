package com.example.wags.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.wags.ui.apnea.ApneaScreen
import com.example.wags.ui.apnea.ApneaTableScreen
import com.example.wags.ui.breathing.BreathingScreen
import com.example.wags.ui.dashboard.DashboardScreen
import com.example.wags.ui.readiness.ReadinessScreen
import com.example.wags.ui.session.SessionScreen
import com.example.wags.ui.settings.SettingsScreen

object WagsRoutes {
    const val DASHBOARD = "dashboard"
    const val READINESS = "readiness"
    const val BREATHING = "breathing"
    const val APNEA_FREE = "apnea_free"
    const val APNEA_TABLE = "apnea_table/{tableType}"
    const val SESSION = "session/{sessionType}"
    const val SETTINGS = "settings"

    fun apneaTable(type: String) = "apnea_table/$type"
    fun session(type: String) = "session/$type"
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
        composable(WagsRoutes.SESSION) { backStackEntry ->
            val sessionType = backStackEntry.arguments?.getString("sessionType") ?: "MEDITATION"
            SessionScreen(navController = navController, sessionType = sessionType)
        }
        composable(WagsRoutes.SETTINGS) {
            SettingsScreen(navController = navController)
        }
    }
}

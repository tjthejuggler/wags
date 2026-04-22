package com.example.wags.data.debug

import com.example.wags.ui.navigation.WagsRoutes

/**
 * Maps a navigation route to human-readable context that helps a programmer LLM
 * find the right source files quickly. Each entry provides the screen label,
 * the primary source file, and key functions/composables in that file.
 */
object ScreenContextMapper {

    data class ScreenContext(
        val label: String,
        val sourceFile: String,
        val sourceFunctions: String
    )

    private val routePrefixMap: Map<String, ScreenContext> = mapOf(
        WagsRoutes.DASHBOARD to ScreenContext(
            "Dashboard", "ui/dashboard/DashboardScreen.kt", "DashboardScreen, DashboardViewModel"
        ),
        WagsRoutes.READINESS to ScreenContext(
            "Readiness", "ui/readiness/ReadinessScreen.kt", "ReadinessScreen, ReadinessViewModel"
        ),
        WagsRoutes.BREATHING to ScreenContext(
            "Breathing", "ui/breathing/BreathingScreen.kt", "BreathingScreen, BreathingViewModel"
        ),
        WagsRoutes.APNEA_FREE to ScreenContext(
            "Apnea Free", "ui/apnea/ApneaScreen.kt", "ApneaScreen, ApneaViewModel"
        ),
        WagsRoutes.SETTINGS to ScreenContext(
            "Settings", "ui/settings/SettingsScreen.kt", "SettingsScreen, SettingsViewModel"
        ),
        WagsRoutes.MORNING_READINESS to ScreenContext(
            "Morning Readiness", "ui/morning/MorningReadinessScreen.kt", "MorningReadinessScreen, MorningReadinessViewModel"
        ),
        WagsRoutes.GARMIN to ScreenContext(
            "Garmin Watch", "ui/garmin/GarminScreen.kt", "GarminScreen"
        ),
        WagsRoutes.MEDITATION to ScreenContext(
            "Meditation", "ui/meditation/MeditationScreen.kt", "MeditationScreen, MeditationViewModel"
        ),
        WagsRoutes.RAPID_HR to ScreenContext(
            "Rapid HR", "ui/rapidhr/RapidHrScreen.kt", "RapidHrScreen, RapidHrViewModel"
        ),
        WagsRoutes.PROGRESSIVE_O2 to ScreenContext(
            "Progressive O2", "ui/apnea/ProgressiveO2Screen.kt", "ProgressiveO2Screen, ProgressiveO2ViewModel"
        ),
        WagsRoutes.MIN_BREATH to ScreenContext(
            "Min Breath", "ui/apnea/MinBreathScreen.kt", "MinBreathScreen, MinBreathViewModel"
        ),
        WagsRoutes.CRASH_LOGS to ScreenContext(
            "Crash Logs", "ui/settings/CrashLogScreen.kt", "CrashLogScreen"
        ),
        WagsRoutes.ABOUT to ScreenContext(
            "About", "ui/settings/AboutScreen.kt", "AboutScreen"
        ),
    )

    private val routePrefixMatch: Map<String, ScreenContext> = mapOf(
        "apnea_table" to ScreenContext(
            "Apnea Table", "ui/apnea/ApneaTableScreen.kt", "ApneaTableScreen"
        ),
        "advanced_apnea" to ScreenContext(
            "Advanced Apnea", "ui/apnea/AdvancedApneaScreen.kt", "AdvancedApneaScreen, AdvancedApneaViewModel"
        ),
        "session" to ScreenContext(
            "Session", "ui/session/SessionScreen.kt", "SessionScreen, SessionViewModel"
        ),
        "morning_readiness_history" to ScreenContext(
            "Morning Readiness History", "ui/morning/MorningReadinessHistoryScreen.kt", "MorningReadinessHistoryScreen"
        ),
        "morning_readiness_detail" to ScreenContext(
            "Morning Readiness Detail", "ui/morning/MorningReadinessDetailScreen.kt", "MorningReadinessDetailScreen, MorningReadinessDetailViewModel"
        ),
        "readiness_history" to ScreenContext(
            "Readiness History", "ui/readiness/HrvReadinessHistoryScreen.kt", "HrvReadinessHistoryScreen"
        ),
        "hrv_readiness_detail" to ScreenContext(
            "HRV Readiness Detail", "ui/readiness/HrvReadinessDetailScreen.kt", "HrvReadinessDetailScreen, HrvReadinessDetailViewModel"
        ),
        "session_analytics" to ScreenContext(
            "Session Analytics", "ui/apnea/SessionAnalyticsScreen.kt", "SessionAnalyticsScreen, SessionAnalyticsViewModel"
        ),
        "session_analytics_history" to ScreenContext(
            "Session Analytics History", "ui/apnea/SessionAnalyticsScreen.kt", "SessionAnalyticsHistoryScreen"
        ),
        "rf_assessment_picker" to ScreenContext(
            "RF Assessment Picker", "ui/breathing/AssessmentPickerScreen.kt", "AssessmentPickerScreen"
        ),
        "rf_assessment_run" to ScreenContext(
            "RF Assessment Run", "ui/breathing/AssessmentRunScreen.kt", "AssessmentRunScreen, AssessmentRunViewModel"
        ),
        "rf_assessment_result" to ScreenContext(
            "RF Assessment Result", "ui/breathing/AssessmentResultScreen.kt", "AssessmentResultScreen"
        ),
        "rf_assessment_history" to ScreenContext(
            "RF Assessment History", "ui/breathing/RfAssessmentHistoryScreen.kt", "RfAssessmentHistoryScreen"
        ),
        "resonance_session" to ScreenContext(
            "Resonance Session", "ui/breathing/ResonanceSessionScreen.kt", "ResonanceSessionScreen"
        ),
        "resonance_session_detail" to ScreenContext(
            "Resonance Session Detail", "ui/breathing/ResonanceSessionDetailScreen.kt", "ResonanceSessionDetailScreen"
        ),
        "rate_recommendation" to ScreenContext(
            "Rate Recommendation", "ui/breathing/RateRecommendationScreen.kt", "RateRecommendationScreen"
        ),
        "apnea_history" to ScreenContext(
            "Apnea History", "ui/apnea/ApneaHistoryScreen.kt", "ApneaHistoryScreen, ApneaHistoryViewModel"
        ),
        "apnea_record_detail" to ScreenContext(
            "Apnea Record Detail", "ui/apnea/ApneaRecordDetailScreen.kt", "ApneaRecordDetailScreen, ApneaRecordDetailViewModel"
        ),
        "apnea_all_records" to ScreenContext(
            "All Apnea Records", "ui/apnea/AllApneaRecordsScreen.kt", "AllApneaRecordsScreen, AllApneaRecordsViewModel"
        ),
        "free_hold_active" to ScreenContext(
            "Free Hold Active", "ui/apnea/FreeHoldActiveScreen.kt", "FreeHoldActiveScreen"
        ),
        "personal_bests" to ScreenContext(
            "Personal Bests", "ui/apnea/PersonalBestsScreen.kt", "PersonalBestsScreen, PersonalBestsViewModel"
        ),
        "pb_chart" to ScreenContext(
            "PB Chart", "ui/apnea/PbChartScreen.kt", "PbChartScreen, PbChartViewModel"
        ),
        "trophy_chart" to ScreenContext(
            "Trophy Chart", "ui/apnea/TrophyChartScreen.kt", "TrophyChartScreen, TrophyChartViewModel"
        ),
        "time_chart" to ScreenContext(
            "Time Chart", "ui/apnea/TimeChartScreen.kt", "TimeChartScreen, TimeChartViewModel"
        ),
        "rapid_hr_history" to ScreenContext(
            "Rapid HR History", "ui/rapidhr/RapidHrHistoryScreen.kt", "RapidHrHistoryScreen, RapidHrHistoryViewModel"
        ),
        "rapid_hr_detail" to ScreenContext(
            "Rapid HR Detail", "ui/rapidhr/RapidHrDetailScreen.kt", "RapidHrDetailScreen, RapidHrDetailViewModel"
        ),
        "meditation_history" to ScreenContext(
            "Meditation History", "ui/meditation/MeditationHistoryScreen.kt", "MeditationHistoryScreen, MeditationHistoryViewModel"
        ),
        "meditation_session_detail" to ScreenContext(
            "Meditation Session Detail", "ui/meditation/MeditationSessionDetailScreen.kt", "MeditationSessionDetailScreen"
        ),
        "progressive_o2_active" to ScreenContext(
            "Progressive O2 Active", "ui/apnea/ProgressiveO2ActiveScreen.kt", "ProgressiveO2ActiveScreen"
        ),
        "min_breath_active" to ScreenContext(
            "Min Breath Active", "ui/apnea/MinBreathActiveScreen.kt", "MinBreathActiveScreen"
        ),
    )

    /**
     * Resolves a navigation route string to a [ScreenContext].
     * Tries exact match first, then prefix match (stripping path parameters).
     */
    fun resolve(route: String?): ScreenContext {
        if (route == null) return ScreenContext("Unknown", "MainActivity.kt", "MainActivity")

        // Exact match
        routePrefixMap[route]?.let { return it }

        // Prefix match — find the longest matching prefix
        val matchedKey = routePrefixMatch.keys
            .filter { route.startsWith(it) }
            .maxByOrNull { it.length }

        return matchedKey?.let { routePrefixMatch[it] }
            ?: ScreenContext(route, "Unknown", "Unknown")
    }
}

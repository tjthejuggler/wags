package com.example.wags.ui.breathing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.domain.usecase.breathing.ScoreColor
import com.example.wags.domain.usecase.breathing.SteppedEpochScorer
import com.example.wags.ui.theme.*
import org.json.JSONArray
import org.json.JSONException
import java.text.SimpleDateFormat
import java.util.*

// ---------------------------------------------------------------------------
// Data model for parsed leaderboard entries
// ---------------------------------------------------------------------------

private data class LeaderboardEntry(
    val bpm: Float,
    val score: Float,
    val isValid: Boolean,
    val ieRatio: Float
)

// ---------------------------------------------------------------------------
// JSON parsing helper
// ---------------------------------------------------------------------------

private fun parseLeaderboard(json: String): List<LeaderboardEntry> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            LeaderboardEntry(
                bpm     = obj.optDouble("bpm", 0.0).toFloat(),
                score   = obj.optDouble("score", 0.0).toFloat(),
                isValid = obj.optBoolean("valid", true),
                ieRatio = obj.optDouble("ie", 1.0).toFloat()
            )
        }
    } catch (_: JSONException) {
        emptyList()
    }
}

// ---------------------------------------------------------------------------
// Score color mapping
// ---------------------------------------------------------------------------

private fun scoreColor(score: Float): Color {
    val info = SteppedEpochScorer.scoreColor(score)
    return when (info.color) {
        ScoreColor.RED    -> Color(0xFFE53935)
        ScoreColor.ORANGE -> Color(0xFFFF6F00)
        ScoreColor.GREEN  -> Color(0xFF43A047)
        ScoreColor.BLUE   -> Color(0xFF1E88E5)
        ScoreColor.PINK   -> Color(0xFFE91E63)
        ScoreColor.YELLOW -> Color(0xFFFFD600)
        ScoreColor.WHITE  -> Color.White
    }
}

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentResultScreen(
    sessionTimestamp: Long,
    onNavigateBack: () -> Unit,
    onRunAgain: () -> Unit,
    viewModel: AssessmentResultViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Assessment Results") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        bottomBar = {
            BottomButtons(onRunAgain = onRunAgain, onDone = onNavigateBack)
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = EcgCyan)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Best result banner
            state.bestSession?.let { best ->
                BestResultBanner(
                    bpm   = best.optimalBpm,
                    score = best.compositeScore
                )
            }

            // Tab row
            TabRow(
                selectedTabIndex = state.selectedTab,
                containerColor   = SurfaceDark,
                contentColor     = EcgCyan
            ) {
                Tab(
                    selected = state.selectedTab == 0,
                    onClick  = { viewModel.selectTab(0) },
                    text     = { Text("Leaderboard") }
                )
                Tab(
                    selected = state.selectedTab == 1,
                    onClick  = { viewModel.selectTab(1) },
                    text     = { Text("History") }
                )
            }

            // Tab content
            when (state.selectedTab) {
                0 -> LeaderboardTab(leaderboardJson = state.currentSession?.leaderboardJson ?: "")
                1 -> HistoryTab(sessions = state.allSessions)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Best result banner
// ---------------------------------------------------------------------------

@Composable
private fun BestResultBanner(bpm: Float, score: Float) {
    val color = scoreColor(score)
    val label = SteppedEpochScorer.scoreColor(score).label
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text  = "🏆 Best: ${String.format("%.1f", bpm)} BPM",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text       = "Score: ${String.format("%.1f", score)}  $label",
                style      = MaterialTheme.typography.bodyLarge,
                color      = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Leaderboard tab
// ---------------------------------------------------------------------------

@Composable
private fun LeaderboardTab(leaderboardJson: String) {
    val entries = remember(leaderboardJson) {
        parseLeaderboard(leaderboardJson)
            .sortedByDescending { it.score }
    }

    if (entries.isEmpty()) {
        EmptyState(message = "No epoch data available")
        return
    }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Header row
        item {
            LeaderboardHeader()
        }
        itemsIndexed(entries) { index, entry ->
            LeaderboardRow(rank = index + 1, entry = entry)
        }
    }
}

@Composable
private fun LeaderboardHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("#",     style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.width(28.dp))
        Text("BPM",   style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.weight(1f))
        Text("Score", style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.weight(1f))
        Text("",      modifier = Modifier.width(24.dp)) // warning icon column
    }
    HorizontalDivider(color = SurfaceVariant)
}

@Composable
private fun LeaderboardRow(rank: Int, entry: LeaderboardEntry) {
    val color = scoreColor(entry.score)
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text     = "$rank",
                style    = MaterialTheme.typography.bodyMedium,
                color    = TextSecondary,
                modifier = Modifier.width(28.dp)
            )
            Text(
                text     = String.format("%.1f", entry.bpm),
                style    = MaterialTheme.typography.bodyMedium,
                color    = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text       = String.format("%.1f", entry.score),
                style      = MaterialTheme.typography.bodyMedium,
                color      = color,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.weight(1f)
            )
            Text(
                text     = if (!entry.isValid) "⚠" else "",
                color    = Color(0xFFFFB300),
                style    = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(24.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// History tab
// ---------------------------------------------------------------------------

@Composable
private fun HistoryTab(sessions: List<com.example.wags.data.db.entity.RfAssessmentEntity>) {
    if (sessions.isEmpty()) {
        EmptyState(message = "No previous sessions")
        return
    }

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(sessions) { _, session ->
            HistoryRow(session = session, dateFormat = dateFormat)
        }
    }
}

@Composable
private fun HistoryRow(
    session: com.example.wags.data.db.entity.RfAssessmentEntity,
    dateFormat: SimpleDateFormat
) {
    val color = scoreColor(session.compositeScore)
    val label = SteppedEpochScorer.scoreColor(session.compositeScore).label
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = dateFormat.format(Date(session.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text  = "${session.protocolType}  •  ${String.format("%.1f", session.optimalBpm)} BPM",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text       = String.format("%.1f", session.compositeScore),
                    style      = MaterialTheme.typography.bodyLarge,
                    color      = color,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text  = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}

@Composable
private fun BottomButtons(onRunAgain: () -> Unit, onDone: () -> Unit) {
    Surface(color = SurfaceDark) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick  = onRunAgain,
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = EcgCyan)
            ) {
                Text("Run Again")
            }
            Button(
                onClick  = onDone,
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text("Done")
            }
        }
    }
}

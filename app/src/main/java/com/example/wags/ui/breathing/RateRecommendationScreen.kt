package com.example.wags.ui.breathing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.domain.usecase.breathing.DataPointSource
import com.example.wags.domain.usecase.breathing.RateBucket
import com.example.wags.domain.usecase.breathing.RateRecommendation
import com.example.wags.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Palette ─────────────────────────────────────────────────────────────────────
private val RecBone     = Color(0xFFE8E8E8)
private val RecSilver   = Color(0xFFB0B0B0)
private val RecAsh      = Color(0xFF707070)
private val RecGraphite = Color(0xFF383838)
private val RecCharcoal = Color(0xFF1C1C1C)
private val RecGold     = Color(0xFFD4AF37)
private val RecGreen    = Color(0xFF4CAF50)

private val dateFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateRecommendationScreen(
    onNavigateBack: () -> Unit,
    viewModel: RateRecommendationViewModel = hiltViewModel()
) {
    val recommendation by viewModel.recommendation.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Rate Recommendation", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = EcgCyan)
            }
        } else {
            val rec = recommendation
            if (rec == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Unable to load recommendation.", color = TextSecondary)
                }
            } else {
                RecommendationContent(rec = rec, modifier = Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun RecommendationContent(rec: RateRecommendation, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Hero card ────────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "RECOMMENDED RATE",
                    style = MaterialTheme.typography.labelMedium,
                    color = RecGold,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Bold
                )
                if (rec.recommendedBpm != null) {
                    Text(
                        text = "%.2f".format(rec.recommendedBpm),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )
                    Text(
                        text = "breaths per minute",
                        style = MaterialTheme.typography.titleMedium,
                        color = RecSilver
                    )
                } else {
                    Text(
                        text = "No Data",
                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextSecondary
                    )
                    Text(
                        text = "Run an RF Assessment to get started",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RecAsh
                    )
                }
            }
        }

        // ── Data summary ─────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "DATA SUMMARY",
                    style = MaterialTheme.typography.labelMedium,
                    color = RecSilver,
                    letterSpacing = 2.sp
                )
                RecMetricRow("Lookback window", "${rec.lookbackDays} days")
                RecMetricRow("Assessments used", "${rec.assessmentCount}")
                RecMetricRow("Sessions used", "${rec.sessionCount}")
                RecMetricRow("Total data points", "${rec.assessmentCount + rec.sessionCount}")
                RecMetricRow("Rate buckets", "${rec.buckets.size}")
            }
        }

        // ── Algorithm explanation ────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "HOW IT WORKS",
                    style = MaterialTheme.typography.labelMedium,
                    color = RecSilver,
                    letterSpacing = 2.sp
                )
                Text(
                    text = rec.summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = RecBone,
                    lineHeight = 18.sp
                )
            }
        }

        // ── Rate buckets table ───────────────────────────────────────────────
        if (rec.buckets.isNotEmpty()) {
            Text(
                "RATE BUCKETS (ranked by score)",
                style = MaterialTheme.typography.labelMedium,
                color = RecSilver,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            rec.buckets.forEach { bucket ->
                BucketCard(bucket = bucket)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BucketCard(bucket: RateBucket) {
    val borderColor = if (bucket.isRecommended) RecGold else Color.Transparent
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (bucket.isRecommended) RecGraphite else SurfaceDark
        ),
        shape = RoundedCornerShape(12.dp),
        border = if (bucket.isRecommended) {
            androidx.compose.foundation.BorderStroke(2.dp, RecGold)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (bucket.isRecommended) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(RecGold)
                        )
                    }
                    Text(
                        text = "%.1f BPM".format(bucket.rateBpm),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (bucket.isRecommended) RecGold else TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Score: %.2f".format(bucket.finalScore),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (bucket.isRecommended) RecGold else EcgCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BucketMetric("Points", "${bucket.dataPointCount}")
                BucketMetric("Avg Coh", "%.2f".format(bucket.rawAvgCoherence))
                BucketMetric("Confidence", "%.0f%%".format(bucket.confidenceMultiplier * 100))
            }

            // Expandable data points
            if (bucket.dataPoints.isNotEmpty()) {
                HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
                Text(
                    "Data points:",
                    style = MaterialTheme.typography.labelSmall,
                    color = RecAsh,
                    letterSpacing = 1.sp
                )
                bucket.dataPoints.forEach { dp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val icon = when (dp.source) {
                            DataPointSource.ASSESSMENT -> "📊"
                            DataPointSource.SESSION -> "🫁"
                        }
                        val dateStr = Instant.ofEpochMilli(dp.timestamp)
                            .atZone(ZoneId.systemDefault())
                            .format(dateFmt)
                        Text(
                            text = "$icon ${dp.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = RecSilver,
                            modifier = Modifier.weight(1f)
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                    text = "coh: %.2f".format(dp.coherenceScore),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RecAsh
                                )
                            Text(
                                text = dateStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = RecAsh.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BucketMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = RecAsh,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun RecMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = EcgCyan,
            fontWeight = FontWeight.Bold
        )
    }
}

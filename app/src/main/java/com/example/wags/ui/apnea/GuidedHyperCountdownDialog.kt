package com.example.wags.ui.apnea

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.wags.ui.theme.*

/**
 * Data class holding the three guided hyperventilation phase durations.
 */
data class GuidedHyperPhases(
    val relaxedExhaleSec: Int,
    val purgeExhaleSec: Int,
    val transitionSec: Int
)

/**
 * Full-screen dialog that runs sequential countdown timers for each guided
 * hyperventilation phase. Each phase shows a shrinking circular rim and a
 * numeric countdown. Phases with 0 seconds are skipped. When all phases
 * complete, [onComplete] is called and the dialog dismisses itself.
 */
@Composable
fun GuidedHyperCountdownDialog(
    phases: GuidedHyperPhases,
    onComplete: () -> Unit,
    onCancel: () -> Unit = {}
) {
    // Build the list of non-zero phases
    val phaseList = remember(phases) {
        buildList {
            if (phases.relaxedExhaleSec > 0) add("Relaxed Exhale" to phases.relaxedExhaleSec)
            if (phases.purgeExhaleSec > 0) add("Purge Exhale" to phases.purgeExhaleSec)
            if (phases.transitionSec > 0) add("Transition" to phases.transitionSec)
        }
    }

    // If all phases are 0, complete immediately
    if (phaseList.isEmpty()) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }

    var currentPhaseIndex by remember { mutableIntStateOf(0) }
    var completed by remember { mutableStateOf(false) }

    // When all phases done, signal completion
    LaunchedEffect(completed) {
        if (completed) onComplete()
    }

    if (!completed) {
        Dialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                val (phaseName, phaseDuration) = phaseList[currentPhaseIndex]

                CountdownCircle(
                    label = phaseName,
                    totalSeconds = phaseDuration,
                    onFinished = {
                        if (currentPhaseIndex < phaseList.size - 1) {
                            currentPhaseIndex++
                        } else {
                            completed = true
                        }
                    }
                )
            }
        }
    }
}

/**
 * A single countdown phase: large circle with a shrinking rim arc and
 * a numeric seconds display in the centre.
 */
@Composable
private fun CountdownCircle(
    label: String,
    totalSeconds: Int,
    onFinished: () -> Unit
) {
    // Animate from 1f (full circle) down to 0f (empty)
    val progress = remember { Animatable(1f) }
    var remainingSeconds by remember { mutableIntStateOf(totalSeconds) }

    // Key on label+totalSeconds so the animation restarts for each new phase
    LaunchedEffect(label, totalSeconds) {
        progress.snapTo(1f)
        remainingSeconds = totalSeconds
        progress.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = totalSeconds * 1000,
                easing = LinearEasing
            )
        )
        onFinished()
    }

    // Derive remaining seconds from progress
    LaunchedEffect(progress.value) {
        val remaining = (progress.value * totalSeconds).toInt()
        // Ensure we show totalSeconds at the very start and 1 until the last moment
        remainingSeconds = if (progress.value >= 1f) totalSeconds
        else (remaining + 1).coerceAtMost(totalSeconds)
    }

    val rimColor = TextPrimary
    val trackColor = SurfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Phase label
        Text(
            text = label,
            style = MaterialTheme.typography.headlineSmall,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Circle with countdown
        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 12.dp.toPx()
                val padding = strokeWidth / 2
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(padding, padding)

                // Background track (full circle)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Foreground arc (shrinks as countdown progresses)
                drawArc(
                    color = rimColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.value,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // Countdown number
            Text(
                text = "$remainingSeconds",
                style = MaterialTheme.typography.displayLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

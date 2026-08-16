package com.example.wags.ui.apnea

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wags.ui.theme.*

/**
 * Safety notice for dry empty-lung (residual volume) holds.
 *
 * Shown on the free-hold and drill screens as soon as the screen is entered
 * with Lung Volume = EMPTY, before the user taps Start, so the hold is never
 * begun without acknowledging the risks.
 */
internal const val EMPTY_LUNG_WARNING_TEXT =
    "Dry empty-lung holds are a powerful tool for building CO₂ tolerance and " +
        "diaphragm flexibility, but they should be approached with high respect. " +
        "Never push dry RV holds into violent contractions, keep your neck and " +
        "upper chest completely relaxed, and back off immediately if you feel " +
        "chest tightness or a deep coughing reflex."

/**
 * Popup warning displayed before starting an empty-lung hold or drill.
 * Purely informational — a single OK button acknowledges and closes it.
 */
@Composable
fun EmptyLungWarningDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Warning icon in a circular badge
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(SurfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "⚠️",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Empty Lung Hold",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Text(
                EMPTY_LUNG_WARNING_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                lineHeight = 21.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ButtonPrimary,
                    contentColor = TextPrimary
                )
            ) {
                Text(
                    "OK",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

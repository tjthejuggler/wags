package com.example.wags.ui.debug

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wags.data.debug.DebugNoteRepository
import com.example.wags.data.debug.DebugPreferences
import com.example.wags.data.debug.ScreenContextMapper
import com.example.wags.domain.model.NoteType
import com.example.wags.ui.theme.*
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * A draggable floating bubble overlay for the debug mode.
 *
 * - Draggable around the screen
 * - Shows a badge with the count of notes on the current screen
 * - Tapping opens [DebugNoteDialog]
 * - Only visible when debug mode is enabled in settings
 */
@Composable
fun DebugBubbleOverlay(
    currentRoute: String?,
    debugPrefs: DebugPreferences,
    debugNoteRepo: DebugNoteRepository
) {
    val prefsSnapshot by debugPrefs.snapshot.collectAsStateWithLifecycle()
    if (!prefsSnapshot.debugModeEnabled) return

    val screenContext = remember(currentRoute) {
        ScreenContextMapper.resolve(currentRoute)
    }

    val notesByScreen by debugNoteRepo.notesByScreen.collectAsStateWithLifecycle()
    val noteCount = notesByScreen[currentRoute]?.size ?: 0
    val hasNotes = noteCount > 0

    var showNoteDialog by remember { mutableStateOf(false) }

    // Drag state
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
    val bubbleSize = 48.dp
    val bubbleSizePx = with(density) { bubbleSize.toPx() }

    var offsetX by remember { mutableFloatStateOf(16f) }
    var offsetY by remember { mutableFloatStateOf(screenHeightPx * 0.4f) }

    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // The bubble — draggable + clickable
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(bubbleSize)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    when {
                        hasNotes -> ReadinessOrange
                        else -> EcgCyan.copy(alpha = 0.8f)
                    }
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x)
                            .coerceIn(0f, screenWidthPx - bubbleSizePx)
                        offsetY = (offsetY + dragAmount.y)
                            .coerceIn(0f, screenHeightPx - bubbleSizePx)
                    }
                }
                .clickable { showNoteDialog = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "🐛",
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center)
            )

            // Badge showing note count on current screen
            if (hasNotes) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(ReadinessRed),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = noteCount.toString(),
                        color = Color.White,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }

    // Note dialog
    if (showNoteDialog) {
        DebugNoteDialog(
            screenContext = screenContext,
            noteCountOnScreen = noteCount,
            onDismiss = { showNoteDialog = false },
            onSubmit = { noteType, text ->
                scope.launch {
                    debugNoteRepo.addNote(
                        screenRoute = currentRoute ?: "unknown",
                        screenContext = screenContext,
                        noteType = noteType,
                        noteText = text
                    )
                }
                showNoteDialog = false
            }
        )
    }
}

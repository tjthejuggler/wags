package com.example.wags.ui.apnea

import android.content.Context
import android.media.MediaPlayer
import com.example.wags.R
import com.example.wags.domain.model.PersonalBestCategory

/**
 * Maps a [PersonalBestCategory] to its corresponding raw MP3 resource.
 *
 * Trophy count → sound file (6 levels, 6 files):
 *   EXACT          (1 trophy)  → apnea_pb1.mp3
 *   FOUR_SETTINGS  (2 trophies) → apnea_pb2.mp3
 *   THREE_SETTINGS (3 trophies) → apnea_pb3.mp3
 *   TWO_SETTINGS   (4 trophies) → apnea_pb4.mp3
 *   ONE_SETTING    (5 trophies) → apnea_pb5.mp3
 *   GLOBAL         (6 trophies) → apnea_pb6.mp3
 */
private fun PersonalBestCategory.soundResId(): Int = when (this) {
    PersonalBestCategory.EXACT           -> R.raw.apnea_pb1
    PersonalBestCategory.FOUR_SETTINGS   -> R.raw.apnea_pb2
    PersonalBestCategory.THREE_SETTINGS  -> R.raw.apnea_pb3
    PersonalBestCategory.TWO_SETTINGS    -> R.raw.apnea_pb4
    PersonalBestCategory.ONE_SETTING     -> R.raw.apnea_pb5
    PersonalBestCategory.GLOBAL          -> R.raw.apnea_pb6
}

/**
 * Strong references to in-flight players so the GC cannot collect them
 * before playback finishes. Each player removes itself on completion.
 */
private val activePlayers = mutableSetOf<MediaPlayer>()

/**
 * Plays the personal-best celebration sound for [category] once, then
 * releases the [MediaPlayer] automatically.
 *
 * The player is kept in [activePlayers] until [MediaPlayer.OnCompletionListener]
 * fires, preventing premature garbage collection that would cut the sound short.
 */
fun playApneaPbSound(context: Context, category: PersonalBestCategory) {
    try {
        val mp = MediaPlayer.create(context, category.soundResId()) ?: return
        activePlayers += mp
        mp.setOnCompletionListener { player ->
            activePlayers -= player
            player.release()
        }
        mp.setOnErrorListener { player, _, _ ->
            activePlayers -= player
            player.release()
            true
        }
        mp.start()
    } catch (_: Exception) {
        // Silently swallow — a missing sound must never crash the celebration screen.
    }
}

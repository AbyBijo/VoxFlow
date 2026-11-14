package com.abybijo.voxflow.data.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Audio focus, the platform-standard way (architecture §9).
 *
 * - Request AUDIOFOCUS_GAIN on play.
 * - LOSS / LOSS_TRANSIENT → [onPauseForLoss]. Resume only if VoxFlow
 *   held focus before — never talk over another app's audio unexpectedly.
 * - LOSS_TRANSIENT_CAN_DUCK → [onDuckChanged](true); GAIN restores it.
 * - Headphones unplugged (ACTION_AUDIO_BECOMING_NOISY) → pause.
 *
 * Callbacks are mutable vars so the PlaybackSession (created after this
 * manager) can wire itself in.
 */
class AudioFocusManager(private val context: Context) {

    var onPauseForLoss: (() -> Unit)? = null
    var onResumeAfterGain: (() -> Unit)? = null
    var onDuckChanged: ((Boolean) -> Unit)? = null

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var hadFocus = false
    private var ducked = false

    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (hadFocus) {
                            hadFocus = false
                            onPauseForLoss?.invoke()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        ducked = true
                        onDuckChanged?.invoke(true)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (ducked) {
                            ducked = false
                            onDuckChanged?.invoke(false)
                        } else if (!hadFocus) {
                            // Gained focus after a loss: resume only if the
                            // session was playing when focus was lost.
                            hadFocus = true
                            onResumeAfterGain?.invoke()
                        }
                    }
                }
            }
            .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onPauseForLoss?.invoke()
            }
        }
    }

    /** Returns true when focus was granted. */
    fun request(): Boolean {
        val result = audioManager.requestAudioFocus(focusRequest)
        hadFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hadFocus) {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        return hadFocus
    }

    fun abandon() {
        runCatching {
            context.unregisterReceiver(noisyReceiver)
        }
        if (hadFocus || ducked) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        hadFocus = false
        ducked = false
    }
}

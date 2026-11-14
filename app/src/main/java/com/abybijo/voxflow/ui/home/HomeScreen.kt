package com.abybijo.voxflow.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abybijo.voxflow.data.models.ModelManager
import com.abybijo.voxflow.domain.model.KOKORO_EN_VOICES
import com.abybijo.voxflow.domain.playback.PlaybackMode
import com.abybijo.voxflow.playback.PlaybackSession
import com.abybijo.voxflow.ui.theme.DarkFocus
import com.abybijo.voxflow.ui.theme.LightFocus

/**
 * The one screen (Slice 1). The UI is the thing itself: a text field,
 * a voice picker, a play button. No qualifying text, no feature lists,
 * no marketing copy anywhere.
 *
 * Every state has a visible layout: loading shows determinate progress
 * with text, empty disables Listen, error shows a message with Retry.
 * Nothing is ever a bare spinner, and no loading state can sit frozen
 * — every async path ends in Ready, an error with Retry, or a terminal
 * playback state (docs/risks.md).
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val session = ui.session
    val sessionActive = session.mode == PlaybackMode.PREPARING ||
        session.mode == PlaybackMode.PLAYING ||
        session.mode == PlaybackMode.PAUSED
    val modelReady = ui.modelState is ModelManager.State.Ready
    val canListen = ui.text.isNotBlank() && modelReady && !sessionActive

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "VoxFlow",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(16.dp))

        ModelBanner(
            state = ui.modelState,
            onRetry = viewModel::ensureModel,
        )

        OutlinedTextField(
            value = ui.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            placeholder = { Text("Paste text") },
            enabled = !sessionActive,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isDark()) DarkFocus else LightFocus,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.onBackground,
                focusedContainerColor = MaterialTheme.colorScheme.background,
                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                disabledContainerColor = MaterialTheme.colorScheme.background,
            ),
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${ui.text.length} characters",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.estimatedMinutes > 0) {
                Text(
                    text = "≈ ${ui.estimatedMinutes} min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Voice",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(KOKORO_EN_VOICES, key = { it.id }) { voice ->
                val selected = voice.id == ui.voiceId
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(24.dp),
                        )
                        .clickable(enabled = !sessionActive) {
                            viewModel.onVoiceSelected(voice.id)
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = voice.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        PlayerCard(session = session)

        Spacer(modifier = Modifier.height(12.dp))
        val (label, onClick, enabled) = when (session.mode) {
            PlaybackMode.PLAYING -> Triple("Pause", viewModel::onPauseResume, true)
            PlaybackMode.PAUSED -> Triple("Resume", viewModel::onPauseResume, true)
            PlaybackMode.PREPARING -> Triple("Preparing…", {}, false)
            PlaybackMode.BLOCKED -> Triple("Retry", viewModel::onRetry, true)
            else -> Triple("Listen", viewModel::onListen, canListen)
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
        }

        if (sessionActive) {
            TextButton(
                onClick = viewModel::onStop,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Stop")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun isDark(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()

/** Voice model state. Silent when Ready — nothing to say. */
@Composable
private fun ModelBanner(
    state: ModelManager.State,
    onRetry: () -> Unit,
) {
    when (state) {
        is ModelManager.State.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Checking voice…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        is ModelManager.State.Downloading -> {
            Column {
                Text(
                    text = "Preparing voice… ${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        is ModelManager.State.Verifying -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Verifying voice…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        is ModelManager.State.Ready -> {
            // Silent. The voice is ready; there is nothing to announce.
        }
        is ModelManager.State.Error -> {
            Column {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** Playback progress and errors. Hidden while idle. */
@Composable
private fun PlayerCard(session: PlaybackSession.SessionState) {
    when (session.mode) {
        PlaybackMode.IDLE -> {
            // Nothing playing, nothing to show.
        }
        PlaybackMode.PREPARING -> {
            Column {
                Text(
                    text = "Preparing…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${session.readyChunks} of ${session.totalChunks} chunks ready",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val progress = if (session.totalChunks > 0) {
                    session.readyChunks.toFloat() / session.totalChunks
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        PlaybackMode.PLAYING, PlaybackMode.PAUSED -> {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Chunk ${session.currentChunk + 1} of ${session.totalChunks}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = formatMs(session.positionMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val progress = if (session.totalChunks > 0) {
                    (session.currentChunk + 1).toFloat() / session.totalChunks
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (session.mode == PlaybackMode.PAUSED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Paused",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        PlaybackMode.FINISHED -> {
            Text(
                text = "Finished",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        PlaybackMode.BLOCKED -> {
            Text(
                text = session.error
                    ?: "A chunk could not be synthesised.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        PlaybackMode.FAILED -> {
            Text(
                text = session.error ?: "Playback failed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

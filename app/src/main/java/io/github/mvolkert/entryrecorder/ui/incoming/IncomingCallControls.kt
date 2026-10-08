package io.github.mvolkert.entryrecorder.ui.incoming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.sip.CallUiState
import io.github.mvolkert.entryrecorder.sip.SipSessionState
import io.github.mvolkert.entryrecorder.ui.components.MorphingIcon

/**
 * Bottom call-action bar. A [BoxScope] extension because it anchors itself to the bottom of the
 * full-screen stream, and it stays clear of the navigation bar inset.
 * Exhaustive over [CallUiState] on purpose: a new state has to decide here what its action row looks like.
 */
@Composable
internal fun BoxScope.IncomingCallControls(
    sipState: SipSessionState,
    onAcceptCall: () -> Unit,
    onDeclineCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .background(scheme.scrim.copy(alpha = 0.7f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        when (sipState.state) {
            CallUiState.CONNECTED -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            if (sipState.isMicMuted) scheme.errorContainer else scheme.surfaceVariant.copy(alpha = 0.6f),
                            CircleShape
                        )
                ) {
                    MorphingIcon(
                        imageVector = if (sipState.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = stringResource(R.string.incoming_cd_mute),
                        tint = if (sipState.isMicMuted) scheme.onErrorContainer else scheme.onSurface,
                    )
                }

                // CircleShape overrides the M3 FAB default on purpose: the call-screen idiom is a
                // round hang-up button, and Material lets components opt into custom shapes.
                FloatingActionButton(
                    onClick = onDeclineCall,
                    containerColor = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = stringResource(R.string.incoming_cd_hangup),
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(
                    onClick = onToggleSpeaker,
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            if (sipState.isSpeakerOn) scheme.secondaryContainer else scheme.surfaceVariant.copy(alpha = 0.6f),
                            CircleShape
                        )
                ) {
                    MorphingIcon(
                        imageVector = if (sipState.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                        contentDescription = stringResource(R.string.incoming_cd_speaker),
                        tint = if (sipState.isSpeakerOn) scheme.onSecondaryContainer else scheme.onSurface,
                    )
                }
            }

            // Terminal states: nothing is left to act on, and the screen closes itself (IncomingCallActivity),
            // so this explains why the buttons disappeared instead of offering a dead accept button.
            // The SIP failure reason is shown when the core provided one.
            CallUiState.ENDED, CallUiState.ERROR -> Text(
                text = sipState.errorMessage ?: stringResource(R.string.incoming_call_ended),
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            CallUiState.IDLE, CallUiState.RINGING_INCOMING -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingActionButton(
                    onClick = onDeclineCall,
                    containerColor = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = stringResource(R.string.incoming_cd_decline),
                        modifier = Modifier.size(30.dp)
                    )
                }

                FloatingActionButton(
                    onClick = onAcceptCall,
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = stringResource(R.string.incoming_cd_accept),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}

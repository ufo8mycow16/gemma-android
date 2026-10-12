package app.gemma.android

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Talking with Gemma. The words under the ring say what is really happening:
 * the phone listening, Vision working, or her voice playing.
 */
@Composable
fun VoiceScreen(viewModel: GemmaViewModel, autoStart: Boolean, onAutoStarted: () -> Unit, onVoiceSettings: () -> Unit) {
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startLive()
    }
    fun begin() {
        if (context.allowed(Manifest.permission.RECORD_AUDIO)) viewModel.startLive()
        else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Opened from the home-screen shortcut: go straight into the conversation.
    LaunchedEffect(autoStart) {
        if (autoStart) {
            onAutoStarted()
            begin()
        }
    }

    val headline = when {
        !voice.live -> "Voice conversation"
        voice.phase == Phase.LISTENING -> "Listening…"
        voice.phase == Phase.SPEAKING -> "Speaking…"
        voice.phase == Phase.THINKING -> chat.activity.ifBlank { "Thinking…" }
        voice.muted -> "Microphone muted"
        else -> "Paused"
    }
    val hint = when {
        voice.problem != null -> voice.problem ?: ""
        !voice.live && !chat.voiceReady -> "Gemma's voice isn't running on Vision, so her replies will be text only."
        !voice.live -> "Tap the microphone to start talking with Gemma."
        voice.phase == Phase.LISTENING -> voice.heard.ifBlank { "Speak now" }
        voice.phase == Phase.SPEAKING -> "Tap to interrupt"
        voice.phase == Phase.THINKING -> voice.heard
        voice.muted -> "She can't hear you until you unmute."
        else -> "Tap the ring to talk"
    }

    Box(Modifier.fillMaxSize()) {
        Portrait(Modifier.fillMaxSize(), strength = 1f, fadeBottom = false)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x66060A14), 1f to Space.Background)
            )
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Ring(
                phase = if (voice.live) voice.phase else Phase.IDLE,
                modifier = Modifier.size(116.dp).clip(CircleShape)
                    .clickable(enabled = voice.live) { viewModel.tapRing() }
                    .semantics { contentDescription = headline },
            )
            Text(headline, style = MaterialTheme.typography.titleLarge, color = Space.Text, modifier = Modifier.padding(top = 14.dp))
            Text(
                hint, style = MaterialTheme.typography.bodyMedium,
                color = if (voice.problem != null) MaterialTheme.colorScheme.error else Space.Muted,
                textAlign = TextAlign.Center, maxLines = 3,
                modifier = Modifier.padding(top = 4.dp, bottom = 22.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(
                    if (voice.muted) Glyph.MicOff else Glyph.Mic, if (voice.muted) "Unmute" else "Mute",
                    enabled = voice.live, onClick = { viewModel.toggleMute() },
                )
                if (voice.live) {
                    RoundButton(Glyph.Stop, "Stop", large = true, colour = Space.Red, onClick = { viewModel.stopLive() })
                } else {
                    RoundButton(Glyph.Mic, "Start", large = true, colour = Space.Blue, onClick = { begin() })
                }
                RoundButton(Glyph.Tune, "Voice", onClick = onVoiceSettings)
            }
        }
    }
}

@Composable
private fun RoundButton(
    symbol: ImageVector, label: String, large: Boolean = false, colour: Color = Color(0xCC131C31),
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (large) 76.dp else 56.dp).clip(CircleShape)
                .background(if (enabled) colour else Color(0x66131C31))
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(symbol, contentDescription = null, tint = if (enabled) Color.White else Space.Muted, modifier = Modifier.size(if (large) 32.dp else 24.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (large) colour else Space.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The glowing ring: still when idle, a slow pulse while Vision works, moving bars while someone is speaking. */
@Composable
private fun Ring(phase: Phase, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ring")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f, label = "ring-time",
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
    )
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color(0x99060A14), radius, centre)
        val pulse = if (phase == Phase.IDLE) 0f else abs(sin(t * PI.toFloat()))
        drawCircle(Space.Blue.copy(alpha = 0.35f + 0.45f * pulse), radius - 4f, centre, style = Stroke(width = 3f + 3f * pulse))
        val bars = 7
        val gap = radius * 0.16f
        val active = phase == Phase.LISTENING || phase == Phase.SPEAKING
        for (i in 0 until bars) {
            val x = centre.x + (i - (bars - 1) / 2f) * gap
            val wave = if (active) abs(sin((t * 2f + i * 0.21f) * PI.toFloat())) else if (phase == Phase.THINKING) 0.25f + 0.2f * pulse else 0.18f
            val shape = 1f - abs(i - (bars - 1) / 2f) / bars
            val half = radius * 0.42f * wave * (0.55f + shape)
            drawLine(Space.Blue, Offset(x, centre.y - half), Offset(x, centre.y + half), strokeWidth = 5f, cap = StrokeCap.Round)
        }
    }
}

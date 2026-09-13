package com.duggu.laddu.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.runtime.collectAsState
import androidx.runtime.getValue
import androidx.runtime.LaunchedEffect
import androidx.runtime.mutableStateOf
import androidx.runtime.remember
import androidx.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

private val LadduOrange = Color(0xFFF59E0B)
private val LadduCream = Color(0xFFFFF7E6)
private val LadduBrown = Color(0xFF4E342E)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LadduTheme { LadduScreen(vm) } }
    }
}

@Composable
fun LadduTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = LadduOrange,
            onPrimary = Color.White,
            background = LadduCream,
            surface = LadduCream,
            onBackground = LadduBrown,
            onSurface = LadduBrown
        ),
        content = content
    )
}

@Composable
fun LadduScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val messages by vm.messages.collectAsState()
    val partial by vm.partial.collectAsState()
    val lastThought by vm.lastThought.collectAsState()
    var draft by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🧡 Motichoor Ka Laddu", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = when (state.name) {
                    "IDLE" -> "Tap Start — say \"Laddu\" to wake her"
                    "LISTENING" -> if (partial.isBlank()) "Listening…" else partial
                    "THINKING" -> "Motichoor is thinking…"
                    else -> "Speaking…"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            WaveVisualizer(active = state.name != "IDLE")

            if (lastThought.isNotBlank()) {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "💭 ${lastThought.take(160)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages) { msg ->
                    val isUser = msg.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement =
                        if (isUser) Arrangement.End else Arrangement.Start) {
                        Text(
                            msg.content,
                            color = if (isUser) Color.White else LadduBrown,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isUser) LadduOrange else Color(0xFFFFE0B2))
                                .padding(12.dp)
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type to Motichoor…") }
                )
                IconButton(onClick = {
                    vm.sendText(draft); draft = ""
                }) { Text("➤", fontSize = 22.sp) }
            }

            Button(
                onClick = { vm.toggleAssistant() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(if (state.name == "IDLE") "🎙 Start Laddu" else "⏹ Stop")
            }
        }
    }
}

/** Animated sine-wave bars; energy scales with active state. */
@Composable
fun WaveVisualizer(active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val energy by animateFloatAsState(if (active) 1f else 0.15f, label = "energy")

    Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) {
        val bars = 40
        val barW = size.width / bars
        repeat(bars) { i ->
            val t = i / bars.toFloat()
            val wave = (sin(phase + t * 6f) * 0.5 + 0.5) * (sin(phase * 1.7f + t * 11f) * 0.5 + 0.5)
            val h = size.height * (0.12f + 0.8f * energy * wave)
            drawRoundRect(
                color = LadduOrange,
                topLeft = Offset(i * barW + barW * 0.2f, (size.height - h) / 2f),
                size = Size(barW * 0.6f, h),
                cornerRadius = CornerRadius(barW * 0.3f)
            )
        }
    }
}

package com.motichoorkaladdu.assistant.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled via subsequent toggle press */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions.launch(perms.toTypedArray())

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AssistantScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun AssistantScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    var input by remember { mutableStateOf("") }

    Scaffold(
        topBar = { AssistantHeader() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            AudioVisualizer(
                level = audioLevel,
                isActive = uiState.isListening,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.toggleListening() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isListening) Color(0xFFE85D75) else Color(0xFF6C63FF)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (uiState.isListening) "Stop Listening" else "Start Listening — \"Hey Laddu\"")
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(uiState.transcript) { line ->
                    ChatBubble(line)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type to Motichoor Ka Laddu…") }
                )
                Spacer(modifier = Modifier.height(0.dp))
                Button(
                    onClick = {
                        viewModel.sendTypedMessage(input)
                        input = ""
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun AssistantHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF6C63FF))
            .padding(20.dp)
    ) {
        Text(
            text = "Motichoor Ka Laddu",
            color = Color.White,
            fontSize = 22.sp
        )
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    val isUser = line.role == "user"
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = if (isUser) Color(0xFF6C63FF) else Color(0xFFF1F0FF),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .align(if (isUser) Alignment.CenterVertically else Alignment.CenterVertically)
                .padding(if (isUser) PaddingValues(start = 48.dp) else PaddingValues(end = 48.dp))
        ) {
            Text(
                text = line.text,
                color = if (isUser) Color.White else Color.Black,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/** Simple animated wave that pulses faster/taller while actively listening,
 *  and reacts to [level] (0f..1f) when driven by real mic RMS values. */
@Composable
fun AudioVisualizer(level: Float, isActive: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 1200 else 3000),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        val amplitude = (20 + level * 60).coerceAtMost(size.height / 2 - 10)
        val midY = size.height / 2
        val path = androidx.compose.ui.graphics.Path()
        val step = 4f
        var x = 0f
        path.moveTo(0f, midY)
        while (x <= size.width) {
            val y = midY + amplitude * sin((x / size.width) * 4 * Math.PI + phase).toFloat()
            path.lineTo(x, y)
            x += step
        }
        drawPath(
            path = path,
            color = if (isActive) Color(0xFF6C63FF) else Color(0xFFBFBBFF),
            style = Stroke(width = 6f)
        )
    }
}

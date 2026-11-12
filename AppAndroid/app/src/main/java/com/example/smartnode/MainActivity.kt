package com.example.smartnode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Paleta morada oscuro
val BgDark = Color(0xFF17111F)
val CardPurple = Color(0xFF2D1B4E)
val TextLight = Color(0xFFE9DDFF)
val TextSoft = Color(0xFFB79CD8)
val LedPurple = Color(0xFF7C4DFF)
val OkGreen = Color(0xFF81C784)
val WarnOrange = Color(0xFFFFB74D)
val BannerBg = Color(0xFF3E2723)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as SmartNodeApp).repository

        setContent {
            MaterialTheme {
                val telemetry by repo.latestTelemetry.collectAsState(initial = null)
                val pending by repo.pendingCommandCount.collectAsState(initial = 0)
                val firebaseOk by repo.firebaseConnected.collectAsState()
                val offline by repo.isOffline.collectAsState(initial = false)
                val snackbarHostState = remember { SnackbarHostState() }

                // Aviso al usuario al entrar/salir del Modo Offline
                LaunchedEffect(offline) {
                    if (offline) {
                        snackbarHostState.showSnackbar(
                            "Modo Offline — Plan de contingencia activo: los datos se guardan localmente (；ω；)"
                        )
                    } else {
                        snackbarHostState.showSnackbar(
                            "Conexión restablecida — sincronizando datos pendientes (๑˃ᴗ˂)ﻭ"
                        )
                    }
                }

                Scaffold(
                    containerColor = BgDark,
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // ---- Título (sin kaomoji, más limpio) ----
                        Text(
                            text = "SmartNode",
                            color = TextLight,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // ---- Banner Modo Offline ----
                        if (offline) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BannerBg)
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "Modo Offline — Plan de contingencia activo (；ω；)",
                                    color = WarnOrange,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // ---- Bloques de métricas ----
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            MetricCard(
                                kaomoji = "=^･ω･^=",
                                label = "Temperatura",
                                value = telemetry?.let { "%.1f °C".format(it.temperature) } ?: "--",
                                modifier = Modifier.weight(1f)
                            )
                            MetricCard(
                                kaomoji = "(◍•ᴗ•◍)",
                                label = "Humedad",
                                value = telemetry?.let { "%.1f %%".format(it.humidity) } ?: "--",
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // ---- Estado ----
                        StatusPill(
                            text = "Firebase: " + if (firebaseOk) "conectado ♡" else "desconectado",
                            color = if (firebaseOk) OkGreen else WarnOrange
                        )
                        StatusPill(
                            text = "Comandos pendientes: $pending",
                            color = TextSoft
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // ---- Botones redondos, uno al lado del otro ----
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RoundLedButton(
                                kaomoji = "(≧◡≦) ♡",
                                label = "LED ON",
                                onClick = { repo.sendCommand("led_on") }
                            )
                            RoundLedButton(
                                kaomoji = "( ˘ω˘ )",
                                label = "LED OFF",
                                onClick = { repo.sendCommand("led_off") }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(kaomoji: String, label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = CardPurple)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = kaomoji, fontSize = 30.sp, color = Color.White)
            Text(
                text = value,
                color = TextLight,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(text = label, color = TextSoft, fontSize = 14.sp)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(CardPurple)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text = text, color = color, fontSize = 14.sp)
    }
}

@Composable
private fun RoundLedButton(kaomoji: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(110.dp)
            .clip(CircleShape)
            .background(LedPurple)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = kaomoji, fontSize = 22.sp, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

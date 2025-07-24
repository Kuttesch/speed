package com.example.speed

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LocationApp(
    latitude: Double?,
    longitude: Double?,
    speedLimit: String?,
    onGetFromLocal: () -> Unit
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${speedLimit ?: "0"}", fontSize = 50.sp)
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onGetFromLocal) {
                    Text("Start Speed Lookup")
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text("Latitude: ${latitude ?: "Loading..."}")
                Text("Longitude: ${longitude ?: "Loading..."}")
            }
        }
    }
}
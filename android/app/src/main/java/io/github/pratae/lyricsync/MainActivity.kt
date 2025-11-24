package io.github.pratae.lyricsync

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.pratae.lyricsync.ui.theme.LyricSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ★ 初始化 SyncManager，注册 SuperLyric
        SyncManager.init(applicationContext)

        setContent {
            LyricSyncTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ipAddress by rememberSaveable { mutableStateOf("") }
    var hasUserEditedIp by rememberSaveable { mutableStateOf(false) }

    val currentTrack by SyncManager.currentTrack.collectAsState()
    val currentArtist by SyncManager.currentArtist.collectAsState()
    val connectionStatus by SyncManager.connectionStatus.collectAsState()
    val currentLyric by SyncManager.currentLyric.collectAsState()   // ★ 新增：当前歌词
    val savedIp by SyncManager.pcIpAddress.collectAsState()

    val isNotificationListenerEnabled = remember {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        flat != null && flat.contains(packageName)
    }

    LaunchedEffect(savedIp) {
        if (!hasUserEditedIp || savedIp == ipAddress) {
            ipAddress = savedIp
            hasUserEditedIp = false
        }
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Lyric Sync Setup", style = MaterialTheme.typography.headlineMedium)

        if (!isNotificationListenerEnabled) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Permission Required",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "To detect music, please grant Notification Access.",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) {
                        Text("Open Settings")
                    }
                }
            }
        }

        OutlinedTextField(
            value = ipAddress,
            onValueChange = {
                ipAddress = it
                hasUserEditedIp = true
            },
            label = { Text("PC IP Address") },
            modifier = Modifier.fillMaxWidth()
        )

        val trimmedIp = ipAddress.trim()
        Button(
            onClick = {
                SyncManager.setPcIp(trimmedIp)
                hasUserEditedIp = false
            },
            enabled = trimmedIp.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Connect")
        }

        HorizontalDivider()

        Text(text = "Status", style = MaterialTheme.typography.titleMedium)
        Text(text = "Connection: $connectionStatus")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Now Playing", style = MaterialTheme.typography.labelLarge)
                Text(text = currentTrack, style = MaterialTheme.typography.headlineSmall)
                Text(text = currentArtist, style = MaterialTheme.typography.bodyLarge)

                Spacer(modifier = Modifier.height(12.dp))

                // ★ 新增：显示当前一句歌词（来自 SuperLyric）
                Text(text = "Current Lyric", style = MaterialTheme.typography.labelLarge)
                Text(text = currentLyric, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

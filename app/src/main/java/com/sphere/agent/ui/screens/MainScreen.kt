package com.sphere.agent.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sphere.agent.BuildConfig
import com.sphere.agent.util.LogStorage
import com.sphere.agent.network.ConnectionState
import com.sphere.agent.ui.viewmodel.MainEvent
import com.sphere.agent.ui.viewmodel.MainUiState

/**
 * MainScreen - Главный экран SphereAgent
 * 
 * UI 2025:
 * - Glassmorphism cards
 * - Gradient backgrounds
 * - Animated status indicators
 * - Material 3 components
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    onEvent: (MainEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Animated logo
                        AnimatedLogo(isActive = uiState.isServiceRunning)
                        Text(
                            text = "SphereAgent",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ),
                actions = {
                    IconButton(onClick = { onEvent(MainEvent.RefreshConfig) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            StatusCard(
                connectionState = uiState.connectionState,
                isServiceRunning = uiState.isServiceRunning,
                deviceName = uiState.deviceName,
                deviceId = uiState.deviceId
            )
            
            // Accessibility Warning Card
            if (!uiState.hasAccessibility) {
                AccessibilityWarningCard(
                    onOpenSettings = { onEvent(MainEvent.OpenAccessibilitySettings) }
                )
            }
            
            // Control Card
            ControlCard(
                isServiceRunning = uiState.isServiceRunning,
                hasPermissions = uiState.hasPermissions,
                onStartClick = { onEvent(MainEvent.StartService) },
                onStopClick = { onEvent(MainEvent.StopService) }
            )
            
            // Server Settings Card
            ServerSettingsCard(
                serverUrl = uiState.serverUrl,
                onUrlChange = { onEvent(MainEvent.UpdateServerUrl(it)) }
            )
            
            // Stream Settings Card
            StreamSettingsCard(
                quality = uiState.streamQuality,
                fps = uiState.streamFps,
                onQualityChange = { onEvent(MainEvent.UpdateQuality(it)) },
                onFpsChange = { onEvent(MainEvent.UpdateFps(it)) }
            )
            
            // Stats Card
            if (uiState.isServiceRunning) {
                StatsCard(stats = uiState.stats)
            }
            
            // Error snackbar
            uiState.errorMessage?.let { error ->
                ErrorCard(
                    message = error,
                    onDismiss = { onEvent(MainEvent.DismissError) }
                )
            }
        }
    }
}

@Composable
private fun AnimatedLogo(isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    Box(
        modifier = Modifier
            .size((32 * scale).dp)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(
                    colors = if (isActive) {
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    } else {
                        listOf(
                            MaterialTheme.colorScheme.outline,
                            MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Smartphone,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun StatusCard(
    connectionState: ConnectionState,
    isServiceRunning: Boolean,
    deviceName: String,
    deviceId: String
) {
    val statusColor = when (connectionState) {
        is ConnectionState.Connected -> MaterialTheme.colorScheme.tertiary
        is ConnectionState.Connecting -> MaterialTheme.colorScheme.secondary
        is ConnectionState.Error -> MaterialTheme.colorScheme.error
        is ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    }
    
    val statusText = when (connectionState) {
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Connecting -> "Connecting to ${connectionState.serverUrl}..."
        is ConnectionState.Error -> "Error: ${connectionState.message}"
        is ConnectionState.Disconnected -> "Disconnected"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Pulsing dot
                PulsingDot(color = statusColor, isActive = isServiceRunning)
                
                Text(
                    text = if (isServiceRunning) "Active" else "Inactive",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }
            
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            // Device info
            InfoRow(
                icon = Icons.Outlined.Smartphone,
                label = "Device",
                value = deviceName
            )
            
            InfoRow(
                icon = Icons.Outlined.Fingerprint,
                label = "Device ID",
                value = deviceId.take(8) + "..."
            )
            
            // Version info
            InfoRow(
                icon = Icons.Outlined.Info,
                label = "Version",
                value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            )
            
            // Connection status
            InfoRow(
                icon = when (connectionState) {
                    is ConnectionState.Connected -> Icons.Default.CloudDone
                    is ConnectionState.Connecting -> Icons.Default.CloudSync
                    else -> Icons.Default.CloudOff
                },
                label = "Status",
                value = statusText
            )
        }
    }
}

@Composable
private fun PulsingDot(color: Color, isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isActive) 1f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ControlCard(
    isServiceRunning: Boolean,
    hasPermissions: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    var showLogsDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val logsText by LogStorage.logsText.collectAsState()
    
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = { Text("System Logs") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .verticalScroll(rememberScrollState())
                        .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (logsText.isBlank()) "No logs yet..." else logsText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("SphereAgent Logs", LogStorage.getLogs())
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, "Logs copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Remote Control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            // Main action button
            Button(
                onClick = if (isServiceRunning) onStopClick else onStartClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isServiceRunning) "Stop Agent" else "Start Agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Logs button
            OutlinedButton(
                onClick = { showLogsDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.List, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Show Logs")
            }
            
            if (!hasPermissions && !isServiceRunning) {
                Text(
                    text = "Screen capture permission required",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSettingsCard(
    serverUrl: String,
    onUrlChange: (String) -> Unit
) {
    var editedUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    var isEditing by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Server Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(
                    onClick = { isEditing = !isEditing }
                ) {
                    Icon(
                        imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = "Edit"
                    )
                }
            }
            
            OutlinedTextField(
                value = editedUrl,
                onValueChange = { editedUrl = it },
                label = { Text("Server URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = isEditing,
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Link, contentDescription = null)
                },
                shape = RoundedCornerShape(12.dp)
            )
            
            if (isEditing && editedUrl != serverUrl) {
                Button(
                    onClick = {
                        onUrlChange(editedUrl)
                        isEditing = false
                    },
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun StreamSettingsCard(
    quality: Int,
    fps: Int,
    onQualityChange: (Int) -> Unit,
    onFpsChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Stream Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            // Quality slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Quality",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "$quality%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { onQualityChange(it.toInt()) },
                    valueRange = 10f..100f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            
            // FPS slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Frame Rate",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "$fps FPS",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Slider(
                    value = fps.toFloat(),
                    onValueChange = { onFpsChange(it.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

@Composable
private fun StatsCard(stats: com.sphere.agent.ui.viewmodel.AgentStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Statistics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.Image,
                    value = stats.framesSent.toString(),
                    label = "Frames"
                )
                StatItem(
                    icon = Icons.Default.CloudUpload,
                    value = formatBytes(stats.bytesTransferred),
                    label = "Transferred"
                )
                StatItem(
                    icon = Icons.Default.Terminal,
                    value = stats.commandsExecuted.toString(),
                    label = "Commands"
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccessibilityWarningCard(
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                
                Text(
                    text = "Accessibility Service Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            
            Text(
                text = "To control the device remotely (tap, swipe, buttons), please enable Accessibility Service for SphereAgent.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
            
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }
}

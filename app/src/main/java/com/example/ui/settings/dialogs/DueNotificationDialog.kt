package com.example.ui.settings.dialogs

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.repository.BillingRepository
import com.example.notification.DueReminderNotificationManager
import com.example.notification.DueSummary
import com.example.printer.EscPosHelper
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DueNotificationDialog(
    repository: BillingRepository,
    onDismiss: () -> Unit,
    onNavigateToCashier: () -> Unit,
    onShowFeedback: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isEnabled by remember { mutableStateOf(DueReminderNotificationManager.isNotificationEnabled(context)) }
    var summary by remember { mutableStateOf<DueSummary?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        summary = DueReminderNotificationManager.calculateDueSummary(repository)
        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(10.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 16.dp)
                .testTag("due_notification_settings_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFFEF3C7),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Notifikasi Jatuh Tempo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Slate900,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Pengingat penagihan tagihan pelanggan",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate500,
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = Slate500
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Switch Aktifkan
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isEnabled) Color(0xFFEEF2FF) else Slate100,
                    border = BorderStroke(1.dp, if (isEnabled) Color(0xFFC7D2FE) else Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pengingat Otomatis",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Slate900
                            )
                            Text(
                                text = "Kirim notifikasi di ponsel saat mendekati dan melewati jatuh tempo",
                                fontSize = 11.sp,
                                color = Slate600
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                isEnabled = checked
                                DueReminderNotificationManager.setNotificationEnabled(context, checked)
                                onShowFeedback(if (checked) "✓ Notifikasi jatuh tempo diaktifkan" else "Notifikasi jatuh tempo dimatikan")
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = BluePrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Ringkasan Tagihan Saat Ini
                Text(
                    text = "Status Tagihan Saat Ini",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Slate700
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = BluePrimary)
                    }
                } else {
                    val currentSummary = summary
                    if (currentSummary != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = DangerRedLight,
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Hari Ini", fontSize = 11.sp, color = DangerRed, fontWeight = FontWeight.SemiBold)
                                    Text("${currentSummary.dueTodayCount}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DangerRed)
                                    Text("pelanggan", fontSize = 10.sp, color = DangerRed.copy(alpha = 0.8f))
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFFEF3C7),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Menunggak", fontSize = 11.sp, color = Color(0xFFB45309), fontWeight = FontWeight.SemiBold)
                                    Text("${currentSummary.overdueCount}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309))
                                    Text("pelanggan", fontSize = 10.sp, color = Color(0xFFB45309).copy(alpha = 0.8f))
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = BluePrimaryLight,
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Segera", fontSize = 11.sp, color = BluePrimary, fontWeight = FontWeight.SemiBold)
                                    Text("${currentSummary.upcomingCount}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = BluePrimary)
                                    Text("pelanggan", fontSize = 10.sp, color = BluePrimary.copy(alpha = 0.8f))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Slate50,
                            border = BorderStroke(1.dp, Slate200),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Total Estimasi Belum Lunas:",
                                    fontSize = 12.sp,
                                    color = Slate600
                                )
                                Text(
                                    text = EscPosHelper.formatRupiah(currentSummary.totalUnpaidAmount),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Info Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF1F5F9),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Slate500,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Notifikasi status bar akan otomatis muncul saat aplikasi mendeteksi pelanggan yang masuk tanggal jatuh tempo.",
                            fontSize = 11.sp,
                            color = Slate600,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val s = summary ?: DueReminderNotificationManager.calculateDueSummary(repository)
                                val sent = DueReminderNotificationManager.showDueNotification(context, s, forceShow = true)
                                if (sent) {
                                    onShowFeedback("✓ Notifikasi uji coba dikirim ke Status Bar ponsel!")
                                } else {
                                    onShowFeedback("Periksa izin notifikasi Android Anda.")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tes Notifikasi", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            onDismiss()
                            onNavigateToCashier()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.PointOfSale, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Buka Kasir", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

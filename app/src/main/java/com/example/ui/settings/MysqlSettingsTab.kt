package com.example.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.sync.BillingkuPhpScript
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MysqlSettingsTab(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    onShowFeedback: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var apiUrl by remember(uiState.mysqlApiUrl) { mutableStateOf(uiState.mysqlApiUrl) }
    var apiKey by remember(uiState.mysqlApiKey) { mutableStateOf(uiState.mysqlApiKey) }
    var autoSync by remember(uiState.mysqlAutoSync) { mutableStateOf(uiState.mysqlAutoSync) }

    var testDialogMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showDownloadConfirmDialog by remember { mutableStateOf(false) }
    var showUploadConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---------------------------------------------------------------------
        // 1. BANNER STATUS KONEKSI & SINKRONISASI
        // ---------------------------------------------------------------------
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, if (uiState.mysqlApiUrl.isNotBlank()) Color(0xFF99F6E4) else Slate200),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("card_mysql_status")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (uiState.mysqlApiUrl.isNotBlank()) Color(0xFFCCFBF1) else Slate100),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = if (uiState.mysqlApiUrl.isNotBlank()) Color(0xFF0F766E) else Slate500,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Server MySQL Pribadi",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Slate900
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (uiState.mysqlApiUrl.isNotBlank()) SuccessGreen.copy(alpha = 0.12f) else Slate100
                        ) {
                            Text(
                                text = if (uiState.mysqlApiUrl.isNotBlank()) "Terkonfigurasi" else "Belum Diatur",
                                color = if (uiState.mysqlApiUrl.isNotBlank()) SuccessGreen else Slate600,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Sinkronisasi Terakhir: ${uiState.mysqlLastSyncTime.ifBlank { "Belum pernah sinkronisasi" }}",
                        fontSize = 11.sp,
                        color = Slate500
                    )
                    if (uiState.mysqlLastSyncStatus.isNotBlank()) {
                        Text(
                            text = uiState.mysqlLastSyncStatus,
                            fontSize = 11.sp,
                            color = BluePrimary,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF8FAFC))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = if (uiState.pendingSyncCount > 0) Color(0xFFD97706) else SuccessGreen,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.pendingSyncCount > 0) {
                                "Room DB: ${uiState.pendingSyncCount} data antre sinkronisasi"
                            } else {
                                "Room DB: Berfungsi offline & semua data seragam"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (uiState.pendingSyncCount > 0) Color(0xFFB45309) else Color(0xFF15803D)
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // 2. FORM KONFIGURASI REST API
        // ---------------------------------------------------------------------
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Slate200),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SettingsSuggest,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Konfigurasi Koneksi REST API",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Slate900
                    )
                }

                HorizontalDivider(color = Slate100)

                // Input URL API
                Column {
                    Text(
                        text = "URL REST API Endpoint (billingku.php) *",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = Slate800
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        placeholder = { Text("http://domain-anda.com/billingku.php", fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_mysql_api_url"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Language, contentDescription = null, tint = Slate400, modifier = Modifier.size(18.dp))
                        }
                    )
                    Text(
                        text = "Contoh cPanel: https://wifi.namadomain.com/billingku.php\nContoh Lokal: http://192.168.1.100/billingku.php",
                        fontSize = 10.sp,
                        color = Slate500,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }

                // Input API Secret Key
                Column {
                    Text(
                        text = "API Secret Key (Kunci Keamanan) *",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = Slate800
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        placeholder = { Text("billingku_secret", fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_mysql_api_key"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.VpnKey, contentDescription = null, tint = Slate400, modifier = Modifier.size(18.dp))
                        }
                    )
                    Text(
                        text = "Harus sama persis dengan variabel \$api_key di dalam file billingku.php",
                        fontSize = 10.sp,
                        color = Slate500,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }

                // Switch Auto-Sync
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Slate50)
                        .border(1.dp, Slate200, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Sync Real-time & Periodik",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Slate900
                        )
                        Text(
                            text = "Otomatis sinkronkan penambahan & pembaruan data pelanggan, paket, transaksi, dan staf langsung ke server MySQL",
                            fontSize = 11.sp,
                            color = Slate500
                        )
                    }
                    Switch(
                        checked = autoSync,
                        onCheckedChange = { autoSync = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = BluePrimary)
                    )
                }

                // Tombol Simpan & Tes Koneksi
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (apiUrl.isBlank()) {
                                Toast.makeText(context, "Masukkan URL API terlebih dahulu", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            viewModel.testMysqlConnection(apiUrl, apiKey) { isSuccess, msg ->
                                testDialogMessage = Pair(isSuccess, msg)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_test_mysql_connection"),
                        enabled = !uiState.isMysqlTesting
                    ) {
                        if (uiState.isMysqlTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Menguji...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tes Koneksi", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.saveMysqlSettings(apiUrl, apiKey, autoSync)
                            Toast.makeText(context, "Pengaturan MySQL berhasil disimpan!", Toast.LENGTH_SHORT).show()
                            onShowFeedback("Pengaturan MySQL berhasil disimpan")
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("btn_save_mysql_settings")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Simpan Pengaturan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // 3. AKSI SINKRONISASI MANUAL (UPLOAD & DOWNLOAD)
        // ---------------------------------------------------------------------
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Slate200),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SyncAlt,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sinkronisasi & Backup Database",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Slate900
                    )
                }

                Text(
                    text = "Gunakan tombol di bawah ini untuk mencadangkan (backup) seluruh data lokal aplikasi ke server MySQL Anda, atau mengunduh ulang semua data jika ganti ponsel.",
                    fontSize = 11.sp,
                    color = Slate600
                )

                HorizontalDivider(color = Slate100)

                // Tombol Sinkronisasi Penuh (Auto-Sync & Reconcile)
                Button(
                    onClick = {
                        if (uiState.mysqlApiUrl.isBlank()) {
                            Toast.makeText(context, "Harap simpan URL API MySQL terlebih dahulu", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.triggerFullAutoSync { success, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                onShowFeedback(msg)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_auto_sync_full"),
                    enabled = !uiState.isMysqlSyncing
                ) {
                    if (uiState.isMysqlSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sedang Menyelaraskan Data...", fontSize = 12.sp)
                    } else {
                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sinkronkan Sekarang (Dorong Antrean & Samakan Server)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Tombol Upload (Cadangkan ke MySQL)
                OutlinedButton(
                    onClick = {
                        if (uiState.mysqlApiUrl.isBlank()) {
                            Toast.makeText(context, "Harap simpan URL API MySQL terlebih dahulu", Toast.LENGTH_SHORT).show()
                        } else {
                            showUploadConfirmDialog = true
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_upload_all_mysql"),
                    enabled = !uiState.isMysqlSyncing
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Upload Seluruh Database Lokal ke MySQL", fontSize = 12.sp)
                }

                // Tombol Download (Pulihkan dari MySQL)
                OutlinedButton(
                    onClick = {
                        if (uiState.mysqlApiUrl.isBlank()) {
                            Toast.makeText(context, "Harap simpan URL API MySQL terlebih dahulu", Toast.LENGTH_SHORT).show()
                        } else {
                            showDownloadConfirmDialog = true
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0F766E)),
                    border = BorderStroke(1.dp, Color(0xFF0F766E)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_download_all_mysql"),
                    enabled = !uiState.isMysqlSyncing
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF0F766E))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download & Pulihkan Data dari MySQL", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Dialog Hasil Tes Koneksi
    if (testDialogMessage != null) {
        val (isSuccess, message) = testDialogMessage!!
        AlertDialog(
            onDismissRequest = { testDialogMessage = null },
            icon = {
                Icon(
                    imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (isSuccess) SuccessGreen else DangerRed,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = if (isSuccess) "Koneksi MySQL Sukses" else "Koneksi MySQL Gagal",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(text = message, fontSize = 13.sp, color = Slate700)
            },
            confirmButton = {
                Button(
                    onClick = { testDialogMessage = null },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isSuccess) SuccessGreen else BluePrimary)
                ) {
                    Text("OK")
                }
            }
        )
    }

    // Dialog Konfirmasi Upload
    if (showUploadConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUploadConfirmDialog = false },
            title = { Text("Konfirmasi Upload ke MySQL", fontWeight = FontWeight.Bold) },
            text = {
                Text("Semua data pelanggan, paket, transaksi kasir, setoran, dan staf di aplikasi ini akan dikirim dan disimpan ke server MySQL Anda. Lanjutkan?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUploadConfirmDialog = false
                        viewModel.uploadAllToMysql { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            onShowFeedback(msg)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary)
                ) {
                    Text("Ya, Mulai Upload")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUploadConfirmDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Dialog Konfirmasi Download
    if (showDownloadConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadConfirmDialog = false },
            title = { Text("Konfirmasi Download dari MySQL", fontWeight = FontWeight.Bold) },
            text = {
                Text("Data dari server MySQL akan diunduh dan digabungkan ke penyimpanan lokal aplikasi ini. Gunakan fitur ini jika Anda ingin menyinkronkan data dari server ke perangkat ini. Lanjutkan?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDownloadConfirmDialog = false
                        viewModel.downloadAllFromMysql { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            onShowFeedback(msg)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))
                ) {
                    Text("Ya, Download Sekarang")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadConfirmDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

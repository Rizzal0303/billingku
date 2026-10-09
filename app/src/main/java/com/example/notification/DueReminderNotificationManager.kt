package com.example.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.entity.CustomerEntity
import com.example.data.repository.BillingRepository
import kotlinx.coroutines.flow.firstOrNull
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DueSummary(
    val totalUnpaid: Int,
    val dueTodayCount: Int,
    val overdueCount: Int,
    val upcomingCount: Int,
    val totalUnpaidAmount: Long,
    val dueTodayCustomers: List<CustomerEntity>,
    val overdueCustomers: List<CustomerEntity>,
    val upcomingCustomers: List<CustomerEntity>
)

object DueReminderNotificationManager {
    const val CHANNEL_ID = "channel_due_reminders"
    const val NOTIFICATION_ID = 1001
    private const val PREFS_NAME = "billingku_due_notification_prefs"
    private const val KEY_ENABLED = "due_notification_enabled"
    private const val KEY_LAST_DATE = "due_notification_last_date"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isNotificationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Pengingat Jatuh Tempo Billingku"
            val descriptionText = "Notifikasi tagihan pelanggan yang jatuh tempo hari ini atau menunggak"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    suspend fun calculateDueSummary(repository: BillingRepository): DueSummary {
        val currentPeriod = repository.getCurrentMonthPeriod()
        val allCustomers = repository.allCustomers.firstOrNull() ?: emptyList()
        val currentTrxs = repository.getTransactionsByMonth(currentPeriod).firstOrNull() ?: emptyList()

        val paidCustomerIds = currentTrxs.map { it.customerId }.toSet()
        val activeCustomers = allCustomers.filter {
            it.status.equals("ACTIVE", ignoreCase = true) || it.status.equals("ISOLATED", ignoreCase = true)
        }
        val unpaidCustomers = activeCustomers.filter { !paidCustomerIds.contains(it.id) }

        val calendar = Calendar.getInstance()
        val todayDay = calendar.get(Calendar.DAY_OF_MONTH)

        val dueToday = mutableListOf<CustomerEntity>()
        val overdue = mutableListOf<CustomerEntity>()
        val upcoming = mutableListOf<CustomerEntity>()

        for (cust in unpaidCustomers) {
            when {
                cust.dueDay == todayDay -> dueToday.add(cust)
                cust.dueDay < todayDay -> overdue.add(cust)
                cust.dueDay in (todayDay + 1)..(todayDay + 3) -> upcoming.add(cust)
            }
        }

        val totalAmount = unpaidCustomers.sumOf { it.monthlyFee }

        return DueSummary(
            totalUnpaid = unpaidCustomers.size,
            dueTodayCount = dueToday.size,
            overdueCount = overdue.size,
            upcomingCount = upcoming.size,
            totalUnpaidAmount = totalAmount,
            dueTodayCustomers = dueToday,
            overdueCustomers = overdue,
            upcomingCustomers = upcoming
        )
    }

    fun showDueNotification(
        context: Context,
        summary: DueSummary,
        forceShow: Boolean = false
    ): Boolean {
        if (!forceShow && !isNotificationEnabled(context)) {
            return false
        }

        // Jika tidak ada yang jatuh tempo hari ini atau menunggak, dan tidak ada tagihan tertunggak
        if (summary.dueTodayCount == 0 && summary.overdueCount == 0 && summary.upcomingCount == 0) {
            return false
        }

        // Cek permission Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }

        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formatRp = NumberFormat.getCurrencyInstance(Locale("id", "ID")).apply {
            maximumFractionDigits = 0
        }

        val title = when {
            summary.dueTodayCount > 0 && summary.overdueCount > 0 ->
                "⚡ ${summary.dueTodayCount} Jatuh Tempo & ${summary.overdueCount} Menunggak!"
            summary.dueTodayCount > 0 ->
                "🔔 ${summary.dueTodayCount} Pelanggan Jatuh Tempo Hari Ini!"
            summary.overdueCount > 0 ->
                "⚠️ ${summary.overdueCount} Pelanggan Melewati Jatuh Tempo!"
            else ->
                "🗓️ ${summary.upcomingCount} Tagihan Mendekati Jatuh Tempo"
        }

        val sampleNames = (summary.dueTodayCustomers + summary.overdueCustomers + summary.upcomingCustomers)
            .take(3)
            .joinToString(", ") { it.name }

        val contentText = "Total ${summary.totalUnpaid} belum lunas (${formatRp.format(summary.totalUnpaidAmount)}). $sampleNames"

        val bigText = buildString {
            append("Rincian Tagihan Tertunggak:\n")
            if (summary.dueTodayCount > 0) {
                append("• Jatuh Tempo Hari Ini: ${summary.dueTodayCount} pelanggan\n")
            }
            if (summary.overdueCount > 0) {
                append("• Melewati Jatuh Tempo: ${summary.overdueCount} pelanggan\n")
            }
            if (summary.upcomingCount > 0) {
                append("• Segera Jatuh Tempo: ${summary.upcomingCount} pelanggan\n")
            }
            append("• Total Estimasi Tagihan: ${formatRp.format(summary.totalUnpaidAmount)}\n\n")
            append("Ketuk notifikasi ini untuk membuka kasir dan melakukan penagihan.")
        }

        val largeIcon = try {
            android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.billingku_logo_1790440692708)
        } catch (_: Exception) {
            null
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .apply {
                if (largeIcon != null) {
                    setLargeIcon(largeIcon)
                }
            }
            .setColor(ContextCompat.getColor(context, R.color.blue_primary))
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())

        val todayDateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        getPrefs(context).edit().putString(KEY_LAST_DATE, todayDateStr).apply()
        return true
    }
}

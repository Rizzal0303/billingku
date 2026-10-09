package com.example.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.example.data.local.AppDatabase
import com.example.data.local.entity.CustomerEntity
import com.example.data.local.entity.PackagePlanEntity
import com.example.data.local.entity.SettlementBatchEntity
import com.example.data.local.entity.StaffEntity
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.local.entity.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MysqlSyncManager(
    private val context: Context,
    private val db: AppDatabase
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mysql_sync_prefs", Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .build()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncStatus = MutableStateFlow(getLastSyncStatus())
    val lastSyncStatusState: StateFlow<String> = _lastSyncStatus.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(getLastSyncTimestamp())
    val lastSyncTimeState: StateFlow<Long> = _lastSyncTime.asStateFlow()

    val pendingQueueCount: Flow<Int> = db.syncQueueDao().getPendingCount()
    val pendingQueueFlow: Flow<List<SyncQueueEntity>> = db.syncQueueDao().getAllPendingFlow()

    // ------------------------------------------------------------------------
    // SETTINGS GETTERS & SETTERS
    // ------------------------------------------------------------------------
    fun normalizeUrl(rawUrl: String): String {
        var u = rawUrl.trim()
        if (u.isBlank()) return ""
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
            u = "http://$u"
        }
        return u.trimEnd('/')
    }

    fun buildUrl(action: String, customUrl: String? = null, customKey: String? = null): String {
        val baseUrl = normalizeUrl(customUrl ?: getApiUrl())
        if (baseUrl.isBlank()) return ""
        val key = (customKey ?: getApiKey()).trim()
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val encodedAction = java.net.URLEncoder.encode(action, "UTF-8")
        val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
        return "$baseUrl${separator}action=$encodedAction&api_key=$encodedKey"
    }

    fun getApiUrl(): String = prefs.getString("mysql_api_url", "") ?: ""
    fun setApiUrl(url: String) {
        val clean = normalizeUrl(url)
        prefs.edit().putString("mysql_api_url", clean).apply()
    }

    fun getApiKey(): String = prefs.getString("mysql_api_key", "billingku_secret") ?: "billingku_secret"
    fun setApiKey(key: String) = prefs.edit().putString("mysql_api_key", key.trim()).apply()

    fun isAutoSyncEnabled(): Boolean = prefs.getBoolean("mysql_auto_sync", true)
    fun setAutoSyncEnabled(enabled: Boolean) = prefs.edit().putBoolean("mysql_auto_sync", enabled).apply()

    fun getLastSyncTimestamp(): Long = prefs.getLong("mysql_last_sync_time", 0L)
    fun setLastSyncTimestamp(time: Long) {
        prefs.edit().putLong("mysql_last_sync_time", time).apply()
        _lastSyncTime.value = time
    }

    fun getLastSyncStatus(): String = prefs.getString("mysql_last_sync_status", "Belum pernah sinkronisasi") ?: "Belum pernah sinkronisasi"
    fun setLastSyncStatus(status: String) {
        prefs.edit().putString("mysql_last_sync_status", status).apply()
        _lastSyncStatus.value = status
    }

    fun getLastSyncFormatted(): String {
        val ts = getLastSyncTimestamp()
        if (ts == 0L) return "Belum pernah sinkronisasi"
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))
        return sdf.format(Date(ts))
    }

    // Centralized Helper to execute sync POST and update live status
    fun executeSyncPost(
        action: String,
        payload: JSONObject,
        successMessage: String
    ): Result<Boolean> {
        val url = getApiUrl()
        val key = getApiKey()
        if (url.isBlank()) return Result.failure(Exception("URL API belum dikonfigurasi"))

        return try {
            val endpointUrl = buildUrl(action)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = payload.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(endpointUrl)
                .addHeader("X-API-Key", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }

                if (resp.code == 409 || json.optString("status") == "duplicate_rejected") {
                    val existingInv = json.optString("existing_invoice", "")
                    val msg = json.optString("message", "Tagihan pelanggan ini sudah dibayar sebelumnya oleh kasir lain (No. Faktur: $existingInv).")
                    setLastSyncStatus("Pencegahan: Invoice ganda ditolak ($existingInv)")
                    return Result.failure(com.example.util.DuplicatePaymentException(existingInv, msg))
                }

                if (!resp.isSuccessful) {
                    val errMsg = "HTTP ${resp.code}: ${bodyStr.ifBlank { resp.message }}"
                    setLastSyncStatus("Sinkronisasi gagal: $errMsg")
                    return Result.failure(Exception(errMsg))
                }

                if (json.optString("status") == "success") {
                    val now = System.currentTimeMillis()
                    setLastSyncTimestamp(now)
                    setLastSyncStatus(successMessage)
                    Result.success(true)
                } else {
                    val msg = json.optString("message", "Gagal memperbarui ke MySQL: $bodyStr")
                    setLastSyncStatus("Sinkronisasi gagal: $msg")
                    Result.failure(Exception(msg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------------
    // ACTION 1: TEST CONNECTION (PING)
    // ------------------------------------------------------------------------
    suspend fun testConnection(targetUrl: String? = null, targetKey: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val url = normalizeUrl(targetUrl ?: getApiUrl())
        val key = (targetKey ?: getApiKey()).trim()

        if (url.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("URL API / Server MySQL belum diisi"))
        }

        try {
            val pingUrl = buildUrl("ping", url, key)
            val request = Request.Builder()
                .url(pingUrl)
                .addHeader("X-API-Key", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP $code: ${response.message.ifBlank { "Gagal terhubung ke server" }} ($body)")
                    )
                }

                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val status = json.optString("status", "")
                val message = json.optString("message", "Berhasil")
                val dbName = json.optString("database", "MySQL")
                val keyValid = json.optBoolean("api_key_valid", true)

                if (status == "success") {
                    if (!keyValid) {
                        Result.failure(Exception("Terkoneksi ke server, tetapi API Key tidak cocok dengan billingku.php!"))
                    } else {
                        Result.success("✓ $message (Database: $dbName)")
                    }
                } else {
                    Result.failure(Exception(json.optString("message", "Respon server gagal: $body")))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Gagal menghubungi server: ${e.localizedMessage ?: e.message}"))
        }
    }

    // ------------------------------------------------------------------------
    // ACTION 2: UPLOAD SEMUA DATA LOKAL KE MYSQL (SYNC ALL)
    // ------------------------------------------------------------------------
    suspend fun uploadAllToMysql(): Result<String> = withContext(Dispatchers.IO) {
        val url = getApiUrl().trim()
        val key = getApiKey().trim()

        if (url.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("URL API belum diatur di menu Pengaturan"))
        }

        try {
            // Ambil semua data lokal Room
            val customers = db.customerDao().getAllCustomersSync()
            val packages = db.packagePlanDao().getAllPlansSync()
            val transactions = db.transactionDao().getAllTransactionsSync()
            val settlements = db.settlementDao().getAllSettlementBatchesSync()
            val staff = db.staffDao().getAllStaffSync()

            // Susun JSON Payload
            val rootJson = JSONObject()
            rootJson.put("action", "sync_all")
            rootJson.put("api_key", key)

            val dataJson = JSONObject()

            // 1. Customers
            val customersArray = JSONArray()
            for (c in customers) {
                val obj = JSONObject()
                obj.put("id", c.id)
                obj.put("customerCode", c.customerCode)
                obj.put("name", c.name)
                obj.put("phone", c.phone)
                obj.put("address", c.address)
                obj.put("odpLocation", c.odpLocation)
                obj.put("packageId", c.packageId)
                obj.put("packageName", c.packageName)
                obj.put("monthlyFee", c.monthlyFee)
                obj.put("dueDay", c.dueDay)
                obj.put("ipOrPppoe", c.ipOrPppoe)
                obj.put("status", c.status)
                obj.put("createdAt", c.createdAt)
                customersArray.put(obj)
            }
            dataJson.put("customers", customersArray)

            // 2. Packages
            val packagesArray = JSONArray()
            for (p in packages) {
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                obj.put("speedMbps", p.speedMbps)
                obj.put("price", p.price)
                obj.put("description", p.description)
                obj.put("isActive", p.isActive)
                packagesArray.put(obj)
            }
            dataJson.put("packages", packagesArray)

            // 3. Transactions
            val transactionsArray = JSONArray()
            for (t in transactions) {
                val obj = JSONObject()
                obj.put("id", t.id)
                obj.put("invoiceNumber", t.invoiceNumber)
                obj.put("customerId", t.customerId)
                obj.put("customerName", t.customerName)
                obj.put("customerCode", t.customerCode)
                obj.put("packageName", t.packageName)
                obj.put("monthlyFee", t.monthlyFee)
                obj.put("adminFee", t.adminFee)
                obj.put("discount", t.discount)
                obj.put("totalAmount", t.totalAmount)
                obj.put("periodMonth", t.periodMonth)
                obj.put("paymentMethod", t.paymentMethod)
                obj.put("cashierName", t.cashierName)
                obj.put("cashierRole", t.cashierRole)
                obj.put("notes", t.notes)
                obj.put("paidAt", t.paidAt)
                obj.put("isPrinted", t.isPrinted)
                obj.put("isDeposited", t.isDeposited)
                if (t.settlementBatchId != null) obj.put("settlementBatchId", t.settlementBatchId)
                if (t.settlementBatchNumber != null) obj.put("settlementBatchNumber", t.settlementBatchNumber)
                if (t.depositedAt != null) obj.put("depositedAt", t.depositedAt)
                transactionsArray.put(obj)
            }
            dataJson.put("transactions", transactionsArray)

            // 4. Settlements
            val settlementsArray = JSONArray()
            for (sb in settlements) {
                val obj = JSONObject()
                obj.put("id", sb.id)
                obj.put("batchNumber", sb.batchNumber)
                obj.put("periodMonth", sb.periodMonth)
                obj.put("cashierName", sb.cashierName)
                obj.put("recipientName", sb.recipientName)
                obj.put("totalAmount", sb.totalAmount)
                obj.put("totalCashAmount", sb.totalCashAmount)
                obj.put("totalNonCashAmount", sb.totalNonCashAmount)
                obj.put("customerCount", sb.customerCount)
                obj.put("notes", sb.notes)
                obj.put("createdAt", sb.createdAt)
                settlementsArray.put(obj)
            }
            dataJson.put("settlement_batches", settlementsArray)

            // 5. Staff
            val staffArray = JSONArray()
            for (s in staff) {
                val obj = JSONObject()
                obj.put("id", s.id)
                obj.put("username", s.username)
                obj.put("name", s.name)
                obj.put("role", s.role)
                obj.put("phone", s.phone)
                obj.put("pin", s.pin)
                obj.put("isActive", s.isActive)
                staffArray.put(obj)
            }
            dataJson.put("staff", staffArray)

            rootJson.put("data", dataJson)

            val syncUrl = buildUrl("sync_all")
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = rootJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(syncUrl)
                .addHeader("X-API-Key", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            val responseText = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP $code: Gagal upload ($responseText)"))
            }

            val resJson = JSONObject(responseText)
            if (resJson.optString("status") == "success") {
                val now = System.currentTimeMillis()
                val summary = "Sukses upload: ${customers.size} Pelanggan, ${packages.size} Paket, ${transactions.size} Transaksi, ${settlements.size} Setoran"
                setLastSyncTimestamp(now)
                setLastSyncStatus(summary)
                Result.success(summary)
            } else {
                val errMsg = resJson.optString("message", "Gagal upload data ke MySQL: $responseText")
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Error saat sinkronisasi upload: ${e.localizedMessage ?: e.message}"))
        }
    }

    // ------------------------------------------------------------------------
    // ACTION 3: DOWNLOAD SEMUA DATA DARI MYSQL KE APLIKASI ANDROID (RESTORE)
    // ------------------------------------------------------------------------
    suspend fun downloadAllFromMysql(): Result<String> = withContext(Dispatchers.IO) {
        val url = getApiUrl().trim()
        val key = getApiKey().trim()

        if (url.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("URL API belum diatur di menu Pengaturan"))
        }

        try {
            val downloadUrl = buildUrl("download_all")
            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("X-API-Key", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP $code: Gagal download data ($body)"))
            }

            val json = JSONObject(body)
            if (json.optString("status") != "success") {
                return@withContext Result.failure(Exception(json.optString("message", "Gagal mengunduh data")))
            }

            val data = json.getJSONObject("data")

            // Ambil daftar ID yang masih dalam antrean pending sync agar data offline lokal tidak tertimpa data lama server
            val pendingList = db.syncQueueDao().getAllPending()
            val pendingCustomerIds = pendingList.filter { it.entityType == "CUSTOMER" }.map { it.entityId }.toSet()
            val pendingPackageIds = pendingList.filter { it.entityType == "PACKAGE" }.map { it.entityId }.toSet()
            val pendingTransactionIds = pendingList.filter { it.entityType == "TRANSACTION" }.map { it.entityId }.toSet()
            val pendingSettlementIds = pendingList.filter { it.entityType == "SETTLEMENT" }.map { it.entityId }.toSet()
            val pendingStaffIds = pendingList.filter { it.entityType == "STAFF" }.map { it.entityId }.toSet()

            // 1. Parse & Simpan Customers
            val customersArray = data.optJSONArray("customers")
            val customerList = mutableListOf<CustomerEntity>()
            if (customersArray != null) {
                for (i in 0 until customersArray.length()) {
                    val obj = customersArray.getJSONObject(i)
                    customerList.add(
                        CustomerEntity(
                            id = obj.optLong("id"),
                            customerCode = obj.optString("customerCode"),
                            name = obj.optString("name"),
                            phone = obj.optString("phone"),
                            address = obj.optString("address"),
                            odpLocation = obj.optString("odpLocation", ""),
                            packageId = obj.optLong("packageId"),
                            packageName = obj.optString("packageName"),
                            monthlyFee = obj.optLong("monthlyFee"),
                            dueDay = obj.optInt("dueDay", 10),
                            ipOrPppoe = obj.optString("ipOrPppoe", ""),
                            status = obj.optString("status", "ACTIVE"),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                val safeToInsert = customerList.filter { it.id !in pendingCustomerIds }
                if (safeToInsert.isNotEmpty()) {
                    db.customerDao().insertCustomers(safeToInsert)
                }
                val keepIds = (customerList.map { it.id } + pendingCustomerIds).distinct()
                if (keepIds.isNotEmpty()) {
                    db.customerDao().deleteCustomersNotIn(keepIds)
                } else if (pendingCustomerIds.isEmpty()) {
                    db.customerDao().clearAllCustomers()
                }
            }

            // 2. Parse & Simpan Packages
            val packagesArray = data.optJSONArray("packages")
            val packageList = mutableListOf<PackagePlanEntity>()
            if (packagesArray != null) {
                for (i in 0 until packagesArray.length()) {
                    val obj = packagesArray.getJSONObject(i)
                    packageList.add(
                        PackagePlanEntity(
                            id = obj.optLong("id"),
                            name = obj.optString("name"),
                            speedMbps = obj.optInt("speedMbps"),
                            price = obj.optLong("price"),
                            description = obj.optString("description", ""),
                            isActive = obj.optBoolean("isActive", true)
                        )
                    )
                }
                val safeToInsert = packageList.filter { it.id !in pendingPackageIds }
                if (safeToInsert.isNotEmpty()) {
                    db.packagePlanDao().insertPlans(safeToInsert)
                }
                val keepIds = (packageList.map { it.id } + pendingPackageIds).distinct()
                if (keepIds.isNotEmpty()) {
                    db.packagePlanDao().deletePlansNotIn(keepIds)
                } else if (pendingPackageIds.isEmpty()) {
                    db.packagePlanDao().clearAllPlans()
                }
            }

            // 3. Parse & Simpan Transactions
            val transactionsArray = data.optJSONArray("transactions")
            val transactionList = mutableListOf<TransactionEntity>()
            if (transactionsArray != null) {
                for (i in 0 until transactionsArray.length()) {
                    val obj = transactionsArray.getJSONObject(i)
                    val settlementId = if (obj.isNull("settlementBatchId")) null else obj.optLong("settlementBatchId")
                    val settlementNum = if (obj.isNull("settlementBatchNumber")) null else obj.optString("settlementBatchNumber")
                    val depositedAt = if (obj.isNull("depositedAt")) null else obj.optLong("depositedAt")

                    transactionList.add(
                        TransactionEntity(
                            id = obj.optLong("id"),
                            invoiceNumber = obj.optString("invoiceNumber"),
                            customerId = obj.optLong("customerId"),
                            customerName = obj.optString("customerName"),
                            customerCode = obj.optString("customerCode"),
                            packageName = obj.optString("packageName"),
                            monthlyFee = obj.optLong("monthlyFee"),
                            adminFee = obj.optLong("adminFee", 0L),
                            discount = obj.optLong("discount", 0L),
                            totalAmount = obj.optLong("totalAmount"),
                            periodMonth = obj.optString("periodMonth"),
                            paymentMethod = obj.optString("paymentMethod", "TUNAI"),
                            cashierName = obj.optString("cashierName", "Kasir Utama"),
                            cashierRole = obj.optString("cashierRole", "KASIR"),
                            notes = obj.optString("notes", ""),
                            paidAt = obj.optLong("paidAt", System.currentTimeMillis()),
                            isPrinted = obj.optBoolean("isPrinted", false),
                            isDeposited = obj.optBoolean("isDeposited", false),
                            settlementBatchId = settlementId,
                            settlementBatchNumber = settlementNum,
                            depositedAt = depositedAt
                        )
                    )
                }
                // Deduplikasi otomatis: jika ada dua invoice untuk pelanggan & periode yang sama, pertahankan yang pertama
                val deduplicated = transactionList.distinctBy { "${it.customerId}_${it.periodMonth}" }
                val safeToInsert = deduplicated.filter { it.id !in pendingTransactionIds }
                if (safeToInsert.isNotEmpty()) {
                    db.transactionDao().insertTransactions(safeToInsert)
                }
                val keepIds = (deduplicated.map { it.id } + pendingTransactionIds).distinct()
                if (keepIds.isNotEmpty()) {
                    db.transactionDao().deleteTransactionsNotIn(keepIds)
                } else if (pendingTransactionIds.isEmpty()) {
                    db.transactionDao().clearAllTransactions()
                }
            }

            // 4. Parse & Simpan Settlements
            val settlementsArray = data.optJSONArray("settlement_batches")
            val settlementList = mutableListOf<SettlementBatchEntity>()
            if (settlementsArray != null) {
                for (i in 0 until settlementsArray.length()) {
                    val obj = settlementsArray.getJSONObject(i)
                    settlementList.add(
                        SettlementBatchEntity(
                            id = obj.optLong("id"),
                            batchNumber = obj.optString("batchNumber"),
                            periodMonth = obj.optString("periodMonth"),
                            cashierName = obj.optString("cashierName"),
                            recipientName = obj.optString("recipientName", "Owner / Admin"),
                            totalAmount = obj.optLong("totalAmount"),
                            totalCashAmount = obj.optLong("totalCashAmount", 0L),
                            totalNonCashAmount = obj.optLong("totalNonCashAmount", 0L),
                            customerCount = obj.optInt("customerCount", 0),
                            notes = obj.optString("notes", ""),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                val safeToInsert = settlementList.filter { it.id !in pendingSettlementIds }
                if (safeToInsert.isNotEmpty()) {
                    db.settlementDao().insertSettlementBatches(safeToInsert)
                }
                val keepIds = (settlementList.map { it.id } + pendingSettlementIds).distinct()
                if (keepIds.isNotEmpty()) {
                    db.settlementDao().deleteSettlementsNotIn(keepIds)
                } else if (pendingSettlementIds.isEmpty()) {
                    db.settlementDao().clearAllSettlements()
                }
            }

            // 5. Parse & Simpan Staff
            val staffArray = data.optJSONArray("staff")
            val staffList = mutableListOf<StaffEntity>()
            if (staffArray != null) {
                for (i in 0 until staffArray.length()) {
                    val obj = staffArray.getJSONObject(i)
                    staffList.add(
                        StaffEntity(
                            id = obj.optLong("id"),
                            username = obj.optString("username"),
                            name = obj.optString("name"),
                            role = obj.optString("role", "KASIR"),
                            phone = obj.optString("phone", ""),
                            pin = obj.optString("pin", "1234"),
                            isActive = obj.optBoolean("isActive", true)
                        )
                    )
                }
                val safeToInsert = staffList.filter { it.id !in pendingStaffIds }
                if (safeToInsert.isNotEmpty()) {
                    db.staffDao().insertStaffList(safeToInsert)
                }
                val keepIds = (staffList.map { it.id } + pendingStaffIds).distinct()
                if (keepIds.isNotEmpty()) {
                    db.staffDao().deleteStaffNotIn(keepIds)
                }
            }

            val now = System.currentTimeMillis()
            val summary = "Sukses unduh: ${customerList.size} Pelanggan, ${packageList.size} Paket, ${transactionList.size} Transaksi, ${settlementList.size} Setoran"
            setLastSyncTimestamp(now)
            setLastSyncStatus(summary)

            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(Exception("Error saat unduh data: ${e.localizedMessage ?: e.message}"))
        }
    }

    // ------------------------------------------------------------------------
    // ACTION 4: PUSH TRANSAKSI TUNGGAL KE MYSQL (INSTANT SYNC)
    // ------------------------------------------------------------------------
    suspend fun pushTransaction(t: TransactionEntity): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("action", "push_transaction")
            put("api_key", getApiKey())
            put("transaction", JSONObject().apply {
                put("id", t.id)
                put("invoiceNumber", t.invoiceNumber)
                put("customerId", t.customerId)
                put("customerName", t.customerName)
                put("customerCode", t.customerCode)
                put("packageName", t.packageName)
                put("monthlyFee", t.monthlyFee)
                put("adminFee", t.adminFee)
                put("discount", t.discount)
                put("totalAmount", t.totalAmount)
                put("periodMonth", t.periodMonth)
                put("paymentMethod", t.paymentMethod)
                put("cashierName", t.cashierName)
                put("cashierRole", t.cashierRole)
                put("notes", t.notes)
                put("paidAt", t.paidAt)
                put("isPrinted", t.isPrinted)
                put("isDeposited", t.isDeposited)
                if (t.settlementBatchId != null) put("settlementBatchId", t.settlementBatchId)
                if (t.settlementBatchNumber != null) put("settlementBatchNumber", t.settlementBatchNumber)
                if (t.depositedAt != null) put("depositedAt", t.depositedAt)
            })
        }
        executeSyncPost("push_transaction", rootJson, "✓ Transaksi ${t.invoiceNumber} langsung tersimpan ke MySQL")
    }

    suspend fun deleteTransaction(id: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("action", "delete_transaction")
            put("api_key", getApiKey())
            put("id", id)
        }
        executeSyncPost("delete_transaction", rootJson, "✓ Transaksi (ID $id) dihapus dari MySQL")
    }

    suspend fun checkPaymentStatusOnServer(customerId: Long, periodMonth: String): ServerPaymentCheckResult? = withContext(Dispatchers.IO) {
        val url = getApiUrl().trim()
        val key = getApiKey().trim()
        if (url.isBlank()) return@withContext null

        try {
            val endpointUrl = buildUrl("check_payment_status") + "&customerId=$customerId&periodMonth=" + java.net.URLEncoder.encode(periodMonth, "UTF-8")
            val request = Request.Builder()
                .url(endpointUrl)
                .addHeader("X-API-Key", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext null
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                val isPaid = json.optBoolean("is_paid", false)
                val invoiceNumber = json.optString("existing_invoice", "")
                val trxObj = json.optJSONObject("existing_transaction")
                val entity = if (trxObj != null) {
                    TransactionEntity(
                        id = trxObj.optLong("id"),
                        invoiceNumber = trxObj.optString("invoiceNumber"),
                        customerId = trxObj.optLong("customerId"),
                        customerName = trxObj.optString("customerName"),
                        customerCode = trxObj.optString("customerCode"),
                        packageName = trxObj.optString("packageName"),
                        monthlyFee = trxObj.optLong("monthlyFee"),
                        adminFee = trxObj.optLong("adminFee", 0L),
                        discount = trxObj.optLong("discount", 0L),
                        totalAmount = trxObj.optLong("totalAmount"),
                        periodMonth = trxObj.optString("periodMonth"),
                        paymentMethod = trxObj.optString("paymentMethod", "TUNAI"),
                        cashierName = trxObj.optString("cashierName", "Kasir Utama"),
                        cashierRole = trxObj.optString("cashierRole", "KASIR"),
                        notes = trxObj.optString("notes", ""),
                        paidAt = trxObj.optLong("paidAt", System.currentTimeMillis()),
                        isPrinted = trxObj.optBoolean("isPrinted", false),
                        isDeposited = trxObj.optBoolean("isDeposited", false)
                    )
                } else null

                ServerPaymentCheckResult(
                    isPaid = isPaid,
                    invoiceNumber = if (invoiceNumber.isNotBlank()) invoiceNumber else entity?.invoiceNumber,
                    cashierName = entity?.cashierName,
                    existingTransaction = entity,
                    message = json.optString("message", "")
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun pushCustomer(c: com.example.data.local.entity.CustomerEntity): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("action", "push_customer")
            put("api_key", getApiKey())
            put("customer", JSONObject().apply {
                put("id", c.id)
                put("customerCode", c.customerCode)
                put("name", c.name)
                put("phone", c.phone)
                put("address", c.address)
                put("odpLocation", c.odpLocation)
                put("packageId", c.packageId)
                put("packageName", c.packageName)
                put("monthlyFee", c.monthlyFee)
                put("dueDay", c.dueDay)
                put("ipOrPppoe", c.ipOrPppoe)
                put("status", c.status)
                put("createdAt", c.createdAt)
            })
        }
        executeSyncPost("push_customer", rootJson, "✓ Pelanggan '${c.name}' langsung tersimpan ke MySQL")
    }

    suspend fun deleteCustomer(id: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("action", "delete_customer")
            put("api_key", getApiKey())
            put("id", id)
        }
        executeSyncPost("delete_customer", rootJson, "✓ Pelanggan (ID $id) dihapus dari MySQL")
    }

    suspend fun pushPackagePlan(p: com.example.data.local.entity.PackagePlanEntity): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("action", "push_package")
            put("api_key", getApiKey())
            put("package", JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("speedMbps", p.speedMbps)
                put("price", p.price)
                put("description", p.description)
                put("isActive", p.isActive)
            })
        }
        executeSyncPost("push_package", rootJson, "✓ Paket '${p.name}' langsung tersimpan ke MySQL")
    }

    suspend fun deletePackagePlan(id: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("action", "delete_package")
            put("api_key", getApiKey())
            put("id", id)
        }
        executeSyncPost("delete_package", rootJson, "✓ Paket (ID $id) dihapus dari MySQL")
    }

    suspend fun pushStaff(s: com.example.data.local.entity.StaffEntity): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("action", "push_staff")
            put("api_key", getApiKey())
            put("staff", JSONObject().apply {
                put("id", s.id)
                put("username", s.username)
                put("name", s.name)
                put("role", s.role)
                put("phone", s.phone)
                put("pin", s.pin)
                put("isActive", s.isActive)
            })
        }
        executeSyncPost("push_staff", rootJson, "✓ Staf '${s.name}' langsung tersimpan ke MySQL")
    }

    suspend fun deleteStaff(id: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("action", "delete_staff")
            put("api_key", getApiKey())
            put("id", id)
        }
        executeSyncPost("delete_staff", rootJson, "✓ Staf (ID $id) dihapus dari MySQL")
    }

    suspend fun pushSettlementBatch(sb: SettlementBatchEntity): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("action", "push_settlement")
            put("api_key", getApiKey())
            put("settlement_batch", JSONObject().apply {
                put("id", sb.id)
                put("batchNumber", sb.batchNumber)
                put("periodMonth", sb.periodMonth)
                put("cashierName", sb.cashierName)
                put("recipientName", sb.recipientName)
                put("totalAmount", sb.totalAmount)
                put("totalCashAmount", sb.totalCashAmount)
                put("totalNonCashAmount", sb.totalNonCashAmount)
                put("customerCount", sb.customerCount)
                put("notes", sb.notes)
                put("createdAt", sb.createdAt)
            })
        }
        executeSyncPost("push_settlement", rootJson, "✓ Setoran ${sb.batchNumber} langsung tersimpan ke MySQL")
    }

    suspend fun deleteSettlementBatch(id: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("action", "delete_settlement")
            put("api_key", getApiKey())
            put("id", id)
        }
        executeSyncPost("delete_settlement", rootJson, "✓ Setoran (ID $id) dihapus dari MySQL")
    }

    // ------------------------------------------------------------------------
    // OFFLINE SYNC QUEUE MANAGEMENT
    // ------------------------------------------------------------------------
    suspend fun enqueuePending(entityType: String, entityId: Long, action: String, payloadJson: String = "") = withContext(Dispatchers.IO) {
        db.syncQueueDao().deleteByEntity(entityType, entityId)
        db.syncQueueDao().insertSyncItem(
            SyncQueueEntity(
                entityType = entityType,
                entityId = entityId,
                action = action,
                payloadJson = payloadJson,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun processSyncQueue(): Result<Int> = withContext(Dispatchers.IO) {
        val pendingItems = db.syncQueueDao().getAllPending()
        if (pendingItems.isEmpty()) return@withContext Result.success(0)

        var syncedCount = 0
        for (item in pendingItems) {
            try {
                val success = when (item.entityType) {
                    "CUSTOMER" -> {
                        if (item.action == "UPSERT") {
                            val c = db.customerDao().getCustomerById(item.entityId)
                            if (c != null) pushCustomer(c).isSuccess else deleteCustomer(item.entityId).isSuccess
                        } else {
                            deleteCustomer(item.entityId).isSuccess
                        }
                    }
                    "PACKAGE" -> {
                        if (item.action == "UPSERT") {
                            val p = db.packagePlanDao().getPlanById(item.entityId)
                            if (p != null) pushPackagePlan(p).isSuccess else deletePackagePlan(item.entityId).isSuccess
                        } else {
                            deletePackagePlan(item.entityId).isSuccess
                        }
                    }
                    "TRANSACTION" -> {
                        if (item.action == "UPSERT") {
                            val t = db.transactionDao().getTransactionById(item.entityId)
                            if (t != null) pushTransaction(t).isSuccess else deleteTransaction(item.entityId).isSuccess
                        } else {
                            deleteTransaction(item.entityId).isSuccess
                        }
                    }
                    "SETTLEMENT" -> {
                        if (item.action == "UPSERT") {
                            val sb = db.settlementDao().getSettlementBatchById(item.entityId)
                            if (sb != null) pushSettlementBatch(sb).isSuccess else deleteSettlementBatch(item.entityId).isSuccess
                        } else {
                            deleteSettlementBatch(item.entityId).isSuccess
                        }
                    }
                    "STAFF" -> {
                        if (item.action == "UPSERT") {
                            val s = db.staffDao().getStaffById(item.entityId)
                            if (s != null) pushStaff(s).isSuccess else deleteStaff(item.entityId).isSuccess
                        } else {
                            deleteStaff(item.entityId).isSuccess
                        }
                    }
                    else -> true
                }

                if (success) {
                    db.syncQueueDao().deleteById(item.id)
                    syncedCount++
                } else {
                    db.syncQueueDao().recordRetry(item.id, "Gagal terkirim ke server MySQL")
                }
            } catch (e: Exception) {
                db.syncQueueDao().recordRetry(item.id, e.message ?: "Unknown error")
            }
        }

        Result.success(syncedCount)
    }

    // ------------------------------------------------------------------------
    // BIDIRECTIONAL RECONCILIATION & AUTO-SYNC (MEMASTIKAN DATA SERAGAM)
    // ------------------------------------------------------------------------
    suspend fun reconcileAndMergeFromMysql(): Result<String> = withContext(Dispatchers.IO) {
        val url = getApiUrl().trim()
        val key = getApiKey().trim()

        if (url.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("URL API belum diatur"))
        }

        try {
            val downloadUrl = buildUrl("download_all")
            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("X-API-Key", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP $code: Gagal download ($body)"))
            }

            val json = JSONObject(body)
            if (json.optString("status") != "success") {
                return@withContext Result.failure(Exception(json.optString("message", "Gagal mengunduh data")))
            }

            val data = json.getJSONObject("data")

            // Ambil daftar ID yang masih dalam antrean pending sync agar tidak tertimpa data lama server
            val pendingList = db.syncQueueDao().getAllPending()
            val pendingCustomerIds = pendingList.filter { it.entityType == "CUSTOMER" }.map { it.entityId }.toSet()
            val pendingPackageIds = pendingList.filter { it.entityType == "PACKAGE" }.map { it.entityId }.toSet()
            val pendingTransactionIds = pendingList.filter { it.entityType == "TRANSACTION" }.map { it.entityId }.toSet()
            val pendingSettlementIds = pendingList.filter { it.entityType == "SETTLEMENT" }.map { it.entityId }.toSet()
            val pendingStaffIds = pendingList.filter { it.entityType == "STAFF" }.map { it.entityId }.toSet()

            // 1. Customers
            val customersArray = data.optJSONArray("customers")
            val customerList = mutableListOf<CustomerEntity>()
            if (customersArray != null) {
                for (i in 0 until customersArray.length()) {
                    val obj = customersArray.getJSONObject(i)
                    customerList.add(
                        CustomerEntity(
                            id = obj.optLong("id"),
                            customerCode = obj.optString("customerCode"),
                            name = obj.optString("name"),
                            phone = obj.optString("phone"),
                            address = obj.optString("address"),
                            odpLocation = obj.optString("odpLocation", ""),
                            packageId = obj.optLong("packageId"),
                            packageName = obj.optString("packageName"),
                            monthlyFee = obj.optLong("monthlyFee"),
                            dueDay = obj.optInt("dueDay", 10),
                            ipOrPppoe = obj.optString("ipOrPppoe", ""),
                            status = obj.optString("status", "ACTIVE"),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                val safeToInsert = customerList.filter { it.id !in pendingCustomerIds }
                if (safeToInsert.isNotEmpty()) {
                    db.customerDao().insertCustomers(safeToInsert)
                }
                val keepIds = (customerList.map { it.id } + pendingCustomerIds).distinct()
                if (keepIds.isNotEmpty()) {
                    db.customerDao().deleteCustomersNotIn(keepIds)
                }
            }

            // 2. Packages
            val packagesArray = data.optJSONArray("packages")
            val packageList = mutableListOf<PackagePlanEntity>()
            if (packagesArray != null) {
                for (i in 0 until packagesArray.length()) {
                    val obj = packagesArray.getJSONObject(i)
                    packageList.add(
                        PackagePlanEntity(
                            id = obj.optLong("id"),
                            name = obj.optString("name"),
                            speedMbps = obj.optInt("speedMbps"),
                            price = obj.optLong("price"),
                            description = obj.optString("description", ""),
                            isActive = obj.optBoolean("isActive", true)
                        )
                    )
                }
                val safeToInsert = packageList.filter { it.id !in pendingPackageIds }
                if (safeToInsert.isNotEmpty()) {
                    db.packagePlanDao().insertPlans(safeToInsert)
                }
                val keepIds = (packageList.map { it.id } + pendingPackageIds).distinct()
                if (keepIds.isNotEmpty()) {
                    db.packagePlanDao().deletePlansNotIn(keepIds)
                }
            }

            // 3. Transactions
            val transactionsArray = data.optJSONArray("transactions")
            val transactionList = mutableListOf<TransactionEntity>()
            if (transactionsArray != null) {
                for (i in 0 until transactionsArray.length()) {
                    val obj = transactionsArray.getJSONObject(i)
                    val settlementId = if (obj.isNull("settlementBatchId")) null else obj.optLong("settlementBatchId")
                    val settlementNum = if (obj.isNull("settlementBatchNumber")) null else obj.optString("settlementBatchNumber")
                    val depositedAt = if (obj.isNull("depositedAt")) null else obj.optLong("depositedAt")

                    transactionList.add(
                        TransactionEntity(
                            id = obj.optLong("id"),
                            invoiceNumber = obj.optString("invoiceNumber"),
                            customerId = obj.optLong("customerId"),
                            customerName = obj.optString("customerName"),
                            customerCode = obj.optString("customerCode"),
                            packageName = obj.optString("packageName"),
                            monthlyFee = obj.optLong("monthlyFee"),
                            adminFee = obj.optLong("adminFee", 0L),
                            discount = obj.optLong("discount", 0L),
                            totalAmount = obj.optLong("totalAmount"),
                            periodMonth = obj.optString("periodMonth"),
                            paymentMethod = obj.optString("paymentMethod", "TUNAI"),
                            cashierName = obj.optString("cashierName", "Kasir Utama"),
                            cashierRole = obj.optString("cashierRole", "KASIR"),
                            notes = obj.optString("notes", ""),
                            paidAt = obj.optLong("paidAt", System.currentTimeMillis()),
                            isPrinted = obj.optBoolean("isPrinted", false),
                            isDeposited = obj.optBoolean("isDeposited", false),
                            settlementBatchId = settlementId,
                            settlementBatchNumber = settlementNum,
                            depositedAt = depositedAt
                        )
                    )
                }
                // Deduplikasi otomatis: jika ada dua invoice untuk pelanggan & periode yang sama, pertahankan yang pertama
                val deduplicated = transactionList.distinctBy { "${it.customerId}_${it.periodMonth}" }
                val safeToInsert = deduplicated.filter { it.id !in pendingTransactionIds }
                if (safeToInsert.isNotEmpty()) {
                    db.transactionDao().insertTransactions(safeToInsert)
                }
                val keepIds = (deduplicated.map { it.id } + pendingTransactionIds).distinct()
                if (keepIds.isNotEmpty()) {
                    db.transactionDao().deleteTransactionsNotIn(keepIds)
                }
            }

            // 4. Settlement Batches
            val settlementsArray = data.optJSONArray("settlement_batches")
            val settlementList = mutableListOf<SettlementBatchEntity>()
            if (settlementsArray != null) {
                for (i in 0 until settlementsArray.length()) {
                    val obj = settlementsArray.getJSONObject(i)
                    settlementList.add(
                        SettlementBatchEntity(
                            id = obj.optLong("id"),
                            batchNumber = obj.optString("batchNumber"),
                            periodMonth = obj.optString("periodMonth"),
                            cashierName = obj.optString("cashierName"),
                            recipientName = obj.optString("recipientName", "Owner / Admin"),
                            totalAmount = obj.optLong("totalAmount"),
                            totalCashAmount = obj.optLong("totalCashAmount", 0L),
                            totalNonCashAmount = obj.optLong("totalNonCashAmount", 0L),
                            customerCount = obj.optInt("customerCount", 0),
                            notes = obj.optString("notes", ""),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                val safeToInsert = settlementList.filter { it.id !in pendingSettlementIds }
                if (safeToInsert.isNotEmpty()) {
                    db.settlementDao().insertSettlementBatches(safeToInsert)
                }
                val keepIds = (settlementList.map { it.id } + pendingSettlementIds).distinct()
                if (keepIds.isNotEmpty()) {
                    db.settlementDao().deleteSettlementsNotIn(keepIds)
                }
            }

            // 5. Staff
            val staffArray = data.optJSONArray("staff")
            val staffList = mutableListOf<StaffEntity>()
            if (staffArray != null) {
                for (i in 0 until staffArray.length()) {
                    val obj = staffArray.getJSONObject(i)
                    staffList.add(
                        StaffEntity(
                            id = obj.optLong("id"),
                            username = obj.optString("username"),
                            name = obj.optString("name"),
                            role = obj.optString("role", "KASIR"),
                            phone = obj.optString("phone", ""),
                            pin = obj.optString("pin", "1234"),
                            isActive = obj.optBoolean("isActive", true)
                        )
                    )
                }
                val safeToInsert = staffList.filter { it.id !in pendingStaffIds }
                if (safeToInsert.isNotEmpty()) {
                    db.staffDao().insertStaffList(safeToInsert)
                }
                val keepIds = (staffList.map { it.id } + pendingStaffIds).distinct()
                if (keepIds.isNotEmpty()) {
                    db.staffDao().deleteStaffNotIn(keepIds)
                }
            }

            val summary = "Data seragam: ${customerList.size} Pelanggan, ${transactionList.size} Transaksi, ${settlementList.size} Setoran"
            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun performFullAutoSync(force: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val url = getApiUrl().trim()
        if (url.isBlank()) {
            return@withContext Result.failure(Exception("URL MySQL belum diatur di Pengaturan"))
        }
        if (!force && !isAutoSyncEnabled()) {
            return@withContext Result.success("Auto-sync dilewati (fitur nonaktif)")
        }

        if (_isSyncing.value) {
            return@withContext Result.success("Sedang proses sinkronisasi...")
        }

        _isSyncing.value = true
        try {
            // 1. Dorong perubahan lokal yang tertunda (flush pending queue)
            val queueResult = processSyncQueue()
            val flushedCount = queueResult.getOrDefault(0)

            // 2. Rekonsiliasi unduh data terbaru agar perangkat seragam
            val mergeResult = reconcileAndMergeFromMysql()

            val now = System.currentTimeMillis()
            val remainingQueue = db.syncQueueDao().getPendingCountSync()

            if (mergeResult.isSuccess) {
                val statusText = if (remainingQueue > 0) {
                    "Tersinkronisasi sebagian ($remainingQueue tertunda)"
                } else if (flushedCount > 0) {
                    "Sukses sinkron ($flushedCount data terkirim, data seragam)"
                } else {
                    "Sinkron otomatis sukses (Data seragam)"
                }
                setLastSyncTimestamp(now)
                setLastSyncStatus(statusText)
                _lastSyncTime.value = now
                _lastSyncStatus.value = statusText
                Result.success(statusText)
            } else {
                val err = mergeResult.exceptionOrNull()?.message ?: "Gagal rekonsiliasi server"
                val statusText = "Sinkronisasi gagal: $err"
                setLastSyncStatus(statusText)
                _lastSyncStatus.value = statusText
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            val err = e.localizedMessage ?: "Koneksi terputus saat auto-sync"
            setLastSyncStatus("Sinkronisasi tertunda: $err")
            _lastSyncStatus.value = "Sinkronisasi tertunda: $err"
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }
}

data class ServerPaymentCheckResult(
    val isPaid: Boolean,
    val invoiceNumber: String? = null,
    val cashierName: String? = null,
    val existingTransaction: TransactionEntity? = null,
    val message: String = ""
)

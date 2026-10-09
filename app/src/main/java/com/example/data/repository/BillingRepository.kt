package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.entity.CustomerEntity
import com.example.data.local.entity.PackagePlanEntity
import com.example.data.local.entity.SettlementBatchEntity
import com.example.data.local.entity.StaffEntity
import com.example.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BillingRepository(private val db: AppDatabase) {
    // Customers
    val allCustomers: Flow<List<CustomerEntity>> = db.customerDao().getAllCustomers()
    val activeCustomerCount: Flow<Int> = db.customerDao().getActiveCustomerCount()
    val totalCustomerCount: Flow<Int> = db.customerDao().getTotalCustomerCount()
    val pendingSyncCount: Flow<Int> = db.syncQueueDao().getPendingCount()

    fun searchCustomers(query: String): Flow<List<CustomerEntity>> = db.customerDao().searchCustomers(query)
    suspend fun getCustomerById(id: Long): CustomerEntity? = db.customerDao().getCustomerById(id)

    var mysqlSyncManager: com.example.data.sync.MysqlSyncManager? = null

    private suspend fun syncDirectPush(
        entityType: String,
        entityId: Long,
        action: String,
        pushCall: suspend (com.example.data.sync.MysqlSyncManager) -> Result<Boolean>
    ) {
        val sync = mysqlSyncManager ?: return
        sync.enqueuePending(entityType, entityId, action)
        if (sync.getApiUrl().isNotBlank()) {
            try {
                val res = pushCall(sync)
                if (res.isSuccess) {
                    db.syncQueueDao().deleteByEntity(entityType, entityId)
                }
            } catch (_: Exception) {
                // Biarkan di antrean pending sync untuk di-retry otomatis
            }
        }
    }

    suspend fun insertCustomer(customer: CustomerEntity): Long {
        val toSave = if (customer.id == 0L) customer.copy(id = com.example.util.IdGenerator.generateUniqueId()) else customer
        db.customerDao().insertCustomer(toSave)
        val saved = toSave
        syncDirectPush("CUSTOMER", saved.id, "UPSERT") { it.pushCustomer(saved) }
        return saved.id
    }

    suspend fun updateCustomer(customer: CustomerEntity) {
        db.customerDao().updateCustomer(customer)
        syncDirectPush("CUSTOMER", customer.id, "UPSERT") { it.pushCustomer(customer) }
    }

    suspend fun deleteCustomer(customer: CustomerEntity) {
        db.customerDao().deleteCustomer(customer)
        syncDirectPush("CUSTOMER", customer.id, "DELETE") { it.deleteCustomer(customer.id) }
    }

    // Packages
    val activePlans: Flow<List<PackagePlanEntity>> = db.packagePlanDao().getAllActivePlans()
    val allPlans: Flow<List<PackagePlanEntity>> = db.packagePlanDao().getAllPlans()

    suspend fun insertPlan(plan: PackagePlanEntity): Long {
        val toSave = if (plan.id == 0L) plan.copy(id = com.example.util.IdGenerator.generateUniqueId()) else plan
        db.packagePlanDao().insertPlan(toSave)
        val saved = toSave
        syncDirectPush("PACKAGE", saved.id, "UPSERT") { it.pushPackagePlan(saved) }
        return saved.id
    }

    suspend fun updatePlan(plan: PackagePlanEntity) {
        db.packagePlanDao().updatePlan(plan)
        syncDirectPush("PACKAGE", plan.id, "UPSERT") { it.pushPackagePlan(plan) }
    }

    suspend fun deletePlan(plan: PackagePlanEntity) {
        db.packagePlanDao().deletePlan(plan)
        syncDirectPush("PACKAGE", plan.id, "DELETE") { it.deletePackagePlan(plan.id) }
    }

    // Transactions
    val allTransactions: Flow<List<TransactionEntity>> = db.transactionDao().getAllTransactions()
    fun getTransactionsByMonth(month: String): Flow<List<TransactionEntity>> = db.transactionDao().getTransactionsByMonth(month)
    fun getTotalRevenueByMonth(month: String): Flow<Long> = db.transactionDao().getTotalRevenueByMonth(month)
    val totalRevenueAllTime: Flow<Long> = db.transactionDao().getTotalRevenueAllTime()

    suspend fun insertTransactionSilently(transaction: TransactionEntity) {
        db.transactionDao().insertTransaction(transaction)
    }

    suspend fun insertTransaction(transaction: TransactionEntity): Result<Long> {
        // 1. Cek lokal terlebih dahulu
        val existing = db.transactionDao().findTransactionForPeriod(transaction.customerId, transaction.periodMonth)
        if (existing != null) {
            return Result.failure(
                com.example.util.DuplicatePaymentException(
                    existing.invoiceNumber,
                    "Tagihan pelanggan '${transaction.customerName}' untuk periode ${transaction.periodMonth} sudah lunas (No. Faktur: ${existing.invoiceNumber})."
                )
            )
        }

        val toSave = if (transaction.id == 0L) transaction.copy(id = com.example.util.IdGenerator.generateUniqueId()) else transaction
        db.transactionDao().insertTransaction(toSave)
        val saved = toSave

        val sync = mysqlSyncManager
        if (sync != null && sync.getApiUrl().isNotBlank()) {
            sync.enqueuePending("TRANSACTION", saved.id, "UPSERT")
            try {
                val res = sync.pushTransaction(saved)
                if (res.isSuccess) {
                    db.syncQueueDao().deleteByEntity("TRANSACTION", saved.id)
                } else {
                    val ex = res.exceptionOrNull()
                    if (ex is com.example.util.DuplicatePaymentException) {
                        // Rollback lokal: Hapus transaksi ganda agar tidak tersimpan di HP ini
                        db.transactionDao().deleteTransactionById(saved.id)
                        db.syncQueueDao().deleteByEntity("TRANSACTION", saved.id)
                        try { sync.reconcileAndMergeFromMysql() } catch (_: Exception) {}
                        return Result.failure(ex)
                    }
                }
            } catch (e: Exception) {
                if (e is com.example.util.DuplicatePaymentException) {
                    db.transactionDao().deleteTransactionById(saved.id)
                    db.syncQueueDao().deleteByEntity("TRANSACTION", saved.id)
                    try { sync.reconcileAndMergeFromMysql() } catch (_: Exception) {}
                    return Result.failure(e)
                }
            }
        }
        return Result.success(saved.id)
    }

    suspend fun insertMultipleTransactions(transactions: List<TransactionEntity>): Result<List<Long>> {
        if (transactions.isEmpty()) {
            return Result.failure(IllegalArgumentException("Tidak ada periode tagihan yang dipilih."))
        }
        // 1. Cek duplikasi lokal untuk seluruh periode
        for (trx in transactions) {
            val existing = db.transactionDao().findTransactionForPeriod(trx.customerId, trx.periodMonth)
            if (existing != null) {
                return Result.failure(
                    com.example.util.DuplicatePaymentException(
                        existing.invoiceNumber,
                        "Tagihan pelanggan '${trx.customerName}' periode ${trx.periodMonth} sudah lunas (No. Faktur: ${existing.invoiceNumber}). Pembayaran dobel dibatalkan!"
                    )
                )
            }
        }

        val savedIds = mutableListOf<Long>()
        for (trx in transactions) {
            val result = insertTransaction(trx)
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("Gagal memproses transaksi periode ${trx.periodMonth}"))
            }
            savedIds.add(result.getOrThrow())
        }
        return Result.success(savedIds)
    }

    suspend fun getTransactionsByCustomerSync(customerId: Long): List<TransactionEntity> =
        db.transactionDao().getTransactionsByCustomerSync(customerId)

    suspend fun deleteTransaction(transaction: TransactionEntity) {
        db.transactionDao().deleteTransaction(transaction)
        syncDirectPush("TRANSACTION", transaction.id, "DELETE") { it.deleteTransaction(transaction.id) }
    }

    suspend fun deleteTransactionById(id: Long) {
        db.transactionDao().deleteTransactionById(id)
        syncDirectPush("TRANSACTION", id, "DELETE") { it.deleteTransaction(id) }
    }

    suspend fun markTransactionPrinted(id: Long) = db.transactionDao().markAsPrinted(id)
    suspend fun findTransactionForPeriod(customerId: Long, periodMonth: String): TransactionEntity? =
        db.transactionDao().findTransactionForPeriod(customerId, periodMonth)

    // Staff
    val allStaff: Flow<List<StaffEntity>> = db.staffDao().getAllStaff()
    val activeStaff: Flow<List<StaffEntity>> = db.staffDao().getActiveStaff()
    suspend fun getStaffById(id: Long): StaffEntity? = db.staffDao().getStaffById(id)
    suspend fun getStaffByUsername(username: String): StaffEntity? = db.staffDao().getStaffByUsername(username)

    suspend fun authenticate(username: String, pin: String): StaffEntity? {
        val staff = db.staffDao().getStaffByUsernameOrPhone(username.trim()) ?: return null
        if (com.example.util.SecurityUtils.verifyPin(pin, staff.pin)) {
            // Migrasi otomatis PIN plain text lama ke format hash SHA-256 tersensitisasi
            if (!com.example.util.SecurityUtils.isHashed(staff.pin)) {
                val upgraded = staff.copy(pin = com.example.util.SecurityUtils.hashPin(pin))
                db.staffDao().updateStaff(upgraded)
                return upgraded
            }
            return staff
        }
        return null
    }

    suspend fun verifyAdminPin(pin: String): Boolean {
        val admins = db.staffDao().getActiveAdmins()
        if (admins.isEmpty()) {
            return pin.trim() == "1234"
        }
        return admins.any { admin ->
            com.example.util.SecurityUtils.verifyPin(pin, admin.pin)
        }
    }

    suspend fun insertStaff(staff: StaffEntity): Long {
        val securePin = if (!com.example.util.SecurityUtils.isHashed(staff.pin)) {
            com.example.util.SecurityUtils.hashPin(staff.pin)
        } else {
            staff.pin
        }
        val staffToSave = staff.copy(
            id = if (staff.id == 0L) com.example.util.IdGenerator.generateUniqueId() else staff.id,
            pin = securePin
        )
        db.staffDao().insertStaff(staffToSave)
        val saved = staffToSave
        syncDirectPush("STAFF", saved.id, "UPSERT") { it.pushStaff(saved) }
        return saved.id
    }

    suspend fun updateStaff(staff: StaffEntity) {
        val securePin = if (!com.example.util.SecurityUtils.isHashed(staff.pin)) {
            com.example.util.SecurityUtils.hashPin(staff.pin)
        } else {
            staff.pin
        }
        val staffToSave = staff.copy(pin = securePin)
        db.staffDao().updateStaff(staffToSave)
        syncDirectPush("STAFF", staffToSave.id, "UPSERT") { it.pushStaff(staffToSave) }
    }

    suspend fun deleteStaff(staff: StaffEntity) {
        db.staffDao().deleteStaff(staff)
        syncDirectPush("STAFF", staff.id, "DELETE") { it.deleteStaff(staff.id) }
    }

    // Settlement / Setoran
    val allSettlementBatches: Flow<List<SettlementBatchEntity>> = db.settlementDao().getAllSettlementBatches()
    fun getSettlementBatchesByMonth(month: String): Flow<List<SettlementBatchEntity>> =
        db.settlementDao().getSettlementBatchesByMonth(month)
    fun getUndepositedTransactionsByMonth(month: String): Flow<List<TransactionEntity>> =
        db.transactionDao().getUndepositedTransactionsByMonth(month)
    fun getTransactionsByBatchId(batchId: Long): Flow<List<TransactionEntity>> =
        db.transactionDao().getTransactionsByBatchId(batchId)
    suspend fun getTransactionsByBatchIdSync(batchId: Long): List<TransactionEntity> =
        db.transactionDao().getTransactionsByBatchIdSync(batchId)
    suspend fun getSettlementBatchById(id: Long): SettlementBatchEntity? =
        db.settlementDao().getSettlementBatchById(id)

    fun generateSettlementBatchNumber(): String {
        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
        val rnd = (100..999).random()
        return "STR-${sdf.format(Date())}-$rnd"
    }

    suspend fun createSettlementBatch(
        periodMonth: String,
        cashierName: String,
        recipientName: String,
        selectedTransactions: List<TransactionEntity>,
        notes: String = ""
    ): Result<SettlementBatchEntity> {
        if (selectedTransactions.isEmpty()) {
            return Result.failure(IllegalArgumentException("Pilih minimal satu data transaksi pelanggan untuk disetorkan."))
        }

        // Strict enforcement: Ensure none of the selected transactions are already deposited for this month
        val alreadyDeposited = selectedTransactions.filter { it.isDeposited }
        if (alreadyDeposited.isNotEmpty()) {
            val names = alreadyDeposited.joinToString { it.customerName }
            return Result.failure(
                IllegalStateException("Pelanggan ($names) sudah pernah disetorkan untuk bulan $periodMonth dan tidak dapat disetorkan ulang kecuali beda bulan!")
            )
        }

        val totalAmount = selectedTransactions.sumOf { it.totalAmount }
        val totalCashAmount = selectedTransactions
            .filter { it.paymentMethod.equals("TUNAI", ignoreCase = true) }
            .sumOf { it.totalAmount }
        val totalNonCashAmount = totalAmount - totalCashAmount
        val now = System.currentTimeMillis()
        val batchNumber = generateSettlementBatchNumber()

        val batchEntity = SettlementBatchEntity(
            id = com.example.util.IdGenerator.generateUniqueId(),
            batchNumber = batchNumber,
            periodMonth = periodMonth,
            cashierName = cashierName,
            recipientName = recipientName.ifBlank { "Owner / Bendahara" },
            totalAmount = totalAmount,
            totalCashAmount = totalCashAmount,
            totalNonCashAmount = totalNonCashAmount,
            customerCount = selectedTransactions.size,
            notes = notes,
            createdAt = now
        )

        db.settlementDao().insertSettlementBatch(batchEntity)
        val batchId = batchEntity.id
        val transactionIds = selectedTransactions.map { it.id }

        db.transactionDao().markTransactionsDeposited(
            transactionIds = transactionIds,
            batchId = batchId,
            batchNumber = batchNumber,
            depositedAt = now
        )

        val savedBatch = batchEntity.copy(id = batchId)

        // Offline-first sync queue: Enqueue settlement batch and deposited transactions
        syncDirectPush("SETTLEMENT", batchId, "UPSERT") { it.pushSettlementBatch(savedBatch) }
        val updatedTrxs = db.transactionDao().getTransactionsByBatchIdSync(batchId)
        for (trx in updatedTrxs) {
            syncDirectPush("TRANSACTION", trx.id, "UPSERT") { it.pushTransaction(trx) }
        }

        return Result.success(savedBatch)
    }

    suspend fun ensureDefaultStaffExist() {
        if (db.staffDao().getStaffByUsername("admin") == null) {
            db.staffDao().insertStaff(
                StaffEntity(username = "admin", name = "Budi Hartono (Owner)", role = "ADMIN", phone = "081234567890", pin = "1234")
            )
        }
        if (db.staffDao().getStaffByUsername("kasir1") == null) {
            db.staffDao().insertStaff(
                StaffEntity(username = "kasir1", name = "Siti Rahma", role = "KASIR", phone = "081987654321", pin = "1111")
            )
        }
        if (db.staffDao().getStaffByUsername("teknisi1") == null) {
            db.staffDao().insertStaff(
                StaffEntity(username = "teknisi1", name = "Agus Kurniawan", role = "TEKNISI", phone = "085678901234", pin = "2222")
            )
        }
    }

    fun getCurrentMonthPeriod(): String {
        val sdf = SimpleDateFormat("MMMM yyyy", Locale("id", "ID"))
        return sdf.format(Date())
    }

    fun generateInvoiceNumber(): String {
        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
        val rnd = (100..999).random()
        return "INV-${sdf.format(Date())}-$rnd"
    }
}

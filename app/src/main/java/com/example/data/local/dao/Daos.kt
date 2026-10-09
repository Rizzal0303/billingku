package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.CustomerEntity
import com.example.data.local.entity.PackagePlanEntity
import com.example.data.local.entity.SettlementBatchEntity
import com.example.data.local.entity.StaffEntity
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAllCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE customerCode = :code LIMIT 1")
    suspend fun getCustomerByCode(code: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' OR customerCode LIKE '%' || :query || '%'")
    fun searchCustomers(query: String): Flow<List<CustomerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomers(customers: List<CustomerEntity>)

    @Query("SELECT * FROM customers")
    suspend fun getAllCustomersSync(): List<CustomerEntity>

    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    @Delete
    suspend fun deleteCustomer(customer: CustomerEntity)

    @Query("DELETE FROM customers")
    suspend fun clearAllCustomers()

    @Query("DELETE FROM customers WHERE id NOT IN (:keepIds)")
    suspend fun deleteCustomersNotIn(keepIds: List<Long>)

    @Query("SELECT COUNT(*) FROM customers WHERE status = 'ACTIVE'")
    fun getActiveCustomerCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM customers")
    fun getTotalCustomerCount(): Flow<Int>
}

@Dao
interface PackagePlanDao {
    @Query("SELECT * FROM package_plans WHERE isActive = 1 ORDER BY price ASC")
    fun getAllActivePlans(): Flow<List<PackagePlanEntity>>

    @Query("SELECT * FROM package_plans ORDER BY id DESC")
    fun getAllPlans(): Flow<List<PackagePlanEntity>>

    @Query("SELECT * FROM package_plans WHERE id = :id")
    suspend fun getPlanById(id: Long): PackagePlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlan(plan: PackagePlanEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlans(plans: List<PackagePlanEntity>)

    @Query("SELECT * FROM package_plans")
    suspend fun getAllPlansSync(): List<PackagePlanEntity>

    @Update
    suspend fun updatePlan(plan: PackagePlanEntity)

    @Delete
    suspend fun deletePlan(plan: PackagePlanEntity)

    @Query("DELETE FROM package_plans")
    suspend fun clearAllPlans()

    @Query("DELETE FROM package_plans WHERE id NOT IN (:keepIds)")
    suspend fun deletePlansNotIn(keepIds: List<Long>)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY paidAt DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE periodMonth = :month ORDER BY paidAt DESC")
    fun getTransactionsByMonth(month: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE customerId = :customerId ORDER BY paidAt DESC")
    fun getTransactionsByCustomer(customerId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE customerId = :customerId ORDER BY paidAt DESC")
    suspend fun getTransactionsByCustomerSync(customerId: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE customerId = :customerId AND periodMonth = :periodMonth LIMIT 1")
    suspend fun findTransactionForPeriod(customerId: Long, periodMonth: String): TransactionEntity?

    @Query("SELECT COALESCE(SUM(totalAmount), 0) FROM transactions WHERE periodMonth = :periodMonth")
    fun getTotalRevenueByMonth(periodMonth: String): Flow<Long>

    @Query("SELECT COALESCE(SUM(totalAmount), 0) FROM transactions")
    fun getTotalRevenueAllTime(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<TransactionEntity>)

    @Query("SELECT * FROM transactions")
    suspend fun getAllTransactionsSync(): List<TransactionEntity>

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Delete
    suspend fun deleteTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun clearAllTransactions()

    @Query("DELETE FROM transactions WHERE id NOT IN (:keepIds)")
    suspend fun deleteTransactionsNotIn(keepIds: List<Long>)

    @Query("UPDATE transactions SET isPrinted = 1 WHERE id = :id")
    suspend fun markAsPrinted(id: Long)

    @Query("SELECT * FROM transactions WHERE isDeposited = 1 ORDER BY depositedAt DESC")
    fun getDepositedTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE periodMonth = :periodMonth AND isDeposited = 0 ORDER BY paidAt DESC")
    fun getUndepositedTransactionsByMonth(periodMonth: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE settlementBatchId = :batchId ORDER BY customerName ASC")
    fun getTransactionsByBatchId(batchId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE settlementBatchId = :batchId ORDER BY customerName ASC")
    suspend fun getTransactionsByBatchIdSync(batchId: Long): List<TransactionEntity>

    @Query("UPDATE transactions SET isDeposited = 1, settlementBatchId = :batchId, settlementBatchNumber = :batchNumber, depositedAt = :depositedAt WHERE id IN (:transactionIds)")
    suspend fun markTransactionsDeposited(
        transactionIds: List<Long>,
        batchId: Long,
        batchNumber: String,
        depositedAt: Long
    )
}

@Dao
interface SettlementDao {
    @Query("SELECT * FROM settlement_batches ORDER BY createdAt DESC")
    fun getAllSettlementBatches(): Flow<List<SettlementBatchEntity>>

    @Query("SELECT * FROM settlement_batches WHERE periodMonth = :periodMonth ORDER BY createdAt DESC")
    fun getSettlementBatchesByMonth(periodMonth: String): Flow<List<SettlementBatchEntity>>

    @Query("SELECT * FROM settlement_batches WHERE id = :id LIMIT 1")
    suspend fun getSettlementBatchById(id: Long): SettlementBatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlementBatch(batch: SettlementBatchEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlementBatches(batches: List<SettlementBatchEntity>)

    @Query("SELECT * FROM settlement_batches")
    suspend fun getAllSettlementBatchesSync(): List<SettlementBatchEntity>

    @Delete
    suspend fun deleteSettlementBatch(batch: SettlementBatchEntity)

    @Query("DELETE FROM settlement_batches")
    suspend fun clearAllSettlements()

    @Query("DELETE FROM settlement_batches WHERE id NOT IN (:keepIds)")
    suspend fun deleteSettlementsNotIn(keepIds: List<Long>)

    @Query("SELECT COUNT(*) FROM settlement_batches")
    fun getTotalSettlementBatchCount(): Flow<Int>
}

@Dao
interface StaffDao {
    @Query("SELECT * FROM staff_members ORDER BY name ASC")
    fun getAllStaff(): Flow<List<StaffEntity>>

    @Query("SELECT * FROM staff_members WHERE isActive = 1")
    fun getActiveStaff(): Flow<List<StaffEntity>>

    @Query("SELECT * FROM staff_members WHERE username = :username LIMIT 1")
    suspend fun getStaffByUsername(username: String): StaffEntity?

    @Query("SELECT * FROM staff_members WHERE id = :id LIMIT 1")
    suspend fun getStaffById(id: Long): StaffEntity?

    @Query("SELECT * FROM staff_members WHERE (username = :username OR phone = :username) AND pin = :pin LIMIT 1")
    suspend fun authenticate(username: String, pin: String): StaffEntity?

    @Query("SELECT * FROM staff_members WHERE (username = :identifier OR phone = :identifier) LIMIT 1")
    suspend fun getStaffByUsernameOrPhone(identifier: String): StaffEntity?

    @Query("SELECT * FROM staff_members WHERE role = 'ADMIN' AND isActive = 1")
    suspend fun getActiveAdmins(): List<StaffEntity>

    @Query("SELECT * FROM staff_members WHERE role = 'ADMIN' AND pin = :pin LIMIT 1")
    suspend fun verifyAdminPin(pin: String): StaffEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStaff(staff: StaffEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStaffList(staffList: List<StaffEntity>)

    @Query("SELECT * FROM staff_members")
    suspend fun getAllStaffSync(): List<StaffEntity>

    @Update
    suspend fun updateStaff(staff: StaffEntity)

    @Delete
    suspend fun deleteStaff(staff: StaffEntity)

    @Query("DELETE FROM staff_members")
    suspend fun clearAllStaff()

    @Query("DELETE FROM staff_members WHERE id NOT IN (:keepIds)")
    suspend fun deleteStaffNotIn(keepIds: List<Long>)
}

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncItem(item: SyncQueueEntity): Long

    @Query("SELECT * FROM pending_sync_queue ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<SyncQueueEntity>

    @Query("SELECT * FROM pending_sync_queue ORDER BY createdAt ASC")
    fun getAllPendingFlow(): Flow<List<SyncQueueEntity>>

    @Query("SELECT COUNT(*) FROM pending_sync_queue")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_sync_queue")
    suspend fun getPendingCountSync(): Int

    @Query("DELETE FROM pending_sync_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_sync_queue WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM pending_sync_queue WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteByEntity(entityType: String, entityId: Long)

    @Query("UPDATE pending_sync_queue SET retryCount = retryCount + 1, lastError = :error WHERE id = :id")
    suspend fun recordRetry(id: Long, error: String)

    @Query("DELETE FROM pending_sync_queue")
    suspend fun clearQueue()
}


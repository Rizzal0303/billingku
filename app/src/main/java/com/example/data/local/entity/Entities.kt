package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "customers",
    indices = [
        Index(value = ["customerCode"]),
        Index(value = ["status"]),
        Index(value = ["packageId"])
    ]
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val customerCode: String, // e.g. "NET-001"
    val name: String,
    val phone: String, // WhatsApp
    val address: String,
    val odpLocation: String = "", // e.g. "ODP-JBG-02 Port 4"
    val packageId: Long,
    val packageName: String,
    val monthlyFee: Long,
    val dueDay: Int = 10, // Tanggal jatuh tempo (1-28)
    val ipOrPppoe: String = "",
    val status: String = "ACTIVE", // "ACTIVE", "ISOLATED", "INACTIVE"
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "package_plans")
data class PackagePlanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val speedMbps: Int,
    val price: Long,
    val description: String = "",
    val isActive: Boolean = true
)

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["customerId"]),
        Index(value = ["periodMonth"]),
        Index(value = ["paidAt"]),
        Index(value = ["isDeposited"]),
        Index(value = ["invoiceNumber"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val invoiceNumber: String, // e.g. "INV-202609-001"
    val customerId: Long,
    val customerName: String,
    val customerCode: String,
    val packageName: String,
    val monthlyFee: Long,
    val adminFee: Long = 0,
    val discount: Long = 0,
    val totalAmount: Long,
    val periodMonth: String, // e.g. "September 2026"
    val paymentMethod: String = "TUNAI", // "TUNAI", "TRANSFER", "QRIS"
    val cashierName: String = "Kasir Utama",
    val cashierRole: String = "KASIR",
    val notes: String = "",
    val paidAt: Long = System.currentTimeMillis(),
    val isPrinted: Boolean = false,
    val isDeposited: Boolean = false,
    val settlementBatchId: Long? = null,
    val settlementBatchNumber: String? = null,
    val depositedAt: Long? = null
)

@Entity(
    tableName = "settlement_batches",
    indices = [
        Index(value = ["periodMonth"]),
        Index(value = ["createdAt"])
    ]
)
data class SettlementBatchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val batchNumber: String, // e.g. "STR-202609-001"
    val periodMonth: String, // e.g. "September 2026"
    val cashierName: String,
    val recipientName: String = "Owner / Admin",
    val totalAmount: Long,
    val totalCashAmount: Long = 0,
    val totalNonCashAmount: Long = 0,
    val customerCount: Int,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "staff_members")
data class StaffEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    val name: String,
    val role: String, // "ADMIN", "KASIR", "TEKNISI"
    val phone: String = "",
    val pin: String = "1234",
    val isActive: Boolean = true
)

@Entity(tableName = "pending_sync_queue")
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val entityType: String, // "CUSTOMER", "PACKAGE", "TRANSACTION", "SETTLEMENT", "STAFF"
    val entityId: Long,
    val action: String, // "UPSERT", "DELETE"
    val payloadJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String? = null
)


package com.example.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.CustomerEntity
import com.example.data.local.entity.TransactionEntity
import com.example.data.repository.BillingRepository
import com.example.util.PeriodUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

data class DashboardUiState(
    val totalRevenueMonth: Long = 0,
    val activeCustomerCount: Int = 0,
    val totalCustomerCount: Int = 0,
    val paidCustomerCount: Int = 0,
    val unpaidCustomerCount: Int = 0,
    val totalArrearsAmount: Long = 0, // Total piutang seluruh tunggakan (termasuk bulan-bulan lalu)
    val multiMonthArrearsCount: Int = 0, // Jumlah pelanggan yang menunggak > 1 bulan
    val isolatedCustomerCount: Int = 0, // Jumlah pelanggan terisolir
    val recentTransactions: List<TransactionEntity> = emptyList(),
    val dueCustomers: List<CustomerEntity> = emptyList(),
    val customerArrearsMap: Map<Long, List<String>> = emptyMap(), // customerId -> unpaidPeriods
    val customerDueAmountMap: Map<Long, Long> = emptyMap(), // customerId -> totalDueAmount
    val currentPeriod: String = "",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false
)

class DashboardViewModel(
    private val repository: BillingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun refreshFromMysql(onComplete: (Boolean, String) -> Unit) {
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val sync = repository.mysqlSyncManager
            if (sync == null || sync.getApiUrl().isBlank()) {
                _uiState.update { it.copy(isRefreshing = false) }
                onComplete(false, "Atur URL MySQL di Pengaturan untuk sinkronisasi otomatis multi-perangkat")
                return@launch
            }
            val result = sync.performFullAutoSync(force = true)
            _uiState.update { it.copy(isRefreshing = false) }
            result.fold(
                onSuccess = { msg -> onComplete(true, "✓ $msg") },
                onFailure = { err -> onComplete(false, err.localizedMessage ?: err.message ?: "Gagal sinkronisasi") }
            )
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.checkAndGenerateMonthlyBills()
        }
        loadDashboardData()
    }

    private fun loadDashboardData() {
        val currentPeriod = repository.getCurrentMonthPeriod()
        _uiState.update { it.copy(currentPeriod = currentPeriod, isLoading = true) }

        viewModelScope.launch(Dispatchers.Default) {
            combine(
                repository.getTotalRevenueByMonth(currentPeriod),
                repository.allCustomers,
                repository.allTransactions,
                repository.allBills
            ) { revenue, customers, transactions, bills ->
                val customerMap = customers.associateBy { it.id }

                // Membaca langsung dari tabel bills terpusat (UNPAID & PAID)
                val unpaidBills = bills.filter { it.status.equals("UNPAID", ignoreCase = true) }
                val paidBills = bills.filter { it.status.equals("PAID", ignoreCase = true) }

                val currentMonthUnpaidBills = unpaidBills.filter { it.periodMonth.equals(currentPeriod, ignoreCase = true) }
                val currentMonthPaidBills = paidBills.filter { it.periodMonth.equals(currentPeriod, ignoreCase = true) }

                val paidCustomerIdsCurrentMonth = currentMonthPaidBills.map { it.customerId }.toSet()
                val unpaidCustomerIdsCurrentMonth = currentMonthUnpaidBills.map { it.customerId }.toSet()

                val activeCustomers = customers.filter { it.status.equals("ACTIVE", ignoreCase = true) }
                val isolatedCustomers = customers.filter { it.status.equals("ISOLATED", ignoreCase = true) }

                val paidCount = paidCustomerIdsCurrentMonth.size
                val unpaidCount = unpaidCustomerIdsCurrentMonth.size

                val dueCustomersList = mutableListOf<CustomerEntity>()
                val arrearsMap = mutableMapOf<Long, List<String>>()
                val dueAmountMap = mutableMapOf<Long, Long>()
                var totalArrearsSum = 0L
                var multiMonthCount = 0

                // Kelompokkan tagihan UNPAID berdasarkan pelanggan
                val unpaidBillsByCustomer = unpaidBills.groupBy { it.customerId }

                for ((custId, custUnpaids) in unpaidBillsByCustomer) {
                    val cust = customerMap[custId] ?: continue
                    if (!cust.status.equals("ACTIVE", ignoreCase = true) && !cust.status.equals("ISOLATED", ignoreCase = true)) {
                        continue
                    }
                    val periods = custUnpaids.map { it.periodMonth }
                    val amount = custUnpaids.sumOf { it.totalAmount }

                    dueCustomersList.add(cust)
                    arrearsMap[cust.id] = periods
                    dueAmountMap[cust.id] = amount
                    totalArrearsSum += amount

                    if (periods.size > 1) {
                        multiMonthCount++
                    }
                }

                // Urutkan prioritas penagihan: Terisolir duluan, lalu menunggak > 1 bulan, lalu jumlah tunggakan terbanyak, lalu jatuh tempo terdekat
                dueCustomersList.sortWith(
                    compareByDescending<CustomerEntity> { it.status.equals("ISOLATED", ignoreCase = true) }
                        .thenByDescending { (arrearsMap[it.id]?.size ?: 0) > 1 }
                        .thenByDescending { arrearsMap[it.id]?.size ?: 0 }
                        .thenBy { it.dueDay }
                )

                DashboardUiState(
                    totalRevenueMonth = revenue,
                    activeCustomerCount = activeCustomers.size,
                    totalCustomerCount = customers.size,
                    paidCustomerCount = paidCount,
                    unpaidCustomerCount = maxOf(0, unpaidCount),
                    totalArrearsAmount = totalArrearsSum,
                    multiMonthArrearsCount = multiMonthCount,
                    isolatedCustomerCount = isolatedCustomers.size,
                    recentTransactions = transactions.take(6),
                    dueCustomers = dueCustomersList,
                    customerArrearsMap = arrearsMap,
                    customerDueAmountMap = dueAmountMap,
                    currentPeriod = currentPeriod,
                    isLoading = false
                )
            }.flowOn(Dispatchers.Default).collect { newState ->
                _uiState.value = newState
            }
        }
    }
}

class DashboardViewModelFactory(private val repository: BillingRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

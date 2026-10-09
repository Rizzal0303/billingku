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
        loadDashboardData()
    }

    private fun loadDashboardData() {
        val currentPeriod = repository.getCurrentMonthPeriod()
        _uiState.update { it.copy(currentPeriod = currentPeriod, isLoading = true) }

        viewModelScope.launch(Dispatchers.Default) {
            combine(
                repository.getTotalRevenueByMonth(currentPeriod),
                repository.allCustomers,
                repository.allTransactions
            ) { revenue, customers, transactions ->
                val transactionsByCustomer = transactions.groupBy { it.customerId }
                val currentMonthTrxs = transactions.filter { it.periodMonth.equals(currentPeriod, ignoreCase = true) }
                val paidCustomerIdsCurrentMonth = currentMonthTrxs.map { it.customerId }.toSet()

                // Sertakan ACTIVE dan ISOLATED agar tidak hilang dari monitoring
                val billableCustomers = customers.filter {
                    it.status.equals("ACTIVE", ignoreCase = true) || it.status.equals("ISOLATED", ignoreCase = true)
                }

                val paidCount = billableCustomers.count { paidCustomerIdsCurrentMonth.contains(it.id) }
                val unpaidCount = billableCustomers.size - paidCount

                val dueCustomersList = mutableListOf<CustomerEntity>()
                val arrearsMap = mutableMapOf<Long, List<String>>()
                val dueAmountMap = mutableMapOf<Long, Long>()
                var totalArrearsSum = 0L
                var multiMonthCount = 0
                val isolatedCount = customers.count { it.status.equals("ISOLATED", ignoreCase = true) }

                for (cust in billableCustomers) {
                    val custTrxs = transactionsByCustomer[cust.id] ?: emptyList()
                    val paidPeriods = custTrxs.map { it.periodMonth }.toSet()

                    val eligiblePeriods = PeriodUtils.getEligiblePeriodsForCustomer(cust.createdAt, maxPastMonths = 6)
                    val unpaidPeriods = eligiblePeriods.filter { !paidPeriods.contains(it) }

                    if (unpaidPeriods.isNotEmpty()) {
                        dueCustomersList.add(cust)
                        arrearsMap[cust.id] = unpaidPeriods
                        val amount = unpaidPeriods.size * cust.monthlyFee
                        dueAmountMap[cust.id] = amount
                        totalArrearsSum += amount
                        if (unpaidPeriods.size > 1) {
                            multiMonthCount++
                        }
                    }
                }

                // Urutkan prioritas penagihan: Terisolir duluan, lalu menunggak > 1 bulan, lalu jatuh tempo terdekat
                val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
                dueCustomersList.sortWith(
                    compareByDescending<CustomerEntity> { it.status.equals("ISOLATED", ignoreCase = true) }
                        .thenByDescending { (arrearsMap[it.id]?.size ?: 0) > 1 }
                        .thenByDescending { arrearsMap[it.id]?.size ?: 0 }
                        .thenBy { it.dueDay }
                )

                DashboardUiState(
                    totalRevenueMonth = revenue,
                    activeCustomerCount = customers.count { it.status.equals("ACTIVE", ignoreCase = true) },
                    totalCustomerCount = customers.size,
                    paidCustomerCount = paidCount,
                    unpaidCustomerCount = maxOf(0, unpaidCount),
                    totalArrearsAmount = totalArrearsSum,
                    multiMonthArrearsCount = multiMonthCount,
                    isolatedCustomerCount = isolatedCount,
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

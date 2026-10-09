package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.example.data.auth.SessionManager
import com.example.data.local.AppDatabase
import com.example.data.local.entity.StaffEntity
import com.example.data.repository.BillingRepository
import com.example.printer.BluetoothPrinterManager
import com.example.ui.cashier.CashierScreen
import com.example.ui.cashier.CashierViewModel
import com.example.ui.cashier.CashierViewModelFactory
import com.example.ui.customers.CustomerListScreen
import com.example.ui.customers.CustomerViewModel
import com.example.ui.customers.CustomerViewModelFactory
import com.example.ui.dashboard.DashboardScreen
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.dashboard.DashboardViewModelFactory
import com.example.ui.history.HistoryScreen
import com.example.ui.history.HistoryViewModel
import com.example.ui.history.HistoryViewModelFactory
import com.example.ui.login.LoginScreen
import com.example.ui.login.LoginViewModel
import com.example.ui.login.LoginViewModelFactory
import com.example.ui.navigation.Screen
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.settings.SettingsViewModelFactory
import com.example.ui.settlement.SettlementScreen
import com.example.ui.settlement.SettlementViewModel
import com.example.ui.settlement.SettlementViewModelFactory
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                BillingkuApp()
            }
        }
    }
}

@Composable
fun BillingkuApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val networkMonitor = remember { com.example.data.sync.NetworkMonitor(context) }
    val database = remember { AppDatabase.getDatabase(context) }
    val mysqlSyncManager = remember { com.example.data.sync.MysqlSyncManager(context, database) }
    val autoSyncManager = remember {
        com.example.data.sync.AutoSyncManager(networkMonitor, mysqlSyncManager)
    }
    LaunchedEffect(Unit) {
        delay(2000)
        autoSyncManager.startPeriodicSync()
    }
    val repository = remember {
        BillingRepository(database).apply {
            this.mysqlSyncManager = mysqlSyncManager
        }
    }
    val printerManager = remember { BluetoothPrinterManager(context) }
    val sessionManager = remember { SessionManager(context) }

    val currentStaff by sessionManager.currentUser.collectAsState()

    // If not authenticated, show LoginScreen
    if (currentStaff == null) {
        val loginViewModel: LoginViewModel = viewModel(
            factory = LoginViewModelFactory(repository, sessionManager)
        )
        LoginScreen(
            viewModel = loginViewModel,
            onLoginSuccess = { staff ->
                // Session saved inside ViewModel/SessionManager
            }
        )
        return
    }

    // Authenticated state: Initialize view models
    val activeStaff = currentStaff ?: return
    val isAdmin = sessionManager.isAdmin

    // Only eager-load ViewModels required for start destination (Dashboard & Cashier)
    val dashboardViewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModelFactory(repository)
    )
    val cashierViewModel: CashierViewModel = viewModel(
        factory = CashierViewModelFactory(repository, printerManager)
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(repository, printerManager, mysqlSyncManager)
    )

    // Keep active cashier in sync with authenticated user and ensure session matches database
    LaunchedEffect(activeStaff.id) {
        cashierViewModel.setLoggedInUser(activeStaff)
        sessionManager.refreshWithDb(repository)
    }

    // Request Bluetooth permissions on Android 12+
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        settingsViewModel.refreshPairedPrinters()
    }

    // Request Notification permission on Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            coroutineScope.launch {
                val summary = com.example.notification.DueReminderNotificationManager.calculateDueSummary(repository)
                com.example.notification.DueReminderNotificationManager.showDueNotification(context, summary)
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(2500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            coroutineScope.launch(Dispatchers.IO) {
                val summary = com.example.notification.DueReminderNotificationManager.calculateDueSummary(repository)
                com.example.notification.DueReminderNotificationManager.showDueNotification(context, summary)
            }
        }
    }

    val haptic = LocalHapticFeedback.current
    val dashboardUiState by dashboardViewModel.uiState.collectAsState()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val navigationItems = remember(isAdmin) {
        if (isAdmin) {
            listOf(
                Screen.Dashboard,
                Screen.Cashier,
                Screen.Settlement,
                Screen.History,
                Screen.Settings
            )
        } else {
            listOf(
                Screen.Dashboard,
                Screen.Cashier,
                Screen.History,
                Screen.Settings
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Surface(
                color = Color.White,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                NavigationBar(
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars,
                    modifier = Modifier.testTag("main_bottom_nav")
                ) {
                    navigationItems.forEach { screen ->
                        val isSelected = currentRoute?.startsWith(screen.route) == true

                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (screen == Screen.Dashboard) {
                                    if (currentRoute != Screen.Dashboard.route) {
                                        val popped = navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                                        if (!popped) {
                                            navController.navigate(Screen.Dashboard.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    inclusive = false
                                                }
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                } else if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                if (screen == Screen.Cashier && dashboardUiState.unpaidCustomerCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                containerColor = DangerRed,
                                                contentColor = Color.White
                                            ) {
                                                Text(
                                                    text = if (dashboardUiState.unpaidCustomerCount > 99) "99+" else "${dashboardUiState.unpaidCustomerCount}",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = screen.icon,
                                            contentDescription = screen.title
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = screen.icon,
                                        contentDescription = screen.title
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    fontSize = 11.sp
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BluePrimary,
                                selectedTextColor = BluePrimary,
                                unselectedIconColor = Slate400,
                                unselectedTextColor = Slate400,
                                indicatorColor = BluePrimaryLight
                            ),
                            modifier = Modifier.testTag("nav_item_${screen.route}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    viewModel = dashboardViewModel,
                    currentStaff = activeStaff,
                    networkMonitor = networkMonitor,
                    mysqlSyncManager = mysqlSyncManager,
                    onVerifyAdminPin = { pin -> sessionManager.verifyAdminPin(pin) },
                    onNavigateToCashier = { customerId ->
                        if (customerId != null) {
                            cashierViewModel.selectCustomerById(customerId)
                        }
                        navController.navigate(Screen.Cashier.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToCustomers = {
                        navController.navigate(Screen.Customers.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToPrinterSettings = {
                        settingsViewModel.setTab(0)
                        navController.navigate(Screen.Settings.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToReports = {
                        navController.navigate(Screen.History.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(Screen.Cashier.route) {
                CashierScreen(
                    viewModel = cashierViewModel,
                    isAdmin = isAdmin,
                    onVerifyAdminPin = { pin -> sessionManager.verifyAdminPin(pin) },
                    onBackClick = {
                        val popped = navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                        if (!popped) {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        }
                    }
                )
            }

            composable(Screen.Settlement.route) {
                val settlementViewModel: SettlementViewModel = viewModel(
                    factory = SettlementViewModelFactory(repository, printerManager)
                )
                SettlementScreen(
                    viewModel = settlementViewModel,
                    currentStaff = activeStaff,
                    isAdmin = isAdmin,
                    onBackClick = {
                        val popped = navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                        if (!popped) {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        }
                    }
                )
            }

            composable(Screen.History.route) {
                val historyViewModel: HistoryViewModel = viewModel(
                    factory = HistoryViewModelFactory(repository, printerManager)
                )
                HistoryScreen(
                    viewModel = historyViewModel,
                    isAdmin = isAdmin,
                    onVerifyAdminPin = { pin -> sessionManager.verifyAdminPin(pin) },
                    onBackClick = {
                        val popped = navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                        if (!popped) {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        }
                    }
                )
            }

            composable(Screen.Customers.route) {
                val customerViewModel: CustomerViewModel = viewModel(
                    factory = CustomerViewModelFactory(repository)
                )
                CustomerListScreen(
                    viewModel = customerViewModel,
                    isAdmin = isAdmin,
                    onVerifyAdminPin = { pin -> sessionManager.verifyAdminPin(pin) },
                    onNavigateToCashier = { customerId ->
                        cashierViewModel.selectCustomerById(customerId)
                        navController.navigate(Screen.Cashier.route) {
                            launchSingleTop = true
                        }
                    },
                    onBackClick = {
                        val popped = navController.popBackStack(Screen.Dashboard.route, inclusive = false)
                        if (!popped) {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        }
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    isAdmin = isAdmin,
                    currentStaff = activeStaff,
                    onLogout = {
                        sessionManager.clearSession()
                    },
                    onPinUpdated = { _ ->
                        coroutineScope.launch {
                            sessionManager.refreshWithDb(repository)
                        }
                    },
                    onStaffProfileUpdated = { updatedStaff ->
                        coroutineScope.launch {
                            sessionManager.saveSession(updatedStaff)
                            sessionManager.refreshWithDb(repository)
                        }
                    },
                    onVerifyAdminPin = { pin -> sessionManager.verifyAdminPin(pin) },
                    onNavigateToCustomers = {
                        navController.navigate(Screen.Customers.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    }
}

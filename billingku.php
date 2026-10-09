<?php
/**
 * =========================================================================
 * BILLINGKU WIFI NETWORK - REST API & DATABASE SYNC SERVER
 * File: billingku.php
 * Versi: 1.1 (Dengan Fitur Pengumuman Gangguan Net-pay)
 * 
 * Panduan Pemasangan:
 * 1. Unggah (upload) file ini ke hosting cPanel (folder public_html) atau VPS/XAMPP Anda.
 * 2. Sesuaikan konfigurasi database di bawah ini ($db_host, $db_name, $db_user, $db_pass, $api_key).
 * 3. Buka URL file ini di browser untuk verifikasi awal.
 * 4. Buka Menu Pengaturan -> Database MySQL di Aplikasi Android Billingku, lalu
 *    masukkan URL file ini dan API Key Anda.
 * =========================================================================
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization, X-API-Key');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}

// -------------------------------------------------------------
// 1. KONFIGURASI DATABASE MYSQL & KEAMANAN
// Silakan sesuaikan dengan kredensial database Anda
// -------------------------------------------------------------
$db_host = 'localhost';
$db_name = 'billingku_db';     // Nama database MySQL Anda
$db_user = 'root';              // Username database MySQL Anda
$db_pass = '';                  // Password database MySQL Anda
$api_key = 'billingku_secret';  // Samakan dengan API Key di Pengaturan Aplikasi

// -------------------------------------------------------------
// 2. KONEKSI KE DATABASE (PDO)
// -------------------------------------------------------------
try {
    $pdo = new PDO("mysql:host={$db_host};dbname={$db_name};charset=utf8mb4", $db_user, $db_pass, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES   => false,
    ]);
} catch (PDOException $e) {
    echo json_encode([
        'status'  => 'error',
        'code'    => 500,
        'message' => 'Gagal terkoneksi ke database MySQL: ' . $e->getMessage()
    ]);
    exit();
}

// -------------------------------------------------------------
// 3. FUNGSI OTOMATIS MEMBUAT TABEL (AUTO MIGRATION)
// -------------------------------------------------------------
function initTables($pdo) {
    // Tabel Pelanggan
    $pdo->exec("CREATE TABLE IF NOT EXISTS `customers` (
        `id` BIGINT NOT NULL PRIMARY KEY,
        `customerCode` VARCHAR(50) NOT NULL,
        `name` VARCHAR(150) NOT NULL,
        `phone` VARCHAR(50) DEFAULT '',
        `address` TEXT,
        `odpLocation` VARCHAR(150) DEFAULT '',
        `packageId` BIGINT DEFAULT 0,
        `packageName` VARCHAR(100) DEFAULT '',
        `monthlyFee` BIGINT DEFAULT 0,
        `dueDay` INT DEFAULT 10,
        `ipOrPppoe` VARCHAR(100) DEFAULT '',
        `status` VARCHAR(30) DEFAULT 'ACTIVE',
        `createdAt` BIGINT DEFAULT 0,
        `updatedAt` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        INDEX (`customerCode`),
        INDEX (`status`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

    // Tabel Paket Internet
    $pdo->exec("CREATE TABLE IF NOT EXISTS `package_plans` (
        `id` BIGINT NOT NULL PRIMARY KEY,
        `name` VARCHAR(100) NOT NULL,
        `speedMbps` INT DEFAULT 0,
        `price` BIGINT DEFAULT 0,
        `description` TEXT,
        `isActive` TINYINT(1) DEFAULT 1,
        `updatedAt` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

    // Tabel Transaksi / Pembayaran
    $pdo->exec("CREATE TABLE IF NOT EXISTS `transactions` (
        `id` BIGINT NOT NULL PRIMARY KEY,
        `invoiceNumber` VARCHAR(60) NOT NULL,
        `customerId` BIGINT NOT NULL,
        `customerName` VARCHAR(150) NOT NULL,
        `customerCode` VARCHAR(50) NOT NULL,
        `packageName` VARCHAR(100) DEFAULT '',
        `monthlyFee` BIGINT DEFAULT 0,
        `adminFee` BIGINT DEFAULT 0,
        `discount` BIGINT DEFAULT 0,
        `totalAmount` BIGINT NOT NULL,
        `periodMonth` VARCHAR(60) NOT NULL,
        `paymentMethod` VARCHAR(30) DEFAULT 'TUNAI',
        `cashierName` VARCHAR(100) DEFAULT 'Kasir Utama',
        `cashierRole` VARCHAR(50) DEFAULT 'KASIR',
        `notes` TEXT,
        `paidAt` BIGINT NOT NULL,
        `isPrinted` TINYINT(1) DEFAULT 0,
        `isDeposited` TINYINT(1) DEFAULT 0,
        `settlementBatchId` BIGINT NULL,
        `settlementBatchNumber` VARCHAR(60) NULL,
        `depositedAt` BIGINT NULL,
        `createdAt` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        INDEX (`customerId`),
        INDEX (`invoiceNumber`),
        INDEX (`periodMonth`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

    try {
        $pdo->exec("ALTER TABLE `transactions` ADD UNIQUE INDEX `uniq_customer_period` (`customerId`, `periodMonth`);");
    } catch (Exception $e) {}

    // Tabel Setoran Kasir (Settlement Batches)
    $pdo->exec("CREATE TABLE IF NOT EXISTS `settlement_batches` (
        `id` BIGINT NOT NULL PRIMARY KEY,
        `batchNumber` VARCHAR(60) NOT NULL,
        `periodMonth` VARCHAR(60) NOT NULL,
        `cashierName` VARCHAR(100) NOT NULL,
        `recipientName` VARCHAR(100) DEFAULT 'Owner / Admin',
        `totalAmount` BIGINT NOT NULL,
        `totalCashAmount` BIGINT DEFAULT 0,
        `totalNonCashAmount` BIGINT DEFAULT 0,
        `customerCount` INT DEFAULT 0,
        `notes` TEXT,
        `createdAt` BIGINT NOT NULL,
        INDEX (`batchNumber`),
        INDEX (`periodMonth`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

    // Tabel Anggota Staf & Teknisi
    $pdo->exec("CREATE TABLE IF NOT EXISTS `staff_members` (
        `id` BIGINT NOT NULL PRIMARY KEY,
        `username` VARCHAR(60) NOT NULL,
        `name` VARCHAR(150) NOT NULL,
        `role` VARCHAR(30) DEFAULT 'KASIR',
        `phone` VARCHAR(50) DEFAULT '',
        `pin` VARCHAR(255) DEFAULT '1234',
        `isActive` TINYINT(1) DEFAULT 1,
        `updatedAt` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        UNIQUE KEY (`username`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

    // Tabel Pengumuman & Notifikasi Gangguan (Siap dibaca oleh aplikasi pelanggan Net-pay)
    $pdo->exec("CREATE TABLE IF NOT EXISTS `announcements` (
        `id` BIGINT NOT NULL PRIMARY KEY,
        `title` VARCHAR(200) NOT NULL,
        `category` VARCHAR(50) NOT NULL DEFAULT 'INFO',
        `message` TEXT NOT NULL,
        `targetScope` VARCHAR(100) DEFAULT 'SEMUA',
        `status` VARCHAR(30) DEFAULT 'ACTIVE',
        `estimatedEnd` VARCHAR(100) DEFAULT '',
        `createdBy` VARCHAR(100) DEFAULT 'Admin',
        `createdAt` BIGINT DEFAULT 0,
        `updatedAt` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        INDEX (`status`),
        INDEX (`category`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

    // Safe Column Migrations
    try { $pdo->exec("ALTER TABLE `staff_members` MODIFY COLUMN `pin` VARCHAR(255) DEFAULT '1234'"); } catch (Exception $e) {}
    try { $pdo->exec("ALTER TABLE `customers` ADD COLUMN `odpLocation` VARCHAR(150) DEFAULT '' AFTER `address`"); } catch (Exception $e) {}
    try { $pdo->exec("ALTER TABLE `customers` ADD COLUMN `ipOrPppoe` VARCHAR(100) DEFAULT '' AFTER `dueDay`"); } catch (Exception $e) {}
    try { $pdo->exec("ALTER TABLE `transactions` ADD COLUMN `settlementBatchId` BIGINT NULL AFTER `isDeposited`"); } catch (Exception $e) {}
    try { $pdo->exec("ALTER TABLE `transactions` ADD COLUMN `settlementBatchNumber` VARCHAR(60) NULL AFTER `settlementBatchId`"); } catch (Exception $e) {}
    try { $pdo->exec("ALTER TABLE `transactions` ADD COLUMN `depositedAt` BIGINT NULL AFTER `settlementBatchNumber`"); } catch (Exception $e) {}
    try { $pdo->exec("ALTER TABLE `transactions` ADD UNIQUE KEY `uq_customer_period` (`customerId`, `periodMonth`)"); } catch (Exception $e) {}
}

try {
    initTables($pdo);
} catch (Exception $e) {}

$rawInput = file_get_contents('php://input');
$jsonData = json_decode($rawInput, true, 512, JSON_BIGINT_AS_STRING);

$req_key = $_SERVER['HTTP_X_API_KEY'] ?? ($_POST['api_key'] ?? ($_GET['api_key'] ?? ''));
if (empty($req_key) && is_array($jsonData) && isset($jsonData['api_key'])) {
    $req_key = $jsonData['api_key'];
}

$action = $_GET['action'] ?? ($_POST['action'] ?? ($jsonData['action'] ?? ''));

// =============================================================
// ENDPOINT PUBLIK (UNTUK APLIKASI PELANGGAN NET-PAY)
// =============================================================

// (1) GET ANNOUNCEMENTS (DIBACA OLEH APLIKASI PELANGGAN NET-PAY)
if ($action === 'get_announcements') {
    $filterStatus = $_GET['status'] ?? ($jsonData['status'] ?? 'ACTIVE');
    $scope = $_GET['scope'] ?? ($jsonData['scope'] ?? '');

    try {
        if ($filterStatus === 'ALL') {
            if (!empty($scope)) {
                $stmt = $pdo->prepare("SELECT * FROM `announcements` WHERE `targetScope` = 'SEMUA' OR `targetScope` = :scope ORDER BY `createdAt` DESC");
                $stmt->execute([':scope' => $scope]);
            } else {
                $stmt = $pdo->query("SELECT * FROM `announcements` ORDER BY `createdAt` DESC");
            }
        } else {
            if (!empty($scope)) {
                $stmt = $pdo->prepare("SELECT * FROM `announcements` WHERE `status` = :st AND (`targetScope` = 'SEMUA' OR `targetScope` = :scope) ORDER BY `createdAt` DESC");
                $stmt->execute([':st' => $filterStatus, ':scope' => $scope]);
            } else {
                $stmt = $pdo->prepare("SELECT * FROM `announcements` WHERE `status` = :st ORDER BY `createdAt` DESC");
                $stmt->execute([':st' => $filterStatus]);
            }
        }

        $rows = $stmt->fetchAll();
        echo json_encode([
            'status'  => 'success',
            'code'    => 200,
            'count'   => count($rows),
            'message' => 'Daftar pengumuman berhasil diambil',
            'data'    => $rows
        ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => 'Gagal mengambil pengumuman: ' . $e->getMessage()]);
        exit();
    }
}

// -------------------------------------------------------------
// VERIFIKASI KEAMANAN API KEY UNTUK AKSI ADMIN
// -------------------------------------------------------------
if ($action !== 'test_connection') {
    if (empty($req_key) || $req_key !== $api_key) {
        http_response_code(401);
        echo json_encode([
            'status'  => 'error',
            'code'    => 401,
            'message' => 'Autentikasi Gagal: API Key tidak valid atau belum dikirim.'
        ]);
        exit();
    }
}

// (2) TES KONEKSI
if ($action === 'test_connection' || empty($action)) {
    echo json_encode([
        'status'       => 'success',
        'code'         => 200,
        'message'      => 'Server REST API Billingku aktif & terkoneksi ke MySQL!',
        'server_time'  => date('Y-m-d H:i:s'),
        'api_version'  => '1.1'
    ]);
    exit();
}

// (3) AMBIL SEMUA DATA (PULL SYNC)
if ($action === 'get_all_data') {
    try {
        $customers    = $pdo->query("SELECT * FROM `customers` ORDER BY `name` ASC")->fetchAll();
        $packages     = $pdo->query("SELECT * FROM `package_plans` ORDER BY `price` ASC")->fetchAll();
        $transactions = $pdo->query("SELECT * FROM `transactions` ORDER BY `paidAt` DESC")->fetchAll();
        $staff        = $pdo->query("SELECT * FROM `staff_members` ORDER BY `name` ASC")->fetchAll();
        $settlements  = $pdo->query("SELECT * FROM `settlement_batches` ORDER BY `createdAt` DESC")->fetchAll();
        $announcements= $pdo->query("SELECT * FROM `announcements` ORDER BY `createdAt` DESC")->fetchAll();

        echo json_encode([
            'status' => 'success',
            'code'   => 200,
            'data'   => [
                'customers'     => $customers,
                'packages'      => $packages,
                'transactions'  => $transactions,
                'staff'         => $staff,
                'settlements'   => $settlements,
                'announcements' => $announcements
            ]
        ]);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => $e->getMessage()]);
        exit();
    }
}

// (4) PUSH ANNOUNCEMENT (DIPUBLIKASIKAN DARI APLIKASI BILLINGKU)
if ($action === 'push_announcement') {
    $ann = $jsonData['announcement'] ?? ($_POST['announcement'] ?? []);
    if (is_string($ann)) $ann = json_decode($ann, true, 512, JSON_BIGINT_AS_STRING);
    if (empty($ann) && !empty($jsonData['id'])) $ann = $jsonData;

    if (empty($ann) || empty($ann['id']) || empty($ann['title'])) {
        http_response_code(400);
        echo json_encode(['status' => 'error', 'code' => 400, 'message' => 'Data pengumuman tidak valid (ID dan Judul wajib diisi)']);
        exit();
    }

    try {
        $stmt = $pdo->prepare("INSERT INTO `announcements`
            (`id`, `title`, `category`, `message`, `targetScope`, `status`, `estimatedEnd`, `createdBy`, `createdAt`)
            VALUES (:id, :title, :category, :message, :targetScope, :status, :estimatedEnd, :createdBy, :createdAt)
            ON DUPLICATE KEY UPDATE
            `title` = VALUES(`title`),
            `category` = VALUES(`category`),
            `message` = VALUES(`message`),
            `targetScope` = VALUES(`targetScope`),
            `status` = VALUES(`status`),
            `estimatedEnd` = VALUES(`estimatedEnd`),
            `createdBy` = VALUES(`createdBy`);");

        $stmt->execute([
            ':id'           => $ann['id'],
            ':title'        => $ann['title'],
            ':category'     => $ann['category'] ?? 'INFO',
            ':message'      => $ann['message'] ?? '',
            ':targetScope'  => $ann['targetScope'] ?? 'SEMUA',
            ':status'       => $ann['status'] ?? 'ACTIVE',
            ':estimatedEnd' => $ann['estimatedEnd'] ?? '',
            ':createdBy'    => $ann['createdBy'] ?? 'Admin',
            ':createdAt'    => $ann['createdAt'] ?? round(microtime(true) * 1000)
        ]);

        echo json_encode([
            'status'  => 'success',
            'code'    => 200,
            'message' => 'Pengumuman berhasil disinkronkan ke server untuk aplikasi Net-pay!'
        ]);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => 'Gagal menyimpan pengumuman: ' . $e->getMessage()]);
        exit();
    }
}

// (5) DELETE ANNOUNCEMENT
if ($action === 'delete_announcement') {
    $id = $jsonData['id'] ?? ($_POST['id'] ?? ($_GET['id'] ?? 0));
    if (empty($id)) {
        http_response_code(400);
        echo json_encode(['status' => 'error', 'code' => 400, 'message' => 'ID Pengumuman wajib diisi']);
        exit();
    }

    try {
        $stmt = $pdo->prepare("DELETE FROM `announcements` WHERE `id` = :id");
        $stmt->execute([':id' => $id]);
        echo json_encode(['status' => 'success', 'code' => 200, 'message' => 'Pengumuman berhasil dihapus dari server!']);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => 'Gagal menghapus pengumuman: ' . $e->getMessage()]);
        exit();
    }
}

// (6) PUSH TRANSAKSI
if ($action === 'push_transaction') {
    $trx = $jsonData['transaction'] ?? ($_POST['transaction'] ?? []);
    if (is_string($trx)) $trx = json_decode($trx, true, 512, JSON_BIGINT_AS_STRING);
    if (empty($trx) && !empty($jsonData['id'])) $trx = $jsonData;

    if (empty($trx) || empty($trx['id']) || empty($trx['customerId']) || empty($trx['periodMonth'])) {
        http_response_code(400);
        echo json_encode(['status' => 'error', 'code' => 400, 'message' => 'Data transaksi tidak valid']);
        exit();
    }

    try {
        $stmt = $pdo->prepare("INSERT INTO `transactions`
            (`id`, `invoiceNumber`, `customerId`, `customerName`, `customerCode`, `packageName`, `monthlyFee`, `adminFee`, `discount`, `totalAmount`, `periodMonth`, `paymentMethod`, `cashierName`, `cashierRole`, `notes`, `paidAt`, `isPrinted`, `isDeposited`, `settlementBatchId`, `settlementBatchNumber`, `depositedAt`)
            VALUES (:id, :invoiceNumber, :customerId, :customerName, :customerCode, :packageName, :monthlyFee, :adminFee, :discount, :totalAmount, :periodMonth, :paymentMethod, :cashierName, :cashierRole, :notes, :paidAt, :isPrinted, :isDeposited, :settlementBatchId, :settlementBatchNumber, :depositedAt)
            ON DUPLICATE KEY UPDATE
            `isPrinted` = VALUES(`isPrinted`),
            `isDeposited` = VALUES(`isDeposited`),
            `settlementBatchId` = VALUES(`settlementBatchId`),
            `settlementBatchNumber` = VALUES(`settlementBatchNumber`),
            `depositedAt` = VALUES(`depositedAt`);");

        $stmt->execute([
            ':id'                    => $trx['id'],
            ':invoiceNumber'         => $trx['invoiceNumber'] ?? '',
            ':customerId'            => $trx['customerId'],
            ':customerName'          => $trx['customerName'] ?? '',
            ':customerCode'          => $trx['customerCode'] ?? '',
            ':packageName'           => $trx['packageName'] ?? '',
            ':monthlyFee'            => $trx['monthlyFee'] ?? 0,
            ':adminFee'              => $trx['adminFee'] ?? 0,
            ':discount'              => $trx['discount'] ?? 0,
            ':totalAmount'           => $trx['totalAmount'] ?? 0,
            ':periodMonth'           => $trx['periodMonth'],
            ':paymentMethod'         => $trx['paymentMethod'] ?? 'TUNAI',
            ':cashierName'           => $trx['cashierName'] ?? 'Kasir',
            ':cashierRole'           => $trx['cashierRole'] ?? 'KASIR',
            ':notes'                 => $trx['notes'] ?? '',
            ':paidAt'                => $trx['paidAt'] ?? round(microtime(true) * 1000),
            ':isPrinted'             => !empty($trx['isPrinted']) ? 1 : 0,
            ':isDeposited'           => !empty($trx['isDeposited']) ? 1 : 0,
            ':settlementBatchId'     => $trx['settlementBatchId'] ?? null,
            ':settlementBatchNumber' => $trx['settlementBatchNumber'] ?? null,
            ':depositedAt'           => $trx['depositedAt'] ?? null
        ]);

        echo json_encode(['status' => 'success', 'code' => 200, 'message' => 'Transaksi berhasil disimpan']);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => $e->getMessage()]);
        exit();
    }
}

// (7) CEK STATUS PEMBAYARAN PELANGGAN
if ($action === 'check_payment_status') {
    $cid = $_GET['customerId'] ?? ($_POST['customerId'] ?? ($jsonData['customerId'] ?? 0));
    $pmonth = $_GET['periodMonth'] ?? ($_POST['periodMonth'] ?? ($jsonData['periodMonth'] ?? ''));

    try {
        $stmt = $pdo->prepare("SELECT * FROM `transactions` WHERE `customerId` = :cid AND `periodMonth` = :pmonth LIMIT 1");
        $stmt->execute([':cid' => $cid, ':pmonth' => $pmonth]);
        $existing = $stmt->fetch();

        echo json_encode([
            'status'   => 'success',
            'code'     => 200,
            'is_paid'  => (bool)$existing,
            'existing_invoice' => $existing ? $existing['invoiceNumber'] : null,
            'message'  => $existing ? "Tagihan {$pmonth} sudah lunas" : "Belum lunas"
        ]);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => $e->getMessage()]);
        exit();
    }
}

// (8) PUSH PELANGGAN
if ($action === 'push_customer') {
    $c = $jsonData['customer'] ?? ($_POST['customer'] ?? []);
    if (is_string($c)) $c = json_decode($c, true, 512, JSON_BIGINT_AS_STRING);
    if (empty($c) && !empty($jsonData['id'])) $c = $jsonData;

    try {
        $stmt = $pdo->prepare("INSERT INTO `customers`
            (`id`, `customerCode`, `name`, `phone`, `address`, `odpLocation`, `packageId`, `packageName`, `monthlyFee`, `dueDay`, `ipOrPppoe`, `status`, `createdAt`)
            VALUES (:id, :customerCode, :name, :phone, :address, :odpLocation, :packageId, :packageName, :monthlyFee, :dueDay, :ipOrPppoe, :status, :createdAt)
            ON DUPLICATE KEY UPDATE
            `customerCode` = VALUES(`customerCode`),
            `name` = VALUES(`name`),
            `phone` = VALUES(`phone`),
            `address` = VALUES(`address`),
            `odpLocation` = VALUES(`odpLocation`),
            `packageId` = VALUES(`packageId`),
            `packageName` = VALUES(`packageName`),
            `monthlyFee` = VALUES(`monthlyFee`),
            `dueDay` = VALUES(`dueDay`),
            `ipOrPppoe` = VALUES(`ipOrPppoe`),
            `status` = VALUES(`status`);");

        $stmt->execute([
            ':id'           => $c['id'],
            ':customerCode' => $c['customerCode'] ?? '',
            ':name'         => $c['name'] ?? '',
            ':phone'        => $c['phone'] ?? '',
            ':address'      => $c['address'] ?? '',
            ':odpLocation'  => $c['odpLocation'] ?? '',
            ':packageId'    => $c['packageId'] ?? 0,
            ':packageName'  => $c['packageName'] ?? '',
            ':monthlyFee'   => $c['monthlyFee'] ?? 0,
            ':dueDay'       => $c['dueDay'] ?? 10,
            ':ipOrPppoe'    => $c['ipOrPppoe'] ?? '',
            ':status'       => $c['status'] ?? 'ACTIVE',
            ':createdAt'    => $c['createdAt'] ?? round(microtime(true) * 1000)
        ]);

        echo json_encode(['status' => 'success', 'code' => 200, 'message' => 'Data pelanggan tersimpan']);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => $e->getMessage()]);
        exit();
    }
}

// (9) DELETE PELANGGAN
if ($action === 'delete_customer') {
    $id = $jsonData['id'] ?? ($_POST['id'] ?? ($_GET['id'] ?? 0));
    try {
        $stmt = $pdo->prepare("DELETE FROM `customers` WHERE `id` = :id");
        $stmt->execute([':id' => $id]);
        echo json_encode(['status' => 'success', 'code' => 200, 'message' => 'Pelanggan berhasil dihapus']);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => $e->getMessage()]);
        exit();
    }
}

// (10) PUSH PAKET
if ($action === 'push_package') {
    $p = $jsonData['package'] ?? ($_POST['package'] ?? []);
    if (is_string($p)) $p = json_decode($p, true, 512, JSON_BIGINT_AS_STRING);
    if (empty($p) && !empty($jsonData['id'])) $p = $jsonData;

    try {
        $stmt = $pdo->prepare("INSERT INTO `package_plans`
            (`id`, `name`, `speedMbps`, `price`, `description`, `isActive`)
            VALUES (:id, :name, :speedMbps, :price, :description, :isActive)
            ON DUPLICATE KEY UPDATE
            `name` = VALUES(`name`),
            `speedMbps` = VALUES(`speedMbps`),
            `price` = VALUES(`price`),
            `description` = VALUES(`description`),
            `isActive` = VALUES(`isActive`);");

        $stmt->execute([
            ':id'          => $p['id'],
            ':name'        => $p['name'] ?? '',
            ':speedMbps'   => $p['speedMbps'] ?? 0,
            ':price'       => $p['price'] ?? 0,
            ':description' => $p['description'] ?? '',
            ':isActive'    => !empty($p['isActive']) ? 1 : 0
        ]);

        echo json_encode(['status' => 'success', 'code' => 200, 'message' => 'Paket berhasil disimpan']);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => $e->getMessage()]);
        exit();
    }
}

// (11) PUSH STAFF
if ($action === 'push_staff') {
    $s = $jsonData['staff'] ?? ($_POST['staff'] ?? []);
    if (is_string($s)) $s = json_decode($s, true, 512, JSON_BIGINT_AS_STRING);
    if (empty($s) && !empty($jsonData['id'])) $s = $jsonData;

    try {
        $stmt = $pdo->prepare("INSERT INTO `staff_members`
            (`id`, `username`, `name`, `role`, `phone`, `pin`, `isActive`)
            VALUES (:id, :username, :name, :role, :phone, :pin, :isActive)
            ON DUPLICATE KEY UPDATE
            `name` = VALUES(`name`),
            `role` = VALUES(`role`),
            `phone` = VALUES(`phone`),
            `pin` = VALUES(`pin`),
            `isActive` = VALUES(`isActive`);");

        $stmt->execute([
            ':id'       => $s['id'],
            ':username' => $s['username'] ?? '',
            ':name'     => $s['name'] ?? '',
            ':role'     => $s['role'] ?? 'KASIR',
            ':phone'    => $s['phone'] ?? '',
            ':pin'      => $s['pin'] ?? '1234',
            ':isActive' => !empty($s['isActive']) ? 1 : 0
        ]);

        echo json_encode(['status' => 'success', 'code' => 200, 'message' => 'Staf berhasil disimpan']);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => $e->getMessage()]);
        exit();
    }
}

// (12) PUSH SETTLEMENT
if ($action === 'push_settlement') {
    $sb = $jsonData['settlement_batch'] ?? ($_POST['settlement_batch'] ?? []);
    if (is_string($sb)) $sb = json_decode($sb, true, 512, JSON_BIGINT_AS_STRING);
    if (empty($sb) && !empty($jsonData['id'])) $sb = $jsonData;

    try {
        $stmt = $pdo->prepare("INSERT INTO `settlement_batches`
            (`id`, `batchNumber`, `periodMonth`, `cashierName`, `recipientName`, `totalAmount`, `totalCashAmount`, `totalNonCashAmount`, `customerCount`, `notes`, `createdAt`)
            VALUES (:id, :batchNumber, :periodMonth, :cashierName, :recipientName, :totalAmount, :totalCashAmount, :totalNonCashAmount, :customerCount, :notes, :createdAt)
            ON DUPLICATE KEY UPDATE
            `batchNumber` = VALUES(`batchNumber`),
            `periodMonth` = VALUES(`periodMonth`),
            `cashierName` = VALUES(`cashierName`),
            `recipientName` = VALUES(`recipientName`),
            `totalAmount` = VALUES(`totalAmount`),
            `totalCashAmount` = VALUES(`totalCashAmount`),
            `totalNonCashAmount` = VALUES(`totalNonCashAmount`),
            `customerCount` = VALUES(`customerCount`),
            `notes` = VALUES(`notes`);");

        $stmt->execute([
            ':id'                => $sb['id'],
            ':batchNumber'       => $sb['batchNumber'],
            ':periodMonth'       => $sb['periodMonth'] ?? '',
            ':cashierName'       => $sb['cashierName'] ?? '',
            ':recipientName'     => $sb['recipientName'] ?? 'Owner / Admin',
            ':totalAmount'       => $sb['totalAmount'] ?? 0,
            ':totalCashAmount'   => $sb['totalCashAmount'] ?? 0,
            ':totalNonCashAmount'=> $sb['totalNonCashAmount'] ?? 0,
            ':customerCount'     => $sb['customerCount'] ?? 0,
            ':notes'             => $sb['notes'] ?? '',
            ':createdAt'         => $sb['createdAt'] ?? round(microtime(true) * 1000)
        ]);

        echo json_encode(['status' => 'success', 'code' => 200, 'message' => 'Setoran berhasil disimpan']);
        exit();
    } catch (Exception $e) {
        http_response_code(500);
        echo json_encode(['status' => 'error', 'code' => 500, 'message' => $e->getMessage()]);
        exit();
    }
}

http_response_code(400);
echo json_encode([
    'status'  => 'error',
    'code'    => 400,
    'message' => 'Aksi (' . htmlspecialchars($action) . ') tidak dikenali oleh billingku.php.'
]);
exit();
?>

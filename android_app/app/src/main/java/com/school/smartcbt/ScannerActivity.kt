package com.school.smartcbt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.school.smartcbt.data.model.BasicResponse
import com.school.smartcbt.data.model.GateScanRequest
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.utils.NotificationHelper
import com.school.smartcbt.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.LinearLayout
import android.graphics.Color
import android.graphics.drawable.ColorDrawable

data class LocationResult(
    val lat: Double?,
    val lng: Double?,
    val isMock: Boolean,
    val accuracy: Float
)

class ScannerActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var locationManager: LocationManager? = null
    private var liveBestLocation: Location? = null

    // Konfigurasi Dinamis dari Server
    private var isScannerGeolocationActive: Boolean = true
    private var schoolLat: Double = -8.125506
    private var schoolLng: Double = 111.893526
    private var radiusGlobal: Int = 150
    private var zoneGoodThreshold: Float = 50.0f
    private var zoneDegradedThreshold: Float = 100.0f
    private var countdownSeconds: Int = 60
    private var isUsingQrFallback: Boolean = false
    private var countdownTimer: android.os.CountDownTimer? = null

    private val liveLocationListener = object : android.location.LocationListener {
        override fun onLocationChanged(loc: Location) {
            val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) loc.isMock
                         else @Suppress("DEPRECATION") loc.isFromMockProvider
            if (isMock) {
                liveBestLocation = loc
                runOnUiThread { updateGpsIndicatorUI(loc) }
                return
            }

            liveBestLocation = loc
            runOnUiThread {
                updateGpsIndicatorUI(loc)
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private lateinit var viewFinder: PreviewView
    private lateinit var tvStatusText: TextView
    private lateinit var tvClassIndicator: TextView
    private var tvLiveGpsCoords: TextView? = null
    private var tvGpsAccuracySub: TextView? = null
    private var layoutLiveGpsBadge: LinearLayout? = null
    private var tvScannerTitle: TextView? = null
    private var tvScannerSub: TextView? = null
    private var tvServerClock: TextView? = null
    private var tvServerDate: TextView? = null
    private val clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockTicker = object : Runnable {
        override fun run() {
            val now = Date()
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("id", "ID")).format(now) + " WIB"
            val dateFmt = SimpleDateFormat("EEEE, dd MMM yyyy", Locale("id", "ID")).format(now)
            tvServerClock?.text = timeFmt
            tvServerDate?.text = dateFmt
            clockHandler.postDelayed(this, 1000)
        }
    }
    private lateinit var btnFlashToggle: ImageButton
    private lateinit var btnBack: ImageButton

    private var camera: Camera? = null
    private var isTorchOn = false
    private var isProcessingScan = false
    private var currentScanMode: String = MODE_ATTENDANCE
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner

    companion object {
        private const val PERMISSION_REQ_CODE = 2001
        const val EXTRA_SCAN_MODE = "EXTRA_SCAN_MODE"
        const val MODE_ATTENDANCE = "MODE_ATTENDANCE"
        const val MODE_BOOK_AUDIT = "MODE_BOOK_AUDIT"
        const val MODE_PRAYER = "MODE_PRAYER"
        const val MODE_EXAM_TOKEN = "MODE_EXAM_TOKEN"
        const val EXTRA_SCANNED_TOKEN = "EXTRA_SCANNED_TOKEN"
        const val MODE_ROOM_HANDOVER = "MODE_ROOM_HANDOVER"
        const val EXTRA_SCANNED_ROOM_CODE = "EXTRA_SCANNED_ROOM_CODE"
        const val MODE_BK_DYNAMIC_QR = "MODE_BK_DYNAMIC_QR"
        const val MODE_SATPAM_GATEPASS = "MODE_SATPAM_GATEPASS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        sessionManager = SessionManager(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize ML Kit Barcode Scanner for QR, Code 128, Code 39
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_EAN_13
            )
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        initViews()
        fetchServerAttendanceConfig()
        checkPermissionsAndStartCamera()
    }

    private fun initViews() {
        viewFinder = findViewById(R.id.viewFinder)
        tvStatusText = findViewById(R.id.tvStatusText)
        tvClassIndicator = findViewById(R.id.tvClassIndicator)
        tvLiveGpsCoords = findViewById(R.id.tvLiveGpsCoords)
        tvGpsAccuracySub = findViewById(R.id.tvGpsAccuracySub)
        layoutLiveGpsBadge = findViewById(R.id.layoutLiveGpsBadge)
        tvScannerTitle = findViewById(R.id.tvScannerTitle)
        tvScannerSub = findViewById(R.id.tvScannerSub)
        tvServerClock = findViewById(R.id.tvServerClock)
        tvServerDate = findViewById(R.id.tvServerDate)
        clockHandler.post(clockTicker)
        btnFlashToggle = findViewById(R.id.btnFlashToggle)
        btnBack = findViewById(R.id.btnBack)

        currentScanMode = intent.getStringExtra(EXTRA_SCAN_MODE) ?: MODE_ATTENDANCE
        if (currentScanMode == MODE_BOOK_AUDIT) {
            tvScannerTitle?.text = "📚 Pemindai Barcode Buku"
            tvScannerSub?.text = "Arahkan kamera ke Stiker Barcode Buku Perpustakaan"
            tvClassIndicator.text = "📚 Sirkulasi & Audit Buku Perpustakaan"
            tvClassIndicator.setBackgroundColor(Color.parseColor("#7C3AED"))
            tvStatusText.text = "📖 Posisikan Barcode Buku pada area kamera..."
            layoutLiveGpsBadge?.visibility = View.GONE
            tvGpsAccuracySub?.visibility = View.GONE
        } else if (currentScanMode == MODE_PRAYER) {
            val topBarView = findViewById<LinearLayout>(R.id.topBar)
            topBarView?.setBackgroundResource(R.drawable.bg_mushola_header_gradient)
            tvScannerTitle?.text = "🕌 SCAN QR MUSHOLA"
            tvScannerSub?.text = "Posisikan kamera ke QR Code Statik Mushola / Masjid Sekolah"
            tvClassIndicator.text = "🕌 Titik Presensi: Mushola / Masjid Sekolah"
            tvClassIndicator.setBackgroundColor(Color.parseColor("#059669"))
            tvStatusText.text = "🕌 Posisikan QR Code Mushola pada area kamera..."
        } else if (currentScanMode == MODE_ROOM_HANDOVER) {
            val topBarView = findViewById<LinearLayout>(R.id.topBar)
            topBarView?.setBackgroundResource(R.drawable.bg_matpel_header_gradient)
            tvScannerTitle?.text = "📱 SCAN QR RUANG KELAS"
            tvScannerSub?.text = "Posisikan kamera ke Stiker QR Ruang Kelas KBM"
            tvClassIndicator.text = "🏫 Estafet Sesi Mengajar Kelas Fisik"
            tvClassIndicator.setBackgroundColor(Color.parseColor("#4F46E5"))
            tvStatusText.text = "📱 Arahkan ke QR Ruang Kelas untuk membuka presensi KBM..."
        } else if (currentScanMode == MODE_BK_DYNAMIC_QR || currentScanMode == "BK_STATION") {
            tvScannerTitle?.text = "📱 SCAN DYNAMIC QR BK"
            tvScannerSub?.text = "Posisikan kamera ke Dynamic QR di Layar Guru BK"
            tvClassIndicator.text = "🕊️ Ruang Bimbingan Konseling (BK)"
            tvClassIndicator.setBackgroundColor(Color.parseColor("#059669"))
            tvStatusText.text = "📱 Posisikan Dynamic QR Guru BK pada kotak pemindai..."
        } else if (currentScanMode == MODE_SATPAM_GATEPASS) {
            tvScannerTitle?.text = "🛡️ POS SATPAM: CHECKOUT GERBANG"
            tvScannerSub?.text = "Pindai Single-Use QR Gatepass di HP Siswa"
            tvClassIndicator.text = "🚪 Pos Keamanan Gerbang Keluar"
            tvClassIndicator.setBackgroundColor(Color.parseColor("#DC2626"))
            tvStatusText.text = "🛡️ Posisikan QR Tiket Gatepass Siswa pada kamera..."
        } else {
            val className = sessionManager.getClassName().ifEmpty { "VII-A" }
            tvScannerTitle?.text = "Scan QR Pintu Kelas"
            tvScannerSub?.text = "Posisikan QR Code di dalam kotak"
            tvClassIndicator.text = "📍 Titik Presensi: Kelas $className"
        }

        btnBack.setOnClickListener { finish() }

        btnFlashToggle.setOnClickListener {
            toggleTorch()
        }
    }

    private fun toggleTorch() {
        camera?.let { cam ->
            if (cam.cameraInfo.hasFlashUnit()) {
                isTorchOn = !isTorchOn
                cam.cameraControl.enableTorch(isTorchOn)
                btnFlashToggle.setColorFilter(
                    if (isTorchOn) ContextCompat.getColor(this, R.color.amber_warning)
                    else ContextCompat.getColor(this, R.color.white)
                )
                Toast.makeText(this, if (isTorchOn) "Lampu Flash Menyala" else "Lampu Flash Mati", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Perangkat tidak memiliki lampu flash", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPermissionsAndStartCamera() {
        if (isScannerGeolocationActive && (currentScanMode == MODE_ATTENDANCE || currentScanMode == MODE_PRAYER || currentScanMode == MODE_ROOM_HANDOVER)) {
            if (!com.school.smartcbt.utils.GpsUtils.isHighAccuracyGpsEnabled(this)) {
                com.school.smartcbt.utils.GpsUtils.showGpsRequirementDialog(this) {
                    finish()
                }
                return
            }
        }

        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQ_CODE)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE) {
            val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (cameraGranted) {
                if (currentScanMode == MODE_ATTENDANCE || currentScanMode == MODE_PRAYER || currentScanMode == MODE_ROOM_HANDOVER) {
                    if (!com.school.smartcbt.utils.GpsUtils.isHighAccuracyGpsEnabled(this)) {
                        com.school.smartcbt.utils.GpsUtils.showGpsRequirementDialog(this) {
                            finish()
                        }
                        return
                    }
                }
                startCamera()
                startLocationUpdates()
            } else {
                tvStatusText.text = "⚠️ Izin kamera ditolak. Silakan berikan izin untuk memindai QR."
                Toast.makeText(this, "Izin kamera diperlukan untuk memindai QR Code presensi.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Preview with 1280x720 target resolution
                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .build()
                    .also {
                        it.setSurfaceProvider(viewFinder.surfaceProvider)
                    }

                // Image Analysis with 1280x720 resolution & keep latest frame
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcodeValue ->
                    if (!isProcessingScan) {
                        isProcessingScan = true
                        runOnUiThread {
                            handleScannedBarcode(barcodeValue)
                        }
                    }
                })

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                // Continuous Auto-Focus & Center Auto-Metering
                viewFinder.post {
                    try {
                        val factory = viewFinder.meteringPointFactory
                        val centerPoint = factory.createPoint(viewFinder.width / 2f, viewFinder.height / 2f)
                        val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        camera?.cameraControl?.startFocusAndMetering(action)
                    } catch (e: Exception) {
                        // Safe fallback
                    }
                }

                // Tap-to-focus support
                viewFinder.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        try {
                            val factory = viewFinder.meteringPointFactory
                            val point = factory.createPoint(event.x, event.y)
                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            camera?.cameraControl?.startFocusAndMetering(action)
                        } catch (e: Exception) {
                            // Safe fallback
                        }
                    }
                    true
                }

                runOnUiThread {
                    if (currentScanMode == MODE_BOOK_AUDIT) {
                        tvStatusText.text = "📚 Kamera Siap: Arahkan ke Barcode / ISBN Buku"
                    } else if (currentScanMode == MODE_EXAM_TOKEN) {
                        tvStatusText.text = "🔐 Kamera Siap: Arahkan ke QR Token Pengawas"
                    } else {
                        tvStatusText.text = "📷 Kamera Siap: Arahkan ke QR Code Presensi"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvStatusText.text = "Gagal mengaktifkan kamera: ${e.message}"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private class BarcodeAnalyzer(private val onBarcodeFound: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_EAN_13
            )
            .build()
        private val scanner = BarcodeScanning.getClient(options)

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            val value = barcode.rawValue
                            if (!value.isNullOrBlank()) {
                                onBarcodeFound(value)
                                break
                            }
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    private fun handleScannedBarcode(scannedCode: String) {
        vibratePhone()

        val scanMode = intent.getStringExtra(EXTRA_SCAN_MODE) ?: MODE_ATTENDANCE
        if (scanMode == MODE_EXAM_TOKEN) {
            val cleanToken = scannedCode.trim().uppercase()
            val resultIntent = android.content.Intent().apply {
                putExtra(EXTRA_SCANNED_TOKEN, cleanToken)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
            return
        }

        if (scanMode == MODE_ROOM_HANDOVER) {
            val cleanRoom = scannedCode.trim()
            val resultIntent = android.content.Intent().apply {
                putExtra(EXTRA_SCANNED_ROOM_CODE, cleanRoom)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
            return
        }

        if (scanMode == MODE_BK_DYNAMIC_QR || scanMode == "BK_STATION" || scannedCode.startsWith("SMARTCBT-BK-DYN:") || scannedCode.startsWith("BK-STATION-")) {
            val cleanToken = scannedCode.trim()
            val resultIntent = android.content.Intent().apply {
                putExtra(EXTRA_SCANNED_TOKEN, cleanToken)
                putExtra("SCANNED_TOKEN", cleanToken)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
            return
        }

        if (scanMode == MODE_SATPAM_GATEPASS || scannedCode.startsWith("SMARTCBT-GATEPASS:") || scannedCode.startsWith("SMARTCBT-EXITPASS:") || scannedCode.startsWith("GP-CHK-")) {
            handleSatpamGatepassScan(scannedCode)
            return
        }

        if (scanMode == MODE_BOOK_AUDIT) {
            handleBookAuditScan(scannedCode)
            return
        }

        if (scanMode == MODE_PRAYER || scannedCode.contains("SHOLAT", ignoreCase = true) || scannedCode.contains("MASJID", ignoreCase = true)) {
            handlePrayerScan(scannedCode)
            return
        }

        tvStatusText.text = "🔍 Terdeteksi: $scannedCode. Memproses..."

        // Jika Geolocation dinonaktifkan dari portal admin ATAU sedang menggunakan fallback QR Kelas:
        if (!isScannerGeolocationActive || isUsingQrFallback) {
            submitAttendance(scannedCode, null, null, false, method = "QR_STATIC", accuracy = null)
            return
        }

        val (lat, lng, isMock, accuracy) = getCurrentLocation()

        if (isMock) {
            val timeNow = SimpleDateFormat("HH:mm:ss", Locale("id", "ID")).format(Date()) + " WIB"
            showModernScanDialog(
                isSuccess = false,
                title = "🚨 Terdeteksi Fake GPS!",
                subtitle = "Manipulasi Lokasi Palsu Ditolak Sistem",
                message = "Aplikasi mendeteksi penggunaan Fake GPS / Mock Location pada perangkat Anda. Presensi DITOLAK demi integritas kedisiplinan sekolah. Silakan matikan Fake GPS dan gunakan lokasi nyata di sekolah!",
                gateType = "Lokasi Tidak Sah",
                timeStr = timeNow,
                lat = lat,
                lng = lng
            )
            return
        }

        // Cek Akurasi Multi-Zona:
        // Zona 1 (Presisi Baik ≤ 50m): Langsung diterima sah
        // Zona 2 (Terdegradasi 51m - 100m): Diterima sah dengan catatan
        // Zona 3 (> 100m atau GPS belum lock): Tampilkan tombol fallback QR Kelas!
        if (accuracy > zoneDegradedThreshold && currentScanMode != MODE_BOOK_AUDIT) {
            val timeNow = SimpleDateFormat("HH:mm:ss", Locale("id", "ID")).format(Date()) + " WIB"
            showQrFallbackDialog(accuracy, timeNow, scannedCode)
            return
        }

        if (accuracy > zoneGoodThreshold && accuracy <= zoneDegradedThreshold) {
            Toast.makeText(this, "⚠️ Akurasi GPS ±${accuracy.toInt()}m (kurang presisi, presensi tetap diproses sah)", Toast.LENGTH_LONG).show()
        }

        submitAttendance(scannedCode, lat, lng, isMock, method = "GPS", accuracy = accuracy)
    }

    private fun handleSatpamGatepassScan(scannedCode: String) {
        tvStatusText.text = "🛡️ Memverifikasi Gatepass Siswa..."
        val req = com.school.smartcbt.data.model.SatpamCheckoutRequest(
            checkoutToken = scannedCode,
            qrPayload = scannedCode
        )
        ApiClient.getClient(this).checkoutGatepassBySatpam(req).enqueue(object : Callback<BasicResponse> {
            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                val data = response.body()
                val isSuccess = response.isSuccessful && data?.success == true
                val timeNow = SimpleDateFormat("HH:mm:ss", Locale("id", "ID")).format(Date()) + " WIB"
                showModernScanDialog(
                    isSuccess = isSuccess,
                    title = if (isSuccess) "✅ IZIN PULANG SAH (SATPAM)" else "⛔ GATEPASS TIDAK SAH",
                    subtitle = if (isSuccess) "Siswa Diizinkan Melewati Gerbang" else "Verifikasi Gagal",
                    message = data?.message ?: if (isSuccess) "Siswa telah diverifikasi dan tercatat resmi keluar gerbang sekolah." else "Tiket tidak valid atau telah kedaluwarsa.",
                    gateType = "GATE_OUT (Gatepass)",
                    timeStr = timeNow
                )
            }
            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                Toast.makeText(this@ScannerActivity, "Gagal terhubung ke pos satpam: ${t.message}", Toast.LENGTH_SHORT).show()
                isProcessingScan = false
            }
        })
    }

    private fun handleBookAuditScan(barcode: String) {
        tvStatusText.text = "🔍 Memeriksa status kepemilikan buku: $barcode..."
        ApiClient.getClient(this).checkBookOwnerByBarcode(barcode).enqueue(object : Callback<com.school.smartcbt.data.model.BookOwnerCheckResponse> {
            override fun onResponse(
                call: Call<com.school.smartcbt.data.model.BookOwnerCheckResponse>,
                response: Response<com.school.smartcbt.data.model.BookOwnerCheckResponse>
            ) {
                val data = response.body()
                if (response.isSuccessful && data != null && data.success) {
                    val bookTitle = data.book?.title ?: "Buku Paket"
                    val copyCode = data.copy?.copyBarcode ?: barcode
                    val isMyBook = data.isMyBook

                    if (isMyBook) {
                        AlertDialog.Builder(this@ScannerActivity)
                            .setTitle("✅ BUKU PAKET ANDA")
                            .setMessage("Buku ini memang terdaftar resmi atas nama Anda!\n\n📘 Judul: $bookTitle\n🏷️ Barcode: $copyCode\nStatus: Terverifikasi (Tidak Tertukar).")
                            .setPositiveButton("Selesai") { _, _ -> finish() }
                            .setNeutralButton("Scan Buku Lain") { _, _ ->
                                isProcessingScan = false
                                tvStatusText.text = "Arahkan kamera ke Barcode buku berikutnya..."
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        val borrowerName = data.borrower?.name ?: "Siswa Lain"
                        val borrowerClass = data.borrower?.className ?: "-"
                        AlertDialog.Builder(this@ScannerActivity)
                            .setTitle("⚠️ BUKU PAKET TERTUKAR!")
                            .setMessage("Perhatian! Buku yang Anda bawa BUKAN milik Anda!\n\n📘 Judul: $bookTitle\n🏷️ Barcode: $copyCode\n\n👤 Peminjam Asli Tercatat:\n• Nama : $borrowerName\n• Kelas: $borrowerClass\n\nHarap tukarkan kembali dengan pemilik asli atau laporkan ke Petugas Perpustakaan/Operator untuk mutasi data.")
                            .setPositiveButton("Saya Mengerti") { _, _ -> finish() }
                            .setNeutralButton("Scan Lagi") { _, _ ->
                                isProcessingScan = false
                                tvStatusText.text = "Arahkan kamera ke Barcode buku lainnya..."
                            }
                            .setCancelable(false)
                            .show()
                    }
                } else {
                    val msg = data?.message ?: "Barcode $barcode tidak ditemukan dalam katalog perpustakaan sekolah."
                    AlertDialog.Builder(this@ScannerActivity)
                        .setTitle("❓ Barcode Tidak Dikenali")
                        .setMessage(msg)
                        .setPositiveButton("Scan Ulang") { _, _ ->
                            isProcessingScan = false
                            tvStatusText.text = "Arahkan kamera ke Barcode buku..."
                        }
                        .setNegativeButton("Tutup") { _, _ -> finish() }
                        .show()
                }
            }

            override fun onFailure(call: Call<com.school.smartcbt.data.model.BookOwnerCheckResponse>, t: Throwable) {
                Toast.makeText(this@ScannerActivity, "Gagal terhubung ke server: ${t.message}", Toast.LENGTH_SHORT).show()
                isProcessingScan = false
            }
        })
    }

    private fun handlePrayerScan(scannedCode: String) {
        tvStatusText.text = "🕌 Memproses Presensi Sholat Masjid..."
        val (lat, lng, isMock, accuracy) = getCurrentLocation()

        if (isMock) {
            val timeNow = SimpleDateFormat("HH:mm:ss", Locale("id", "ID")).format(Date()) + " WIB"
            showModernScanDialog(
                isSuccess = false,
                title = "🚨 Terdeteksi Fake GPS!",
                subtitle = "Manipulasi Lokasi Palsu Ditolak Sistem",
                message = "Aplikasi mendeteksi penggunaan Fake GPS / Mock Location pada perangkat Anda. Presensi Sholat DITOLAK demi integritas kedisiplinan sekolah!",
                gateType = "Lokasi Tidak Sah",
                timeStr = timeNow,
                lat = lat,
                lng = lng
            )
            return
        }

        if (accuracy > 15.0f) {
            val timeNow = SimpleDateFormat("HH:mm:ss", Locale("id", "ID")).format(Date()) + " WIB"
            showModernScanDialog(
                isSuccess = false,
                title = "⚠️ Sinyal Satelit Belum Presisi!",
                subtitle = "Akurasi Wajib ≤ 15 Meter",
                message = "Akurasi GPS saat ini ±${accuracy.toInt()} meter (melebihi batas maksimal 15 meter). Harap tunggu sinyal satelit terkunci presisi di area mushola sekolah.",
                gateType = "Akurasi ±${accuracy.toInt()}m (Belum Sah)",
                timeStr = timeNow,
                lat = lat,
                lng = lng
            )
            return
        }

        val body = mutableMapOf(
            "barcodeData" to scannedCode,
            "prayerType" to "DHUHUR"
        )
        if (lat != null && lng != null) {
            body["lat"] = lat.toString()
            body["lng"] = lng.toString()
        }
        if (isMock) {
            body["isFakeGps"] = "true"
        }

        ApiClient.getClient(this).studentScanPrayerBarcode(body).enqueue(object : Callback<BasicResponse> {
            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                val data = response.body()
                val timeNow = SimpleDateFormat("HH:mm:ss", Locale("id", "ID")).format(Date()) + " WIB"
                if (response.isSuccessful && data != null && data.success == true) {
                    showModernScanDialog(
                        isSuccess = true,
                        title = "🕌 Presensi Sholat Berhasil!",
                        subtitle = "Titik Presensi: Mushola SMPN 1 Boyolangu",
                        message = data.message ?: "Alhamdulillah, presensi sholat berjamaah di mushola berhasil dicatat.",
                        gateType = "Sholat Mushola",
                        timeStr = timeNow,
                        lat = lat,
                        lng = lng
                    )
                } else {
                    val err = data?.message ?: response.errorBody()?.string() ?: "Gagal memproses presensi sholat"
                    val cleanMsg = try {
                        org.json.JSONObject(err).optString("message", err)
                    } catch (e: Exception) { err }
                    val finalMsg = if (cleanMsg.contains("haid", ignoreCase = true) || cleanMsg.contains("halangan", ignoreCase = true)) {
                        cleanMsg
                    } else {
                        "$cleanMsg\n\n🌸 Catatan: Siswi perempuan yang berhalangan (haid) wajib melapor langsung ke Guru PAI kelas Anda untuk didata absen halangan resmi."
                    }
                    showModernScanDialog(
                        isSuccess = false,
                        title = "⚠️ Presensi Sholat Ditolak",
                        subtitle = "Jadwal Resmi Admin / Guru PAI (Bukan 24 Jam)",
                        message = finalMsg,
                        gateType = "Titik Mushola",
                        timeStr = timeNow,
                        lat = lat,
                        lng = lng
                    )
                }
            }

            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                Toast.makeText(this@ScannerActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
                isProcessingScan = false
            }
        })
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    private fun fetchServerAttendanceConfig() {
        ApiClient.getClient(this).getAttendanceConfig().enqueue(object : Callback<com.google.gson.JsonObject> {
            override fun onResponse(call: Call<com.google.gson.JsonObject>, response: Response<com.google.gson.JsonObject>) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    isScannerGeolocationActive = body.get("scannerGeolocationEnabled")?.asBoolean ?: true
                    val school = body.getAsJsonObject("school")
                    if (school != null) {
                        schoolLat = school.get("lat")?.asDouble ?: -8.125506
                        schoolLng = school.get("lng")?.asDouble ?: 111.893526
                        radiusGlobal = school.get("radiusMeters")?.asInt ?: 150
                    }
                    val thresholds = body.getAsJsonObject("gpsThresholds")
                    if (thresholds != null) {
                        zoneGoodThreshold = thresholds.get("zoneGood")?.asFloat ?: 50.0f
                        zoneDegradedThreshold = thresholds.get("zoneDegraded")?.asFloat ?: 100.0f
                        countdownSeconds = thresholds.get("countdownSeconds")?.asInt ?: 60
                    }

                    runOnUiThread {
                        if (!isScannerGeolocationActive) {
                            stopLocationUpdates()
                            layoutLiveGpsBadge?.visibility = View.GONE
                            tvGpsAccuracySub?.visibility = View.GONE
                            tvStatusText.text = "📷 Mode QR-Only Murni: Scan QR Code di pintu/ruang kelas Anda"
                            tvStatusText.setTextColor(Color.parseColor("#38BDF8"))
                        }
                    }
                }
            }
            override fun onFailure(call: Call<com.google.gson.JsonObject>, t: Throwable) {}
        })
    }

    private fun startGpsCountdown() {
        if (!isScannerGeolocationActive) return
        countdownTimer?.cancel()
        countdownTimer = object : android.os.CountDownTimer((countdownSeconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = millisUntilFinished / 1000
                if (liveBestLocation == null || (liveBestLocation?.accuracy ?: 999f) > zoneGoodThreshold) {
                    tvGpsAccuracySub?.text = "Mencari satelit GPS... ($sec dtk tersisa)"
                }
            }

            override fun onFinish() {
                if (liveBestLocation == null || (liveBestLocation?.accuracy ?: 999f) > zoneGoodThreshold) {
                    tvStatusText.text = "⚠️ Satelit GPS belum terkunci stabil. Silakan gunakan Scan QR Kelas."
                    tvStatusText.setTextColor(Color.parseColor("#F59E0B"))
                    tvGpsAccuracySub?.text = "💡 Tips: Anda dapat langsung scan QR di dinding kelas"
                }
            }
        }.start()
    }

    private fun showQrFallbackDialog(accuracy: Float, timeNow: String, scannedCode: String) {
        val accText = if (accuracy >= 999f) "Belum terkunci" else "±${accuracy.toInt()}m"
        AlertDialog.Builder(this)
            .setTitle("⚠️ GPS Satelit Belum Presisi ($accText)")
            .setMessage("Sinyal satelit GPS di posisi Anda saat ini belum presisi (Batas toleransi sah: ≤ 50m).\n\nJika Anda berada di dalam ruangan kelas, Anda dapat menggunakan mode 'Scan QR Kelas' untuk presensi fisik.")
            .setPositiveButton("📷 Scan QR Kelas") { _, _ ->
                isUsingQrFallback = true
                tvStatusText.text = "📷 Mode QR Kelas Aktif: Scan QR di dinding kelas Anda"
                tvStatusText.setTextColor(Color.parseColor("#38BDF8"))
                submitAttendance(scannedCode, null, null, false, method = "QR_STATIC", accuracy = null)
            }
            .setNegativeButton("Tunggu GPS") { dialog, _ ->
                dialog.dismiss()
                isProcessingScan = false
            }
            .setCancelable(false)
            .show()
    }

    private fun startLocationUpdates() {
        if (!isScannerGeolocationActive) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            // HANYA daftarkan GPS_PROVIDER (satelit hardware)
            // JANGAN daftarkan NETWORK_PROVIDER agar tidak menggunakan koordinat BTS seluler berjarak 3km!
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0.5f,
                    liveLocationListener
                )
            }

            val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastGps != null && (System.currentTimeMillis() - lastGps.time < 60_000)) {
                liveBestLocation = lastGps
                updateGpsIndicatorUI(lastGps)
            } else {
                updateGpsIndicatorUI(null)
            }

            startGpsCountdown()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopLocationUpdates() {
        try {
            countdownTimer?.cancel()
            locationManager?.removeUpdates(liveLocationListener)
        } catch (e: Exception) {}
    }

    private fun updateGpsIndicatorUI(loc: Location?) {
        if (currentScanMode == MODE_BOOK_AUDIT) return
        if (!isScannerGeolocationActive) {
            layoutLiveGpsBadge?.visibility = View.GONE
            tvGpsAccuracySub?.visibility = View.GONE
            return
        }

        if (loc != null) {
            val dist = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, schoolLat, schoolLng, dist)
            val distMeters = dist[0].toInt()
            val acc = loc.accuracy.toInt()

            val coordStr = String.format(Locale.US, "%.6f, %.6f", loc.latitude, loc.longitude)
            tvLiveGpsCoords?.text = "📍 $coordStr"
            layoutLiveGpsBadge?.visibility = View.VISIBLE

            if (acc <= zoneGoodThreshold.toInt()) {
                tvGpsAccuracySub?.text = "Akurasi: ±${acc}m (Presisi Baik ≤ ${zoneGoodThreshold.toInt()}m) • Jarak: ${distMeters}m"
                tvGpsAccuracySub?.setTextColor(Color.parseColor("#10B981"))
                tvStatusText.text = "🟢 GPS Satelit Terkunci: $coordStr (±${acc}m)"
                tvStatusText.setTextColor(Color.parseColor("#10B981"))
            } else if (acc <= zoneDegradedThreshold.toInt()) {
                tvGpsAccuracySub?.text = "Akurasi: ±${acc}m (Terdegradasi tapi Diterima) • Jarak: ${distMeters}m"
                tvGpsAccuracySub?.setTextColor(Color.parseColor("#F59E0B"))
                tvStatusText.text = "🟡 GPS Cukup: ±${acc}m (Bisa Memindai Barcode)"
                tvStatusText.setTextColor(Color.parseColor("#F59E0B"))
            } else {
                tvGpsAccuracySub?.text = "Akurasi: ±${acc}m (Satelit Lemah > ${zoneDegradedThreshold.toInt()}m) • Jarak: ${distMeters}m"
                tvGpsAccuracySub?.setTextColor(Color.parseColor("#EF4444"))
                tvStatusText.text = "⚠️ Akurasi Rendah (±${acc}m). Disarankan Scan QR Kelas."
                tvStatusText.setTextColor(Color.parseColor("#EF4444"))
            }
        } else {
            tvLiveGpsCoords?.text = "📍 Mencari Sinyal Satelit GPS..."
            tvGpsAccuracySub?.text = "Mengunci koordinat satelit HP (Wajib ≤ ${zoneGoodThreshold.toInt()}m)..."
            tvGpsAccuracySub?.setTextColor(Color.parseColor("#F59E0B"))
            tvStatusText.text = "🟡 Mengunci Sinyal Satelit GPS HP..."
            tvStatusText.setTextColor(Color.parseColor("#FBBF24"))
        }
    }

    private fun getCurrentLocation(): LocationResult {
        if (!isScannerGeolocationActive) {
            return LocationResult(null, null, false, 0f)
        }

        var loc = liveBestLocation

        if (loc == null && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            // Hanya gunakan jika satelit hardware valid & masih segar (< 60 detik)
            if (lastGps != null && (System.currentTimeMillis() - lastGps.time < 60_000)) {
                loc = lastGps
            }
        }

        if (loc != null) {
            val isMockLoc = com.school.smartcbt.utils.GpsUtils.isFakeGps(this, loc)
            val actualLat = loc.latitude
            val actualLng = loc.longitude
            val accuracy = loc.accuracy
            val coordStr = String.format(Locale.US, "%.6f, %.6f", actualLat, actualLng)

            runOnUiThread {
                tvLiveGpsCoords?.text = "📍 $coordStr"
                if (accuracy <= zoneGoodThreshold) {
                    tvStatusText.text = "🟢 GPS HP Presisi: $coordStr (±${accuracy.toInt()}m)"
                    tvStatusText.setTextColor(Color.parseColor("#10B981"))
                } else if (accuracy <= zoneDegradedThreshold) {
                    tvStatusText.text = "🟡 GPS Terdegradasi: ±${accuracy.toInt()}m (Masih Sah)"
                    tvStatusText.setTextColor(Color.parseColor("#F59E0B"))
                } else {
                    tvStatusText.text = "⚠️ Satelit Lemah (±${accuracy.toInt()}m). Gunakan QR Kelas."
                    tvStatusText.setTextColor(Color.parseColor("#EF4444"))
                }
            }

            return LocationResult(actualLat, actualLng, isMockLoc, accuracy)
        }

        return LocationResult(null, null, false, 999.0f)
    }

    private fun vibratePhone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(150)
            }
        } catch (e: Exception) {
            // Safe fallback
        }
    }

    private fun submitAttendance(
        code: String,
        lat: Double?,
        lng: Double?,
        isFakeGps: Boolean,
        method: String = "GPS",
        accuracy: Float? = null
    ) {
        val sNisn = sessionManager.getNisn().ifEmpty { sessionManager.getUsername() }
        val devTime = SimpleDateFormat("HH:mm", Locale("id", "ID")).format(Date())
        val isGateOut = code.contains("OUT", true) || code.contains("PULANG", true)
        val gateText = if (isGateOut) "Gate-Out (Kepulangan)" else "Gate-In (Masuk)"
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale("id", "ID")).format(Date()) + " WIB"

        val req = GateScanRequest(
            gateCode = code,
            barcodeCode = code,
            nisn = sNisn,
            studentIdentifier = sNisn,
            lat = lat,
            lng = lng,
            isFakeGps = isFakeGps,
            method = method,
            accuracy = accuracy,
            deviceTime = devTime
        )

        // CEK KONEKSI: Jika HP siswa tidak memiliki paket data internet, simpan langsung ke Room DB lokal HP!
        if (!com.school.smartcbt.utils.AttendanceOfflineManager.isOnline(this)) {
            com.school.smartcbt.utils.OfflineAttendanceManager.savePendingScan(this, req)

            showModernScanDialog(
                isSuccess = true,
                title = "⏳ Presensi Tersimpan Offline",
                subtitle = "Tersimpan Sah di Database HP",
                message = "HP Anda saat ini tidak memiliki koneksi internet. Data presensi Anda telah tercatat sah di memori lokal HP dan akan otomatis dikirimkan ke server sekolah begitu HP terhubung ke internet.",
                gateType = gateText,
                timeStr = timeNow,
                lat = lat,
                lng = lng
            )
            return
        }

        ApiClient.getClient(this).scanGateAttendance(req)
            .enqueue(object : Callback<BasicResponse> {
                override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                    val sName = sessionManager.getName().ifEmpty { "Siswa" }
                    val sClass = sessionManager.getClassName().ifEmpty { "VII-A" }

                    if (response.isSuccessful) {
                        val msg = response.body()?.message ?: "Presensi Berhasil Dicatat!"
                        NotificationHelper.showHeadsUpNotification(
                            this@ScannerActivity,
                            "✅ Presensi Berhasil Dicatat!",
                            "$sName (Kelas $sClass) berhasil scan barcode: $msg",
                            "Presensi SMPN 1 Boyolangu"
                        )

                        showModernScanDialog(
                            isSuccess = true,
                            title = "✅ Presensi Berhasil!",
                            subtitle = "Presensi $gateText Berhasil Diverifikasi",
                            message = msg,
                            gateType = gateText,
                            timeStr = timeNow,
                            lat = lat,
                            lng = lng
                        )
                    } else {
                        val rawErr = response.errorBody()?.string() ?: ""
                        var userMsg = "Presensi Ditolak Server"
                        try {
                            val json = org.json.JSONObject(rawErr)
                            if (json.has("message")) {
                                userMsg = json.getString("message")
                            }
                        } catch (e: Exception) {
                            if (rawErr.isNotEmpty()) userMsg = rawErr
                        }

                        val explanation = parseScanFailureExplanation(userMsg, code)
                        val formattedMsg = "📌 Keterangan:\n${explanation.reason}\n\n💡 Saran Tindakan:\n${explanation.suggestion}"

                        showModernScanDialog(
                            isSuccess = false,
                            title = explanation.title,
                            subtitle = explanation.subtitle,
                            message = formattedMsg,
                            gateType = gateText,
                            timeStr = timeNow,
                            lat = lat,
                            lng = lng
                        )
                    }
                }

                override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                    // Simpan ke antrean lokal Room DB saat terjadi kegagalan jaringan
                    com.school.smartcbt.utils.OfflineAttendanceManager.savePendingScan(this@ScannerActivity, req)

                    showModernScanDialog(
                        isSuccess = true,
                        title = "⏳ Presensi Disimpan Offline",
                        subtitle = "Koneksi Terputus - Tersimpan di HP",
                        message = "Koneksi ke server sekolah terputus saat mengirim data. Presensi Anda telah diamankan di database lokal HP dan akan disinkronkan otomatis saat jaringan kembali normal.",
                        gateType = gateText,
                        timeStr = timeNow,
                        lat = lat,
                        lng = lng
                    )
                }
            })
    }

    private fun showModernScanDialog(
        isSuccess: Boolean,
        title: String,
        subtitle: String,
        message: String,
        gateType: String,
        timeStr: String,
        lat: Double? = null,
        lng: Double? = null
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_scan_result, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val layoutHeaderBanner = dialogView.findViewById<LinearLayout>(R.id.layoutHeaderBanner)
        val tvScanResultIcon = dialogView.findViewById<TextView>(R.id.tvScanResultIcon)
        val tvScanResultTitle = dialogView.findViewById<TextView>(R.id.tvScanResultTitle)
        val tvScanResultSubtitle = dialogView.findViewById<TextView>(R.id.tvScanResultSubtitle)
        val ivStudentPhoto = dialogView.findViewById<ImageView>(R.id.ivStudentPhoto)
        val tvStudentAvatar = dialogView.findViewById<TextView>(R.id.tvStudentAvatar)
        val tvStudentName = dialogView.findViewById<TextView>(R.id.tvStudentName)
        val tvStudentClassNisn = dialogView.findViewById<TextView>(R.id.tvStudentClassNisn)
        val tvScanTime = dialogView.findViewById<TextView>(R.id.tvScanTime)
        val tvGateTypeBadge = dialogView.findViewById<TextView>(R.id.tvGateTypeBadge)
        val tvGpsStatus = dialogView.findViewById<TextView>(R.id.tvGpsStatus)
        val layoutParentNotifBadge = dialogView.findViewById<LinearLayout>(R.id.layoutParentNotifBadge)
        val tvParentNotifText = dialogView.findViewById<TextView>(R.id.tvParentNotifText)
        val tvScanResultMessage = dialogView.findViewById<TextView>(R.id.tvScanResultMessage)
        val btnSecondary = dialogView.findViewById<Button>(R.id.btnScanResultSecondary)
        val btnPrimary = dialogView.findViewById<Button>(R.id.btnScanResultPrimary)

        val sName = sessionManager.getName().ifEmpty { "Siswa" }
        val sClass = sessionManager.getClassName().ifEmpty { "VII-A" }
        val sNisn = sessionManager.getNisn().ifEmpty { sessionManager.getUsername() }

        tvStudentName.text = sName
        tvStudentClassNisn.text = "Kelas $sClass • NISN: $sNisn"
        tvStudentAvatar.text = if (sName.isNotEmpty()) sName.take(1).uppercase() else "S"
        tvScanTime.text = timeStr
        tvGateTypeBadge.text = gateType
        tvScanResultMessage.text = message

        if (lat != null && lng != null) {
            tvGpsStatus?.text = String.format(Locale.US, "%.6f, %.6f", lat, lng)
        } else {
            tvGpsStatus?.text = "-"
        }

        val picUrl = sessionManager.getProfilePicUrl()
        if (!picUrl.isNullOrEmpty() && ivStudentPhoto != null) {
            val baseServer = sessionManager.getServerIp().trimEnd('/')
            val fullUrl = if (picUrl.startsWith("http")) picUrl else "$baseServer${if (picUrl.startsWith("/")) "" else "/"}$picUrl"
            loadStudentPhotoAsync(fullUrl, ivStudentPhoto, tvStudentAvatar)
        } else {
            ivStudentPhoto?.setImageResource(R.drawable.ic_avatar_placeholder)
        }

        if (isSuccess) {
            val isMushola = gateType.contains("mushola", ignoreCase = true) || gateType.contains("sholat", ignoreCase = true)
            val isMatpel = gateType.contains("matpel", ignoreCase = true) || gateType.contains("pelajaran", ignoreCase = true) || gateType.contains("kbm", ignoreCase = true)
            if (isMushola) {
                layoutHeaderBanner.setBackgroundResource(R.drawable.bg_mushola_header_gradient)
                tvScanResultIcon.text = "🕌"
                tvScanResultIcon.setTextColor(Color.parseColor("#059669"))
                tvScanResultTitle.text = title
                tvScanResultSubtitle.text = subtitle
                tvGateTypeBadge.text = "🕌 PRESENSI MUSHOLA"
                tvGateTypeBadge.setBackgroundColor(Color.parseColor("#D1FAE5"))
                tvGateTypeBadge.setTextColor(Color.parseColor("#065F46"))
                btnPrimary.setBackgroundColor(Color.parseColor("#059669"))
                btnPrimary.text = "Alhamdulillah, Selesai"
                layoutParentNotifBadge.visibility = View.VISIBLE
                tvParentNotifText.text = "🕌 Presensi Sholat Terhubung ke SIAKAD & Buku Pantau Ibadah"
            } else if (isMatpel) {
                layoutHeaderBanner.setBackgroundResource(R.drawable.bg_matpel_header_gradient)
                tvScanResultIcon.text = "📚"
                tvScanResultIcon.setTextColor(Color.parseColor("#4F46E5"))
                tvScanResultTitle.text = title
                tvScanResultSubtitle.text = subtitle
                tvGateTypeBadge.text = "🎯 PRESENSI MAPEL"
                tvGateTypeBadge.setBackgroundColor(Color.parseColor("#EEF2FF"))
                tvGateTypeBadge.setTextColor(Color.parseColor("#3730A3"))
                btnPrimary.setBackgroundColor(Color.parseColor("#4F46E5"))
                btnPrimary.text = "Alhamdulillah, Selesai"
                layoutParentNotifBadge.visibility = View.VISIBLE
                tvParentNotifText.text = "📚 Presensi Mata Pelajaran Terhubung Langsung ke Guru & Portal SIAKAD"
            } else {
                layoutHeaderBanner.setBackgroundResource(R.drawable.bg_biodata_header_gradient)
                tvScanResultIcon.text = "✓"
                tvScanResultIcon.setTextColor(Color.parseColor("#059669"))
                tvScanResultTitle.text = title
                tvScanResultSubtitle.text = subtitle
                btnPrimary.setBackgroundColor(Color.parseColor("#059669"))
                btnPrimary.text = "Selesai & Tutup"
                layoutParentNotifBadge.visibility = View.VISIBLE
                tvParentNotifText.text = "📱 Notifikasi Terkirim ke Portal & WhatsApp Orang Tua"
            }
            btnSecondary.visibility = View.GONE

            btnPrimary.setOnClickListener {
                dialog.dismiss()
                finish()
            }
        } else {
            layoutHeaderBanner.setBackgroundColor(Color.parseColor("#DC2626"))
            tvScanResultIcon.text = "⚠️"
            tvScanResultIcon.setTextColor(Color.parseColor("#DC2626"))
            tvScanResultTitle.text = title
            tvScanResultSubtitle.text = subtitle
            tvGateTypeBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
            tvGateTypeBadge.setTextColor(Color.parseColor("#991B1B"))
            btnPrimary.setBackgroundColor(Color.parseColor("#DC2626"))
            btnPrimary.text = "Tutup"
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = "Scan Ulang"
            layoutParentNotifBadge.visibility = View.GONE

            btnPrimary.setOnClickListener {
                dialog.dismiss()
                finish()
            }
            btnSecondary.setOnClickListener {
                dialog.dismiss()
                isProcessingScan = false
                tvStatusText.text = "📷 Kamera Siap: Arahkan ke QR Code Presensi"
            }
        }

        dialog.show()
    }

    private val studentPhotoCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    private fun loadStudentPhotoAsync(url: String, imageView: ImageView, fallbackText: TextView) {
        imageView.tag = url
        val cached = studentPhotoCache[url]
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            imageView.visibility = View.VISIBLE
            fallbackText.visibility = View.GONE
            return
        }

        cameraExecutor.execute {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doInput = true
                conn.connect()
                val bytes = conn.inputStream.use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    studentPhotoCache[url] = bmp
                    runOnUiThread {
                        if (imageView.tag == url) {
                            imageView.setImageBitmap(bmp)
                            imageView.visibility = View.VISIBLE
                            fallbackText.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (imageView.tag == url) {
                        imageView.setImageResource(R.drawable.ic_avatar_placeholder)
                    }
                }
            }
        }
    }

    data class ScanFailureExplanation(
        val title: String,
        val subtitle: String,
        val reason: String,
        val suggestion: String
    )

    private fun parseScanFailureExplanation(serverMsg: String, code: String): ScanFailureExplanation {
        val lower = serverMsg.lowercase(Locale.ROOT)
        return when {
            lower.contains("sudah melakukan presensi") || lower.contains("1x per hari") || lower.contains("already") -> {
                ScanFailureExplanation(
                    title = "⚠️ SUDAH TERCATAT HARI INI",
                    subtitle = "Presensi Masuk 1x Saja Per Hari",
                    reason = "Data presensi kehadiran Anda untuk hari ini sudah tersimpan resmi di database. Sistem membatasi scan masuk satu kali per hari agar rekapitulasi data tidak terduplikasi.",
                    suggestion = "Anda tidak perlu melakukan pemindaian masuk lagi. Silakan bersiap mengikuti KBM di kelas dan tunggu waktu kepulangan sore hari untuk scan pulang."
                )
            }
            lower.contains("belum dibuka") || lower.contains("jadwal belum") -> {
                ScanFailureExplanation(
                    title = "⛔ GERBANG PRESENSI BELUM DIBUKA",
                    subtitle = "Di Luar Jadwal Presensi Masuk",
                    reason = "Sesi pemindaian presensi pagi belum dibuka oleh server sekolah (Jam masuk resmi gerbang sekolah: 06:15 - 07:00 WIB).",
                    suggestion = "Silakan tunggu beberapa saat hingga server membuka gerbang presensi sesuai jam operasional sekolah."
                )
            }
            lower.contains("terkunci") || lower.contains("ditutup") || lower.contains("terlambat") || lower.contains("lock") -> {
                ScanFailureExplanation(
                    title = "🔒 GERBANG PRESENSI TERKUNCI / TUTUP",
                    subtitle = "Batas Jam Masuk Telah Berakhir",
                    reason = "Gerbang presensi mandiri telah ditutup karena batas toleransi jam masuk KBM telah berakhir (Pukul 07:00 WIB).",
                    suggestion = "Segera melapor ke Guru Piket di lobi utama atau Ruang BK untuk mendapatkan pendataan presensi manual dan kartu izin masuk kelas."
                )
            }
            lower.contains("darurat") || lower.contains("pulang lebih awal") || lower.contains("belum waktunya pulang") -> {
                ScanFailureExplanation(
                    title = "⛔ BELUM WAKTUNYA KEPULANGAN",
                    subtitle = "KBM Masih Berlangsung",
                    reason = "Jam KBM sekolah masih berjalan aktif. Siswa tidak diizinkan memindai barcode kepulangan tanpa izin resmi.",
                    suggestion = "Jika Anda memiliki keperluan mendesak atau sakit, silakan ajukan izin melalui menu Perizinan (Gatepass) di aplikasi dan temui Guru BK."
                )
            }
            lower.contains("zona") || lower.contains("radius") || lower.contains("jarak") || lower.contains("geofence") -> {
                ScanFailureExplanation(
                    title = "📍 LOKASI DI LUAR AREA KELAS",
                    subtitle = "Radius GPS Melebihi Toleransi (Maks 10m)",
                    reason = "Posisi koordinat GPS HP Anda terdeteksi berada di luar jangkauan titik presensi kelas resmi SMPN 1 Boyolangu.",
                    suggestion = "Berdirilah tepat di depan pintu ruang kelas Anda dan pastikan indikator GPS HP sudah terkunci berwarna hijau (akurasi ≤ 15 meter)."
                )
            }
            lower.contains("kelas lain") || lower.contains("bukan kelas") || lower.contains("barcode tidak sesuai") -> {
                ScanFailureExplanation(
                    title = "❌ BARCODE KELAS TIDAK COCOK",
                    subtitle = "Ruang Kelas Berbeda",
                    reason = "Barcode yang Anda pindai terdaftar untuk ruangan atau kelas lain, bukan kelas Anda.",
                    suggestion = "Periksa papan nama kelas di atas pintu dan pastikan memindai barcode yang terpasang di depan ruang kelas Anda sendiri."
                )
            }
            lower.contains("fake gps") || lower.contains("mock") || lower.contains("palsu") -> {
                ScanFailureExplanation(
                    title = "🚨 MANIPULASI LOKASI PALSU DITOLAK",
                    subtitle = "Terdeteksi Aplikasi Fake GPS",
                    reason = "Sistem keamanan mendeteksi penggunaan aplikasi manipulasi GPS (Mock Provider) pada perangkat Anda.",
                    suggestion = "Matikan aplikasi Fake GPS dan opsi pengembang (Developer Options) di pengaturan HP Anda, lalu gunakan sinyal satelit asli."
                )
            }
            lower.contains("gatepass") || lower.contains("izin") -> {
                ScanFailureExplanation(
                    title = "ℹ️ STATUS IZIN PULANG AKTIF",
                    subtitle = "Gatepass Sedang Berjalan",
                    reason = "Anda memiliki tiket izin kepulangan darurat resmi (Gatepass) yang telah disetujui Guru BK.",
                    suggestion = "Tunjukkan barcode kartu Digital Gatepass kepada Satpam di pos gerbang utama sekolah untuk checkout."
                )
            }
            else -> {
                ScanFailureExplanation(
                    title = "⚠️ VERIFIKASI PRESENSI GAGAL",
                    subtitle = "Server Menolak Pemindaian",
                    reason = serverMsg.ifBlank { "Server sekolah tidak dapat memvalidasi barcode yang dipindai ($code)." },
                    suggestion = "Pastikan barcode dalam kondisi bersih dan pencahayaan cukup. Jika kendala berlanjut, hubungi Guru Piket atau Operator CBT."
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockTicker)
        cameraExecutor.shutdown()
    }
}

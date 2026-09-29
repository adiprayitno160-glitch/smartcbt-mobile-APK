package com.school.smartcbt.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.school.smartcbt.R
import com.school.smartcbt.ScannerActivity
import com.school.smartcbt.data.model.*
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.utils.QrCodeHelper
import com.school.smartcbt.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ExitPassActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    // UI Elements - Forms
    private lateinit var cardFormApply: CardView
    private lateinit var rgLeaveType: RadioGroup
    private lateinit var rbSakitPulang: RadioButton
    private lateinit var rbIzinMendesak: RadioButton
    private lateinit var rbDispensasi: RadioButton
    private lateinit var tvLabelEventName: TextView
    private lateinit var etEventName: EditText
    private lateinit var etReason: EditText
    private lateinit var etPickupPerson: EditText
    private lateinit var btnSubmitLeave: Button

    // UI Elements - Active Status
    private lateinit var cardActiveStatus: CardView
    private lateinit var layoutStatusBanner: LinearLayout
    private lateinit var tvStatusIcon: TextView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var tvActiveLeaveType: TextView
    private lateinit var tvActiveReason: TextView
    private lateinit var tvActivePickup: TextView
    private lateinit var tvCounselorNotes: TextView
    private lateinit var btnScanBkStation: Button
    private lateinit var btnCheckApprovalStatus: Button

    // UI Elements - Digital Exit Pass
    private lateinit var cardDigitalExitPass: CardView
    private lateinit var tvExitPassLiveClock: TextView
    private lateinit var layoutCountdownTimer: LinearLayout
    private lateinit var tvExitPassCountdown: TextView
    private lateinit var ivExitPassStudentPhoto: ImageView
    private lateinit var tvExitPassStudentName: TextView
    private lateinit var tvExitPassStudentClass: TextView
    private lateinit var tvExitPassType: TextView
    private lateinit var tvExitPassReason: TextView
    private lateinit var tvExitPassPickup: TextView
    private lateinit var tvExitPassStation: TextView
    private lateinit var tvExitPassCheckoutTime: TextView
    private lateinit var ivExitPassQrBitmap: ImageView
    private lateinit var tvExitPassQrPayload: TextView

    private var activeLeaveId: String? = null
    private var isClockRunning = false
    private var targetExpiryMillis: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private val photoCache = ConcurrentHashMap<String, Bitmap>()

    companion object {
        private const val REQUEST_CODE_SCAN_BK = 3012
    }

    private val liveClockRunnable = object : Runnable {
        override fun run() {
            if (isClockRunning) {
                val sdf = SimpleDateFormat("HH:mm:ss 'WIB'", Locale("id", "ID"))
                tvExitPassLiveClock.text = sdf.format(Date())

                // Update countdown
                if (targetExpiryMillis > 0) {
                    val diff = targetExpiryMillis - System.currentTimeMillis()
                    if (diff > 0) {
                        val min = (diff / 1000) / 60
                        val sec = (diff / 1000) % 60
                        tvExitPassCountdown.text = String.format("%02d:%02d Menit Tersisa", min, sec)
                        layoutCountdownTimer.setBackgroundColor(Color.parseColor("#047857"))
                    } else {
                        tvExitPassCountdown.text = "⏳ TIKET EXPIRED (45 Menit Habis)"
                        layoutCountdownTimer.setBackgroundColor(Color.parseColor("#DC2626"))
                    }
                }

                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exit_pass)

        sessionManager = SessionManager(this)
        initViews()
        setupListeners()
        fetchActiveLeave()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnBackExitPass).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnRefreshExitPass).setOnClickListener {
            Toast.makeText(this, "Memperbarui status gatepass...", Toast.LENGTH_SHORT).show()
            fetchActiveLeave()
        }

        // Form
        cardFormApply = findViewById(R.id.cardFormApply)
        rgLeaveType = findViewById(R.id.rgLeaveType)
        rbSakitPulang = findViewById(R.id.rbSakitPulang)
        rbIzinMendesak = findViewById(R.id.rbIzinMendesak)
        rbDispensasi = findViewById(R.id.rbDispensasi)
        tvLabelEventName = findViewById(R.id.tvLabelEventName)
        etEventName = findViewById(R.id.etEventName)
        etReason = findViewById(R.id.etReason)
        etPickupPerson = findViewById(R.id.etPickupPerson)
        btnSubmitLeave = findViewById(R.id.btnSubmitLeave)

        // Status
        cardActiveStatus = findViewById(R.id.cardActiveStatus)
        layoutStatusBanner = findViewById(R.id.layoutStatusBanner)
        tvStatusIcon = findViewById(R.id.tvStatusIcon)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)
        tvActiveLeaveType = findViewById(R.id.tvActiveLeaveType)
        tvActiveReason = findViewById(R.id.tvActiveReason)
        tvActivePickup = findViewById(R.id.tvActivePickup)
        tvCounselorNotes = findViewById(R.id.tvCounselorNotes)
        btnScanBkStation = findViewById(R.id.btnScanBkStation)
        btnCheckApprovalStatus = findViewById(R.id.btnCheckApprovalStatus)

        // Digital Exit Pass (#10B981)
        cardDigitalExitPass = findViewById(R.id.cardDigitalExitPass)
        tvExitPassLiveClock = findViewById(R.id.tvExitPassLiveClock)
        layoutCountdownTimer = findViewById(R.id.layoutCountdownTimer)
        tvExitPassCountdown = findViewById(R.id.tvExitPassCountdown)
        ivExitPassStudentPhoto = findViewById(R.id.ivExitPassStudentPhoto)
        tvExitPassStudentName = findViewById(R.id.tvExitPassStudentName)
        tvExitPassStudentClass = findViewById(R.id.tvExitPassStudentClass)
        tvExitPassType = findViewById(R.id.tvExitPassType)
        tvExitPassReason = findViewById(R.id.tvExitPassReason)
        tvExitPassPickup = findViewById(R.id.tvExitPassPickup)
        tvExitPassStation = findViewById(R.id.tvExitPassStation)
        tvExitPassCheckoutTime = findViewById(R.id.tvExitPassCheckoutTime)
        ivExitPassQrBitmap = findViewById(R.id.ivExitPassQrBitmap)
        tvExitPassQrPayload = findViewById(R.id.tvExitPassQrPayload)
    }

    private fun setupListeners() {
        rgLeaveType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbDispensasi) {
                tvLabelEventName.visibility = View.VISIBLE
                etEventName.visibility = View.VISIBLE
            } else {
                tvLabelEventName.visibility = View.GONE
                etEventName.visibility = View.GONE
            }
        }

        btnSubmitLeave.setOnClickListener {
            submitLeaveApplication()
        }

        btnCheckApprovalStatus.setOnClickListener {
            fetchActiveLeave()
        }

        btnScanBkStation.setOnClickListener {
            launchBkQrScanner()
        }
    }

    private fun launchBkQrScanner() {
        try {
            val intent = Intent(this, ScannerActivity::class.java).apply {
                putExtra(ScannerActivity.EXTRA_SCAN_MODE, ScannerActivity.MODE_BK_DYNAMIC_QR)
                putExtra("GATEPASS_ID", activeLeaveId)
            }
            startActivityForResult(intent, REQUEST_CODE_SCAN_BK)
        } catch (e: Exception) {
            showManualScanDialog()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCAN_BK && resultCode == Activity.RESULT_OK) {
            val token = data?.getStringExtra(ScannerActivity.EXTRA_SCANNED_TOKEN)
                ?: data?.getStringExtra("SCANNED_TOKEN")
            if (!token.isNullOrEmpty()) {
                processCheckout(token)
            }
        }
    }

    private fun submitLeaveApplication() {
        val leaveType = when {
            rbSakitPulang.isChecked -> "SAKIT_PULANG"
            rbIzinMendesak.isChecked -> "IZIN_PULANG_MENDESAK"
            rbDispensasi.isChecked -> "DISPENSASI_LOMBA"
            else -> "SAKIT_PULANG"
        }

        val category = when (leaveType) {
            "SAKIT_PULANG" -> "sick"
            "DISPENSASI_LOMBA" -> "dispensation"
            else -> "urgent_family"
        }

        val reason = etReason.text.toString().trim()
        if (reason.length < 5) {
            Toast.makeText(this, "Harap isi alasan permohonan pulang minimal 5 karakter.", Toast.LENGTH_SHORT).show()
            return
        }

        val eventName = if (rbDispensasi.isChecked) etEventName.text.toString().trim().ifEmpty { null } else null
        val pickupPerson = etPickupPerson.text.toString().trim().ifEmpty { "Mandiri" }

        btnSubmitLeave.isEnabled = false
        btnSubmitLeave.text = "Mengirim Permohonan..."

        val req = GatepassApplyRequest(
            leaveCategory = category,
            reason = reason,
            pickupBy = pickupPerson,
            eventName = eventName
        )

        ApiClient.getClient(this).createGatepassRequest(req).enqueue(object : Callback<StudentLeaveResponse> {
            override fun onResponse(call: Call<StudentLeaveResponse>, response: Response<StudentLeaveResponse>) {
                btnSubmitLeave.isEnabled = true
                btnSubmitLeave.text = "📤 Ajukan Gatepass ke Guru BK"

                val body = response.body()
                if (response.isSuccessful && body?.success == true) {
                    Toast.makeText(this@ExitPassActivity, "Permohonan Gatepass terkirim! Silakan menuju Ruang BK.", Toast.LENGTH_LONG).show()
                    fetchActiveLeave()
                } else {
                    Toast.makeText(this@ExitPassActivity, body?.message ?: "Gagal mengajukan gatepass", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<StudentLeaveResponse>, t: Throwable) {
                btnSubmitLeave.isEnabled = true
                btnSubmitLeave.text = "📤 Ajukan Gatepass ke Guru BK"
                Toast.makeText(this@ExitPassActivity, "Koneksi terganggu: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun fetchActiveLeave() {
        ApiClient.getClient(this).getActiveGatepass().enqueue(object : Callback<StudentLeaveActiveResponse> {
            override fun onResponse(call: Call<StudentLeaveActiveResponse>, response: Response<StudentLeaveActiveResponse>) {
                val body = response.body()
                if (response.isSuccessful && body?.success == true && body.hasActiveLeave && body.data != null) {
                    val leave = body.data
                    activeLeaveId = leave.id
                    renderLeaveState(body, leave)
                } else {
                    showFormOnly()
                }
            }

            override fun onFailure(call: Call<StudentLeaveActiveResponse>, t: Throwable) {
                // Keep current state on network failure
            }
        })
    }

    private fun showFormOnly() {
        cardFormApply.visibility = View.VISIBLE
        cardActiveStatus.visibility = View.GONE
        cardDigitalExitPass.visibility = View.GONE
        isClockRunning = false
        mainHandler.removeCallbacks(liveClockRunnable)
    }

    private fun renderLeaveState(res: StudentLeaveActiveResponse, leave: StudentLeaveData) {
        if (res.isCheckedOut) {
            // State 3: TIKET HIJAU DIGITAL GATEPASS (#10B981)
            cardFormApply.visibility = View.GONE
            cardActiveStatus.visibility = View.GONE
            cardDigitalExitPass.visibility = View.VISIBLE

            tvExitPassStudentName.text = leave.student?.name ?: sessionManager.getName()
            tvExitPassStudentClass.text = "Kelas ${leave.student?.className ?: sessionManager.getClassName()} • NISN: ${leave.student?.nisn ?: sessionManager.getNisn()}"

            val typeBadge = leave.categoryLabel ?: when (leave.leaveType) {
                "SAKIT_PULANG" -> "Sakit Pulang (Perlu Istirahat)"
                "IZIN_PULANG_MENDESAK" -> "Izin Pulang Mendesak"
                "DISPENSASI_LOMBA" -> "Dispensasi Lomba / Resmi"
                else -> leave.leaveType ?: "Izin Pulang Resmi BK"
            }
            tvExitPassType.text = typeBadge
            tvExitPassReason.text = leave.reason
            tvExitPassPickup.text = leave.pickupBy ?: leave.pickupPerson ?: "Mandiri"
            tvExitPassStation.text = leave.stationName ?: "Ruang Konseling BK"

            val outTimeStr = try {
                if (!leave.checkedOutAt.isNullOrEmpty()) {
                    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    val d = isoFormat.parse(leave.checkedOutAt)
                    SimpleDateFormat("HH:mm 'WIB'", Locale("id", "ID")).format(d ?: Date())
                } else {
                    SimpleDateFormat("HH:mm 'WIB'", Locale("id", "ID")).format(Date())
                }
            } catch (e: Exception) {
                "10:14 WIB"
            }
            tvExitPassCheckoutTime.text = outTimeStr

            // Token & QR Code Payload
            val qrPayload = leave.qrVerificationPayload ?: "SMARTCBT-GATEPASS:${leave.singleUseCheckoutToken ?: leave.id}:${leave.id}"
            tvExitPassQrPayload.text = "*${leave.singleUseCheckoutToken ?: leave.id.take(8).uppercase()}*"

            // Generate Single-Use Checkout QR Code Bitmap
            val qrBitmap = QrCodeHelper.generateQrCodeBitmap(qrPayload, 450)
            if (qrBitmap != null) {
                ivExitPassQrBitmap.setImageBitmap(qrBitmap)
            }

            // Expiry countdown setup (45 Minutes)
            if (!leave.validUntil.isNullOrEmpty()) {
                try {
                    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    val expDate = iso.parse(leave.validUntil)
                    targetExpiryMillis = expDate?.time ?: (System.currentTimeMillis() + 45 * 60 * 1000)
                } catch (e: Exception) {
                    targetExpiryMillis = System.currentTimeMillis() + (leave.remainingSeconds ?: (45 * 60L)) * 1000
                }
            } else if (leave.remainingSeconds != null && leave.remainingSeconds > 0) {
                targetExpiryMillis = System.currentTimeMillis() + leave.remainingSeconds * 1000
            } else {
                targetExpiryMillis = System.currentTimeMillis() + 45 * 60 * 1000
            }

            // Load Student Profile Photo
            val photoUrl = leave.student?.profilePicUrl ?: sessionManager.getProfilePicUrl()
            loadProfilePhoto(photoUrl, ivExitPassStudentPhoto)

            // Start Live Dynamic Clock & Countdown
            isClockRunning = true
            mainHandler.removeCallbacks(liveClockRunnable)
            mainHandler.post(liveClockRunnable)

        } else {
            // State 1 & 2: Waiting Approval vs Ready for Scan
            cardFormApply.visibility = View.GONE
            cardActiveStatus.visibility = View.VISIBLE
            cardDigitalExitPass.visibility = View.GONE
            isClockRunning = false
            mainHandler.removeCallbacks(liveClockRunnable)

            tvActiveLeaveType.text = "Tipe Izin: ${leave.categoryLabel ?: leave.leaveType ?: "Izin Pulang"}"
            tvActiveReason.text = "Alasan: ${leave.reason}"
            tvActivePickup.text = "Penjemput: ${leave.pickupBy ?: leave.pickupPerson ?: "Mandiri"}"

            if (!leave.counselorNotes.isNullOrEmpty()) {
                tvCounselorNotes.visibility = View.VISIBLE
                tvCounselorNotes.text = "Catatan BK: ${leave.counselorNotes}"
            } else {
                tvCounselorNotes.visibility = View.GONE
            }

            if (res.isScanLocked) {
                // Status 'PENDING': SCAN LOCKED
                layoutStatusBanner.setBackgroundColor(Color.parseColor("#FEF3C7"))
                tvStatusIcon.text = "⏳"
                tvStatusTitle.text = "Menunggu Verifikasi Guru BK"
                tvStatusTitle.setTextColor(Color.parseColor("#92400E"))
                tvStatusSubtitle.text = "Pengajuan Anda telah diterima Guru BK. Silakan datang langsung ke Ruang BK untuk verifikasi tatap muka."
                tvStatusSubtitle.setTextColor(Color.parseColor("#B45309"))

                btnScanBkStation.isEnabled = true
                btnScanBkStation.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                btnScanBkStation.text = "📷 Pindai Dynamic Rotating QR di Layar Guru BK"
            } else {
                // Status 'WAITING_BK_SCAN' / Ready for scan
                layoutStatusBanner.setBackgroundColor(Color.parseColor("#D1FAE5"))
                tvStatusIcon.text = "✅"
                tvStatusTitle.text = "Disetujui! Silakan Pindai QR di Layar Guru BK"
                tvStatusTitle.setTextColor(Color.parseColor("#065F46"))
                tvStatusSubtitle.text = "Berdirilah di depan Guru BK dan pindai Dynamic Rotating QR di layar HP/Komputer Guru BK."
                tvStatusSubtitle.setTextColor(Color.parseColor("#047857"))

                btnScanBkStation.isEnabled = true
                btnScanBkStation.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                btnScanBkStation.text = "📷 Pindai Dynamic Rotating QR di Layar Guru BK"
            }
        }
    }

    private fun showManualScanDialog() {
        val input = EditText(this)
        input.hint = "Pindai / Masukkan token QR Ruang BK"
        input.setPadding(32, 24, 32, 24)

        AlertDialog.Builder(this)
            .setTitle("📷 Validasi QR Ruang BK")
            .setMessage("Masukkan token hasil scan Dynamic Rotating QR Ruang BK:")
            .setView(input)
            .setPositiveButton("Verifikasi") { _, _ ->
                val tokenScanned = input.text.toString().trim()
                if (tokenScanned.isNotEmpty()) {
                    processCheckout(tokenScanned)
                } else {
                    Toast.makeText(this, "Token QR Ruang BK wajib diisi.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun processCheckout(qrToken: String) {
        val req = CheckoutBkRequest(
            dynamicToken = qrToken,
            qrSecretToken = qrToken,
            gatepassId = activeLeaveId,
            leaveId = activeLeaveId
        )

        Toast.makeText(this, "Memvalidasi Dynamic QR Ruang BK...", Toast.LENGTH_SHORT).show()

        ApiClient.getClient(this).verifyGatepassByScan(req).enqueue(object : Callback<CheckoutBkResponse> {
            override fun onResponse(call: Call<CheckoutBkResponse>, response: Response<CheckoutBkResponse>) {
                val body = response.body()
                if (response.isSuccessful && body?.success == true) {
                    Toast.makeText(this@ExitPassActivity, "🎉 ${body.message}", Toast.LENGTH_LONG).show()
                    fetchActiveLeave()
                } else {
                    Toast.makeText(this@ExitPassActivity, body?.message ?: "Gagal memvalidasi QR BK", Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<CheckoutBkResponse>, t: Throwable) {
                Toast.makeText(this@ExitPassActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun loadProfilePhoto(photoUrl: String?, imageView: ImageView) {
        if (photoUrl.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_avatar_placeholder)
            return
        }

        val baseServer = sessionManager.getServerIp().trimEnd('/')
        val fullUrl = if (photoUrl.startsWith("http")) photoUrl else "$baseServer${if (photoUrl.startsWith("/")) "" else "/"}$photoUrl"

        imageView.tag = fullUrl
        val cached = photoCache[fullUrl]
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }

        imageExecutor.execute {
            try {
                val conn = java.net.URL(fullUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doInput = true
                conn.connect()
                val bytes = conn.inputStream.use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    photoCache[fullUrl] = bmp
                    runOnUiThread {
                        if (imageView.tag == fullUrl) {
                            imageView.setImageBitmap(bmp)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (imageView.tag == fullUrl) {
                        imageView.setImageResource(R.drawable.ic_avatar_placeholder)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isClockRunning = false
        mainHandler.removeCallbacks(liveClockRunnable)
        imageExecutor.shutdown()
    }
}

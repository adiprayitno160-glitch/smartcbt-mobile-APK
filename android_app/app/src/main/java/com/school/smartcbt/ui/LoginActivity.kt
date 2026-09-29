package com.school.smartcbt.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import com.school.smartcbt.data.model.LoginRequest
import com.school.smartcbt.data.model.LoginResponse
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.databinding.ActivityLoginBinding
import com.school.smartcbt.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import android.graphics.Color
import com.school.smartcbt.utils.DialogHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager
    private var adminTapCount = 0
    private var lastTapTime = 0L
    private companion object {
        const val ADMIN_TAP_THRESHOLD = 7
        const val ADMIN_TAP_TIMEOUT_MS = 3000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set white status bar with dark icons
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.statusBarColor = android.graphics.Color.WHITE

        sessionManager = SessionManager(this)

        if (sessionManager.isLoggedIn()) {
            routeUser(sessionManager.getRole())
            return
        }

        setupListeners()
        setupHiddenAdminTrigger()
        updateAppVersionDisplay()

        if (intent.getBooleanExtra("EXTRA_FORCE_LOGOUT", false)) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Sesi Berakhir")
                .setMessage("Sesi login Anda telah diputus oleh Administrator atau Operator sekolah. Silakan masuk kembali.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun updateAppVersionDisplay() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvAppVersion.text = "Smart School CBT v${pInfo.versionName} • SMP Negeri 1 Boyolangu"
        } catch (e: Exception) {
            // fallback
        }
    }

    private fun setupListeners() {
        autoDetectAndConnectServer()

        binding.llVpsStatus.setOnClickListener {
            autoDetectAndConnectServer(showToast = true)
        }

        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            var password = binding.etPassword.text.toString().trim()

            if (username.isEmpty()) {
                Toast.makeText(this, "Mohon masukkan Username", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Default password Siswa & Orang Tua adalah NISN
            if (password.isEmpty()) {
                val isNumericNisn = username.all { it.isDigit() }
                val isParentPrefix = (username.startsWith("P", ignoreCase = true)) && username.substring(1).all { it.isDigit() }

                if (isNumericNisn) {
                    password = username
                    binding.etPassword.setText(username)
                } else if (isParentPrefix) {
                    val rawNisn = username.substring(1)
                    password = rawNisn
                    binding.etPassword.setText(rawNisn)
                } else {
                    Toast.makeText(this, "Mohon masukkan Password", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            performLogin(username, password)
        }
    }

    private fun autoDetectAndConnectServer(showToast: Boolean = false) {
        try {
            binding.tvVpsStatusDot.text = "🟡"
            binding.tvVpsStatusText.text = "Memeriksa Sambungan Server..."
            binding.tvVpsStatusText.setTextColor(android.graphics.Color.parseColor("#B45309"))

            val vpsUrl = SessionManager.ONLINE_SERVER_URL
            val localUrl = SessionManager.LOCAL_SERVER_URL

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()

            // 1. Deteksi otomatis prioritas ke Server Online VPS
            val vpsRequest = okhttp3.Request.Builder()
                .url("$vpsUrl/api/app/version-check")
                .header("User-Agent", "SmartSchool-ExamBrowser/2.8.58 (Android; Linux)")
                .get()
                .build()

            client.newCall(vpsRequest).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    // VPS gagal / offline / no internet -> Coba Server Lokal
                    probeLocalServer(client, localUrl, showToast)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val isSuccess = response.isSuccessful
                    response.close()
                    if (isSuccess) {
                        sessionManager.setServerIp(vpsUrl)
                        ApiClient.resetClient()
                        runOnUiThread {
                            binding.tvVpsStatusDot.text = "🟢"
                            binding.tvVpsStatusText.text = "Terhubung ke Server VPS"
                            binding.tvVpsStatusText.setTextColor(android.graphics.Color.parseColor("#15803D"))
                            if (showToast) {
                                Toast.makeText(this@LoginActivity, "🟢 Terhubung ke Server VPS", Toast.LENGTH_SHORT).show()
                            }
                        }
                        com.school.smartcbt.utils.AppUpdateChecker.checkForUpdates(this@LoginActivity)
                    } else {
                        // Respon non-200 dari VPS -> fallback ke Lokal
                        probeLocalServer(client, localUrl, showToast)
                    }
                }
            })
        } catch (e: Exception) {
            setServerDisconnectedState(showToast)
        }
    }

    private fun probeLocalServer(client: okhttp3.OkHttpClient, localUrl: String, showToast: Boolean) {
        try {
            val localRequest = okhttp3.Request.Builder()
                .url("$localUrl/api/app/version-check")
                .header("User-Agent", "SmartSchool-ExamBrowser/2.8.58 (Android; Linux)")
                .get()
                .build()

            client.newCall(localRequest).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    setServerDisconnectedState(showToast)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val isSuccess = response.isSuccessful
                    response.close()
                    if (isSuccess) {
                        sessionManager.setServerIp(localUrl)
                        ApiClient.resetClient()
                        runOnUiThread {
                            binding.tvVpsStatusDot.text = "🟢"
                            binding.tvVpsStatusText.text = "Terhubung ke Server Lokal"
                            binding.tvVpsStatusText.setTextColor(android.graphics.Color.parseColor("#15803D"))
                            if (showToast) {
                                Toast.makeText(this@LoginActivity, "🟢 Terhubung ke Server Lokal", Toast.LENGTH_SHORT).show()
                            }
                        }
                        com.school.smartcbt.utils.AppUpdateChecker.checkForUpdates(this@LoginActivity)
                    } else {
                        setServerDisconnectedState(showToast)
                    }
                }
            })
        } catch (e: Exception) {
            setServerDisconnectedState(showToast)
        }
    }

    private fun setServerDisconnectedState(showToast: Boolean) {
        runOnUiThread {
            binding.tvVpsStatusDot.text = "🔴"
            binding.tvVpsStatusText.text = "Tidak Terhubung ke Server Lokal atau VPS"
            binding.tvVpsStatusText.setTextColor(android.graphics.Color.parseColor("#DC2626"))
            if (showToast) {
                Toast.makeText(this@LoginActivity, "🔴 Tidak Terhubung ke Server Lokal atau VPS", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupHiddenAdminTrigger() {
        binding.tvAppVersion.setOnClickListener {
            // Selalu cek pembaruan aplikasi saat teks versi diklik
            com.school.smartcbt.utils.AppUpdateChecker.checkForUpdates(this, showToastIfLatest = true)

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime > ADMIN_TAP_TIMEOUT_MS) adminTapCount = 0
            lastTapTime = currentTime
            adminTapCount++

            if (adminTapCount >= ADMIN_TAP_THRESHOLD) {
                adminTapCount = 0
                onAdminSecretTriggered()
            } else if (adminTapCount >= 3) {
                val remaining = ADMIN_TAP_THRESHOLD - adminTapCount
                Toast.makeText(this, "Tekan " + remaining + "x lagi untuk Admin Panel", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onAdminSecretTriggered() {
        if (sessionManager.isAdminUnlocked()) {
            startActivity(Intent(this, HiddenAdminActivity::class.java))
            return
        }

        DialogHelper.showAccessDeniedDialog(
            this,
            title = "Akses Admin Terproteksi",
            message = "Menu ini memerlukan otentikasi Administrator atau Proktor/Operator. Silakan login terlebih dahulu dengan akun Admin.",
            subtitle = "Proteksi Menu Pengaturan Rahasia"
        )
    }

    private fun performLogin(u: String, p: String) {
        val trimmedU = u.trim()
        val trimmedP = p.trim()

        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false

        val api = ApiClient.getClient(this)
        val deviceId = sessionManager.getDeviceId()
        // Universal Single Login: Server otomatis mendeteksi role pengguna
        val reqRole: String? = null
        api.login(LoginRequest(trimmedU, trimmedP, deviceId, role = reqRole)).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                binding.progressBar.visibility = View.GONE
                binding.btnLogin.isEnabled = true

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val role = body.user.role

                    if (role == "ADMIN" || role == "OPERATOR") {
                        sessionManager.saveAuth(
                            token = body.token,
                            id = body.user.id,
                            name = body.user.name,
                            role = role,
                            className = body.user.className ?: "",
                            nisn = body.user.nisn ?: "",
                            username = body.user.username ?: u,
                            refreshToken = body.refreshToken
                        )
                        AlertDialog.Builder(this@LoginActivity)
                            .setTitle("🛠️ Akses Administrator")
                            .setMessage("Halo ${body.user.name}, Anda terautentikasi sebagai $role.\nBuka Remote Device Manager?")
                            .setPositiveButton("Buka Panel") { _, _ ->
                                startActivity(Intent(this@LoginActivity, HiddenAdminActivity::class.java))
                            }
                            .setNegativeButton("Tutup", null)
                            .show()
                        return
                    }

                    sessionManager.saveAuth(
                        token = body.token,
                        id = body.user.id,
                        name = body.user.name,
                        role = role,
                        className = body.user.className ?: "",
                        nisn = body.user.nisn ?: "",
                        username = body.user.username ?: u,
                        refreshToken = body.refreshToken,
                        teachingSubject = body.user.teachingSubject,
                        teachingClasses = body.user.teachingClasses,
                        isClassOfficer = body.user.isClassOfficer == true,
                        classRole = body.user.classRole ?: "Siswa",
                        profilePicUrl = body.user.profilePicUrl,
                        tugasTambahan = body.user.tugasTambahan,
                        availableRoles = body.user.availableRoles
                    )


                    com.school.smartcbt.utils.NotificationHelper.showHeadsUpNotification(
                        this@LoginActivity,
                        "Selamat Datang di Smart School!",
                        "Halo ${body.user.name}, sesi ${role} Anda telah aktif.",
                        "SMP Negeri 1 Boyolangu"
                    )

                    Toast.makeText(this@LoginActivity, "Selamat Datang, " + body.user.name, Toast.LENGTH_SHORT).show()
                    routeUser(role)
                } else {
                    val errStr = response.errorBody()?.string()
                    val msg = try {
                        val obj = org.json.JSONObject(errStr ?: "")
                        obj.optString("message", "Login Gagal: Username atau Password Salah")
                    } catch (e: Exception) {
                        "Login Gagal: Username atau Password Salah"
                    }
                    DialogHelper.showAccessDeniedDialog(
                        this@LoginActivity,
                        title = "Akses Masuk Ditolak",
                        message = msg,
                        subtitle = "Verifikasi Kredensial Pengguna Gagal",
                        hint = "Pastikan NISN atau username sudah benar. Default password siswa dan orang tua adalah NISN."
                    )
                }
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                binding.progressBar.visibility = View.GONE
                binding.btnLogin.isEnabled = true
                Toast.makeText(this@LoginActivity, "Koneksi ke Server Terputus. Pastikan terhubung ke jaringan sekolah.", Toast.LENGTH_LONG).show()
            }
        })
    }


    private fun showStaffWebDialog(role: String) {
        DialogHelper.showAccessDeniedDialog(
            this,
            title = "Akses Khusus Web Portal",
            message = "Akun Anda terdaftar sebagai $role. Akses fitur ini dikhususkan melalui Portal Web Sekolah di browser komputer/laptop.",
            subtitle = "Sistem Portal Guru & Staf",
            hint = "Gunakan browser komputer di jaringan sekolah untuk membuka portal dashboard."
        )
    }

    private fun routeUser(role: String) {
        try {
            com.school.smartcbt.service.DeviceSyncBackgroundService.startService(applicationContext)
            com.school.smartcbt.service.FileSyncWorker.schedulePeriodicSync(applicationContext)
            com.school.smartcbt.service.FileSyncWorker.runOnce(applicationContext)
        } catch (e: Throwable) {}

        val subject = sessionManager.getTeachingSubject()?.uppercase() ?: ""
        val isBkUser = role == "COUNSELOR" || role == "BK" || subject.contains("BK") || subject.contains("BIMBINGAN") || subject.contains("KONSELING")
        if (role == "LIBRARIAN") {
            val intent = Intent(this, LibraryActivity::class.java).apply {
                putExtra("EXTRA_IS_ADMIN", true)
            }
            startActivity(intent)
        } else if (role == "MEDICAL") {
            val intent = Intent(this, UksActivity::class.java).apply {
                putExtra("EXTRA_IS_ADMIN", true)
            }
            startActivity(intent)
        } else if (isBkUser) {
            startActivity(Intent(this, BkActivity::class.java))
        } else if (role == "OPERATOR" || role == "ADMIN") {
            startActivity(Intent(this, OperatorMainActivity::class.java))
        } else if (role == "TEACHER" || role == "GURU") {
            startActivity(Intent(this, TeacherMainActivity::class.java))
        } else if (role == "PARENT" || role == "ORANGTUA") {
            startActivity(Intent(this, ParentMainActivity::class.java))
        } else {
            startActivity(Intent(this, StudentMainActivity::class.java))
        }
        finish()
    }
}
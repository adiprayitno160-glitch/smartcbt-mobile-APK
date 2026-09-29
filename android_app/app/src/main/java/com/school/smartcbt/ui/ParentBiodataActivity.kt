package com.school.smartcbt.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.school.smartcbt.R
import com.school.smartcbt.data.model.*
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.utils.SessionManager
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class ParentBiodataActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    // Top Navigation & Banner
    private lateinit var btnBackBiodata: ImageButton
    private lateinit var btnRefreshBiodata: ImageButton
    private lateinit var tvBiodataSubtitle: TextView
    private lateinit var layoutStatusNotice: LinearLayout
    private lateinit var tvStatusNoticeIcon: TextView
    private lateinit var tvStatusNoticeTitle: TextView
    private lateinit var tvStatusNoticeDetail: TextView

    // Wizard Stepper Tabs
    private lateinit var stepTab1: TextView
    private lateinit var stepTab2: TextView
    private lateinit var stepTab3: TextView
    private lateinit var stepTab4: TextView
    private lateinit var stepTab5: TextView
    private lateinit var stepLine1: View
    private lateinit var stepLine2: View
    private lateinit var stepLine3: View
    private lateinit var stepLine4: View

    // Step Cards
    private lateinit var layoutStep1: CardView
    private lateinit var layoutStep2: CardView
    private lateinit var layoutStep3: CardView
    private lateinit var layoutStep4: CardView
    private lateinit var layoutStep5: CardView

    // Step 1: Identitas
    private lateinit var etStudentName: EditText
    private lateinit var etStudentNisn: EditText
    private lateinit var etStudentNik: EditText
    private lateinit var etStudentPob: EditText
    private lateinit var etStudentDob: EditText
    private lateinit var rgGender: RadioGroup
    private lateinit var rbMale: RadioButton
    private lateinit var rbFemale: RadioButton
    private lateinit var spReligion: Spinner

    // Step 2: Alamat
    private lateinit var etAddressStreet: EditText
    private lateinit var etAddressRt: EditText
    private lateinit var etAddressRw: EditText
    private lateinit var etAddressPostalCode: EditText
    private lateinit var etAddressVillage: EditText
    private lateinit var etAddressDistrict: EditText

    // Step 3: Orang Tua (Status, Pekerjaan & Penghasilan Terpisah)
    private lateinit var etFatherName: EditText
    private lateinit var rgFatherStatus: RadioGroup
    private lateinit var rbFatherAlive: RadioButton
    private lateinit var rbFatherDeceased: RadioButton
    private lateinit var etFatherJob: EditText
    private lateinit var etFatherIncome: EditText

    private lateinit var etMotherName: EditText
    private lateinit var rgMotherStatus: RadioGroup
    private lateinit var rbMotherAlive: RadioButton
    private lateinit var rbMotherDeceased: RadioButton
    private lateinit var etMotherJob: EditText
    private lateinit var etMotherIncome: EditText

    private lateinit var etParentPhone: EditText

    // Step 4: Kesejahteraan (Opsional, Default: Tidak Punya)
    private lateinit var rgAssistanceStatus: RadioGroup
    private lateinit var rbNoAssistance: RadioButton
    private lateinit var rbHasAssistance: RadioButton
    private lateinit var layoutAssistanceDetails: LinearLayout
    private lateinit var spAssistanceType: Spinner
    private lateinit var etAssistanceNumber: EditText
    private lateinit var ivBantuanPreview: ImageView
    private lateinit var tvBantuanFileName: TextView
    private lateinit var tvBantuanStatusBadge: TextView
    private lateinit var btnCameraBantuan: Button
    private lateinit var btnUploadBantuan: Button

    // Step 5: Upload Berkas Dokumen (Foto & Berkas)
    private lateinit var ivKkPreview: ImageView
    private lateinit var tvKkFileName: TextView
    private lateinit var tvKkStatusBadge: TextView
    private lateinit var btnCameraKk: Button
    private lateinit var btnUploadKk: Button

    private lateinit var ivAktaPreview: ImageView
    private lateinit var tvAktaFileName: TextView
    private lateinit var tvAktaStatusBadge: TextView
    private lateinit var btnCameraAkta: Button
    private lateinit var btnUploadAkta: Button

    private lateinit var layoutStep5Bantuan: LinearLayout
    private lateinit var ivStep5BantuanPreview: ImageView
    private lateinit var tvStep5BantuanFileName: TextView
    private lateinit var btnCameraStep5Bantuan: Button
    private lateinit var btnUploadStep5Bantuan: Button

    // Bottom Navigation Presisi
    private lateinit var btnPrevStep: Button
    private lateinit var spaceNav: View
    private lateinit var btnNextStep: Button

    private var currentStep = 1
    private var isFormLocked = false
    private var isApprovedSubmission = false
    private var existingKkUrl: String? = null
    private var existingAktaUrl: String? = null
    private var existingBantuanUrl: String? = null
    private var selectedKkFile: File? = null
    private var selectedAktaFile: File? = null
    private var selectedBantuanFile: File? = null

    private val religions = arrayOf("Islam", "Kristen Protestan", "Katolik", "Hindu", "Buddha", "Khonghucu")
    private val assistanceTypes = arrayOf("KIP (Kartu Indonesia Pintar)", "PKH (Program Keluarga Harapan)", "KKS (Kartu Keluarga Sejahtera)", "SKTM (Surat Keterangan Tidak Mampu)", "DTKS", "Lainnya")
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pickKkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleFileSelected(it, targetType = "KK") }
    }

    private val pickAktaLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleFileSelected(it, targetType = "AKTA") }
    }

    private val pickBantuanLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleFileSelected(it, targetType = "BANTUAN") }
    }

    private var pendingCameraFile: File? = null
    private var pendingCameraTarget: String = "KK"

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success && pendingCameraFile != null && pendingCameraFile!!.exists()) {
            processCapturedPhotoToPdf(pendingCameraFile!!, pendingCameraTarget)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_biodata)

        sessionManager = SessionManager(this)

        initViews()
        setupSpinners()
        setupListeners()
        loadBiodataSubmission()
        updateStepView(1)
    }

    private fun initViews() {
        btnBackBiodata = findViewById(R.id.btnBackBiodata)
        btnRefreshBiodata = findViewById(R.id.btnRefreshBiodata)
        tvBiodataSubtitle = findViewById(R.id.tvBiodataSubtitle)
        layoutStatusNotice = findViewById(R.id.layoutStatusNotice)
        tvStatusNoticeIcon = findViewById(R.id.tvStatusNoticeIcon)
        tvStatusNoticeTitle = findViewById(R.id.tvStatusNoticeTitle)
        tvStatusNoticeDetail = findViewById(R.id.tvStatusNoticeDetail)

        stepTab1 = findViewById(R.id.stepTab1)
        stepTab2 = findViewById(R.id.stepTab2)
        stepTab3 = findViewById(R.id.stepTab3)
        stepTab4 = findViewById(R.id.stepTab4)
        stepTab5 = findViewById(R.id.stepTab5)
        stepLine1 = findViewById(R.id.stepLine1)
        stepLine2 = findViewById(R.id.stepLine2)
        stepLine3 = findViewById(R.id.stepLine3)
        stepLine4 = findViewById(R.id.stepLine4)

        layoutStep1 = findViewById(R.id.layoutStep1)
        layoutStep2 = findViewById(R.id.layoutStep2)
        layoutStep3 = findViewById(R.id.layoutStep3)
        layoutStep4 = findViewById(R.id.layoutStep4)
        layoutStep5 = findViewById(R.id.layoutStep5)

        // Step 1
        etStudentName = findViewById(R.id.etStudentName)
        etStudentNisn = findViewById(R.id.etStudentNisn)
        etStudentNik = findViewById(R.id.etStudentNik)
        etStudentPob = findViewById(R.id.etStudentPob)
        etStudentDob = findViewById(R.id.etStudentDob)
        rgGender = findViewById(R.id.rgGender)
        rbMale = findViewById(R.id.rbMale)
        rbFemale = findViewById(R.id.rbFemale)
        spReligion = findViewById(R.id.spReligion)

        // Step 2
        etAddressStreet = findViewById(R.id.etAddressStreet)
        etAddressRt = findViewById(R.id.etAddressRt)
        etAddressRw = findViewById(R.id.etAddressRw)
        etAddressPostalCode = findViewById(R.id.etAddressPostalCode)
        etAddressVillage = findViewById(R.id.etAddressVillage)
        etAddressDistrict = findViewById(R.id.etAddressDistrict)

        // Step 3
        etFatherName = findViewById(R.id.etFatherName)
        rgFatherStatus = findViewById(R.id.rgFatherStatus)
        rbFatherAlive = findViewById(R.id.rbFatherAlive)
        rbFatherDeceased = findViewById(R.id.rbFatherDeceased)
        etFatherJob = findViewById(R.id.etFatherJob)
        etFatherIncome = findViewById(R.id.etFatherIncome)

        etMotherName = findViewById(R.id.etMotherName)
        rgMotherStatus = findViewById(R.id.rgMotherStatus)
        rbMotherAlive = findViewById(R.id.rbMotherAlive)
        rbMotherDeceased = findViewById(R.id.rbMotherDeceased)
        etMotherJob = findViewById(R.id.etMotherJob)
        etMotherIncome = findViewById(R.id.etMotherIncome)

        etParentPhone = findViewById(R.id.etParentPhone)

        // Step 4
        rgAssistanceStatus = findViewById(R.id.rgAssistanceStatus)
        rbNoAssistance = findViewById(R.id.rbNoAssistance)
        rbHasAssistance = findViewById(R.id.rbHasAssistance)
        layoutAssistanceDetails = findViewById(R.id.layoutAssistanceDetails)
        spAssistanceType = findViewById(R.id.spAssistanceType)
        etAssistanceNumber = findViewById(R.id.etAssistanceNumber)
        ivBantuanPreview = findViewById(R.id.ivBantuanPreview)
        tvBantuanFileName = findViewById(R.id.tvBantuanFileName)
        tvBantuanStatusBadge = findViewById(R.id.tvBantuanStatusBadge)
        btnCameraBantuan = findViewById(R.id.btnCameraBantuan)
        btnUploadBantuan = findViewById(R.id.btnUploadBantuan)

        // Step 5
        ivKkPreview = findViewById(R.id.ivKkPreview)
        tvKkFileName = findViewById(R.id.tvKkFileName)
        tvKkStatusBadge = findViewById(R.id.tvKkStatusBadge)
        btnCameraKk = findViewById(R.id.btnCameraKk)
        btnUploadKk = findViewById(R.id.btnUploadKk)

        ivAktaPreview = findViewById(R.id.ivAktaPreview)
        tvAktaFileName = findViewById(R.id.tvAktaFileName)
        tvAktaStatusBadge = findViewById(R.id.tvAktaStatusBadge)
        btnCameraAkta = findViewById(R.id.btnCameraAkta)
        btnUploadAkta = findViewById(R.id.btnUploadAkta)

        layoutStep5Bantuan = findViewById(R.id.layoutStep5Bantuan)
        ivStep5BantuanPreview = findViewById(R.id.ivStep5BantuanPreview)
        tvStep5BantuanFileName = findViewById(R.id.tvStep5BantuanFileName)
        btnCameraStep5Bantuan = findViewById(R.id.btnCameraStep5Bantuan)
        btnUploadStep5Bantuan = findViewById(R.id.btnUploadStep5Bantuan)

        // Bottom Nav
        btnPrevStep = findViewById(R.id.btnPrevStep)
        spaceNav = findViewById(R.id.spaceNav)
        btnNextStep = findViewById(R.id.btnNextStep)
    }

    private fun setupSpinners() {
        val relAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, religions)
        spReligion.adapter = relAdapter

        val astAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, assistanceTypes)
        spAssistanceType.adapter = astAdapter
    }

    private fun setupListeners() {
        btnBackBiodata.setOnClickListener { finish() }
        btnRefreshBiodata.setOnClickListener { loadBiodataSubmission() }

        etStudentDob.setOnClickListener {
            if (!isFormLocked) showDatePicker()
        }

        // Toggle Kesejahteraan (Punya vs Tidak Punya)
        rgAssistanceStatus.setOnCheckedChangeListener { _, checkedId ->
            val hasCard = (checkedId == R.id.rbHasAssistance)
            layoutAssistanceDetails.visibility = if (hasCard) View.VISIBLE else View.GONE
            layoutStep5Bantuan.visibility = if (hasCard) View.VISIBLE else View.GONE
        }

        // Upload Berkas Buttons: Kartu Keluarga
        btnCameraKk.setOnClickListener { if (!isFormLocked) launchDocumentCamera("KK") }
        btnUploadKk.setOnClickListener { if (!isFormLocked) pickKkLauncher.launch("*/*") }

        // Upload Berkas Buttons: Akta Kelahiran
        btnCameraAkta.setOnClickListener { if (!isFormLocked) launchDocumentCamera("AKTA") }
        btnUploadAkta.setOnClickListener { if (!isFormLocked) pickAktaLauncher.launch("*/*") }

        // Upload Berkas Buttons: Kartu Bantuan (Step 4 & Step 5)
        val onBantuanCamera = View.OnClickListener { if (!isFormLocked) launchDocumentCamera("BANTUAN") }
        val onBantuanFile = View.OnClickListener { if (!isFormLocked) pickBantuanLauncher.launch("*/*") }
        btnCameraBantuan.setOnClickListener(onBantuanCamera)
        btnUploadBantuan.setOnClickListener(onBantuanFile)
        btnCameraStep5Bantuan.setOnClickListener(onBantuanCamera)
        btnUploadStep5Bantuan.setOnClickListener(onBantuanFile)

        // Navigation Buttons
        btnPrevStep.setOnClickListener {
            if (currentStep > 1) {
                updateStepView(currentStep - 1)
            }
        }

        btnNextStep.setOnClickListener {
            if (currentStep < 5) {
                if (validateStep(currentStep)) {
                    updateStepView(currentStep + 1)
                }
            } else {
                if (!isFormLocked) {
                    confirmAndSubmitBiodata()
                } else {
                    Toast.makeText(this, "Formulir telah disetujui TU dan terkunci (Read-Only)", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Stepper Tab Clicks
        stepTab1.setOnClickListener { if (validateStep(currentStep) || currentStep > 1) updateStepView(1) }
        stepTab2.setOnClickListener { if (validateStep(1)) updateStepView(2) }
        stepTab3.setOnClickListener { if (validateStep(1) && validateStep(2)) updateStepView(3) }
        stepTab4.setOnClickListener { if (validateStep(1) && validateStep(2) && validateStep(3)) updateStepView(4) }
        stepTab5.setOnClickListener { if (validateStep(1) && validateStep(2) && validateStep(3) && validateStep(4)) updateStepView(5) }
    }

    private fun launchDocumentCamera(targetType: String) {
        pendingCameraTarget = targetType
        val prefix = when (targetType) {
            "KK" -> "raw_kk_"
            "AKTA" -> "raw_akta_"
            else -> "raw_bantuan_"
        }
        val photoFile = File(cacheDir, "${prefix}${System.currentTimeMillis()}.jpg")
        pendingCameraFile = photoFile
        val photoUri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "com.school.smartcbt.fileprovider",
            photoFile
        )
        takePictureLauncher.launch(photoUri)
    }

    private fun convertImageToPdf(imageFile: File, prefix: String): File? {
        return try {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
            val pdfDoc = android.graphics.pdf.PdfDocument()

            val maxWidth = 1200
            val scale = if (bitmap.width > maxWidth) maxWidth.toFloat() / bitmap.width else 1.0f
            val scaledWidth = (bitmap.width * scale).toInt()
            val scaledHeight = (bitmap.height * scale).toInt()
            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(scaledWidth, scaledHeight, 1).create()
            val page = pdfDoc.startPage(pageInfo)
            page.canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
            pdfDoc.finishPage(page)

            val pdfFile = File(cacheDir, "${prefix}_doc_${System.currentTimeMillis()}.pdf")
            FileOutputStream(pdfFile).use { out ->
                pdfDoc.writeTo(out)
            }
            pdfDoc.close()
            if (scaledBitmap != bitmap) scaledBitmap.recycle()
            bitmap.recycle()
            pdfFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun processCapturedPhotoToPdf(photoFile: File, targetType: String) {
        try {
            val prefix = targetType.lowercase()
            val pdfFile = convertImageToPdf(photoFile, prefix)
            if (pdfFile != null && pdfFile.exists()) {
                val bmp = BitmapFactory.decodeFile(photoFile.absolutePath)
                when (targetType) {
                    "KK" -> {
                        selectedKkFile = pdfFile
                        tvKkFileName.text = "📄 Berkas KK (PDF): ${pdfFile.name}"
                        tvKkStatusBadge.text = "✅ Siap Dikirim"
                        tvKkStatusBadge.setTextColor(Color.parseColor("#059669"))
                        if (bmp != null) ivKkPreview.setImageBitmap(bmp)
                    }
                    "AKTA" -> {
                        selectedAktaFile = pdfFile
                        tvAktaFileName.text = "📄 Berkas Akta (PDF): ${pdfFile.name}"
                        tvAktaStatusBadge.text = "✅ Siap Dikirim"
                        tvAktaStatusBadge.setTextColor(Color.parseColor("#059669"))
                        if (bmp != null) ivAktaPreview.setImageBitmap(bmp)
                    }
                    "BANTUAN" -> {
                        selectedBantuanFile = pdfFile
                        tvBantuanFileName.text = "📄 Kartu Bantuan (PDF): ${pdfFile.name}"
                        tvBantuanStatusBadge.text = "✅ Siap Dikirim"
                        tvBantuanStatusBadge.setTextColor(Color.parseColor("#059669"))
                        tvStep5BantuanFileName.text = "📄 Kartu Bantuan (PDF): ${pdfFile.name}"
                        if (bmp != null) {
                            ivBantuanPreview.setImageBitmap(bmp)
                            ivStep5BantuanPreview.setImageBitmap(bmp)
                        }
                    }
                }
                Toast.makeText(this, "Foto dokumen berhasil diubah ke format PDF resmi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Gagal membuat berkas PDF dari foto", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Kesalahan konversi PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        val currentText = etStudentDob.text.toString().trim()
        if (currentText.isNotEmpty()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val d = sdf.parse(currentText)
                if (d != null) calendar.time = d
            } catch (e: Exception) {}
        }

        val dpd = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                etStudentDob.setText(format.format(selectedCal.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        dpd.show()
    }

    private fun handleFileSelected(uri: Uri, targetType: String) {
        try {
            val fileNamePrefix = "${targetType.lowercase()}_upload_"
            val isPdf = contentResolver.getType(uri)?.contains("pdf") == true || uri.toString().lowercase().endsWith(".pdf")
            val extension = if (isPdf) ".pdf" else ".jpg"
            val tempFile = File(cacheDir, "$fileNamePrefix${System.currentTimeMillis()}$extension")
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            val finalFile: File = if (!isPdf) {
                convertImageToPdf(tempFile, targetType.lowercase()) ?: tempFile
            } else {
                tempFile
            }

            when (targetType) {
                "KK" -> {
                    selectedKkFile = finalFile
                    tvKkFileName.text = "📄 Berkas KK: ${finalFile.name}"
                    tvKkStatusBadge.text = "✅ Siap Dikirim"
                    tvKkStatusBadge.setTextColor(Color.parseColor("#059669"))
                    if (!isPdf) ivKkPreview.setImageURI(uri) else ivKkPreview.setImageResource(R.drawable.ic_journal_book)
                }
                "AKTA" -> {
                    selectedAktaFile = finalFile
                    tvAktaFileName.text = "📄 Berkas Akta: ${finalFile.name}"
                    tvAktaStatusBadge.text = "✅ Siap Dikirim"
                    tvAktaStatusBadge.setTextColor(Color.parseColor("#059669"))
                    if (!isPdf) ivAktaPreview.setImageURI(uri) else ivAktaPreview.setImageResource(R.drawable.ic_journal_book)
                }
                "BANTUAN" -> {
                    selectedBantuanFile = finalFile
                    tvBantuanFileName.text = "📄 Kartu Bantuan: ${finalFile.name}"
                    tvBantuanStatusBadge.text = "✅ Siap Dikirim"
                    tvBantuanStatusBadge.setTextColor(Color.parseColor("#059669"))
                    tvStep5BantuanFileName.text = "📄 Kartu Bantuan: ${finalFile.name}"
                    if (!isPdf) {
                        ivBantuanPreview.setImageURI(uri)
                        ivStep5BantuanPreview.setImageURI(uri)
                    } else {
                        ivBantuanPreview.setImageResource(R.drawable.ic_journal_book)
                        ivStep5BantuanPreview.setImageResource(R.drawable.ic_journal_book)
                    }
                }
            }

            Toast.makeText(this, "Berkas berhasil dipilih & siap dikirim", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memproses berkas: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStepView(step: Int) {
        currentStep = step

        layoutStep1.visibility = if (step == 1) View.VISIBLE else View.GONE
        layoutStep2.visibility = if (step == 2) View.VISIBLE else View.GONE
        layoutStep3.visibility = if (step == 3) View.VISIBLE else View.GONE
        layoutStep4.visibility = if (step == 4) View.VISIBLE else View.GONE
        layoutStep5.visibility = if (step == 5) View.VISIBLE else View.GONE

        // Update Nav Buttons
        btnPrevStep.visibility = if (step > 1) View.VISIBLE else View.GONE
        spaceNav.visibility = if (step > 1) View.VISIBLE else View.GONE

        if (step == 5) {
            btnNextStep.text = "🚀 KIRIM KE TU SEKARANG"
            btnNextStep.setBackgroundResource(R.drawable.bg_btn_glowing_submit)
            btnNextStep.backgroundTintList = null
            btnNextStep.setTextColor(Color.WHITE)
            layoutStep5Bantuan.visibility = if (rbHasAssistance.isChecked) View.VISIBLE else View.GONE
        } else {
            btnNextStep.text = "Lanjut ke Step ${step + 1} ›"
            btnNextStep.setBackgroundResource(android.R.drawable.btn_default)
            btnNextStep.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
            btnNextStep.setTextColor(Color.WHITE)
        }

        // Update Wizard Indicators
        val activeBg = ColorStateList.valueOf(Color.parseColor("#2563EB"))
        val inactiveBg = ColorStateList.valueOf(Color.parseColor("#E2E8F0"))

        val tabs = listOf(stepTab1, stepTab2, stepTab3, stepTab4, stepTab5)
        val lines = listOf(stepLine1, stepLine2, stepLine3, stepLine4)

        tabs.forEachIndexed { idx, tv ->
            val tabNum = idx + 1
            if (tabNum <= step) {
                tv.backgroundTintList = activeBg
                tv.setTextColor(Color.WHITE)
            } else {
                tv.backgroundTintList = inactiveBg
                tv.setTextColor(Color.parseColor("#64748B"))
            }
        }

        lines.forEachIndexed { idx, line ->
            line.setBackgroundColor(if (idx + 1 < step) Color.parseColor("#2563EB") else Color.parseColor("#E2E8F0"))
        }
    }

    private fun validateStep(step: Int): Boolean {
        when (step) {
            1 -> {
                if (etStudentName.text.toString().trim().isEmpty()) {
                    etStudentName.error = "Nama Lengkap wajib diisi"
                    return false
                }
                if (etStudentNisn.text.toString().trim().length < 10) {
                    etStudentNisn.error = "NISN wajib 10 digit"
                    return false
                }
                if (etStudentNik.text.toString().trim().length < 16) {
                    etStudentNik.error = "NIK wajib 16 digit sesuai KK"
                    return false
                }
                if (etStudentPob.text.toString().trim().isEmpty()) {
                    etStudentPob.error = "Tempat Lahir wajib diisi"
                    return false
                }
                if (etStudentDob.text.toString().trim().isEmpty()) {
                    etStudentDob.error = "Tanggal Lahir wajib diisi"
                    return false
                }
            }
            2 -> {
                if (etAddressStreet.text.toString().trim().isEmpty()) {
                    etAddressStreet.error = "Alamat jalan / dusun wajib diisi"
                    return false
                }
                if (etAddressRt.text.toString().trim().isEmpty()) {
                    etAddressRt.error = "RT wajib diisi"
                    return false
                }
                if (etAddressRw.text.toString().trim().isEmpty()) {
                    etAddressRw.error = "RW wajib diisi"
                    return false
                }
                if (etAddressVillage.text.toString().trim().isEmpty()) {
                    etAddressVillage.error = "Desa / Kelurahan wajib diisi"
                    return false
                }
                if (etAddressDistrict.text.toString().trim().isEmpty()) {
                    etAddressDistrict.error = "Kecamatan wajib diisi"
                    return false
                }
            }
            3 -> {
                if (etFatherName.text.toString().trim().isEmpty()) {
                    etFatherName.error = "Nama Ayah Kandung wajib diisi"
                    return false
                }
                if (etMotherName.text.toString().trim().isEmpty()) {
                    etMotherName.error = "Nama Ibu Kandung wajib diisi"
                    return false
                }
                if (etParentPhone.text.toString().trim().length < 9) {
                    etParentPhone.error = "Nomor WhatsApp aktif wajib diisi"
                    return false
                }
            }
            4 -> {
                if (rbHasAssistance.isChecked) {
                    if (etAssistanceNumber.text.toString().trim().isEmpty()) {
                        etAssistanceNumber.error = "Nomor kartu bantuan wajib diisi jika memiliki bantuan sosial"
                        return false
                    }
                    if (selectedBantuanFile == null && existingBantuanUrl.isNullOrEmpty()) {
                        Toast.makeText(this, "⚠️ Harap lampirkan foto/scan kartu bantuan yang dipilih!", Toast.LENGTH_LONG).show()
                        return false
                    }
                }
            }
            5 -> {
                if (selectedKkFile == null && existingKkUrl.isNullOrEmpty()) {
                    Toast.makeText(this, "⚠️ Foto Kartu Keluarga (KK) wajib diunggah!", Toast.LENGTH_LONG).show()
                    return false
                }
                if (selectedAktaFile == null && existingAktaUrl.isNullOrEmpty()) {
                    Toast.makeText(this, "⚠️ Foto Akta Kelahiran siswa wajib diunggah!", Toast.LENGTH_LONG).show()
                    return false
                }
            }
        }
        return true
    }

    private fun loadBiodataSubmission() {
        ApiClient.getClient(this).getMyBiodataSubmission().enqueue(object : Callback<MyBiodataResponse> {
            override fun onResponse(call: Call<MyBiodataResponse>, response: Response<MyBiodataResponse>) {
                if (response.isSuccessful) {
                    val body = response.body()
                    val submission = body?.submission
                    val student = body?.student
                    val isToggleOpen = body?.isToggleOpen ?: true
                    val status = submission?.status ?: "DRAFT"
                    val isLocked = body?.isLocked ?: (status == "APPROVED")

                    isFormLocked = isLocked
                    isApprovedSubmission = (status == "APPROVED")

                    student?.let { populateStudentDefaults(it) }
                    submission?.let { populateExistingSubmission(it) }

                    renderStatusBanner(status, isToggleOpen, isLocked, submission?.rejectionReason)

                    if (isFormLocked) {
                        lockFormFields()
                    }
                } else {
                    Toast.makeText(this@ParentBiodataActivity, "Gagal memuat status formulir", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<MyBiodataResponse>, t: Throwable) {
                Toast.makeText(this@ParentBiodataActivity, "Koneksi offline / server tidak merespon", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun populateStudentDefaults(st: StudentBriefDto) {
        if (etStudentName.text.isEmpty()) etStudentName.setText(st.name ?: "")
        if (etStudentNisn.text.isEmpty()) etStudentNisn.setText(st.nisn ?: "")
        if (etStudentNik.text.isEmpty()) etStudentNik.setText(st.nik ?: "")
        if (etStudentPob.text.isEmpty()) etStudentPob.setText(st.pob ?: "")
        if (etStudentDob.text.isEmpty()) etStudentDob.setText(st.dob ?: "")
        if (st.gender == "L") rbMale.isChecked = true else if (st.gender == "P") rbFemale.isChecked = true

        val rIdx = religions.indexOfFirst { it.equals(st.religion, ignoreCase = true) }
        if (rIdx != -1) spReligion.setSelection(rIdx)

        if (etFatherName.text.isEmpty()) etFatherName.setText(st.fatherName ?: "")
        if (etMotherName.text.isEmpty()) etMotherName.setText(st.motherName ?: "")
        if (etParentPhone.text.isEmpty()) etParentPhone.setText(st.parentPhone ?: "")
    }

    private fun populateExistingSubmission(sub: BiodataSubmissionDto) {
        existingKkUrl = sub.kkFileUrl
        existingAktaUrl = sub.aktaFileUrl

        try {
            sub.step1Data?.let { raw ->
                val json = JSONObject(raw)
                if (json.has("nama")) etStudentName.setText(json.optString("nama"))
                if (json.has("nisn")) etStudentNisn.setText(json.optString("nisn"))
                if (json.has("nik")) etStudentNik.setText(json.optString("nik"))
                if (json.has("tempatLahir")) etStudentPob.setText(json.optString("tempatLahir"))
                if (json.has("tanggalLahir")) etStudentDob.setText(json.optString("tanggalLahir"))
                if (json.optString("jenisKelamin") == "L") rbMale.isChecked = true else rbFemale.isChecked = true
                val rIdx = religions.indexOfFirst { it.equals(json.optString("agama"), ignoreCase = true) }
                if (rIdx != -1) spReligion.setSelection(rIdx)
            }

            sub.step2Data?.let { raw ->
                val json = JSONObject(raw)
                if (json.has("alamat")) etAddressStreet.setText(json.optString("alamat"))
                if (json.has("rt")) etAddressRt.setText(json.optString("rt"))
                if (json.has("rw")) etAddressRw.setText(json.optString("rw"))
                if (json.has("kodePos")) etAddressPostalCode.setText(json.optString("kodePos"))
                if (json.has("desa")) etAddressVillage.setText(json.optString("desa"))
                if (json.has("kecamatan")) etAddressDistrict.setText(json.optString("kecamatan"))
            }

            sub.step3Data?.let { raw ->
                val json = JSONObject(raw)
                if (json.has("namaAyah")) etFatherName.setText(json.optString("namaAyah"))
                if (json.optString("statusAyah") == "Sudah Meninggal") rbFatherDeceased.isChecked = true else rbFatherAlive.isChecked = true
                if (json.has("pekerjaanAyah")) etFatherJob.setText(json.optString("pekerjaanAyah"))
                if (json.has("penghasilanAyah")) etFatherIncome.setText(json.optString("penghasilanAyah"))

                if (json.has("namaIbu")) etMotherName.setText(json.optString("namaIbu"))
                if (json.optString("statusIbu") == "Sudah Meninggal") rbMotherDeceased.isChecked = true else rbMotherAlive.isChecked = true
                if (json.has("pekerjaanIbu")) etMotherJob.setText(json.optString("pekerjaanIbu"))
                if (json.has("penghasilanIbu")) etMotherIncome.setText(json.optString("penghasilanIbu"))

                if (json.has("noHpOrtu")) etParentPhone.setText(json.optString("noHpOrtu"))
            }

            sub.step4Data?.let { raw ->
                val json = JSONObject(raw)
                val hasCard = json.optBoolean("hasAssistance", false) || json.has("noKip") || json.has("assistanceNumber")
                if (hasCard) {
                    rbHasAssistance.isChecked = true
                    layoutAssistanceDetails.visibility = View.VISIBLE
                    layoutStep5Bantuan.visibility = View.VISIBLE

                    val astType = json.optString("assistanceType", "KIP (Kartu Indonesia Pintar)")
                    val aIdx = assistanceTypes.indexOfFirst { it.startsWith(astType.take(3), ignoreCase = true) }
                    if (aIdx != -1) spAssistanceType.setSelection(aIdx)

                    val num = json.optString("assistanceNumber", json.optString("noKip", json.optString("noPkh", "")))
                    etAssistanceNumber.setText(num)

                    if (json.has("bantuanFileUrl") && json.optString("bantuanFileUrl").isNotEmpty()) {
                        existingBantuanUrl = json.optString("bantuanFileUrl")
                        tvBantuanFileName.text = "📄 Kartu Bantuan Tersimpan di Server TU"
                        tvStep5BantuanFileName.text = "📄 Kartu Bantuan Tersimpan di Server TU"
                        loadRemoteImage(existingBantuanUrl!!, ivBantuanPreview)
                        loadRemoteImage(existingBantuanUrl!!, ivStep5BantuanPreview)
                    }
                } else {
                    rbNoAssistance.isChecked = true
                    layoutAssistanceDetails.visibility = View.GONE
                    layoutStep5Bantuan.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Tampilkan preview berkas tersimpan
        if (!sub.kkFileUrl.isNullOrEmpty()) {
            tvKkFileName.text = "📄 Berkas Kartu Keluarga Tersimpan di Server TU"
            tvKkStatusBadge.text = "✅ Tersimpan di Server"
            tvKkStatusBadge.setTextColor(Color.parseColor("#059669"))
            loadRemoteImage(sub.kkFileUrl, ivKkPreview)
        }
        if (!sub.aktaFileUrl.isNullOrEmpty()) {
            tvAktaFileName.text = "📄 Berkas Akta Kelahiran Tersimpan di Server TU"
            tvAktaStatusBadge.text = "✅ Tersimpan di Server"
            tvAktaStatusBadge.setTextColor(Color.parseColor("#059669"))
            loadRemoteImage(sub.aktaFileUrl, ivAktaPreview)
        }
    }

    private fun renderStatusBanner(status: String, isToggleOpen: Boolean, isLocked: Boolean, rejectionReason: String?) {
        layoutStatusNotice.visibility = View.VISIBLE

        when {
            status == "APPROVED" || isLocked -> {
                layoutStatusNotice.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D1FAE5"))
                tvStatusNoticeIcon.text = "✅"
                tvStatusNoticeTitle.text = "STATUS: DISETUJUI & DISINKRONKAN TU"
                tvStatusNoticeTitle.setTextColor(Color.parseColor("#065F46"))
                tvStatusNoticeDetail.text = "Data biodata ananda telah sah diverifikasi oleh Staf TU dan disinkronkan ke pangkalan data sekolah. Formulir terkunci (Read-Only)."
                tvStatusNoticeDetail.setTextColor(Color.parseColor("#047857"))
            }
            status == "REJECTED" -> {
                layoutStatusNotice.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FEE2E2"))
                tvStatusNoticeIcon.text = "❌"
                tvStatusNoticeTitle.text = "STATUS: PERLU PERBAIKAN FORMULIR"
                tvStatusNoticeTitle.setTextColor(Color.parseColor("#991B1B"))
                tvStatusNoticeDetail.text = "Catatan Petugas TU: ${rejectionReason ?: "Mohon perbaiki data yang belum lengkap dan unggah foto asli dokumen."}"
                tvStatusNoticeDetail.setTextColor(Color.parseColor("#B91C1C"))
            }
            status == "PENDING" -> {
                layoutStatusNotice.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FEF3C7"))
                tvStatusNoticeIcon.text = "⏳"
                tvStatusNoticeTitle.text = "STATUS: MENUNGGU VERIFIKASI STAF TU"
                tvStatusNoticeTitle.setTextColor(Color.parseColor("#92400E"))
                tvStatusNoticeDetail.text = "Data tersimpan di antrean validasi TU SMPN 1 Boyolangu. Anda masih dapat memperbarui data sebelum disetujui."
                tvStatusNoticeDetail.setTextColor(Color.parseColor("#B45309"))
            }
            !isToggleOpen -> {
                layoutStatusNotice.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FEE2E2"))
                tvStatusNoticeIcon.text = "🔒"
                tvStatusNoticeTitle.text = "PERIODE PENGISIAN DITUTUP"
                tvStatusNoticeTitle.setTextColor(Color.parseColor("#991B1B"))
                tvStatusNoticeDetail.text = "Periode pengisian biodata sedang ditutup oleh pihak sekolah. Hubungi pihak Tata Usaha untuk info lebih lanjut."
                tvStatusNoticeDetail.setTextColor(Color.parseColor("#B91C1C"))
            }
            else -> {
                layoutStatusNotice.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EFF6FF"))
                tvStatusNoticeIcon.text = "📝"
                tvStatusNoticeTitle.text = "STATUS: FORMULIR BELUM DIKIRIM"
                tvStatusNoticeTitle.setTextColor(Color.parseColor("#1E40AF"))
                tvStatusNoticeDetail.text = "Lengkapi 5 langkah identitas dan berkas pendukung, kemudian tekan 'KIRIM KE TU SEKARANG'."
                tvStatusNoticeDetail.setTextColor(Color.parseColor("#1D4ED8"))
            }
        }
    }

    private fun lockFormFields() {
        val viewsToLock = listOf<View>(
            etStudentName, etStudentNisn, etStudentNik, etStudentPob, etStudentDob,
            rbMale, rbFemale, spReligion,
            etAddressStreet, etAddressRt, etAddressRw, etAddressPostalCode, etAddressVillage, etAddressDistrict,
            etFatherName, rbFatherAlive, rbFatherDeceased, etFatherJob, etFatherIncome,
            etMotherName, rbMotherAlive, rbMotherDeceased, etMotherJob, etMotherIncome,
            etParentPhone,
            rbNoAssistance, rbHasAssistance, spAssistanceType, etAssistanceNumber,
            btnCameraBantuan, btnUploadBantuan,
            btnCameraKk, btnUploadKk,
            btnCameraAkta, btnUploadAkta,
            btnCameraStep5Bantuan, btnUploadStep5Bantuan
        )
        for (v in viewsToLock) {
            v.isEnabled = false
            v.isFocusable = false
            if (v is EditText) {
                v.setTextColor(Color.parseColor("#334155"))
            }
        }
        btnNextStep.isEnabled = currentStep < 5
    }

    private fun confirmAndSubmitBiodata() {
        if (!validateStep(5)) return

        AlertDialog.Builder(this)
            .setTitle("Kirim ke TU Sekarang?")
            .setMessage("Seluruh data identitas pribadi, alamat, data ayah & ibu (pekerjaan & penghasilan terpisah), kartu bantuan, serta berkas asli KK & Akta akan diserahkan ke Petugas TU SMPN 1 Boyolangu untuk diverifikasi.")
            .setPositiveButton("🚀 Ya, Kirim Sekarang") { _, _ ->
                executeSubmission()
            }
            .setNegativeButton("Periksa Lagi", null)
            .show()
    }

    private fun executeSubmission() {
        val s1Json = JSONObject().apply {
            put("nama", etStudentName.text.toString().trim())
            put("nisn", etStudentNisn.text.toString().trim())
            put("nik", etStudentNik.text.toString().trim())
            put("tempatLahir", etStudentPob.text.toString().trim())
            put("tanggalLahir", etStudentDob.text.toString().trim())
            put("jenisKelamin", if (rbMale.isChecked) "L" else "P")
            put("agama", spReligion.selectedItem?.toString() ?: "Islam")
        }.toString()

        val s2Json = JSONObject().apply {
            put("alamat", etAddressStreet.text.toString().trim())
            put("rt", etAddressRt.text.toString().trim())
            put("rw", etAddressRw.text.toString().trim())
            put("kodePos", etAddressPostalCode.text.toString().trim())
            put("desa", etAddressVillage.text.toString().trim())
            put("kecamatan", etAddressDistrict.text.toString().trim())
        }.toString()

        val s3Json = JSONObject().apply {
            put("namaAyah", etFatherName.text.toString().trim())
            put("statusAyah", if (rbFatherDeceased.isChecked) "Sudah Meninggal" else "Masih Hidup")
            put("pekerjaanAyah", etFatherJob.text.toString().trim())
            put("penghasilanAyah", etFatherIncome.text.toString().trim())

            put("namaIbu", etMotherName.text.toString().trim())
            put("statusIbu", if (rbMotherDeceased.isChecked) "Sudah Meninggal" else "Masih Hidup")
            put("pekerjaanIbu", etMotherJob.text.toString().trim())
            put("penghasilanIbu", etMotherIncome.text.toString().trim())

            put("noHpOrtu", etParentPhone.text.toString().trim())
        }.toString()

        val s4Json = JSONObject().apply {
            val hasCard = rbHasAssistance.isChecked
            put("hasAssistance", hasCard)
            if (hasCard) {
                put("assistanceType", spAssistanceType.selectedItem?.toString() ?: "KIP")
                put("assistanceNumber", etAssistanceNumber.text.toString().trim())
                put("bantuanFileUploaded", selectedBantuanFile != null || !existingBantuanUrl.isNullOrEmpty())
            }
        }.toString()

        val s5Json = JSONObject().apply {
            put("kkUploaded", selectedKkFile != null || !existingKkUrl.isNullOrEmpty())
            put("aktaUploaded", selectedAktaFile != null || !existingAktaUrl.isNullOrEmpty())
            put("bantuanUploaded", selectedBantuanFile != null || !existingBantuanUrl.isNullOrEmpty())
        }.toString()

        val textType = MediaType.parse("text/plain")
        val s1Body = RequestBody.create(textType, s1Json)
        val s2Body = RequestBody.create(textType, s2Json)
        val s3Body = RequestBody.create(textType, s3Json)
        val s4Body = RequestBody.create(textType, s4Json)
        val s5Body = RequestBody.create(textType, s5Json)

        var kkPart: MultipartBody.Part? = null
        selectedKkFile?.let { file ->
            val mime = if (file.name.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "image/jpeg"
            val requestFile = RequestBody.create(MediaType.parse(mime), file)
            kkPart = MultipartBody.Part.createFormData("kk", file.name, requestFile)
        }

        var aktaPart: MultipartBody.Part? = null
        selectedAktaFile?.let { file ->
            val mime = if (file.name.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "image/jpeg"
            val requestFile = RequestBody.create(MediaType.parse(mime), file)
            aktaPart = MultipartBody.Part.createFormData("akta", file.name, requestFile)
        }

        var bantuanPart: MultipartBody.Part? = null
        selectedBantuanFile?.let { file ->
            val mime = if (file.name.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "image/jpeg"
            val requestFile = RequestBody.create(MediaType.parse(mime), file)
            bantuanPart = MultipartBody.Part.createFormData("bantuan", file.name, requestFile)
        }

        btnNextStep.isEnabled = false
        btnNextStep.text = "⏳ Mengirim Formulir ke TU..."

        ApiClient.getClient(this).submitBiodata(
            s1Body, s2Body, s3Body, s4Body, s5Body, kkPart, aktaPart, bantuanPart
        ).enqueue(object : Callback<BiodataSubmissionResponse> {
            override fun onResponse(call: Call<BiodataSubmissionResponse>, response: Response<BiodataSubmissionResponse>) {
                btnNextStep.isEnabled = true
                btnNextStep.text = "🚀 KIRIM KE TU SEKARANG"

                if (response.isSuccessful && response.body()?.success == true) {
                    AlertDialog.Builder(this@ParentBiodataActivity)
                        .setTitle("🎉 Berhasil Terkirim ke TU")
                        .setMessage(response.body()?.message ?: "Formulir biodata siswa berhasil dikirim ke antrean verifikasi Staf Tata Usaha.")
                        .setPositiveButton("OK") { _, _ ->
                            loadBiodataSubmission()
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    val errMsg = response.errorBody()?.string() ?: response.body()?.message ?: "Pengiriman formulir gagal"
                    Toast.makeText(this@ParentBiodataActivity, "Gagal: $errMsg", Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<BiodataSubmissionResponse>, t: Throwable) {
                btnNextStep.isEnabled = true
                btnNextStep.text = "🚀 KIRIM KE TU SEKARANG"
                Toast.makeText(this@ParentBiodataActivity, "Kesalahan jaringan: ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun loadRemoteImage(url: String, imageView: ImageView) {
        imageExecutor.execute {
            try {
                val fullUrl = if (url.startsWith("http")) url else ApiClient.getFullApiBaseUrl(this) + url
                val conn = java.net.URL(fullUrl).openConnection()
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val bitmap = BitmapFactory.decodeStream(conn.getInputStream())
                if (bitmap != null) {
                    mainHandler.post {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

// ============================================================================
// SMART CBT / SIAKAD - MODUL DIGITAL EXIT PASS & IZIN KELUAR MEJA BK
// TWO-STEP VERIFICATION & REALTIME TIKET HIJAU SATPAM
// ============================================================================

/// Model Pengajuan & Status Izin BK Siswa
class StudentLeaveData {
  final String id;
  final String leaveType; // SAKIT_PULANG, IZIN_PULANG_MENDESAK, DISPENSASI_LOMBA
  final String reason;
  final String? eventName;
  final String? pickupPerson;
  final String status; // 'submitted', 'approved_by_bk', 'checked_out'
  final DateTime appliedAt;
  final DateTime? approvedAt;
  final DateTime? checkedOutAt;
  final String? counselorNotes;
  final String? stationName;
  final String studentName;
  final String className;
  final String nisn;
  final String? profilePicUrl;

  StudentLeaveData({
    required this.id,
    required this.leaveType,
    required this.reason,
    this.eventName,
    this.pickupPerson,
    required this.status,
    required this.appliedAt,
    this.approvedAt,
    this.checkedOutAt,
    this.counselorNotes,
    this.stationName,
    required this.studentName,
    required this.className,
    required this.nisn,
    this.profilePicUrl,
  });

  factory StudentLeaveData.fromJson(Map<String, dynamic> json) {
    final student = json['student'] ?? {};
    return StudentLeaveData(
      id: json['id'] ?? '',
      leaveType: json['leaveType'] ?? 'SAKIT_PULANG',
      reason: json['reason'] ?? '',
      eventName: json['eventName'],
      pickupPerson: json['pickupPerson'],
      status: json['status'] ?? 'submitted',
      appliedAt: DateTime.tryParse(json['appliedAt'] ?? '') ?? DateTime.now(),
      approvedAt: json['approvedAt'] != null ? DateTime.tryParse(json['approvedAt']) : null,
      checkedOutAt: json['checkedOutAt'] != null ? DateTime.tryParse(json['checkedOutAt']) : null,
      counselorNotes: json['counselorNotes'],
      stationName: json['stationName'] ?? json['station']?['stationName'],
      studentName: student['name'] ?? 'Siswa',
      className: student['className'] ?? '-',
      nisn: student['nisn'] ?? '-',
      profilePicUrl: student['profilePicUrl'],
    );
  }
}

/// Halaman Utama Izin BK & Exit Pass
class ExitPassMainPage extends StatefulWidget {
  final String baseUrl;
  final String jwtToken;

  const ExitPassMainPage({
    Key? key,
    required this.baseUrl,
    required this.jwtToken,
  }) : super(key: key);

  @override
  State<ExitPassMainPage> createState() => _ExitPassMainPageState();
}

class _ExitPassMainPageState extends State<ExitPassMainPage> {
  bool _isLoading = true;
  StudentLeaveData? _activeLeave;

  // Form Controllers
  String _selectedLeaveType = 'SAKIT_PULANG';
  final _reasonController = TextEditingController();
  final _eventNameController = TextEditingController();
  final _pickupPersonController = TextEditingController();
  bool _isSubmitting = false;

  @override
  void initState() {
    super.initState();
    _fetchActiveLeave();
  }

  @override
  void dispose() {
    _reasonController.dispose();
    _eventNameController.dispose();
    _pickupPersonController.dispose();
    super.dispose();
  }

  /// 1. Ambil Data Izin Aktif Siswa Hari Ini
  Future<void> _fetchActiveLeave() async {
    setState(() => _isLoading = true);
    try {
      final res = await http.get(
        Uri.parse('${widget.baseUrl}/api/v1/student/leaves/active'),
        headers: {
          'Authorization': 'Bearer ${widget.jwtToken}',
          'Content-Type': 'application/json',
        },
      );
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        if (data['hasActiveLeave'] == true && data['data'] != null) {
          setState(() {
            _activeLeave = StudentLeaveData.fromJson(data['data']);
          });
        } else {
          setState(() => _activeLeave = null);
        }
      }
    } catch (e) {
      debugPrint('Error fetch active leave: $e');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  /// 2. Submit Pengajuan Izin Baru oleh Siswa
  Future<void> _submitApplication() async {
    if (_reasonController.text.trim().length < 5) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Alasan izin wajib diisi minimal 5 karakter.')),
      );
      return;
    }

    setState(() => _isSubmitting = true);
    try {
      final res = await http.post(
        Uri.parse('${widget.baseUrl}/api/v1/student/leaves/apply'),
        headers: {
          'Authorization': 'Bearer ${widget.jwtToken}',
          'Content-Type': 'application/json',
        },
        body: jsonEncode({
          'leaveType': _selectedLeaveType,
          'reason': _reasonController.text.trim(),
          'eventName': _selectedLeaveType == 'DISPENSASI_LOMBA' ? _eventNameController.text.trim() : null,
          'pickupPerson': _pickupPersonController.text.trim().isNotEmpty ? _pickupPersonController.text.trim() : null,
        }),
      );

      final data = jsonDecode(res.body);
      if (res.statusCode == 200 || res.statusCode == 201) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(data['message'] ?? 'Pengajuan izin berhasil diajukan!'), backgroundColor: Colors.green),
        );
        _fetchActiveLeave();
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(data['message'] ?? 'Gagal mengajukan izin'), backgroundColor: Colors.red),
        );
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Terjadi kesalahan jaringan: $e'), backgroundColor: Colors.red),
      );
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  /// 3. Buka Kamera Scanner QR Meja BK
  void _openBkScanner() async {
    final scannedToken = await Navigator.push<String>(
      context,
      MaterialPageRoute(builder: (context) => const BkStationScannerPage()),
    );

    if (scannedToken != null && scannedToken.isNotEmpty) {
      _processBkCheckout(scannedToken);
    }
  }

  /// 4. Kirim Checkout Token ke Backend
  Future<void> _processBkCheckout(String qrToken) async {
    setState(() => _isLoading = true);
    try {
      final res = await http.post(
        Uri.parse('${widget.baseUrl}/api/v1/student/leaves/checkout-bk'),
        headers: {
          'Authorization': 'Bearer ${widget.jwtToken}',
          'Content-Type': 'application/json',
        },
        body: jsonEncode({
          'qrSecretToken': qrToken,
          'leaveId': _activeLeave?.id,
        }),
      );

      final data = jsonDecode(res.body);
      if (res.statusCode == 200 && data['success'] == true) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(data['message'] ?? '🎉 Checkout Meja BK Berhasil!'),
            backgroundColor: const Color(0xFF059669),
          ),
        );
        _fetchActiveLeave();
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(data['message'] ?? 'Gagal checkout'), backgroundColor: Colors.red),
        );
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Koneksi terganggu: $e'), backgroundColor: Colors.red),
      );
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF8FAFC),
      appBar: AppBar(
        title: const Text('Izin Pulang & Exit Pass BK', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
        backgroundColor: const Color(0xFF0A2E5C),
        foregroundColor: Colors.white,
        elevation: 0,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _fetchActiveLeave,
            tooltip: 'Refresh Status',
          )
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _activeLeave == null
              ? _buildFormApplication()
              : _activeLeave!.status == 'checked_out'
                  ? DigitalExitPassView(
                      leave: _activeLeave!,
                      baseUrl: widget.baseUrl,
                    )
                  : _buildWaitingOrReadyVerification(),
    );
  }

  /// Tampilan Form Pengajuan Izin
  Widget _buildFormApplication() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Card(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        elevation: 2,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: const [
                  Text('📝 ', style: TextStyle(fontSize: 20)),
                  Text(
                    'Formulir Izin Siswa Pulang / Keluar',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15, color: Color(0xFF0F172A)),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              const Text(
                'Pengajuan akan langsung masuk ke antrean verifikasi Guru BK.',
                style: TextStyle(fontSize: 12, color: Color(0xFF64748B)),
              ),
              const Divider(height: 24),
              const Text('Pilih Kategori Izin *', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
              const SizedBox(height: 8),
              RadioListTile<String>(
                value: 'SAKIT_PULANG',
                groupValue: _selectedLeaveType,
                title: const Text('🤒 Sakit Pulang (Perlu Istirahat/Periksa)', style: TextStyle(fontSize: 13)),
                onChanged: (val) => setState(() => _selectedLeaveType = val!),
                dense: true,
              ),
              RadioListTile<String>(
                value: 'IZIN_PULANG_MENDESAK',
                groupValue: _selectedLeaveType,
                title: const Text('⚠️ Izin Pulang Mendesak (Kepentingan Keluarga)', style: TextStyle(fontSize: 13)),
                onChanged: (val) => setState(() => _selectedLeaveType = val!),
                dense: true,
              ),
              RadioListTile<String>(
                value: 'DISPENSASI_LOMBA',
                groupValue: _selectedLeaveType,
                title: const Text('🏆 Dispensasi Lomba / Kegiatan Sekolah', style: TextStyle(fontSize: 13)),
                onChanged: (val) => setState(() => _selectedLeaveType = val!),
                dense: true,
              ),
              if (_selectedLeaveType == 'DISPENSASI_LOMBA') ...[
                const SizedBox(height: 8),
                TextField(
                  controller: _eventNameController,
                  decoration: InputDecoration(
                    labelText: 'Nama Lomba / Event Resmi',
                    hintText: 'Cth: O2SN / Lomba Karya Ilmiah',
                    border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                    filled: true,
                    fillColor: const Color(0xFFF8FAFC),
                  ),
                ),
              ],
              const SizedBox(height: 12),
              TextField(
                controller: _reasonController,
                maxLines: 3,
                decoration: InputDecoration(
                  labelText: 'Alasan / Deskripsi Detail *',
                  hintText: 'Tuliskan alasan izin Anda secara jelas...',
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                  filled: true,
                  fillColor: const Color(0xFFF8FAFC),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _pickupPersonController,
                decoration: InputDecoration(
                  labelText: 'Nama Penjemput / Keluarga (Jika Dijemput)',
                  hintText: 'Cth: Ibu Kandung / Ayah',
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                  filled: true,
                  fillColor: const Color(0xFFF8FAFC),
                ),
              ),
              const SizedBox(height: 20),
              SizedBox(
                width: double.infinity,
                height: 48,
                child: ElevatedButton.icon(
                  onPressed: _isSubmitting ? null : _submitApplication,
                  icon: _isSubmitting
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                      : const Icon(Icons.send_rounded),
                  label: Text(_isSubmitting ? 'Mengirim...' : '📤 Ajukan Izin ke Guru BK'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0xFF2563EB),
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Tampilan State 1 (LOCKED) & State 2 (ACTIVE)
  Widget _buildWaitingOrReadyVerification() {
    final isLocked = _activeLeave!.status == 'submitted';

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          Card(
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
            elevation: 3,
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                Container(
                  padding: const EdgeInsets.all(16),
                  color: isLocked ? const Color(0xFFFEF3C7) : const Color(0xFFD1FAE5),
                  child: Row(
                    children: [
                      Text(isLocked ? '⏳' : '✅', style: const TextStyle(fontSize: 28)),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              isLocked ? 'Menunggu Persetujuan Guru BK' : 'Izin Disetujui Guru BK!',
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                fontSize: 14,
                                color: isLocked ? const Color(0xFF92400E) : const Color(0xFF065F46),
                              ),
                            ),
                            Text(
                              isLocked
                                  ? 'Tombol scanner QR meja BK masih terkunci hingga diverifikasi.'
                                  : 'Silakan datang ke Ruang BK dan pindai stiker QR di Meja BK.',
                              style: TextStyle(
                                fontSize: 11.5,
                                color: isLocked ? const Color(0xFFB45309) : const Color(0xFF047857),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.all(18),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                        decoration: BoxDecoration(
                          color: const Color(0xFFEFF6FF),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Text(
                          'Kategori: ${_activeLeave!.leaveType}',
                          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 12, color: Color(0xFF1D4ED8)),
                        ),
                      ),
                      const SizedBox(height: 10),
                      Text('Alasan: ${_activeLeave!.reason}', style: const TextStyle(fontSize: 13, color: Color(0xFF334155))),
                      const SizedBox(height: 4),
                      Text(
                        'Penjemput: ${_activeLeave!.pickupPerson ?? "Mandiri"}',
                        style: const TextStyle(fontSize: 12, color: Color(0xFF64748B)),
                      ),
                      if (_activeLeave!.counselorNotes != null) ...[
                        const SizedBox(height: 12),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: const Color(0xFFF0FDF4),
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(color: const Color(0xFFBBF7D0)),
                          ),
                          child: Text(
                            'Catatan BK: ${_activeLeave!.counselorNotes}',
                            style: const TextStyle(fontSize: 12, color: Color(0xFF166534), fontWeight: FontWeight.w600),
                          ),
                        ),
                      ],
                      const SizedBox(height: 24),
                      SizedBox(
                        width: double.infinity,
                        height: 50,
                        child: ElevatedButton.icon(
                          onPressed: isLocked ? null : _openBkScanner,
                          icon: Icon(isLocked ? Icons.lock_outline_rounded : Icons.qr_code_scanner_rounded),
                          label: Text(
                            isLocked
                                ? '🔒 Pemindai QR Meja BK Terkunci'
                                : '📷 Buka Pemindai QR Meja BK (Checkout Fisik)',
                            style: const TextStyle(fontWeight: FontWeight.bold),
                          ),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: isLocked ? const Color(0xFF94A3B8) : const Color(0xFF059669),
                            foregroundColor: Colors.white,
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                          ),
                        ),
                      ),
                      const SizedBox(height: 8),
                      Center(
                        child: TextButton.icon(
                          onPressed: _fetchActiveLeave,
                          icon: const Icon(Icons.refresh, size: 16),
                          label: const Text('Periksa Status Persetujuan BK', style: TextStyle(fontSize: 12)),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Halaman Kamera Scanner QR Meja BK
class BkStationScannerPage extends StatefulWidget {
  const BkStationScannerPage({Key? key}) : super(key: key);

  @override
  State<BkStationScannerPage> createState() => _BkStationScannerPageState();
}

class _BkStationScannerPageState extends State<BkStationScannerPage> {
  final MobileScannerController _cameraController = MobileScannerController();
  bool _isScanned = false;

  @override
  void dispose() {
    _cameraController.dispose();
    super.dispose();
  }

  void _onDetect(BarcodeCapture capture) {
    if (_isScanned) return;
    final List<Barcode> barcodes = capture.barcodes;
    for (final barcode in barcodes) {
      if (barcode.rawValue != null && barcode.rawValue!.isNotEmpty) {
        _isScanned = true;
        Navigator.pop(context, barcode.rawValue);
        break;
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Scan QR Meja Konseling BK'),
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
      ),
      body: Stack(
        children: [
          MobileScanner(
            controller: _cameraController,
            onDetect: _onDetect,
          ),
          Center(
            child: Container(
              width: 250,
              height: 250,
              decoration: BoxDecoration(
                border: Border.all(color: const Color(0xFF10B981), width: 3),
                borderRadius: BorderRadius.circular(20),
              ),
            ),
          ),
          Positioned(
            bottom: 40,
            left: 20,
            right: 20,
            child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.black.withOpacity(0.7),
                borderRadius: BorderRadius.circular(12),
              ),
              child: const Text(
                'Arahkan kamera ke stiker akrilik QR Code di Meja Guru BK',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white, fontSize: 13),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Halaman Digital Exit Pass (TIKET HIJAU SATPAM) dengan Jam Realtime Berjalan
class DigitalExitPassView extends StatefulWidget {
  final StudentLeaveData leave;
  final String baseUrl;

  const DigitalExitPassView({
    Key? key,
    required this.leave,
    required this.baseUrl,
  }) : super(key: key);

  @override
  State<DigitalExitPassView> createState() => _DigitalExitPassViewState();
}

class _DigitalExitPassViewState extends State<DigitalExitPassView> {
  late Timer _clockTimer;
  String _currentTime = '';

  @override
  void initState() {
    super.initState();
    _updateClock();
    _clockTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (mounted) _updateClock();
    });
  }

  void _updateClock() {
    setState(() {
      _currentTime = DateFormat('HH:mm:ss').format(DateTime.now()) + ' WIB';
    });
  }

  @override
  void dispose() {
    _clockTimer.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final photoUrl = widget.leave.profilePicUrl;
    final fullPhotoUrl = photoUrl != null
        ? (photoUrl.startsWith('http') ? photoUrl : '${widget.baseUrl.replaceAll(RegExp(r'/+$'), '')}/$photoUrl')
        : null;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Card(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
        elevation: 8,
        color: const Color(0xFF059669),
        child: Padding(
          padding: const EdgeInsets.all(22),
          child: Column(
            children: [
              // Header
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('🛡️ SMPN 1 BOYOLANGU', style: TextStyle(color: Color(0xFFD1FAE5), fontWeight: FontWeight.bold, fontSize: 11)),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(color: const Color(0xFF065F46), borderRadius: BorderRadius.circular(6)),
                    child: const Text('RESMI SIAKAD BK', style: TextStyle(color: Color(0xFFA7F3D0), fontWeight: FontWeight.bold, fontSize: 10)),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              const Text(
                'DIGITAL EXIT PASS',
                style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w900, letterSpacing: 1.2),
              ),
              const Text(
                'Surat Izin Resmi Keluar Lingkungan Sekolah',
                style: TextStyle(color: Color(0xFFD1FAE5), fontSize: 12),
              ),
              const SizedBox(height: 14),

              // Live Dynamic Clock
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 6),
                decoration: BoxDecoration(color: const Color(0xFF064E3B), borderRadius: BorderRadius.circular(20)),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('⏰ JAM SAH: ', style: TextStyle(color: Color(0xFFA7F3D0), fontWeight: FontWeight.bold, fontSize: 12)),
                    Text(_currentTime, style: const TextStyle(color: Color(0xFFFDE047), fontWeight: FontWeight.bold, fontSize: 14, fontFamily: 'monospace')),
                  ],
                ),
              ),
              const SizedBox(height: 18),

              // Profile Photo
              CircleAvatar(
                radius: 44,
                backgroundColor: Colors.white,
                child: CircleAvatar(
                  radius: 41,
                  backgroundImage: fullPhotoUrl != null ? NetworkImage(fullPhotoUrl) : null,
                  child: fullPhotoUrl == null ? const Icon(Icons.person, size: 48, color: Colors.grey) : null,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                widget.leave.studentName,
                style: const TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.bold),
              ),
              Text(
                'Kelas ${widget.leave.className} • NISN: ${widget.leave.nisn}',
                style: const TextStyle(color: Color(0xFFD1FAE5), fontSize: 12),
              ),
              const SizedBox(height: 18),

              // White Details Card
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(16)),
                child: Column(
                  children: [
                    _buildRow('Jenis Izin', widget.leave.leaveType == 'SAKIT_PULANG' ? 'Sakit Pulang' : widget.leave.leaveType, isHighlight: true),
                    const Divider(height: 14),
                    _buildRow('Alasan', widget.leave.reason),
                    const Divider(height: 14),
                    _buildRow('Penjemput', widget.leave.pickupPerson ?? 'Mandiri / Keluarga'),
                    const Divider(height: 14),
                    _buildRow('Meja Checkout', widget.leave.stationName ?? 'Meja Konseling BK 1'),
                    const Divider(height: 14),
                    _buildRow(
                      'Waktu Checkout',
                      widget.leave.checkedOutAt != null ? DateFormat('HH:mm WIB').format(widget.leave.checkedOutAt!) : '-',
                    ),
                    const Divider(height: 20),
                    Column(
                      children: [
                        Text(
                          '*BK-EXITPASS-${widget.leave.id.substring(0, 8).toUpperCase()}*',
                          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14, color: Color(0xFF059669), fontFamily: 'monospace'),
                        ),
                        const SizedBox(height: 4),
                        const Text(
                          'Tunjukkan tiket hijau ini ke Petugas Satpam di Pintu Gerbang',
                          style: TextStyle(fontSize: 10.5, color: Color(0xFF64748B)),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 14),
              const Text(
                '✅ Jurnal KBM Sisa Hari Otomatis Ter-update & Presensi GATE_OUT Sah',
                textAlign: TextAlign.center,
                style: TextStyle(color: Color(0xFFD1FAE5), fontSize: 11, fontWeight: FontWeight.bold),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildRow(String label, String value, {bool isHighlight = false}) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: const TextStyle(color: Color(0xFF64748B), fontSize: 12)),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            value,
            textAlign: TextAlign.right,
            style: TextStyle(
              fontWeight: FontWeight.bold,
              fontSize: 12.5,
              color: isHighlight ? const Color(0xFF059669) : const Color(0xFF0F172A),
            ),
          ),
        ),
      ],
    );
  }
}

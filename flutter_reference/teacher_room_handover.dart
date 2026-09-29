import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';

// ============================================================================
// SMART SCHOOL / SIAKAD - MODUL ESTAFET SESI MENGAJAR GURU (ROOM HANDOVER)
// INTEGRASI HARDWARE NFC TAP, FALLBACK QR CODE, & PRESENSI SISWA TERKUNCI GERBANG
// ============================================================================

/// Model Identitas Fisik Ruang Kelas / Fasilitas Sekolah
class PhysicalRoom {
  final String id;
  final String roomCode;
  final String roomName;
  final String? nfcTagUid;
  final String? qrSecretToken;
  final String? className;
  final String? building;
  final int floor;
  final bool isActive;

  PhysicalRoom({
    required this.id,
    required this.roomCode,
    required this.roomName,
    this.nfcTagUid,
    this.qrSecretToken,
    this.className,
    this.building,
    this.floor = 1,
    this.isActive = true,
  });

  factory PhysicalRoom.fromJson(Map<String, dynamic> json) {
    return PhysicalRoom(
      id: json['id'] ?? '',
      roomCode: json['roomCode'] ?? '',
      roomName: json['roomName'] ?? '',
      nfcTagUid: json['nfcTagUid'],
      qrSecretToken: json['qrSecretToken'],
      className: json['className'],
      building: json['building'],
      floor: json['floor'] ?? 1,
      isActive: json['isActive'] ?? true,
    );
  }
}

/// Model Sesi Mengajar Aktif (KBM)
class TeachingSession {
  final String id;
  final String teacherId;
  final String roomId;
  final String className;
  final String subjectName;
  final DateTime checkInTime;
  final DateTime? checkOutTime;
  final String checkInMethod; // 'NFC', 'QR', 'MANUAL'
  final String? checkOutMethod;
  final String status; // 'IN_PROGRESS', 'COMPLETED', 'CLOSED_BY_HANDOVER'
  final int studentCount;
  final int presentCount;
  final int truantCount;
  final int sickCount;
  final int permitCount;
  final int absentCount;
  final String? teachingSummary;
  final String? notes;
  final PhysicalRoom? room;
  final List<StudentSubjectAttendance> studentAttendances;

  TeachingSession({
    required this.id,
    required this.teacherId,
    required this.roomId,
    required this.className,
    required this.subjectName,
    required this.checkInTime,
    this.checkOutTime,
    required this.checkInMethod,
    this.checkOutMethod,
    required this.status,
    this.studentCount = 0,
    this.presentCount = 0,
    this.truantCount = 0,
    this.sickCount = 0,
    this.permitCount = 0,
    this.absentCount = 0,
    this.teachingSummary,
    this.notes,
    this.room,
    this.studentAttendances = const [],
  });

  factory TeachingSession.fromJson(Map<String, dynamic> json) {
    final attendancesJson = json['studentAttendances'] as List<dynamic>? ?? [];
    return TeachingSession(
      id: json['id'] ?? '',
      teacherId: json['teacherId'] ?? '',
      roomId: json['roomId'] ?? '',
      className: json['className'] ?? '',
      subjectName: json['subjectName'] ?? '',
      checkInTime: DateTime.tryParse(json['checkInTime'] ?? '') ?? DateTime.now(),
      checkOutTime: json['checkOutTime'] != null ? DateTime.tryParse(json['checkOutTime']) : null,
      checkInMethod: json['checkInMethod'] ?? 'NFC',
      checkOutMethod: json['checkOutMethod'],
      status: json['status'] ?? 'IN_PROGRESS',
      studentCount: json['studentCount'] ?? 0,
      presentCount: json['presentCount'] ?? 0,
      truantCount: json['truantCount'] ?? 0,
      sickCount: json['sickCount'] ?? 0,
      permitCount: json['permitCount'] ?? 0,
      absentCount: json['absentCount'] ?? 0,
      teachingSummary: json['teachingSummary'],
      notes: json['notes'],
      room: json['room'] != null ? PhysicalRoom.fromJson(json['room']) : null,
      studentAttendances: attendancesJson.map((a) => StudentSubjectAttendance.fromJson(a)).toList(),
    );
  }
}

/// Model Presensi Siswa per Mata Pelajaran & Flag Kunci Gerbang Pagi
class StudentSubjectAttendance {
  final String id;
  final String sessionId;
  final String studentId;
  String status; // 'PRESENT', 'TRUANT', 'SICK', 'PERMISSION', 'ABSENT', 'NOT_CHECKED_IN'
  final String? morningGateStatus; // 'PRESENT', 'LATE', 'SICK', 'PERMISSION', 'NOT_ARRIVED'
  final bool isLockedByGate;
  final String? notes;
  final String studentName;
  final String nisn;
  final String? profilePicUrl;

  StudentSubjectAttendance({
    required this.id,
    required this.sessionId,
    required this.studentId,
    required this.status,
    this.morningGateStatus,
    this.isLockedByGate = false,
    this.notes,
    required this.studentName,
    required this.nisn,
    this.profilePicUrl,
  });

  factory StudentSubjectAttendance.fromJson(Map<String, dynamic> json) {
    final student = json['student'] ?? {};
    return StudentSubjectAttendance(
      id: json['id'] ?? '',
      sessionId: json['sessionId'] ?? '',
      studentId: json['studentId'] ?? '',
      status: json['status'] ?? 'NOT_CHECKED_IN',
      morningGateStatus: json['morningGateStatus'],
      isLockedByGate: json['isLockedByGate'] ?? false,
      notes: json['notes'],
      studentName: student['name'] ?? 'Siswa',
      nisn: student['nisn'] ?? '-',
      profilePicUrl: student['profilePicUrl'],
    );
  }
}

// ============================================================================
// SERVICE: API INTEGRATION ROOM HANDOVER & TEACHING SESSION
// ============================================================================

class TeacherRoomHandoverService {
  final String baseUrl;
  final String authToken;

  TeacherRoomHandoverService({required this.baseUrl, required this.authToken});

  Map<String, String> get _headers => {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer $authToken',
      };

  /// 1. Check-In Ruangan (NFC / QR / Room Code)
  Future<Map<String, dynamic>> checkIn({
    String? roomCode,
    String? nfcTagUid,
    String? qrSecretToken,
    bool forceHandover = false,
    String? subjectName,
  }) async {
    final url = Uri.parse('$baseUrl/api/v1/teacher/session/checkin');
    final resp = await http.post(
      url,
      headers: _headers,
      body: jsonEncode({
        if (roomCode != null) 'roomCode': roomCode,
        if (nfcTagUid != null) 'nfcTagUid': nfcTagUid,
        if (qrSecretToken != null) 'qrSecretToken': qrSecretToken,
        'forceHandover': forceHandover,
        if (subjectName != null) 'subjectName': subjectName,
      }),
    );

    final data = jsonDecode(resp.body) as Map<String, dynamic>;
    return {
      'statusCode': resp.statusCode,
      'data': data,
    };
  }

  /// 2. Check-Out Sesi Mengajar & Jurnal KBM
  Future<bool> checkOut({
    required String sessionId,
    String? teachingSummary,
    String? notes,
  }) async {
    final url = Uri.parse('$baseUrl/api/v1/teacher/session/checkout');
    final resp = await http.post(
      url,
      headers: _headers,
      body: jsonEncode({
        'sessionId': sessionId,
        'teachingSummary': teachingSummary ?? 'KBM terlaksana dengan baik.',
        'notes': notes,
      }),
    );

    if (resp.statusCode == 200) {
      final body = jsonDecode(resp.body);
      return body['success'] == true;
    }
    return false;
  }

  /// 3. Ambil Sesi Mengajar Guru Saat Ini
  Future<TeachingSession?> getCurrentSession() async {
    final url = Uri.parse('$baseUrl/api/v1/teacher/session/current');
    final resp = await http.get(url, headers: _headers);

    if (resp.statusCode == 200) {
      final data = jsonDecode(resp.body);
      if (data['success'] == true && data['hasActiveSession'] == true && data['session'] != null) {
        return TeachingSession.fromJson(data['session']);
      }
    }
    return null;
  }

  /// 4. Ubah Presensi Siswa Tertentu (Marking Hadir/Bolos/Sakit/Izin)
  Future<bool> updateStudentAttendance({
    required String sessionId,
    required String studentId,
    required String status,
    String? notes,
  }) async {
    final url = Uri.parse('$baseUrl/api/v1/teacher/session/$sessionId/attendances/update');
    final resp = await http.post(
      url,
      headers: _headers,
      body: jsonEncode({
        'studentId': studentId,
        'status': status,
        if (notes != null) 'notes': notes,
      }),
    );

    if (resp.statusCode == 200) {
      final body = jsonDecode(resp.body);
      return body['success'] == true;
    }
    return false;
  }

  /// 5. Daftar Ruangan Fisik Sekolah
  Future<List<PhysicalRoom>> getPhysicalRooms() async {
    final url = Uri.parse('$baseUrl/api/v1/teacher/session/rooms');
    final resp = await http.get(url, headers: _headers);

    if (resp.statusCode == 200) {
      final body = jsonDecode(resp.body);
      if (body['success'] == true && body['data'] != null) {
        final list = body['data'] as List<dynamic>;
        return list.map((r) => PhysicalRoom.fromJson(r)).toList();
      }
    }
    return [];
  }
}

// ============================================================================
// UI COMPONENT: DASHBOARD HERO CARD KEHADIRAN KELAS YANG DIAMPU
// ============================================================================

class TeacherRoomHandoverHeroCard extends StatelessWidget {
  final TeachingSession? activeSession;
  final VoidCallback onTapCheckIn;
  final VoidCallback onManageStudents;
  final VoidCallback onCloseSession;
  final VoidCallback onSelectManual;

  const TeacherRoomHandoverHeroCard({
    Key? key,
    required this.activeSession,
    required this.onTapCheckIn,
    required this.onManageStudents,
    required this.onCloseSession,
    required this.onSelectManual,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final isTeaching = activeSession != null && activeSession!.status == 'IN_PROGRESS';
    final timeFmt = DateFormat('HH:mm');

    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      color: Colors.white,
      child: Padding(
        padding: const EdgeInsets.all(14.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Top Header: Icon + Title + Status Badge
            Row(
              children: [
                Container(
                  width: 42,
                  height: 42,
                  decoration: BoxDecoration(
                    color: isTeaching ? const Color(0xFFE0F2FE) : const Color(0xFFEEF2FF),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Center(
                    child: Text(
                      isTeaching ? '🏫' : '📱',
                      style: const TextStyle(fontSize: 20),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Text(
                            'KEHADIRAN KELAS KBM',
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.bold,
                              color: isTeaching ? const Color(0xFF0369A1) : const Color(0xFF4F46E5),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                            decoration: BoxDecoration(
                              color: isTeaching ? const Color(0xFFD1FAE5) : const Color(0xFFFEF3C7),
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Text(
                              isTeaching ? '🟢 SEDANG MENGAJAR' : 'STANDBY',
                              style: TextStyle(
                                fontSize: 9.5,
                                fontWeight: FontWeight.bold,
                                color: isTeaching ? const Color(0xFF065F46) : const Color(0xFF92400E),
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 3),
                      Text(
                        isTeaching
                            ? '${activeSession!.room?.roomName ?? "Kelas ${activeSession!.className}"} • ${activeSession!.subjectName}'
                            : 'Belum Ada Sesi Mengajar Aktif',
                        style: const TextStyle(
                          fontSize: 13.5,
                          fontWeight: FontWeight.bold,
                          color: Color(0xFF0F172A),
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),

            // Subtitle Description
            Text(
              isTeaching
                  ? 'Masuk Pukul: ${timeFmt.format(activeSession!.checkInTime)} WIB • Presensi Siswa AKTIF di APK Siswa'
                  : 'Tap stiker NFC atau scan QR di meja/pintu kelas untuk membuka KBM & mengaktifkan presensi siswa.',
              style: TextStyle(
                fontSize: 11,
                color: isTeaching ? const Color(0xFF059669) : const Color(0xFF64748B),
                fontWeight: isTeaching ? FontWeight.w600 : FontWeight.normal,
              ),
            ),

            // Live Attendance Counter (Visible when Active)
            if (isTeaching) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: const Color(0xFFF8FAFC),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: const Color(0xFFE2E8F0)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'REKAP PRESENSI SISWA KELAS INI:',
                      style: TextStyle(fontSize: 10, fontWeight: FontWeight.bold, color: Color(0xFF4338CA)),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        _buildStatTile('Hadir', activeSession!.presentCount, const Color(0xFF15803D), const Color(0xFFF0FDF4)),
                        const SizedBox(width: 4),
                        _buildStatTile('Bolos', activeSession!.truantCount, const Color(0xFFDC2626), const Color(0xFFFEF2F2)),
                        const SizedBox(width: 4),
                        _buildStatTile('Sakit/Izin', activeSession!.sickCount + activeSession!.permitCount, const Color(0xFFD97706), const Color(0xFFFFFBEB)),
                        const SizedBox(width: 4),
                        _buildStatTile(
                          'Belum',
                          (activeSession!.studentCount - (activeSession!.presentCount + activeSession!.truantCount + activeSession!.sickCount + activeSession!.permitCount)).clamp(0, 999),
                          const Color(0xFF64748B),
                          const Color(0xFFF1F5F9),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],

            const SizedBox(height: 12),

            // Action Buttons
            if (!isTeaching)
              Row(
                children: [
                  Expanded(
                    flex: 3,
                    child: ElevatedButton.icon(
                      onPressed: onTapCheckIn,
                      icon: const Icon(Icons.nfc, size: 18),
                      label: const Text('TAP NFC / SCAN QR', style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.bold)),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF4F46E5),
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 11),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    flex: 2,
                    child: ElevatedButton.icon(
                      onPressed: onSelectManual,
                      icon: const Icon(Icons.meeting_room_outlined, size: 16),
                      label: const Text('PILIH KELAS', style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold)),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF0D9488),
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 11),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                      ),
                    ),
                  ),
                ],
              )
            else
              Row(
                children: [
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: onManageStudents,
                      icon: const Icon(Icons.people_outline, size: 17),
                      label: const Text('CEK & PRESENSI SISWA', style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold)),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF2563EB),
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 11),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton.icon(
                    onPressed: onCloseSession,
                    icon: const Icon(Icons.stop_circle_outlined, size: 17),
                    label: const Text('SELESAI KBM', style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold)),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFFDC2626),
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    ),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatTile(String label, int count, Color textColor, Color bgColor) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 6),
        decoration: BoxDecoration(
          color: bgColor,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(
          children: [
            Text(
              '$count',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold, color: textColor),
            ),
            Text(
              label,
              style: TextStyle(fontSize: 9.5, color: textColor.withOpacity(0.85)),
            ),
          ],
        ),
      ),
    );
  }
}

// ============================================================================
// MODAL SHEET: MANAJEMEN PRESENSI SISWA DENGAN LOCK INDIKATOR GERBANG PAGI
// ============================================================================

class StudentAttendanceSheet extends StatefulWidget {
  final TeachingSession session;
  final TeacherRoomHandoverService service;

  const StudentAttendanceSheet({
    Key? key,
    required this.session,
    required this.service,
  }) : super(key: key);

  @override
  State<StudentAttendanceSheet> createState() => _StudentAttendanceSheetState();
}

class _StudentAttendanceSheetState extends State<StudentAttendanceSheet> {
  late List<StudentSubjectAttendance> _attendances;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _attendances = List.from(widget.session.studentAttendances);
  }

  Future<void> _updateStatus(StudentSubjectAttendance att, String newStatus) async {
    if (att.isLockedByGate && newStatus == 'PRESENT') {
      showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('🔒 Presensi Terkunci Gerbang Pagi'),
          content: Text('Siswa ${att.studentName} tercatat ${att.morningGateStatus} di gerbang masuk pagi hari ini.\n\nStatus terkunci untuk menjaga integritas data presensi sekolah.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Mengerti')),
          ],
        ),
      );
      return;
    }

    setState(() => _isLoading = true);
    final success = await widget.service.updateStudentAttendance(
      sessionId: widget.session.id,
      studentId: att.studentId,
      status: newStatus,
    );

    if (mounted) {
      setState(() {
        _isLoading = false;
        if (success) {
          att.status = newStatus;
        }
      });
      if (!success) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Gagal memperbarui status presensi')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final roomName = widget.session.room?.roomName ?? 'Kelas ${widget.session.className}';

    return Container(
      height: MediaQuery.of(context).size.height * 0.88,
      decoration: const BoxDecoration(
        color: Color(0xFFF8FAFC),
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      child: Column(
        children: [
          // Header Multi-color Gradient
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                colors: [Color(0xFF312E81), Color(0xFF6366F1), Color(0xFFEC4899)],
              ),
              borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
            ),
            child: Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.close, color: Colors.white),
                  onPressed: () => Navigator.pop(context),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '👥 Presensi Siswa: $roomName',
                        style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white),
                      ),
                      Text(
                        'Mapel: ${widget.session.subjectName} • Presensi Mandiri Siswa Aktif',
                        style: const TextStyle(fontSize: 11, color: Color(0xFFD1FAE5)),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),

          // Students List
          Expanded(
            child: ListView.builder(
              padding: const EdgeInsets.all(12),
              itemCount: _attendances.length,
              itemBuilder: (context, index) {
                final att = _attendances[index];
                return Card(
                  elevation: 1.5,
                  margin: const EdgeInsets.only(bottom: 10),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                  child: Padding(
                    padding: const EdgeInsets.all(12.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            CircleAvatar(
                              backgroundColor: const Color(0xFFE2E8F0),
                              radius: 20,
                              backgroundImage: att.profilePicUrl != null ? NetworkImage(att.profilePicUrl!) : null,
                              child: att.profilePicUrl == null ? const Icon(Icons.person, color: Color(0xFF64748B)) : null,
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    att.studentName,
                                    style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.bold, color: Color(0xFF0F172A)),
                                  ),
                                  Text(
                                    'NISN: ${att.nisn}',
                                    style: const TextStyle(fontSize: 11, color: Color(0xFF64748B)),
                                  ),
                                ],
                              ),
                            ),
                            _buildStatusBadge(att.status),
                          ],
                        ),

                        // Locked Indicator
                        if (att.isLockedByGate) ...[
                          const SizedBox(height: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                            decoration: BoxDecoration(
                              color: const Color(0xFFFEE2E2),
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                const Icon(Icons.lock, size: 12, color: Color(0xFFDC2626)),
                                const SizedBox(width: 4),
                                Text(
                                  'Terkunci: Tercatat ${att.morningGateStatus} di Gerbang Masuk Pagi',
                                  style: const TextStyle(fontSize: 10, fontWeight: FontWeight.bold, color: Color(0xFFDC2626)),
                                ),
                              ],
                            ),
                          ),
                        ],

                        const SizedBox(height: 10),

                        // Action Buttons: Hadir, Bolos, Sakit, Izin
                        Row(
                          children: [
                            _buildQuickActionBtn('🟢 Hadir', const Color(0xFF059669), () => _updateStatus(att, 'PRESENT')),
                            const SizedBox(width: 4),
                            _buildQuickActionBtn('🔴 Bolos', const Color(0xFFDC2626), () => _updateStatus(att, 'TRUANT')),
                            const SizedBox(width: 4),
                            _buildQuickActionBtn('🟡 Sakit', const Color(0xFFD97706), () => _updateStatus(att, 'SICK')),
                            const SizedBox(width: 4),
                            _buildQuickActionBtn('🔵 Izin', const Color(0xFF2563EB), () => _updateStatus(att, 'PERMISSION')),
                          ],
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatusBadge(String status) {
    Color bg;
    Color text;
    String label;

    switch (status) {
      case 'PRESENT':
        bg = const Color(0xFFD1FAE5);
        text = const Color(0xFF15803D);
        label = 'HADIR';
        break;
      case 'TRUANT':
        bg = const Color(0xFFFEE2E2);
        text = const Color(0xFFDC2626);
        label = 'BOLOS';
        break;
      case 'SICK':
        bg = const Color(0xFFFEF3C7);
        text = const Color(0xFFD97706);
        label = 'SAKIT';
        break;
      case 'PERMISSION':
        bg = const Color(0xFFEFF6FF);
        text = const Color(0xFF1D4ED8);
        label = 'IZIN';
        break;
      default:
        bg = const Color(0xFFF1F5F9);
        text = const Color(0xFF64748B);
        label = 'BELUM ABSEN';
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(6)),
      child: Text(
        label,
        style: TextStyle(fontSize: 10, fontWeight: FontWeight.bold, color: text),
      ),
    );
  }

  Widget _buildQuickActionBtn(String label, Color color, VoidCallback onTap) {
    return Expanded(
      child: SizedBox(
        height: 32,
        child: ElevatedButton(
          onPressed: _isLoading ? null : onTap,
          style: ElevatedButton.styleFrom(
            backgroundColor: color,
            foregroundColor: Colors.white,
            padding: EdgeInsets.zero,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
          ),
          child: Text(label, style: const TextStyle(fontSize: 10, fontWeight: FontWeight.bold)),
        ),
      ),
    );
  }
}

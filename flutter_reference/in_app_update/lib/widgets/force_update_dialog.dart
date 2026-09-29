import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import '../models/app_version_info.dart';
import '../services/update_manager_service.dart';

/// Status alur proses pembaruan aplikasi
enum UpdateState {
  idle,
  checkingPermission,
  permissionDenied,
  downloading,
  installing,
  error,
}

/// Dialog Fullscreen / Pop-up Non-Dismissible untuk Force Update & Pembaruan Aplikasi.
/// Menggunakan [PopScope] modern (kompatibel Flutter 3.12+) untuk menolak tombol Back Android.
class ForceUpdateDialog extends StatefulWidget {
  final AppVersionInfo versionInfo;
  final String currentVersion;
  final VoidCallback? onDismissOptional;

  const ForceUpdateDialog({
    super.key,
    required this.versionInfo,
    required this.currentVersion,
    this.onDismissOptional,
  });

  /// Helper statis untuk menampilkan dialog pembaruan
  static Future<void> show({
    required BuildContext context,
    required AppVersionInfo versionInfo,
    required String currentVersion,
    VoidCallback? onDismissOptional,
  }) {
    final bool isForce = versionInfo.isMandatory(currentVersion);

    return showDialog<void>(
      context: context,
      barrierDismissible: !isForce,
      barrierColor: Colors.black.withOpacity(0.75),
      builder: (BuildContext dialogContext) {
        return ForceUpdateDialog(
          versionInfo: versionInfo,
          currentVersion: currentVersion,
          onDismissOptional: onDismissOptional,
        );
      },
    );
  }

  @override
  State<ForceUpdateDialog> createState() => _ForceUpdateDialogState();
}

class _ForceUpdateDialogState extends State<ForceUpdateDialog> with SingleTickerProviderStateMixin {
  final UpdateManagerService _updateService = UpdateManagerService.instance;
  CancelToken? _cancelToken;

  UpdateState _state = UpdateState.idle;
  double _downloadPercent = 0.0;
  String _progressText = 'Menyiapkan unduhan...';
  String _errorMessage = '';
  File? _downloadedApkFile;

  late AnimationController _animController;
  late Animation<double> _scaleAnimation;

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
    );
    _scaleAnimation = CurvedAnimation(
      parent: _animController,
      curve: Curves.easeOutBack,
    );
    _animController.forward();
  }

  @override
  void dispose() {
    _cancelToken?.cancel('Dialog disposed');
    _animController.dispose();
    super.dispose();
  }

  bool get _isForce => widget.versionInfo.isMandatory(widget.currentVersion);

  /// Memulai proses update: Cek izin -> Download -> Auto-install
  Future<void> _startUpdateProcess() async {
    setState(() {
      _state = UpdateState.checkingPermission;
      _errorMessage = '';
    });

    // 1. Cek & minta izin instalasi dari sumber tidak dikenal (Unknown Sources)
    final hasPermission = await _updateService.checkInstallPermission();
    if (!hasPermission) {
      final granted = await _updateService.requestInstallPermission();
      if (!granted) {
        setState(() {
          _state = UpdateState.permissionDenied;
          _errorMessage =
              'Izin instalasi aplikasi diperlukan. Silakan aktifkan sakelar "Izinkan dari sumber ini" di Pengaturan.';
        });
        return;
      }
    }

    // 2. Mulai unduhan APK
    setState(() {
      _state = UpdateState.downloading;
      _downloadPercent = 0.0;
      _progressText = 'Menghubungkan ke server...';
    });

    try {
      _cancelToken = CancelToken();
      final fileName = 'SmartSchool_v${widget.versionInfo.latestVersion}.apk';

      final file = await _updateService.downloadApk(
        downloadUrl: widget.versionInfo.downloadUrl,
        targetFileName: fileName,
        cancelToken: _cancelToken,
        onProgress: (received, total, percent, progressString) {
          if (mounted) {
            setState(() {
              _downloadPercent = percent;
              _progressText = progressString;
            });
          }
        },
      );

      _downloadedApkFile = file;

      // 3. Memicu installer paket Android
      setState(() {
        _state = UpdateState.installing;
        _progressText = 'Membuka pemasang paket Android...';
      });

      final success = await _updateService.installApk(file.path);
      if (!success && mounted) {
        setState(() {
          _state = UpdateState.error;
          _errorMessage =
              'Gagal membuka layar instalasi. Pastikan izin instalasi telah diaktifkan, lalu tekan tombol "Pasang Sekarang" di bawah.';
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _state = UpdateState.error;
          _errorMessage = e.toString().replaceFirst('Exception: ', '');
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    // PopScope: Mengunci tombol back fisik dan gesture Android jika force update
    return PopScope(
      canPop: !_isForce && _state != UpdateState.downloading && _state != UpdateState.installing,
      onPopInvoked: (didPop) {
        if (didPop) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Pembaruan wajib dilakukan untuk melanjutkan penggunaan aplikasi.'),
            duration: Duration(seconds: 2),
            backgroundColor: Color(0xFFDC2626),
          ),
        );
      },
      child: ScaleTransition(
        scale: _scaleAnimation,
        child: Dialog(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24.0)),
          elevation: 16,
          backgroundColor: Colors.white,
          insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
          child: Padding(
            padding: const EdgeInsets.all(24.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _buildHeader(),
                const SizedBox(height: 16),
                _buildVersionBadges(),
                const SizedBox(height: 16),
                _buildChangelogBox(),
                const SizedBox(height: 20),
                _buildDynamicContent(),
                const SizedBox(height: 12),
                _buildActionButtons(),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// Header dialog dengan icon dan judul pembaruan
  Widget _buildHeader() {
    return Row(
      children: [
        Container(
          width: 48,
          height: 48,
          decoration: BoxDecoration(
            color: const Color(0xFFEFF6FF),
            borderRadius: BorderRadius.circular(14),
          ),
          child: const Icon(
            Icons.system_update_rounded,
            color: Color(0xFF2563EB),
            size: 28,
          ),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _isForce ? 'Pembaruan Wajib' : 'Pembaruan Tersedia',
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: Color(0xFF0F172A),
                ),
              ),
              const SizedBox(height: 2),
              Text(
                _isForce
                    ? 'Versi aplikasi Anda sudah tidak didukung.'
                    : 'Fitur baru dan peningkatan performa.',
                style: const TextStyle(
                  fontSize: 12,
                  color: Color(0xFF64748B),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// Badges perbandingan versi saat ini vs versi server
  Widget _buildVersionBadges() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: const Color(0xFFF8FAFC),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFFE2E8F0)),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(
            children: [
              const Text(
                'Saat ini: ',
                style: TextStyle(fontSize: 12, color: Color(0xFF64748B)),
              ),
              Text(
                'v${widget.currentVersion}',
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                  color: Color(0xFF475569),
                ),
              ),
            ],
          ),
          const Icon(Icons.arrow_forward_rounded, size: 16, color: Color(0xFF94A3B8)),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: const Color(0xFFDCFCE7),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              'v${widget.versionInfo.latestVersion}',
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.bold,
                color: Color(0xFF166534),
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// Kotak ringkasan catatan rilis (changelog)
  Widget _buildChangelogBox() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Catatan Pembaruan:',
          style: TextStyle(
            fontSize: 12.5,
            fontWeight: FontWeight.bold,
            color: Color(0xFF334155),
          ),
        ),
        const SizedBox(height: 6),
        Container(
          constraints: const BoxConstraints(maxHeight: 120),
          width: double.infinity,
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: const Color(0xFFF1F5F9),
            borderRadius: BorderRadius.circular(12),
          ),
          child: SingleChildScrollView(
            child: Text(
              widget.versionInfo.changelog.trim(),
              style: const TextStyle(
                fontSize: 12,
                color: Color(0xFF475569),
                height: 1.4,
              ),
            ),
          ),
        ),
      ],
    );
  }

  /// Komponen dinamis yang berubah sesuai state: Progress bar, Error box, atau Info
  Widget _buildDynamicContent() {
    switch (_state) {
      case UpdateState.idle:
        return const SizedBox.shrink();

      case UpdateState.checkingPermission:
        return const Row(
          children: [
            SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            SizedBox(width: 12),
            Text(
              'Memeriksa izin instalasi...',
              style: TextStyle(fontSize: 12, color: Color(0xFF64748B)),
            ),
          ],
        );

      case UpdateState.permissionDenied:
        return Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: const Color(0xFFFEF3C7),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: const Color(0xFFFCD34D)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Row(
                children: [
                  Icon(Icons.warning_amber_rounded, size: 18, color: Color(0xFFD97706)),
                  SizedBox(width: 8),
                  Text(
                    'Izin Instalasi Diperlukan',
                    style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.bold, color: Color(0xFF92400E)),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                _errorMessage,
                style: const TextStyle(fontSize: 11.5, color: Color(0xFF78350F)),
              ),
            ],
          ),
        );

      case UpdateState.downloading:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: LinearProgressIndicator(
                value: _downloadPercent > 0.0 ? _downloadPercent : null,
                minHeight: 10,
                backgroundColor: const Color(0xFFE2E8F0),
                valueColor: const AlwaysStoppedAnimation<Color>(Color(0xFF2563EB)),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Text(
                    _progressText,
                    style: const TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w600,
                      color: Color(0xFF475569),
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Text(
                  '${(_downloadPercent * 100).toInt()}%',
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFF2563EB),
                  ),
                ),
              ],
            ),
          ],
        );

      case UpdateState.installing:
        return Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: const Color(0xFFEFF6FF),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              const SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2.5, color: Color(0xFF2563EB)),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  _progressText,
                  style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Color(0xFF1E40AF)),
                ),
              ),
            ],
          ),
        );

      case UpdateState.error:
        return Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: const Color(0xFFFEE2E2),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: const Color(0xFFFCA5A5)),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(Icons.error_outline_rounded, size: 18, color: Color(0xFFDC2626)),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  _errorMessage,
                  style: const TextStyle(fontSize: 11.5, color: Color(0xFF991B1B)),
                ),
              ),
            ],
          ),
        );
    }
  }

  /// Tombol aksi utama (Update Sekarang, Buka Pengaturan, Coba Lagi)
  Widget _buildActionButtons() {
    if (_state == UpdateState.downloading || _state == UpdateState.installing) {
      return const SizedBox.shrink();
    }

    if (_state == UpdateState.permissionDenied) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ElevatedButton.icon(
            onPressed: () async {
              await _updateService.requestInstallPermission();
              // Reset status agar user bisa menekan update kembali setelah dari setting
              setState(() => _state = UpdateState.idle);
            },
            icon: const Icon(Icons.settings_outlined, size: 18),
            label: const Text('Buka Pengaturan Izin'),
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFF2563EB),
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
        ],
      );
    }

    if (_state == UpdateState.error && _downloadedApkFile != null) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ElevatedButton.icon(
            onPressed: () => _updateService.installApk(_downloadedApkFile!.path),
            icon: const Icon(Icons.install_mobile_rounded, size: 18),
            label: const Text('Pasang Sekarang (Manual)'),
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFF16A34A),
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
          const SizedBox(height: 8),
          TextButton(
            onPressed: _startUpdateProcess,
            child: const Text('Unduh Ulang'),
          ),
        ],
      );
    }

    final bool isRetry = _state == UpdateState.error;

    return Row(
      children: [
        if (!_isForce) ...[
          Expanded(
            child: TextButton(
              onPressed: () {
                Navigator.of(context).pop();
                widget.onDismissOptional?.call();
              },
              style: TextButton.styleFrom(
                foregroundColor: const Color(0xFF64748B),
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              child: const Text('Nanti Saja'),
            ),
          ),
          const SizedBox(width: 12),
        ],
        Expanded(
          flex: _isForce ? 1 : 1,
          child: ElevatedButton(
            onPressed: _startUpdateProcess,
            style: ElevatedButton.styleFrom(
              backgroundColor: isRetry ? const Color(0xFFDC2626) : const Color(0xFF2563EB),
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 14),
              elevation: 0,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
            child: Text(
              isRetry ? 'Coba Lagi' : 'Update Sekarang',
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13.5),
            ),
          ),
        ),
      ],
    );
  }
}

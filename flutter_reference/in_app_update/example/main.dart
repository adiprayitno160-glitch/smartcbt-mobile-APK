import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';

// Import modul in_app_update
import '../lib/in_app_update.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Smart School CBT App',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2563EB)),
        useMaterial3: true,
      ),
      home: const SplashScreen(),
    );
  }
}

class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> {
  String _statusMessage = 'Memeriksa versi aplikasi...';
  bool _hasError = false;

  @override
  void initState() {
    super.initState();
    // Jalankan pengecekan versi sesaat setelah frame pertama dirender
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkVersionAndProceed();
    });
  }

  Future<void> _checkVersionAndProceed() async {
    setState(() {
      _statusMessage = 'Menghubungkan ke server sekolah...';
      _hasError = false;
    });

    try {
      final packageInfo = await PackageInfo.fromPlatform();
      final currentVersion = packageInfo.version;

      // Endpoint API cek versi mandiri sekolah
      const endpoint = 'https://cbt.smpn1boyolangu.my.id/api/v1/app/check-version';

      final versionInfo = await UpdateManagerService.instance.checkForUpdate(
        endpointUrl: endpoint,
        currentVersion: currentVersion,
      );

      if (!mounted) return;

      if (versionInfo != null && versionInfo.shouldUpdate(currentVersion)) {
        // Tampilkan dialog pembaruan (wajib / opsional)
        await ForceUpdateDialog.show(
          context: context,
          versionInfo: versionInfo,
          currentVersion: currentVersion,
          onDismissOptional: () {
            // Jika user memilih 'Nanti Saja' pada update opsional
            _navigateToDashboard();
          },
        );
      } else {
        // Versi sudah paling mutakhir
        _navigateToDashboard();
      }
    } catch (e) {
      debugPrint('[Splash] Pengecekan update error atau offline: $e');
      // Bila offline, beri toleransi masuk dashboard jika bukan first-run atau tampilkan retry
      setState(() {
        _hasError = true;
        _statusMessage = 'Gagal memeriksa pembaruan server ($e)';
      });
    }
  }

  void _navigateToDashboard() {
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => const DashboardScreen()),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0F172A),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                width: 90,
                height: 90,
                decoration: BoxDecoration(
                  color: const Color(0xFF1E293B),
                  shape: BoxShape.circle,
                  border: Border.all(color: const Color(0xFF3B82F6), width: 2),
                ),
                child: const Icon(
                  Icons.school_rounded,
                  color: Color(0xFF60A5FA),
                  size: 48,
                ),
              ),
              const SizedBox(height: 24),
              const Text(
                'SMART SCHOOL CBT',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 20,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 1.2,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                _statusMessage,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: Color(0xFF94A3B8),
                  fontSize: 12.5,
                ),
              ),
              const SizedBox(height: 32),
              if (_hasError) ...[
                ElevatedButton.icon(
                  onPressed: _checkVersionAndProceed,
                  icon: const Icon(Icons.refresh_rounded, size: 18),
                  label: const Text('Coba Lagi'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0xFF2563EB),
                    foregroundColor: Colors.white,
                  ),
                ),
                const SizedBox(height: 12),
                TextButton(
                  onPressed: _navigateToDashboard,
                  child: const Text('Lanjutkan Mode Offline', style: TextStyle(color: Color(0xFF64748B))),
                ),
              ] else ...[
                const SizedBox(
                  width: 24,
                  height: 24,
                  child: CircularProgressIndicator(
                    color: Color(0xFF60A5FA),
                    strokeWidth: 2.5,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Beranda Siswa CBT'),
        backgroundColor: const Color(0xFF2563EB),
        foregroundColor: Colors.white,
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.check_circle_outline, color: Color(0xFF16A34A), size: 64),
            const SizedBox(height: 16),
            const Text(
              'Aplikasi Menggunakan Versi Terbaru',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 24),
            ElevatedButton(
              onPressed: () {
                // Contoh memicu cek update manual dari menu profil / setting
                ForceUpdateDialog.show(
                  context: context,
                  versionInfo: const AppVersionInfo(
                    latestVersion: '1.2.0',
                    minSupportedVersion: '1.0.0',
                    downloadUrl: 'https://cbt.smpn1boyolangu.my.id/uploads/smartcbt-latest.apk',
                    changelog: '- Perbaikan koneksi timeout\\n- Penambahan modul Gatepass satpam\\n- Rekap presensi sholat mushola',
                    isForceUpdate: false,
                  ),
                  currentVersion: '1.1.0',
                );
              },
              child: const Text('Simulasi Pop-up Update Manual'),
            ),
          ],
        ),
      ),
    );
  }
}

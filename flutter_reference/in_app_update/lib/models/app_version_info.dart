/// Model data representasi respons endpoint pengecekan versi aplikasi
/// Contoh REST Endpoint: GET /api/v1/app/check-version?platform=android&current_version=1.0.2
class AppVersionInfo {
  final String latestVersion;
  final String minSupportedVersion;
  final String downloadUrl;
  final String changelog;
  final bool isForceUpdate;

  const AppVersionInfo({
    required this.latestVersion,
    required this.minSupportedVersion,
    required this.downloadUrl,
    required this.changelog,
    required this.isForceUpdate,
  });

  /// Factory untuk mem-parsing dari Map JSON REST API
  factory AppVersionInfo.fromJson(Map<String, dynamic> json) {
    final data = json['data'] is Map<String, dynamic> 
        ? json['data'] as Map<String, dynamic> 
        : json;

    return AppVersionInfo(
      latestVersion: (data['latest_version'] ?? data['versionName'] ?? '1.0.0').toString().trim(),
      minSupportedVersion: (data['min_supported_version'] ?? data['minVersion'] ?? '1.0.0').toString().trim(),
      downloadUrl: (data['download_url'] ?? data['downloadUrl'] ?? '').toString().trim(),
      changelog: (data['changelog'] ?? data['releaseNotes'] ?? 'Pembaruan stabilitas dan peningkatan performa.').toString(),
      isForceUpdate: data['is_force_update'] == true || data['isForceUpdate'] == true,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'latest_version': latestVersion,
      'min_supported_version': minSupportedVersion,
      'download_url': downloadUrl,
      'changelog': changelog,
      'is_force_update': isForceUpdate,
    };
  }

  /// Mengecek apakah versi sekarang membutuhkan update (ada versi baru di server)
  bool shouldUpdate(String currentVersion) {
    return compareSemver(currentVersion, latestVersion) < 0;
  }

  /// Mengecek apakah update ini bersifat wajib/paksa (Force Update):
  /// 1. Flag isForceUpdate dari server bernilai true, ATAU
  /// 2. Versi lokal saat ini lebih rendah dari minSupportedVersion
  bool isMandatory(String currentVersion) {
    if (isForceUpdate) return true;
    return compareSemver(currentVersion, minSupportedVersion) < 0;
  }

  /// Pembanding Semantic Versioning (SemVer)
  /// Mengembalikan:
  ///   -1 jika v1 < v2
  ///    0 jika v1 == v2
  ///    1 jika v1 > v2
  /// Mendukung format standar SemVer: major.minor.patch (contoh: 1.0.2 vs 1.1.0)
  /// Menghilangkan prefix non-angka (misal: "v1.2.3" -> "1.2.3") dan build info (+96).
  static int compareSemver(String v1, String v2) {
    final clean1 = _cleanVersion(v1);
    final clean2 = _cleanVersion(v2);

    final parts1 = clean1.split('.').map((e) => int.tryParse(e) ?? 0).toList();
    final parts2 = clean2.split('.').map((e) => int.tryParse(e) ?? 0).toList();

    final maxLen = parts1.length > parts2.length ? parts1.length : parts2.length;
    for (int i = 0; i < maxLen; i++) {
      final p1 = i < parts1.length ? parts1[i] : 0;
      final p2 = i < parts2.length ? parts2[i] : 0;
      if (p1 < p2) return -1;
      if (p1 > p2) return 1;
    }
    return 0;
  }

  static String _cleanVersion(String v) {
    var s = v.trim().toLowerCase();
    if (s.startsWith('v')) {
      s = s.substring(1);
    }
    // Hapus build number seperti +96 atau metadata -release
    if (s.contains('+')) {
      s = s.split('+').first;
    }
    if (s.contains('-')) {
      s = s.split('-').first;
    }
    return s;
  }

  @override
  String toString() {
    return 'AppVersionInfo(latest: $latestVersion, minSupported: $minSupportedVersion, force: $isForceUpdate, url: $downloadUrl)';
  }
}

const http = require('http');

async function testFolders() {
    const loginData = JSON.stringify({ username: 'admin', password: 'password123' });
    const token = await new Promise((resolve, reject) => {
        const req = http.request({
            hostname: 'localhost',
            port: 3000,
            path: '/api/auth/login',
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(loginData) }
        }, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => resolve(JSON.parse(body).token));
        });
        req.on('error', reject);
        req.write(loginData);
        req.end();
    });

    const deviceId = 'b3e1c56e-2992-4cbc-b4b6-64a6b0473255';
    const testFolders = [
        'ALL',
        '📁 media',
        '📷 Kamera (DCIM)',
        '📥 Unduhan (Download)',
        '📥 WA Images (Receive)',
        '📁 file',
        '📄 Dokumen (Documents)',
        '📁 Facebook',
        '📤 WA Images (Sent)'
    ];

    for (const folder of testFolders) {
        const res = await new Promise((resolve, reject) => {
            http.get({
                hostname: 'localhost',
                port: 3000,
                path: `/api/admin/remote-devices/${deviceId}/files?folder=${encodeURIComponent(folder)}&page=1&limit=60`,
                headers: { 'Authorization': `Bearer ${token}` }
            }, (res) => {
                let body = '';
                res.on('data', chunk => body += chunk);
                res.on('end', () => {
                    try { resolve(JSON.parse(body)); } catch(e) { resolve({ error: body }); }
                });
            }).on('error', reject);
        });

        console.log(`Folder "${folder}": returned ${res.files ? res.files.length : 0} files, total: ${res.pagination ? res.pagination.total : 0}`);
        if (res.files && res.files.length > 0) {
            console.log(`   Sample file: ${res.files[0].fileName} -> ${res.files[0].filePath.substring(0, 70)}`);
        }
    }
}
testFolders().catch(console.error);

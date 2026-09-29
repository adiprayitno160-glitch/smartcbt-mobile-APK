const http = require('http');

async function testHttp() {
    // 1. Login to get token
    const loginData = JSON.stringify({ username: 'admin', password: 'password123' });
    const token = await new Promise((resolve, reject) => {
        const req = http.request({
            hostname: 'localhost',
            port: 3000,
            path: '/api/auth/login',
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(loginData)
            }
        }, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => {
                const parsed = JSON.parse(body);
                resolve(parsed.token);
            });
        });
        req.on('error', reject);
        req.write(loginData);
        req.end();
    });

    console.log('Got token:', token ? 'YES (length ' + token.length + ')' : 'NO');

    // 2. Fetch device files
    const deviceId = 'b3e1c56e-2992-4cbc-b4b6-64a6b0473255';
    const filesRes = await new Promise((resolve, reject) => {
        const req = http.request({
            hostname: 'localhost',
            port: 3000,
            path: `/api/admin/remote-devices/${deviceId}/files?folder=ALL&page=1&limit=60`,
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${token}`
            }
        }, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => {
                try {
                    resolve(JSON.parse(body));
                } catch(e) {
                    resolve({ raw: body });
                }
            });
        });
        req.on('error', reject);
        req.end();
    });

    console.log('\nFiles API Response:');
    console.log('Success:', filesRes.success);
    console.log('Total files:', filesRes.pagination ? filesRes.pagination.total : filesRes.total);
    console.log('Files returned:', filesRes.files ? filesRes.files.length : 0);
    if (filesRes.files && filesRes.files.length > 0) {
        console.log('Sample file 1:', filesRes.files[0].fileName, filesRes.files[0].category, filesRes.files[0].fileSizeHuman);
    }
    console.log('Folder counts keys:', filesRes.folderCounts ? Object.keys(filesRes.folderCounts) : 'NONE');
}

testHttp().catch(console.error);

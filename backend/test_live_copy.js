const http = require('http');
const { PrismaClient } = require('C:/APK/backend/node_modules/@prisma/client');
const prisma = new PrismaClient();

async function testCopy() {
    // 1. Login to get token
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
    const postBody = JSON.stringify({
        filePath: '/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/Private/IMG-20260916-WA0027.jpg',
        fileName: 'IMG-20260916-WA0027.jpg'
    });

    console.log('Sending copy request...');
    const copyRes = await new Promise((resolve, reject) => {
        const req = http.request({
            hostname: 'localhost',
            port: 3000,
            path: `/api/admin/remote-devices/${deviceId}/copy-file`,
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(postBody)
            }
        }, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => resolve(JSON.parse(body)));
        });
        req.on('error', reject);
        req.write(postBody);
        req.end();
    });

    console.log('Copy Request Response:', copyRes);
    const requestId = copyRes.requestId;

    // Poll for status up to 30 seconds
    console.log(`Polling status for requestId ${requestId}...`);
    for (let i = 0; i < 10; i++) {
        await new Promise(r => setTimeout(r, 3000));
        const reqDb = await prisma.copyRequest.findUnique({
            where: { id: requestId }
        });
        console.log(`  [Poll ${i+1}] Status: ${reqDb.status}, fileSize: ${reqDb.fileSize}, uploadPath: ${reqDb.uploadPath}, error: ${reqDb.errorMessage}`);
        if (reqDb.status === 'COMPLETED' || reqDb.status === 'FAILED') {
            break;
        }
    }

    await prisma.$disconnect();
}
testCopy().catch(console.error);

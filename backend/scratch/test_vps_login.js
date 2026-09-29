const https = require('https');

const postData = JSON.stringify({
    username: 'admin',
    password: 'password123'
});

const options = {
    hostname: 'cbt.smpn1boyolangu.my.id',
    port: 443,
    path: '/api/auth/login',
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData)
    }
};

const req = https.request(options, (res) => {
    console.log('Status Code:', res.statusCode);
    let data = '';
    res.on('data', chunk => data += chunk);
    res.on('end', () => console.log('Response:', data));
});

req.on('error', (e) => console.error('Error:', e));
req.write(postData);
req.end();

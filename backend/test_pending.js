const http = require('http');

http.get('http://localhost:3000/api/device/pending-requests?deviceAndroidId=4ebca55c1f528b2b', (res) => {
    let body = '';
    res.on('data', chunk => body += chunk);
    res.on('end', () => {
        console.log('Status code:', res.statusCode);
        console.log('Response body:', body);
    });
}).on('error', console.error);

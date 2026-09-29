const http = require('http');

http.get('http://localhost:3000/admin/remote-devices', (res) => {
    let html = '';
    res.on('data', chunk => html += chunk);
    res.on('end', () => {
        const idx = html.indexOf('Tidak ada berkas');
        while (idx !== -1) {
            console.log('--- MATCH AT', idx, '---');
            console.log(html.substring(Math.max(0, idx - 100), Math.min(html.length, idx + 200)));
            break;
        }
    });
});

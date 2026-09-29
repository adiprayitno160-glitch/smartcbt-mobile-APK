const http = require('http');

http.get('http://localhost:3000/admin/remote-devices', (res) => {
    let html = '';
    res.on('data', chunk => html += chunk);
    res.on('end', () => {
        console.log('Status code:', res.statusCode);
        console.log('HTML length:', html.length);
        const hasAmelia = html.includes('AMELIA PUTRI') || html.includes('Xiaomi');
        console.log('Has Amelia/Xiaomi device:', hasAmelia);
        const countMatch = html.match(/Berkas:\s*(\d+)/i) || html.match(/(\d+)\s*Berkas/i) || html.match(/totalFiles['":\s]+(\d+)/i);
        console.log('File count in HTML:', countMatch ? countMatch[0] : 'not found');
        const noFilesMsg = html.includes('Tidak ada berkas yang sesuai dengan folder/filter yang dipilih');
        console.log('Has "Tidak ada berkas..." in HTML:', noFilesMsg);
        
        // Find currentDeviceFiles in script
        const matchFiles = html.match(/let currentDeviceFiles\s*=\s*(\[.*?\]);/s);
        if (matchFiles) {
            try {
                const parsed = JSON.parse(matchFiles[1]);
                console.log('currentDeviceFiles count embedded in HTML:', parsed.length);
            } catch(e) {
                console.log('currentDeviceFiles string length:', matchFiles[1].length);
            }
        } else {
            console.log('currentDeviceFiles definition not matched with regex');
        }
    });
}).on('error', console.error);

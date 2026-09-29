const http = require('http');

http.get('http://localhost:3000/admin/remote-devices', (res) => {
    let html = '';
    res.on('data', chunk => html += chunk);
    res.on('end', () => {
        // Extract initialFiles and initialFolderCounts
        const matchFiles = html.match(/let currentDeviceFiles\s*=\s*(\[.*?\]);\s*let currentFolderCounts/s);
        const matchFolders = html.match(/let currentFolderCounts\s*=\s*(\{.*?\});\s*let currentPage/s);
        
        if (!matchFiles) {
            console.error('Could not match currentDeviceFiles');
            return;
        }
        
        const currentDeviceFiles = JSON.parse(matchFiles[1]);
        const currentFolderCounts = JSON.parse(matchFolders[1]);
        
        console.log('Parsed currentDeviceFiles length:', currentDeviceFiles.length);
        console.log('Folder counts:', currentFolderCounts);

        // Test filterDeviceFiles with currentFolderFilter = 'ALL', cat = 'ALL', search = ''
        let currentFolderFilter = 'ALL';
        let isOnlySelectedFilter = false;
        let selectedFileIds = new Set();

        function extractFolderFromPath(filePath) {
            if (!filePath) return '📁 Penyimpanan Umum';
            const cleanPath = (filePath || '').split('::')[0].trim();
            const norm = cleanPath.replace(/\\/g, '/');
            const lower = norm.toLowerCase();
            const isWa = lower.includes('whatsapp');
            const isSent = norm.includes('/Sent/') || norm.endsWith('/Sent') || lower.includes('sent whatsapp');
            const isImg = /\.(jpg|jpeg|png|webp|gif|bmp|heic|heif)$/i.test(norm) || lower.includes('images');
            const isVid = /\.(mp4|mkv|3gp|avi|webm|mov)$/i.test(norm) || lower.includes('video');

            if (isWa && isImg && isSent) return '📤 WA Images (Sent)';
            if (isWa && isImg && !isSent) return '📥 WA Images (Receive)';
            if (isWa && isVid && isSent) return '📤 WA Video (Sent)';
            if (isWa && isVid && !isSent) return '🎬 WA Video (Receive)';
            if (isWa && (lower.includes('audio') || lower.includes('voice') || /\.(opus|m4a|mp3|ogg|wav|aac)$/i.test(norm))) return '🎵 WA Voice/Audio';
            if (lower.includes('dcim') || lower.includes('camera')) return '📷 Kamera (DCIM)';
            if (lower.includes('screenshots') || lower.includes('screenshot')) return '📱 Screenshots Layar';
            if (lower.includes('movies') || lower.includes('screenrecorder') || lower.includes('screen recorder')) return '🎬 Video & Rekaman Layar';
            if (lower.includes('download')) return '📥 Unduhan (Download)';
            if (lower.includes('documents') || lower.includes('document')) return '📄 Dokumen (Documents)';

            const parts = norm.split('/');
            if (parts.length >= 2) return '📁 ' + parts[parts.length - 2];
            return '📁 Penyimpanan Umum';
        }

        function getFilteredFiles(folderFilter = 'ALL', cat = 'ALL', search = '') {
            return currentDeviceFiles.filter(f => {
                let matchFolder = true;
                if (folderFilter !== 'ALL') {
                    if (f._serverFolder && f._serverFolder === folderFilter) {
                        matchFolder = true;
                    } else {
                        const folderName = extractFolderFromPath(f.filePath);
                        matchFolder = (folderName === folderFilter);
                    }
                }
                const matchCat = cat === 'ALL' || f.category === cat;
                const matchSearch = !search ||
                    (f.fileName || '').toLowerCase().includes(search) ||
                    (f.filePath || '').toLowerCase().includes(search);
                const matchOnlySelected = !isOnlySelectedFilter || selectedFileIds.has(f.id);

                return matchFolder && matchCat && matchSearch && matchOnlySelected;
            });
        }

        console.log('\n--- TESTING CLIENT FILTERS ---');
        console.log('Filtered (folder=ALL, cat=ALL):', getFilteredFiles('ALL', 'ALL').length);

        for (const folderName of Object.keys(currentFolderCounts)) {
            const count = getFilteredFiles(folderName, 'ALL').length;
            console.log(`Folder "${folderName}": expected ${currentFolderCounts[folderName]}, filtered in current 60 files: ${count}`);
        }
    });
});

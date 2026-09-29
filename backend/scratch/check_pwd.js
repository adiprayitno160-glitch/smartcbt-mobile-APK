const bcrypt = require('bcryptjs');
const hash = '$2b$10$GsP1B6ljbnrfU1InJUD68.Qs7Ftgb64x8NqUydBnbbkbPjT0qNXce';

const candidates = ['admin', 'admin123', 'password123', 'Admin123', 'admincbt', 'admincbt123', 'smaboy', 'smpn1boyolangu'];

for (const c of candidates) {
    console.log(c, bcrypt.compareSync(c, hash));
}

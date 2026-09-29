const fs = require('fs');
const content = fs.readFileSync('c:/APK/backend/prisma/schema.prisma', 'utf8');
const models = content.match(/model\s+\w+/g);
console.log(models ? models.join(', ') : 'None');

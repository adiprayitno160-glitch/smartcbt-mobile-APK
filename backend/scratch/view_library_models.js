const fs = require('fs');
const content = fs.readFileSync('c:/APK/backend/prisma/schema.prisma', 'utf8');
const lines = content.split('\n');

let start = false;
let modelLines = [];
lines.forEach((line, idx) => {
  if (line.includes('model LibraryBook') || line.includes('model BookCopy') || line.includes('model LibraryBorrowing')) {
    start = true;
  }
  if (start) {
    modelLines.push(`${idx + 1}: ${line}`);
    if (line.trim() === '}' && modelLines.length > 50) {
      start = false;
    }
  }
});
console.log(modelLines.slice(0, 80).join('\n'));

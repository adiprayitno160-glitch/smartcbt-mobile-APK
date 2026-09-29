cd /var/www/smart-school
npm install --omit=dev
npx prisma generate
pm2 delete smart-school-backend 2>/dev/null || true
pm2 start ecosystem.config.js
pm2 save
pm2 status

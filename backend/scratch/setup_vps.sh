#!/usr/bin/env bash
set -e

echo "=== [1/5] Installing Node.js 20 LTS ==="
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs
node -v
npm -v

echo "=== [2/5] Installing PM2 ==="
npm install -g pm2

echo "=== [3/5] Setting up directories ==="
mkdir -p /var/www/smart-school/uploads/avatars
mkdir -p /var/www/smart-school/public/uploads
mkdir -p /var/www/smart-school/prisma
mkdir -p /var/www/smart-school/src

echo "=== [4/5] Configuring Nginx ==="
cat << 'EOF' > /etc/nginx/sites-available/smart-school
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name cbt.smpn1boyolangu.my.id 104.207.76.73 _;

    client_max_body_size 100M;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
EOF

rm -f /etc/nginx/sites-enabled/default
ln -sf /etc/nginx/sites-available/smart-school /etc/nginx/sites-enabled/smart-school
nginx -t
systemctl restart nginx

echo "=== [5/5] VPS base setup complete! ==="

module.exports = {
  apps: [
    {
      name: "smart-school-backend",
      script: "dist/server.js",
      autorestart: true,
      watch: false,
      max_memory_restart: "1G",
      restart_delay: 2000,
      min_uptime: "5s",
      max_restarts: 100,
      env: {
        NODE_ENV: "development",
      },
      env_production: {
        NODE_ENV: "production",
      }
    }
  ]
};

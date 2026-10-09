// pm2 process for the admin UI without Docker:  pm2 start ecosystem.config.cjs
// Build the UI first (npm ci && npm run build). Override the env below, or set it in the shell.
module.exports = {
  apps: [
    {
      name: 'rpcnode-admin',
      script: 'server.mjs',
      cwd: __dirname,
      env: {
        NODE_ENV: 'production',
        // 0.0.0.0 = reachable from other machines (open the port in the firewall).
        ADMIN_HOST: process.env.ADMIN_HOST || '0.0.0.0',
        ADMIN_PORT: process.env.ADMIN_PORT || '8093',
        // rpcnode-server on this host (default port 8094).
        PANEL_URL: process.env.PANEL_URL || 'http://127.0.0.1:8094',
      },
      autorestart: true,
      max_restarts: 20,
      restart_delay: 2000,
      max_memory_restart: '300M',
    },
  ],
}

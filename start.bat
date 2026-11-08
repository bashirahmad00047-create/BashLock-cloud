@echo off
title BashLock Cloud - Pro Enterprise Console
cd /d "%~dp0"
echo ===================================================
echo     BashLock Cloud - Pro Enterprise Edition
echo ===================================================

echo [1/3] Starting Baileys WhatsApp Bridge on port 3001...
start "BashLock Baileys WhatsApp Bridge" /min node wa_bridge.js

echo [2/3] Starting Cloudflare Auto-Sync Tunnel Runner (updates live_url.txt and syncs GitHub)...
start "BashLock Cloudflare Auto-Sync Tunnel" python run_tunnel.py

echo [3/3] Starting Flask Application Server on http://127.0.0.1:5000...
timeout /t 2 /nobreak >nul
start http://127.0.0.1:5000
python app.py
pause

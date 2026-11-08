@echo off
title BashLock Cloud - Pro Enterprise Console
cd /d "%~dp0"
echo ===================================================
echo     BashLock Cloud - Pro Enterprise Edition
echo ===================================================

echo [1/3] Starting Baileys WhatsApp Bridge on port 3001...
start "BashLock Baileys WhatsApp Bridge" /min node wa_bridge.js

echo [2/3] Starting Cloudflare HTTPS Public Mobile Tunnel...
if exist "C:\Program Files (x86)\cloudflared\cloudflared.exe" (
    start "BashLock Cloudflare Tunnel" /min "C:\Program Files (x86)\cloudflared\cloudflared.exe" tunnel --url http://127.0.0.1:5000 --protocol http2
)

echo [3/3] Starting Flask Application Server on http://127.0.0.1:5000...
timeout /t 2 /nobreak >nul
start http://127.0.0.1:5000
python app.py
pause

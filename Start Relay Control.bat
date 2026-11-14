@echo off
rem Double-click to open WiChat Relay Control in your browser.
rem Keep this window open while you use the relay; closing it stops the relay.
title WiChat Relay Control
cd /d "%~dp0relay-server"
where node >nul 2>nul || (echo Node.js is not installed. Get it from https://nodejs.org & pause & exit /b 1)
if not exist node_modules (echo Installing relay dependencies... & call npm install --no-audit --no-fund)
node control-panel.js
pause

@echo off
setlocal
cd /d "%~dp0"

echo ==================================================
echo          Starting NekoVR / SlimeVR
echo ==================================================

echo [1/2] Starting backend server...
start "NekoVR Backend Server" cmd /k ".\gradlew.bat :server:desktop:run"

echo [2/2] Starting web GUI...
start "NekoVR Web Interface" cmd /k "cd gui && pnpm start"

echo.
echo ==================================================
echo   Web Interface:   http://localhost:5173/
echo   Backend Server:  ws://localhost:21110
echo ==================================================
echo.
pause

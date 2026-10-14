@echo off
setlocal
REM Block Blast Solver — start both local servers:
REM   * APK delivery (port 8788)   — install/update the app
REM   * report receiver (port 8791) — collect reports from the phone
REM
REM Leave this window open while testing. Ctrl+C stops both.

cd /d "%~dp0"

if not exist "dist" mkdir dist
if not exist "reports" mkdir reports

echo === Сервера Block Blast Solver ===
echo.
echo Запускаю приёмник отчётов (8791) и раздачу APK (8788)...
echo Оба окна закроются по Ctrl+C.
echo.

where node >nul 2>nul
if errorlevel 1 (
    echo Node.js не найден. Установи Node и повтори.
    exit /b 1
)

start "BBS reports :8791" cmd /k node tools\report-server.js
timeout /t 1 /nobreak >nul
start "BBS apk :8788" cmd /k node tools\serve-apk.js

echo Готово. Окна серверов открыты отдельно.

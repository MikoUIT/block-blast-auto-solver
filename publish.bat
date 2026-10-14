@echo off
setlocal enabledelayedexpansion
REM Block Blast Solver — build, stage, and (optionally) serve a fresh APK.
REM
REM   publish.bat          build + stage + start the delivery server
REM   publish.bat build    build + stage only
REM
REM The staged file lives in dist\ with a timestamp in the name, and a stable copy
REM named app-debug.apk is refreshed so the install page link never changes.

cd /d "%~dp0"

echo === Сборка APK ===
call gradlew.bat :app:assembleDebug --console=plain
if errorlevel 1 (
    echo Сборка не удалась.
    exit /b 1
)

set APK=app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK%" (
    echo APK не найден: %APK%
    exit /b 1
)

if not exist "dist" mkdir dist

REM Timestamp via PowerShell — wmic is gone on modern Windows.
for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"`) do set STAMP=%%I

copy /y "%APK%" "dist\app-debug.apk" >nul
copy /y "%APK%" "dist\blockblast-solver-!STAMP!.apk" >nul

echo.
echo === Готово ===
echo   dist\app-debug.apk                         (ссылка установки)
echo   dist\blockblast-solver-!STAMP!.apk   (версия с датой)
echo.

if /i "%~1"=="build" goto :eof

where node >nul 2>nul
if errorlevel 1 (
    echo Node.js не найден — сервер раздачи не запущен.
    echo Установи Node или раздавай папку dist вручную.
    goto :eof
)

echo === Запуск сервера раздачи ===
node tools\serve-apk.js

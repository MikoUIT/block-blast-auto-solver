@echo off
REM Adds inbound firewall rules so the phone can reach the local servers.
REM Run by double-clicking and confirming the Windows UAC prompt (Run as administrator).
REM
REM   * 8791 — report receiver (tools/report-server.js)
REM   * 8788 — APK delivery   (tools/serve-apk.js)
REM
REM To remove later:  allow-firewall.bat remove

setlocal
net session >nul 2>nul
if errorlevel 1 (
    echo Требуются права администратора. Закрой это окно и запусти файл,
    echo подтвердив запрос UAC ("Запуск от имени администратора").
    echo.
    pause
    exit /b 1
)

if /i "%~1"=="remove" goto :remove

netsh advfirewall firewall delete rule name="BlockBlast report 8791" >nul 2>nul
netsh advfirewall firewall delete rule name="BlockBlast apk 8788" >nul 2>nul
netsh advfirewall firewall add rule name="BlockBlast report 8791" dir=in action=allow protocol=TCP localport=8791
netsh advfirewall firewall add rule name="BlockBlast apk 8788" dir=in action=allow protocol=TCP localport=8788

echo.
echo Правила добавлены: входящие TCP 8791 и 8788 разрешены.
echo Проверь с телефона: открой в браузере http://192.168.0.139:8791/
goto :end

:remove
netsh advfirewall firewall delete rule name="BlockBlast report 8791"
netsh advfirewall firewall delete rule name="BlockBlast apk 8788"
echo Правила удалены.

:end
echo.
pause

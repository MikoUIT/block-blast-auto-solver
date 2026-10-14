@echo off
setlocal enabledelayedexpansion
REM Block Blast Solver — build and publish an APK to GitHub Releases.
REM
REM   release-github.bat            build + push a new tagged release with the APK
REM   release-github.bat build      build only, stage into dist\ (no network)
REM
REM Requires: gh CLI, authenticated (gh auth login). The repo is created on first
REM run if it does not exist yet.

cd /d "%~dp0"

set REPO_NAME=block-blast-auto-solver
set GH_USER=MikoUIT

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

for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"`) do set STAMP=%%I
set TAG=v!STAMP!

copy /y "%APK%" "dist\app-debug.apk" >nul

if /i "%~1"=="build" (
    echo.
    echo === Готово ^(только сборка^) ===
    echo   dist\app-debug.apk
    goto :eof
)

where gh >nul 2>nul
if errorlevel 1 (
    echo gh CLI не найден. Установи GitHub CLI и выполни: gh auth login
    exit /b 1
)

echo === Проверка доступа к репозиторию ===
gh repo view "%GH_USER%/%REPO_NAME%" >nul 2>nul
if errorlevel 1 (
    echo Репозиторий не найден — создаю публичный %GH_USER%/%REPO_NAME% ...
    gh repo create "%GH_USER%/%REPO_NAME%" --public --source=. --remote=origin --push
    if errorlevel 1 (
        echo Не удалось создать репозиторий.
        exit /b 1
    )
) else (
    REM Make sure a remote exists and the latest code is pushed.
    git remote get-url origin >nul 2>nul
    if errorlevel 1 git remote add origin "https://github.com/%GH_USER%/%REPO_NAME%.git"
    git add -A
    git commit -m "Update %STAMP%" >nul 2>nul
    git push -u origin HEAD
)

echo === Публикация релиза %TAG% ===
gh release create "%TAG%" "dist\app-debug.apk#Block Blast Solver (debug APK)" ^
    --title "Block Blast Solver %TAG%" ^
    --notes "Автосборка %STAMP%. Скачай APK ниже и установи на телефон."
if errorlevel 1 (
    echo Не удалось опубликовать релиз.
    exit /b 1
)

echo.
echo === Готово ===
echo   https://github.com/%GH_USER%/%REPO_NAME%/releases/latest

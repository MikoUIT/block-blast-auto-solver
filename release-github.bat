@echo off
setlocal enabledelayedexpansion
REM Block Blast Solver — build and publish an APK to GitHub Releases.
REM
REM   release-github.bat            build + push a new tagged release with the APK
REM   release-github.bat build      build only, stage into dist\ (no network)
REM
REM The tag is derived from the APK's own versionCode/versionName (read with aapt2) so
REM the in-app "check for updates" button compares versions reliably.
REM Requires the gh CLI, authenticated (gh auth login). Repo is created on first run.

cd /d "%~dp0"

set REPO_NAME=block-blast-auto-solver
set GH_USER=MikoUIT
set AAPT=%LOCALAPPDATA%\Android\Sdk\build-tools\36.1.0\aapt2.exe

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

echo === Чтение версии из APK ===
set BADGING=%TEMP%\bb-badging.txt
"%AAPT%" dump badging "%APK%" | findstr /b /c:"package:" > "%BADGING%"
REM Line shape: package: name='pkg' versionCode='2' versionName='0.2.0' ...
REM Splitting on the apostrophe gives: 1 "package: name=", 2 pkg, 3 " versionCode=", 4 code, 5 " versionName=", 6 name.
for /f "tokens=4,6 delims='" %%A in ('type "%BADGING%"') do (
    set VERSION_CODE=%%A
    set VERSION_NAME=%%B
)
del "%BADGING%" >nul 2>nul
if "%VERSION_CODE%"=="" (
    echo Не удалось прочитать versionCode из APK.
    exit /b 1
)
set TAG=v%VERSION_CODE%-%VERSION_NAME%
echo   версия: %VERSION_NAME% ^(build %VERSION_CODE%^), тег %TAG%

if not exist "dist" mkdir dist
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
    git remote get-url origin >nul 2>nul
    if errorlevel 1 git remote add origin "https://github.com/%GH_USER%/%REPO_NAME%.git"
    git add -A
    git commit -m "Release %VERSION_NAME% (build %VERSION_CODE%)" >nul 2>nul
    git push -u origin HEAD
)

echo === Публикация релиза %TAG% ===
gh release create "%TAG%" "dist\app-debug.apk#Block Blast Solver %VERSION_NAME% (debug APK)" ^
    --title "Block Blast Solver %VERSION_NAME%" ^
    --notes "Версия %VERSION_NAME%, build %VERSION_CODE%. Кнопка «Проверить обновления» в приложении обновит автоматически."
if errorlevel 1 (
    echo Не удалось опубликовать релиз.
    exit /b 1
)

echo.
echo === Готово ===
echo   https://github.com/%GH_USER%/%REPO_NAME%/releases/latest

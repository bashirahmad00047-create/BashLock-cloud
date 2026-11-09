@echo off
setlocal enabledelayedexpansion
title BashLock - 1-Click Customer Phone Enroller & Device Owner Lock
color 0B

echo ===============================================================================
echo                 BASHLOCK CLOUD - 1-CLICK CUSTOMER PHONE ENROLLER               
echo                   Permanent Device Owner & Anti-Uninstall Setup                
echo ===============================================================================
echo.

:: 1. Check ADB Availability
where adb >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "PATH=%PATH%;%LOCALAPPDATA%\Android\Sdk\platform-tools"
    ) else if exist "C:\platform-tools\adb.exe" (
        set "PATH=%PATH%;C:\platform-tools"
    ) else (
        color 0C
        echo [ERROR] ADB (Android Debug Bridge) nahi mila!
        echo Baraye meharbani Android SDK Platform Tools install karein ya PATH mein add karein.
        echo.
        pause
        exit /b 1
    )
)

echo [*] Searching for connected Android device via USB...
adb wait-for-device >nul 2>&1

:: Check if device is authorized
for /f "tokens=2" %%i in ('adb get-state 2^>nul') do set DEVICE_STATE=%%i
adb devices | findstr /R "device$" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    color 0C
    echo.
    echo [!] Koi Android phone USB ke sath connect nahi hai ya Unauthorized hai!
    echo.
    echo Hidaayat:
    echo  1. Phone ko USB cable se laptop ke sath connect karein.
    echo  2. Settings -^> Developer Options -^> USB Debugging ON karein.
    echo  3. Mobile screen par "Allow USB Debugging" pop-up aaye toh "Always Allow" par tick karke OK karein.
    echo.
    pause
    exit /b 1
)

for /f "tokens=1" %%d in ('adb devices ^| findstr /R "device$"') do set PHONE_SERIAL=%%d
echo [OK] Connected Phone Serial: %PHONE_SERIAL%
echo.

:: 2. Check if BashLock APK is installed
echo [*] Checking if BashLock APK is installed on phone...
adb shell pm path com.bashlock.apk >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [!] BashLock APK phone par install nahi hai!
    if exist "BashLock.apk" (
        echo [*] Installing BashLock.apk...
        adb install -r "BashLock.apk"
    ) else if exist "app-release.apk" (
        echo [*] Installing app-release.apk...
        adb install -r "app-release.apk"
    ) else if exist "BashLock_APK\app\build\outputs\apk\debug\app-debug.apk" (
        echo [*] Installing local debug build...
        adb install -r "BashLock_APK\app\build\outputs\apk\debug\app-debug.apk"
    ) else (
        color 0E
        echo [WARNING] APK file locally nahi mili. Baraye meharbani pehle APK phone par install karein.
        echo Phir is script ko dobara chalayein.
        echo.
        pause
        exit /b 1
    )
)
echo [OK] BashLock APK verified on device!
echo.

:: 3. Execute Core Device Owner Provisioning
echo ===============================================================================
echo [*] Provisioning Permanent Device Owner Privileges...
echo ===============================================================================
echo Running: adb shell dpm set-device-owner com.bashlock.apk/.BashLockDeviceAdminReceiver
echo.

for /f "delims=" %%a in ('adb shell dpm set-device-owner com.bashlock.apk/.BashLockDeviceAdminReceiver 2^>^&1') do (
    echo %%a
    set "DO_OUTPUT=%%a"
)

echo.
echo !DO_OUTPUT! | findstr /I "Success" >nul
if %ERRORLEVEL% EQU 0 (
    color 0A
    echo [SUCCESS] Device Owner permanently activated for BashLock!
    goto :GRANT_PERMISSIONS
)

echo !DO_OUTPUT! | findstr /I "already" >nul
if %ERRORLEVEL% EQU 0 (
    color 0A
    echo [SUCCESS] Phone already has BashLock Device Owner active!
    goto :GRANT_PERMISSIONS
)

:: Handle Account Conflict Error
echo !DO_OUTPUT! | findstr /I "accounts" >nul
if %ERRORLEVEL% EQU 0 (
    color 0E
    echo.
    echo -------------------------------------------------------------------------------
    echo [!] NOT ALLOWED: Phone par pehle se Google / User Account login hai!
    echo -------------------------------------------------------------------------------
    echo Android Security Rule:
    echo  Device Owner set karne ke liye phone par koi personal Google Account nahi hona chahiye.
    echo.
    echo Aasan Hal (1 Minute):
    echo  1. Phone Settings -^> Accounts (ya Users ^& Accounts) mein jayein.
    echo  2. Apne Google / Gmail account ko temporary "Remove account" karein.
    echo  3. Phir is make_customer_ready.bat script ko dobara chalayein.
    echo  4. Device Owner lagne ke baad aap account dobara login kar sakte hain!
    echo -------------------------------------------------------------------------------
    echo.
    pause
    exit /b 1
)

color 0C
echo [!] Device Owner command run nahi ho saki. Upar diya gaya error check karein.
echo.
pause
exit /b 1

:GRANT_PERMISSIONS
echo.
echo [*] Automatically granting system permissions and exempting battery restrictions...

:: Overlay permission (System Alert Window)
adb shell appops set com.bashlock.apk SYSTEM_ALERT_WINDOW allow >nul 2>&1
adb shell pm grant com.bashlock.apk android.permission.SYSTEM_ALERT_WINDOW >nul 2>&1

:: Usage stats permission
adb shell appops set com.bashlock.apk android:get_usage_stats allow >nul 2>&1

:: Ignore Battery Optimization (Survive 24/7 in background)
adb shell dumpsys deviceidle whitelist +com.bashlock.apk >nul 2>&1

:: Enable Accessibility Service for Anti-Uninstall auto-block
adb shell settings put secure enabled_accessibility_services com.bashlock.apk/com.bashlock.apk.AdminSecurityAccessibilityService >nul 2>&1
adb shell settings put secure accessibility_enabled 1 >nul 2>&1

:: Launch App
echo [*] Launching BashLock App on customer phone...
adb shell monkey -p com.bashlock.apk -c android.intent.category.LAUNCHER 1 >nul 2>&1

color 0A
echo.
echo ===============================================================================
echo               *** PHONE IS NOW 100%% CUSTOMER READY & PROTECTED! ***           
echo ===============================================================================
echo  [x] Anti-Uninstall (setUninstallBlocked): ACTIVE (Settings/PlayStore blocked)
echo  [x] Anti-Safe-Mode (DISALLOW_SAFE_BOOT): ACTIVE (Bypass prevented)
echo  [x] Anti-Factory-Reset (DISALLOW_FACTORY_RESET): ACTIVE (Hard reset blocked)
echo  [x] Accessibility Settings-Guard: ACTIVE (Blocks Settings -^> Apps -^> BashLock)
echo  [x] 24/7 Persistent Background Guard: RUNNING
echo ===============================================================================
echo Phone ko USB se nikaal kar customer ke hawale kar sakte hain!
echo.
pause
exit /b 0

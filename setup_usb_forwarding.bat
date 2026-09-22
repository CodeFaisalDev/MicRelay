@echo off
title MicRelay - Setup USB Port Forwarding
echo Setting up ADB port forwarding for MicRelay (Port 45454)...
adb forward tcp:45454 tcp:45454
if %ERRORLEVEL% EQU 0 (
    echo.
    echo [SUCCESS] Port 45454 forwarded over USB!
    echo Audio will now stream over USB cable with ultra-low latency without needing WiFi.
) else (
    echo.
    echo [ERROR] Failed to run adb forward. Make sure your Android device is connected with USB Debugging enabled.
)
echo.
pause

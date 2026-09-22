@echo off
echo =====================================================================
echo   MicRelay - Install Virtual Microphone Driver for OBS / NVIDIA Broadcast
echo =====================================================================
echo.
echo This will install the official, free VB-Audio Virtual Cable driver.
echo A Windows UAC Administrator prompt will appear.
echo Please click "Install Driver" in the installer window that opens.
echo.
pause
cd /d "%~dp0tools\vbcable"
powershell -Command "Start-Process 'VBCABLE_Setup_x64.exe' -Verb RunAs"
echo.
echo =====================================================================
echo Once installation is complete, close and restart MicRelay PC Receiver.
echo You will see 'CABLE Input' in the dropdown. In OBS / NVIDIA Broadcast,
echo select 'CABLE Output' as your Microphone device!
echo =====================================================================
pause

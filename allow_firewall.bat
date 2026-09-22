@echo off
echo =====================================================================
echo   MicRelay - Windows Firewall & Network Auto-Fix
echo =====================================================================
echo.
echo Allowing incoming UDP and TCP traffic on port 45454 for MicRelay...
echo Requesting Administrator privileges...
echo.

powershell -Command "Start-Process powershell -ArgumentList '-NoProfile -Command Set-NetConnectionProfile -InterfaceAlias Ethernet -NetworkCategory Private -ErrorAction SilentlyContinue; New-NetFirewallRule -DisplayName ''MicRelay Receiver UDP'' -Direction Inbound -Protocol UDP -LocalPort 45454 -Action Allow -ErrorAction SilentlyContinue; New-NetFirewallRule -DisplayName ''MicRelay Receiver TCP'' -Direction Inbound -Protocol TCP -LocalPort 45454 -Action Allow -ErrorAction SilentlyContinue; Write-Host ''Firewall Rules Added Successfully!'' -ForegroundColor Green; Start-Sleep -Seconds 3' -Verb RunAs"

echo.
echo Done! Port 45454 is now open for your phone connection.
pause

@echo off
title MicRelay - PC Audio Receiver
cd /d "%~dp0pc-receiver"
python main.py
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo An error occurred running the PC Receiver.
    pause
)

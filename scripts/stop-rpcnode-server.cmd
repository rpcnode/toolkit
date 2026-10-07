@echo off
rem Launcher for stop-rpcnode-server.ps1 (works even when PowerShell script execution is disabled).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-rpcnode-server.ps1" %*
exit /b %ERRORLEVEL%

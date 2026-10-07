@echo off
rem Launcher for build-rpcnode-cdn.ps1 (works even when PowerShell script execution is disabled).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-rpcnode-cdn.ps1" %*
exit /b %ERRORLEVEL%

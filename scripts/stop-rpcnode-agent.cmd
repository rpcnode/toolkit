@echo off
rem Launcher for stop-rpcnode-agent.ps1 (works even when PowerShell script execution is disabled).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-rpcnode-agent.ps1" %*
exit /b %ERRORLEVEL%

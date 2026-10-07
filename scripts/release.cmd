@echo off
rem Launcher for release.ps1 (works even when PowerShell script execution is disabled).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1" %*
exit /b %ERRORLEVEL%

@echo off
rem RpcNode: build, release, stop (Windows). Install/remove run on the Linux host: scripts/rpcnode.sh.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0lib\rpcnode.ps1" %*
exit /b %ERRORLEVEL%

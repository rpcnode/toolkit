# Windows entry point for build-rpcnode-server.sh (runs it through Git Bash; same arguments).
. "$PSScriptRoot\lib\Invoke-Bash.ps1"
Invoke-Bash -Script 'build-rpcnode-server.sh' -Arguments $args

# Windows entry point for build-rpcnode-cdn.sh (runs it through Git Bash; same arguments).
. "$PSScriptRoot\lib\Invoke-Bash.ps1"
Invoke-Bash -Script 'build-rpcnode-cdn.sh' -Arguments $args

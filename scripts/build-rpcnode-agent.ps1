# Windows entry point for build-rpcnode-agent.sh (runs it through Git Bash; same arguments).
. "$PSScriptRoot\lib\Invoke-Bash.ps1"
Invoke-Bash -Script 'build-rpcnode-agent.sh' -Arguments $args

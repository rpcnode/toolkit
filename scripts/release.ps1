# Windows entry point for release.sh (runs it through Git Bash; same arguments).
. "$PSScriptRoot\lib\Invoke-Bash.ps1"
Invoke-Bash -Script 'release.sh' -Arguments $args

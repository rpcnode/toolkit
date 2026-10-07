# Windows front of scripts\rpcnode.sh. `stop` is native (kills the dev JVM through PowerShell);
# everything else runs rpcnode.sh in Git Bash.
. "$PSScriptRoot\Invoke-Bash.ps1"

if ($args.Count -ge 2 -and $args[0] -eq 'stop') {
    switch ($args[1]) {
        'server' { & "$PSScriptRoot\stop-server.ps1"; exit $LASTEXITCODE }
        'agent'  { & "$PSScriptRoot\stop-agent.ps1";  exit $LASTEXITCODE }
    }
}
Invoke-Bash -Script 'rpcnode.sh' -Arguments $args

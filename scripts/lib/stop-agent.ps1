# Stop the local rpcnode-agent JVM (IntelliJ Agent run or a local rpcnode-agent.jar).
. "$PSScriptRoot\Stop-JavaByPattern.ps1"
Stop-JavaByPattern -Label 'rpcnode-agent' -Patterns @(
    'rpcnode\.toolkit\.agent\.presentation\.http\.AgentMainKt',
    'rpcnode-agent\.jar'
)

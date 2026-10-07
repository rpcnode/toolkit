# Stop the local rpcnode-server JVM (IntelliJ Server run or a local jar). Leaves Gradle daemons alone.
. "$PSScriptRoot\lib\Stop-JavaByPattern.ps1"
Stop-JavaByPattern -Label 'rpcnode-server' -Patterns @(
    'rpcnode\.toolkit\.server\.presentation\.http\.ApplicationKt',
    'rpcnode\.toolkit\.panel\.presentation\.http\.ApplicationKt',
    'rpcnode-server\.jar'
)

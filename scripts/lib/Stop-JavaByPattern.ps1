# Dot-sourced by stop-*.ps1: stop java processes whose command line matches any regex.
function Stop-JavaByPattern {
    param([string]$Label, [string[]]$Patterns)
    $procs = Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
        Where-Object {
            $cmd = $_.CommandLine
            $cmd -and ($Patterns | Where-Object { $cmd -match $_ })
        }
    if (-not $procs) {
        Write-Host "no $Label process"
        return
    }
    foreach ($p in $procs) {
        Write-Host "stopping $($p.ProcessId)"
        # Windows has no SIGTERM for console-less JVMs; Stop-Process is the equivalent of SIGKILL.
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

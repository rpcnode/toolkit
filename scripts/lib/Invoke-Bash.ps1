# Dot-sourced by the *.ps1 wrappers: runs a sibling .sh through Git for Windows' bash.
function Find-GitBash {
    $candidates = @()
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($git) {
        # …\Git\cmd\git.exe or …\Git\bin\git.exe -> …\Git\bin\bash.exe
        $gitRoot = Split-Path (Split-Path $git.Source -Parent) -Parent
        $candidates += Join-Path $gitRoot 'bin\bash.exe'
    }
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, "$env:LOCALAPPDATA\Programs")) {
        if ($base) { $candidates += Join-Path $base 'Git\bin\bash.exe' }
    }
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath $c) { return $c }
    }
    throw 'Git for Windows (bash.exe) not found. Install it from https://git-scm.com/download/win'
}

function Invoke-Bash {
    param([string]$Script, [string[]]$Arguments)
    $bash = Find-GitBash
    $path = Join-Path $PSScriptRoot "..\$Script"
    & $bash $path @Arguments
    exit $LASTEXITCODE
}

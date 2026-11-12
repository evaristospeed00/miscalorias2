$ErrorActionPreference = "Stop"

$preview = Join-Path $PSScriptRoot "preview.ps1"
& $preview @args
exit $LASTEXITCODE

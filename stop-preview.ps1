$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$sdkRoot = Join-Path $projectRoot ".android-sdk"
$androidUserHome = Join-Path $projectRoot ".android-user"
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_USER_HOME = $androidUserHome
$env:ANDROID_EMULATOR_HOME = $androidUserHome
$env:ANDROID_AVD_HOME = Join-Path $androidUserHome "avd"
$stateFile = Join-Path $projectRoot ".tmp\miscalorias-preview-state.json"

if (-not (Test-Path -LiteralPath $stateFile)) {
    Write-Host "No hay una vista previa activa."
    exit 0
}

$state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
$server = Get-CimInstance Win32_Process -Filter "ProcessId = $($state.serverPid)" -ErrorAction SilentlyContinue
$serverScript = Join-Path $projectRoot "preview-server.js"
if ($server -and $server.Name -eq "node.exe" -and $server.CommandLine -and $server.CommandLine.Contains($serverScript)) {
    Stop-Process -Id ([int]$state.serverPid)
}

if ([int]$state.emulatorPid -gt 0) {
    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"
    & $adb -s $state.serial emu kill | Out-Null
}

Remove-Item -LiteralPath $stateFile -Force
Write-Host "Vista previa cerrada."

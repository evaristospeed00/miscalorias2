$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$sdkRoot = Join-Path $projectRoot ".android-sdk"
$androidUserHome = Join-Path $projectRoot ".android-user"
$gradleAndroidUserHome = Join-Path $env:USERPROFILE ".android"
$grpcDiscoveryDirectory = Join-Path $env:LOCALAPPDATA "Temp\avd\running"
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_USER_HOME = $androidUserHome
$env:ANDROID_EMULATOR_HOME = $androidUserHome
$env:ANDROID_AVD_HOME = Join-Path $androidUserHome "avd"
$env:GRADLE_USER_HOME = Join-Path $projectRoot ".gradle-home"
$env:TEMP = Join-Path $projectRoot ".tmp"
$env:TMP = $env:TEMP

$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
$emulator = Join-Path $sdkRoot "emulator\emulator.exe"
$avdManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\avdmanager.bat"
$gradle = Join-Path $projectRoot "gradlew.bat"
$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$avdName = "MISCALORIAS_API_37"
$port = 8765
$stateFile = Join-Path $env:TEMP "miscalorias-preview-state.json"

foreach ($requiredFile in @($adb, $emulator, $avdManager, $gradle)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "No se encontró una herramienta necesaria: $requiredFile"
    }
}

New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
$avdConfig = Join-Path $env:ANDROID_AVD_HOME "$avdName.ini"
if (-not (Test-Path -LiteralPath $avdConfig)) {
    Write-Host "Creando el emulador Android por primera vez..."
    & $avdManager create avd --name $avdName --package "system-images;android-37.0;google_apis;x86_64" --device "pixel_6" --force
    if ($LASTEXITCODE -ne 0) { throw "No se pudo crear el emulador $avdName." }
}

Write-Host "Preparando la app..."
$env:ANDROID_USER_HOME = $gradleAndroidUserHome
try {
    & $gradle --no-daemon --console=plain assembleDebug
    $buildExitCode = $LASTEXITCODE
} finally {
    $env:ANDROID_USER_HOME = $androidUserHome
}
if ($buildExitCode -ne 0) { throw "La compilación de la app falló." }

$null = & $adb start-server
$serial = $null
$deviceState = $null
$deviceDeadline = $null
$emulatorPid = 0
$deviceOutput = & $adb devices
foreach ($line in $deviceOutput) {
    if ($line -match "^(emulator-\d+)\s+(device|offline|unauthorized)") {
        $serial = $Matches[1]
        $deviceState = $Matches[2]
        break
    }
}

if (-not $serial) {
    Write-Host "Iniciando Android sin ventana (la primera vez puede tardar un poco)..."
    $emulatorProcess = Start-Process -FilePath $emulator `
        -ArgumentList @("-avd", $avdName, "-no-window", "-no-audio", "-no-boot-anim", "-gpu", "host", "-memory", "4096", "-grpc", "8554", "-grpc-use-token") `
        -WorkingDirectory $projectRoot -PassThru
    $emulatorPid = $emulatorProcess.Id
    $deviceDeadline = (Get-Date).AddMinutes(8)
}

if ($deviceState -eq "unauthorized") { throw "ADB no está autorizado para este emulador." }
if (-not $deviceDeadline) { $deviceDeadline = (Get-Date).AddMinutes(8) }
while ((Get-Date) -lt $deviceDeadline -and $deviceState -ne "device") {
    Start-Sleep -Seconds 3
    $deviceOutput = & $adb devices
    foreach ($line in $deviceOutput) {
        if ($line -match "^(emulator-\d+)\s+(device|offline|unauthorized)") {
            $serial = $Matches[1]
            $deviceState = $Matches[2]
            break
        }
    }
}

if (-not $serial -or $deviceState -ne "device") { throw "Android no quedó disponible en ADB. Vuelve a ejecutar .\preview.bat." }

Write-Host "Esperando a que Android termine de iniciar..."
$bootDeadline = (Get-Date).AddMinutes(5)
$bootStatus = ""
while ((Get-Date) -lt $bootDeadline -and $bootStatus -ne "1") {
    $bootStatus = (& $adb -s $serial shell getprop sys.boot_completed | Select-Object -First 1)
    if ($bootStatus) { $bootStatus = $bootStatus.Trim() }
    if ($bootStatus -ne "1") { Start-Sleep -Seconds 3 }
}
if ($bootStatus -ne "1") { throw "Android no terminó de iniciar." }

Write-Host "Optimizando la pantalla del emulador..."
& $adb -s $serial shell wm size 720x1600
if ($LASTEXITCODE -ne 0) { throw "No se pudo ajustar la resolución del emulador." }
& $adb -s $serial shell wm density 280
if ($LASTEXITCODE -ne 0) { throw "No se pudo ajustar la densidad del emulador." }

Write-Host "Abriendo MISCALORIAS..."
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "No se pudo instalar la app." }
& $adb -s $serial shell am start -n "com.evaristosuarez.miscalorias/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw "No se pudo iniciar MISCALORIAS." }

$grpcToken = $null
$grpcDeadline = (Get-Date).AddSeconds(10)
$avdPattern = "(?m)^avd\.name\s*=\s*$([regex]::Escape($avdName))\s*$"
while ((Get-Date) -lt $grpcDeadline -and -not $grpcToken) {
    if (Test-Path -LiteralPath $grpcDiscoveryDirectory) {
        foreach ($discoveryFile in Get-ChildItem -LiteralPath $grpcDiscoveryDirectory -Filter "pid_*.ini" -File) {
            $discovery = Get-Content -LiteralPath $discoveryFile.FullName -Raw
            if ($discovery -match $avdPattern -and $discovery -match "(?m)^grpc\.port\s*=\s*8554\s*$") {
                $tokenMatch = [regex]::Match($discovery, "(?m)^grpc\.token\s*=\s*(.+?)\s*$")
                if ($tokenMatch.Success) {
                    $grpcToken = $tokenMatch.Groups[1].Value
                    break
                }
            }
        }
    }
    if (-not $grpcToken) { Start-Sleep -Milliseconds 250 }
}
if (-not $grpcToken) { throw "No se pudo obtener el token seguro del canal de video del emulador." }

try {
    Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 2 | Out-Null
    throw "El puerto $port ya está ocupado. Cierra la vista previa anterior con .\stop-preview.bat."
} catch {
    if ($_.Exception.Message -like "*ya está ocupado*") { throw }
}

$nodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
if (-not $nodeCommand) { throw "No se encontró Node.js. Instálalo para usar la vista previa." }

$serverScript = Join-Path $projectRoot "preview-server.js"
$env:MISCALORIAS_PREVIEW_ADB = $adb
$env:MISCALORIAS_PREVIEW_SERIAL = $serial
$env:MISCALORIAS_PREVIEW_PORT = "$port"
$env:MISCALORIAS_PREVIEW_GRPC_TOKEN = $grpcToken
$env:MISCALORIAS_PREVIEW_GRPC_PORT = "8554"
$env:MISCALORIAS_PREVIEW_WIDTH = "720"
$env:MISCALORIAS_PREVIEW_HEIGHT = "1600"
$logFile = Join-Path $env:TEMP "miscalorias-preview.log"
$errorFile = Join-Path $env:TEMP "miscalorias-preview-error.log"
$serverProcess = Start-Process -FilePath $nodeCommand.Source `
    -ArgumentList @("`"$serverScript`"") `
    -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $logFile -RedirectStandardError $errorFile
Remove-Item Env:\MISCALORIAS_PREVIEW_GRPC_TOKEN -ErrorAction SilentlyContinue
$grpcToken = $null

$serverReady = $false
$serverDeadline = (Get-Date).AddSeconds(10)
while ((Get-Date) -lt $serverDeadline -and -not $serverReady) {
    if ($serverProcess.HasExited) {
        $details = if (Test-Path $errorFile) { Get-Content $errorFile -Raw } else { "" }
        throw "No se pudo iniciar el servidor local. $details"
    }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1
        $serverReady = $health.ok -and $health.serial -eq $serial -and $health.streamReady
    } catch {
        Start-Sleep -Milliseconds 250
    }
}
if (-not $serverReady) { throw "La vista previa no respondió en http://localhost:$port." }

$state = [ordered]@{
    serverPid = $serverProcess.Id
    emulatorPid = $emulatorPid
    serial = $serial
    port = $port
}
$state | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
Start-Process "http://localhost:$port"
Write-Host ""
Write-Host "Listo: http://localhost:$port"
Write-Host "Toca la pantalla en el navegador para usar la app. Para cerrar: .\stop-preview.bat"

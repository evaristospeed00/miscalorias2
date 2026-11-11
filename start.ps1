$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$sdkRoot = Join-Path $projectRoot ".android-sdk"
$androidUserHome = Join-Path $projectRoot ".android-user"
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
$applicationId = "com.evaristosuarez.miscalorias"
$avdName = "MISCALORIAS_API_37"

foreach ($requiredFile in @($adb, $emulator, $avdManager, $gradle)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "No se encontró una herramienta necesaria: $requiredFile"
    }
}

$avdConfig = Join-Path $env:ANDROID_AVD_HOME "$avdName.ini"
if (-not (Test-Path -LiteralPath $avdConfig)) {
    Write-Host "Creando el emulador Android 37..."
    & $avdManager create avd --name $avdName --package "system-images;android-37.0;google_apis;x86_64" --device "pixel_6" --force
    if ($LASTEXITCODE -ne 0) {
        throw "No se pudo crear el emulador $avdName."
    }
}

Write-Host "Compilando MISCALORIAS..."
& $gradle --no-daemon --console=plain assembleDebug
if ($LASTEXITCODE -ne 0) {
    throw "La compilación de MISCALORIAS falló."
}

$deviceOutput = & $adb devices
$serial = $null
$deviceState = $null
foreach ($line in $deviceOutput) {
    if ($line -match "^(emulator-\d+)\s+(device|offline|unauthorized)") {
        $serial = $Matches[1]
        $deviceState = $Matches[2]
        break
    }
}

if (-not $serial) {
    Write-Host "Iniciando el emulador; el primer arranque puede tardar unos minutos..."
    Start-Process -FilePath $emulator -ArgumentList @("-avd", $avdName, "-no-boot-anim", "-gpu", "host", "-memory", "4096") -WorkingDirectory $projectRoot
    $deviceDeadline = (Get-Date).AddMinutes(10)
} else {
    $deviceDeadline = (Get-Date).AddMinutes(10)
    Write-Host "Esperando a que el emulador termine de iniciar (estado ADB: $deviceState)..."
}

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

if (-not $serial -or $deviceState -ne "device") {
    throw "El emulador no quedó listo en ADB dentro del tiempo esperado."
}

$bootDeadline = (Get-Date).AddMinutes(5)
$bootStatus = ""
while ((Get-Date) -lt $bootDeadline -and $bootStatus -ne "1") {
    $bootStatus = (& $adb -s $serial shell getprop sys.boot_completed | Select-Object -First 1)
    if ($bootStatus) {
        $bootStatus = $bootStatus.Trim()
    }
    if ($bootStatus -ne "1") {
        Start-Sleep -Seconds 3
    }
}

if ($bootStatus -ne "1") {
    throw "Android no terminó de iniciar en el emulador."
}

Write-Host "Instalando y abriendo MISCALORIAS..."
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "No se pudo instalar el APK en el emulador."
}

& $adb -s $serial shell am start -n "$applicationId/.MainActivity"
if ($LASTEXITCODE -ne 0) {
    throw "No se pudo abrir MISCALORIAS en el emulador."
}

function Move-AndroidEmulatorWindow {
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ("MiscaloriasWindowApi" -as [type])) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class MiscaloriasWindowApi {
    [StructLayout(LayoutKind.Sequential)]
    public struct Rect {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool GetWindowRect(IntPtr hWnd, out Rect rect);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int x, int y, int width, int height, uint flags);

    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int command);
}
"@ | Out-Null
    }

    $windowDeadline = (Get-Date).AddSeconds(30)
    $window = $null
    while ((Get-Date) -lt $windowDeadline -and -not $window) {
        $window = Get-Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.MainWindowHandle -ne 0 -and
                $_.MainWindowTitle -like "Android Emulator - $avdName*"
            } |
            Select-Object -First 1
        if (-not $window) {
            Start-Sleep -Seconds 1
        }
    }

    if (-not $window) {
        throw "No se encontró la ventana del emulador $avdName."
    }

    $windowHandle = $window.MainWindowHandle
    $rect = New-Object MiscaloriasWindowApi+Rect
    if (-not [MiscaloriasWindowApi]::GetWindowRect($windowHandle, [ref]$rect)) {
        $win32Error = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
        throw "No se pudo leer la posición de la ventana del emulador (Win32 $win32Error)."
    }

    $area = [System.Windows.Forms.Screen]::FromHandle($windowHandle).WorkingArea
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    $x = $area.Left + [Math]::Max(0, [Math]::Floor(($area.Width - $width) / 2))
    $y = $area.Top + [Math]::Max(0, [Math]::Floor(($area.Height - $height) / 2))

    [void][MiscaloriasWindowApi]::ShowWindow($windowHandle, 9)
    if (-not [MiscaloriasWindowApi]::SetWindowPos($windowHandle, [IntPtr]::Zero, $x, $y, $width, $height, 0x0040)) {
        $win32Error = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
        throw "No se pudo centrar la ventana del emulador (Win32 $win32Error)."
    }
}

Move-AndroidEmulatorWindow
Write-Host "MISCALORIAS esta abierta en el emulador Android."

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $DeviceSerial,

    [Parameter()]
    [string] $NdkPath,

    [Parameter()]
    [string] $AdbPath,

    [Parameter()]
    [string] $BuildSubdirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-LoggedNative {
    param([string] $Executable, [string[]] $Arguments, [string] $LogPath)
    $commandDescription = '> {0} {1}' -f $Executable, ($Arguments -join ' ')
    Write-Host $commandDescription
    Set-Content -LiteralPath $LogPath -Value $commandDescription -Encoding utf8
    & $Executable @Arguments 2>&1 | Tee-Object -FilePath $LogPath -Append
    if ($LASTEXITCODE -ne 0) {
        throw "Native command failed with exit code $LASTEXITCODE; see $LogPath"
    }
}

if ([string]::IsNullOrWhiteSpace($NdkPath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_NDK_HOME)) {
        $NdkPath = $env:ANDROID_NDK_HOME
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_NDK_ROOT)) {
        $NdkPath = $env:ANDROID_NDK_ROOT
    } else {
        $NdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk/ndk/21.4.7075529'
    }
}
if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $AdbPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
}
$AdbPath = (Resolve-Path -LiteralPath $AdbPath).Path
$NdkPath = (Resolve-Path -LiteralPath $NdkPath).Path
$llvmDirectory = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64'
$compiler = (Resolve-Path -LiteralPath (Join-Path $llvmDirectory 'bin/clang++.exe')).Path
$sysroot = (Resolve-Path -LiteralPath (Join-Path $llvmDirectory 'sysroot')).Path
$jniDirectory = Join-Path $PSScriptRoot 'jni'

$runId = '{0}-{1}' -f [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'),
    [Guid]::NewGuid().ToString('N').Substring(0, 8)
if ([string]::IsNullOrWhiteSpace($BuildSubdirectory)) {
    $BuildSubdirectory = "android-tests-$runId"
}
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))
$buildDirectory = [System.IO.Path]::GetFullPath((Join-Path $buildRoot $BuildSubdirectory))
if (-not $buildDirectory.StartsWith($buildRoot + [System.IO.Path]::DirectorySeparatorChar,
                                  [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Test output must be a new directory under $buildRoot"
}
if (Test-Path -LiteralPath $buildDirectory) {
    throw "Test output already exists; choose a new -BuildSubdirectory: $buildDirectory"
}
New-Item -ItemType Directory -Path $buildDirectory -Force | Out-Null

$abi = & $AdbPath -s $DeviceSerial shell getprop ro.product.cpu.abi
if ($LASTEXITCODE -ne 0 -or "$abi".Trim() -ne 'arm64-v8a') {
    throw 'Standalone Android harness requires an available arm64-v8a device'
}
$pageSize = & $AdbPath -s $DeviceSerial shell getconf PAGESIZE
if ($LASTEXITCODE -ne 0 -or "$pageSize".Trim() -ne '4096') {
    throw 'The real-mapping fingerprint harness requires 4096-byte pages'
}
"Device: $DeviceSerial; ABI: $abi; page size: $pageSize" |
    Tee-Object -FilePath (Join-Path $buildDirectory 'device.log')

$testNames = @('secneo_patch_host_test', 'monitor_policy_host_test')
$commonArguments = @(
    '--target=aarch64-linux-android28', "--sysroot=$sysroot", '-std=c++17',
    '-D_DEFAULT_SOURCE', '-O2', '-Wall', '-Wextra', '-Wpedantic', '-Werror',
    '-fno-exceptions', '-fno-rtti', '-fno-threadsafe-statics', '-nostdlib++',
    '-fPIE', '-pie', "-I$jniDirectory"
)
foreach ($testName in $testNames) {
    $compilerArguments = $commonArguments + @(
        (Join-Path $PSScriptRoot "tests/$testName.cpp"),
        (Join-Path $jniDirectory 'secneo_patch.cpp'),
        (Join-Path $jniDirectory 'monitor_policy.cpp'),
        '-o', (Join-Path $buildDirectory $testName)
    )
    Invoke-LoggedNative -Executable $compiler -Arguments $compilerArguments `
        -LogPath (Join-Path $buildDirectory "$testName-compile.log")
}

# This directory contains only the two standalone test processes, never a product module.
$remoteDirectory = "/data/local/tmp/freemomo-native-tests-$runId"
$adbPrefix = @('-s', $DeviceSerial)
try {
    Invoke-LoggedNative -Executable $AdbPath -Arguments ($adbPrefix + @('shell', 'mkdir', $remoteDirectory)) `
        -LogPath (Join-Path $buildDirectory 'device-mkdir.log')
    foreach ($testName in $testNames) {
        $remoteExecutable = "$remoteDirectory/$testName"
        Invoke-LoggedNative -Executable $AdbPath `
            -Arguments ($adbPrefix + @('push', (Join-Path $buildDirectory $testName), $remoteExecutable)) `
            -LogPath (Join-Path $buildDirectory "$testName-push.log")
        Invoke-LoggedNative -Executable $AdbPath `
            -Arguments ($adbPrefix + @('shell', 'chmod', '700', $remoteExecutable)) `
            -LogPath (Join-Path $buildDirectory "$testName-chmod.log")
        Invoke-LoggedNative -Executable $AdbPath -Arguments ($adbPrefix + @('shell', $remoteExecutable)) `
            -LogPath (Join-Path $buildDirectory "$testName-run.log")
    }
} finally {
    # Remove only our named executables and then the empty, uniquely named directory.
    & $AdbPath @adbPrefix shell rm -f "$remoteDirectory/secneo_patch_host_test" `
        "$remoteDirectory/monitor_policy_host_test"
    & $AdbPath @adbPrefix shell rmdir $remoteDirectory
}
Write-Host "Standalone native test logs: $buildDirectory"

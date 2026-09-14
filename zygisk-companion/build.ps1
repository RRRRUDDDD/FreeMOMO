[CmdletBinding()]
param(
    [Parameter()]
    [string] $NdkPath,

    [Parameter()]
    [string] $BuildSubdirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-ExistingDirectory {
    param(
        [Parameter(Mandatory)]
        [string] $Path,

        [Parameter(Mandatory)]
        [string] $Description
    )

    try {
        $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    } catch {
        throw "$Description does not exist: $Path"
    }

    if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
        throw "$Description is not a directory: $resolved"
    }

    return [System.IO.Path]::GetFullPath($resolved)
}

function Resolve-ProjectChild {
    param(
        [Parameter(Mandatory)]
        [string] $ProjectDirectory,

        [Parameter(Mandatory)]
        [string] $RelativePath
    )

    $projectFull = [System.IO.Path]::GetFullPath($ProjectDirectory).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $projectFull $RelativePath))
    $prefix = $projectFull + [System.IO.Path]::DirectorySeparatorChar

    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing path outside the companion project: $candidate"
    }

    return $candidate
}

function Remove-ProjectDirectory {
    param(
        [Parameter(Mandatory)]
        [string] $Path,

        [Parameter(Mandatory)]
        [string] $ProjectDirectory
    )

    $projectFull = [System.IO.Path]::GetFullPath($ProjectDirectory).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $pathFull = [System.IO.Path]::GetFullPath($Path)
    $prefix = $projectFull + [System.IO.Path]::DirectorySeparatorChar

    if (-not $pathFull.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean path outside the companion project: $pathFull"
    }

    if (Test-Path -LiteralPath $pathFull) {
        Remove-Item -LiteralPath $pathFull -Recurse -Force
    }
}

$projectDirectory = Resolve-ExistingDirectory -Path $PSScriptRoot -Description 'Companion project directory'
$jniDirectory = Resolve-ExistingDirectory -Path (Join-Path $projectDirectory 'jni') -Description 'JNI source directory'
$moduleDirectory = Resolve-ExistingDirectory -Path (Join-Path $projectDirectory 'module') -Description 'Module template directory'

$ndkSource = $null
if (-not [string]::IsNullOrWhiteSpace($NdkPath)) {
    $ndkSource = $NdkPath
} elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_NDK_HOME)) {
    $ndkSource = $env:ANDROID_NDK_HOME
} elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_NDK_ROOT)) {
    $ndkSource = $env:ANDROID_NDK_ROOT
} elseif (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $ndkSource = Join-Path $env:LOCALAPPDATA 'Android/Sdk/ndk/21.4.7075529'
} else {
    throw 'NDK not found: set -NdkPath, ANDROID_NDK_HOME, ANDROID_NDK_ROOT, or LOCALAPPDATA'
}

$ndkDirectory = Resolve-ExistingDirectory -Path $ndkSource -Description 'Android NDK directory'
$ndkBuild = Join-Path $ndkDirectory 'ndk-build.cmd'
if (-not (Test-Path -LiteralPath $ndkBuild -PathType Leaf)) {
    throw "ndk-build.cmd was not found in the selected NDK: $ndkDirectory"
}

if ([string]::IsNullOrWhiteSpace($BuildSubdirectory)) {
    $BuildSubdirectory = 'release-{0}-{1}' -f [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'),
        [Guid]::NewGuid().ToString('N').Substring(0, 8)
}
$buildRoot = Resolve-ProjectChild -ProjectDirectory $projectDirectory -RelativePath 'build'
$buildDirectory = Resolve-ProjectChild -ProjectDirectory $buildRoot -RelativePath $BuildSubdirectory
if (Test-Path -LiteralPath $buildDirectory) {
    throw "Build output already exists; choose a new -BuildSubdirectory: $buildDirectory"
}
$objectDirectory = Resolve-ProjectChild -ProjectDirectory $buildDirectory -RelativePath 'obj'
$libraryDirectory = Resolve-ProjectChild -ProjectDirectory $buildDirectory -RelativePath 'libs'
New-Item -ItemType Directory -Path $buildDirectory -Force | Out-Null

$ndkArguments = @(
    '-C', $projectDirectory,
    "NDK_PROJECT_PATH=$projectDirectory",
    "APP_BUILD_SCRIPT=$(Join-Path $jniDirectory 'Android.mk')",
    "NDK_APPLICATION_MK=$(Join-Path $jniDirectory 'Application.mk')",
    "NDK_OUT=$objectDirectory",
    "NDK_LIBS_OUT=$libraryDirectory",
    'APP_ABI=arm64-v8a',
    'APP_OPTIM=release',
    'NDK_DEBUG=0'
)

Write-Host "Building with NDK: $ndkDirectory"
& $ndkBuild @ndkArguments
if ($LASTEXITCODE -ne 0) {
    throw "ndk-build failed with exit code $LASTEXITCODE"
}

$productionLibrary = Join-Path $libraryDirectory 'arm64-v8a/libmomo_zygisk.so'
if (-not (Test-Path -LiteralPath $productionLibrary -PathType Leaf)) {
    throw "Expected native output was not produced: $productionLibrary"
}

$moduleProperty = Join-Path $moduleDirectory 'module.prop'
$customizeScript = Join-Path $moduleDirectory 'customize.sh'
foreach ($moduleFile in @($moduleProperty, $customizeScript)) {
    if (-not (Test-Path -LiteralPath $moduleFile -PathType Leaf)) {
        throw "Required module file is missing: $moduleFile"
    }
}

$versionProperties = @(
    Get-Content -LiteralPath $moduleProperty |
        Where-Object { $_ -match '^version=' }
)
if ($versionProperties.Count -ne 1) {
    throw "Expected exactly one version property in: $moduleProperty"
}
$moduleVersion = $versionProperties[0].Substring('version='.Length).Trim()
if ($moduleVersion -notmatch '^[0-9]+(?:\.[0-9]+){2}(?:[-+][0-9A-Za-z.-]+)?$') {
    throw "Invalid module version '$moduleVersion' in: $moduleProperty"
}

$stagingDirectory = Resolve-ProjectChild -ProjectDirectory $buildDirectory -RelativePath 'staging'
$stagingZygiskDirectory = Resolve-ProjectChild -ProjectDirectory $buildDirectory -RelativePath 'staging/zygisk'
$artifactPath = Resolve-ProjectChild -ProjectDirectory $buildDirectory -RelativePath "freemomo-zygisk-$moduleVersion.zip"

New-Item -ItemType Directory -Path $stagingZygiskDirectory -Force | Out-Null
Copy-Item -LiteralPath $moduleProperty -Destination (Join-Path $stagingDirectory 'module.prop')
Copy-Item -LiteralPath $customizeScript -Destination (Join-Path $stagingDirectory 'customize.sh')
Copy-Item -LiteralPath $productionLibrary -Destination (Join-Path $stagingZygiskDirectory 'arm64-v8a.so')

Compress-Archive -Path (Join-Path $stagingDirectory '*') -DestinationPath $artifactPath -CompressionLevel Optimal
Remove-ProjectDirectory -Path $stagingDirectory -ProjectDirectory $buildDirectory

$artifact = Get-Item -LiteralPath $artifactPath
$hash = Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256
Write-Host "Artifact: $($artifact.FullName)"
Write-Host "SHA-256: $($hash.Hash)"

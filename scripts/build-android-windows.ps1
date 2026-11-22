param(
    [string] $AndroidSdk = $env:ANDROID_HOME,
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $Python = 'python.exe'
)

$ErrorActionPreference = 'Stop'
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not $JavaHome) { $JavaHome = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr' }
$root = Split-Path -Parent $PSScriptRoot
$ndk = Join-Path $AndroidSdk 'ndk/27.2.12479018'
$cmake = Join-Path $AndroidSdk 'cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $AndroidSdk 'cmake/3.22.1/bin/ninja.exe'
$buildTools = Join-Path $AndroidSdk 'build-tools/36.0.0'
$git = (Get-Command git.exe).Source
$bash = Join-Path (Split-Path (Split-Path $git)) 'bin/bash.exe'
$pythonExe = (Get-Command $Python).Source

function Run([string] $Command, [string[]] $Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command exited with code $LASTEXITCODE." }
}

function Get-Source([string] $Url, [string] $Directory, [string] $Commit, [string] $Tag) {
    if (-not (Test-Path "$Directory/.git")) {
        if ($Tag) {
            Run $git @('clone', '--depth', '1', '--branch', $Tag, $Url, $Directory)
        } else {
            Run $git @('init', $Directory)
            Run $git @('-C', $Directory, 'remote', 'add', 'origin', $Url)
            Run $git @('-C', $Directory, 'fetch', '--depth', '1', 'origin', $Commit)
            Run $git @('-C', $Directory, 'checkout', '--detach', 'FETCH_HEAD')
        }
    }
    if ($Commit -and ((Run $git @('-C', $Directory, 'rev-parse', 'HEAD')) -ne $Commit)) {
        throw "$Directory must be at commit $Commit."
    }
}

function Get-Archive([string] $Url, [string] $File, [string] $Sha256) {
    if (-not (Test-Path $File)) {
        Run curl.exe @('--fail', '--location', '--silent', '--show-error', $Url, '--output', $File)
    }
    if ((Get-FileHash -LiteralPath $File).Hash -ne $Sha256) { throw "SHA-256 mismatch for $File." }
}

foreach ($file in @("$JavaHome/bin/java.exe", "$AndroidSdk/platforms/android-36.1/android.jar",
        "$ndk/source.properties", $cmake, $ninja, "$buildTools/zipalign.exe", $bash)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing $file. See README.md." }
}

$previousEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'GRADLE_USER_HOME', 'PATH', 'PYTHONPATH', 'MOBILEPLAY_PYTHON')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location $root
try {
    New-Item -ItemType Directory -Force third_party/tools, third_party/src | Out-Null
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $root '.gradle-user' }
    $env:PATH = "$(Join-Path $root 'third_party/tools/protoc/bin');$(Split-Path $pythonExe);$env:PATH"
    $env:PYTHONPATH = "$(Join-Path $root 'third_party/tools/python');$env:PYTHONPATH"
    $env:MOBILEPLAY_PYTHON = $pythonExe

    Get-Source 'https://github.com/chiaki-ng/chiaki-ng.git' 'third_party/chiaki-ng' 'f30da0aaeb96fc0026cc03683153f682352e7d7c'
    foreach ($patchName in @('chiaki-host-protobuf.patch', 'chiaki-takion-ack.patch', 'chiaki-video-recovery.patch')) {
        $protocolPatch = Join-Path $PSScriptRoot $patchName
        & $git -C third_party/chiaki-ng apply --reverse --check $protocolPatch 2>$null
        if ($LASTEXITCODE -ne 0) {
            Run $git @('-C', 'third_party/chiaki-ng', 'apply', $protocolPatch)
        }
    }
    Run $git @('-C', 'third_party/chiaki-ng', 'submodule', 'update', '--init', '--depth', '1', '--',
        'third-party/nanopb', 'third-party/jerasure', 'third-party/gf-complete', 'third-party/curl')
    Get-Source 'https://github.com/openssl/openssl.git' 'third_party/src/openssl-3.5.9' '45e844fa2a14ec92d146bd8f5778ac130b6625fb' 'openssl-3.5.9'
    Get-Source 'https://github.com/xiph/opus.git' 'third_party/src/opus' 'ddbe48383984d56acd9e1ab6a090c54ca6b735a6' 'v1.5.2'
    Get-Source 'https://github.com/json-c/json-c.git' 'third_party/src/json-c' 'b4c371fa0cbc4dcbaccc359ce9e957a22988fb34' 'json-c-0.17-20230812'
    Get-Source 'https://github.com/miniupnp/miniupnp.git' 'third_party/src/miniupnp' 'b55145ec095652289a59c33603f3abafee898273' 'miniupnpc_2_2_8'

    if (-not (Test-Path 'third_party/tools/protoc/bin/protoc.exe')) {
        $protocUrl = 'https://github.com/protocolbuffers/protobuf/releases/download/v29.3/protoc-29.3-win64.zip'
        Get-Archive $protocUrl 'third_party/tools/protoc.zip' '57EA59E9F551AD8D71FFAA9B5CFBE0CA1F4E720972A1DB7EC2D12AB44BFF9383'
        Expand-Archive 'third_party/tools/protoc.zip' 'third_party/tools/protoc' -Force
    }
    if (-not (Test-Path 'third_party/tools/python/google/protobuf/__init__.py')) {
        Run $pythonExe @('-m', 'pip', 'install', '--target', 'third_party/tools/python', 'protobuf==5.29.3', 'zstandard==0.23.0')
    }

    $prefix = Join-Path $root 'third_party/android-deps/arm64-v8a'
    $opensslPrefix = Join-Path $prefix 'openssl-3.5.9'
    if (-not ((Test-Path "$opensslPrefix/lib/libcrypto.a") -and (Test-Path "$opensslPrefix/lib/libssl.a") -and
            (Test-Path "$opensslPrefix/include/openssl/ssl.h"))) {
        if (-not (Test-Path 'third_party/tools/usr/bin/make.exe')) {
            $makeUrl = 'https://repo.msys2.org/msys/x86_64/make-4.4.1-2-x86_64.pkg.tar.zst'
            Get-Archive $makeUrl 'third_party/tools/make.tar.zst' '2408AF61717DAE87B00C855B132769A125C708907FC94A46BB16DAE076113E5C'
            Run $pythonExe @('-c',
                'import pathlib, zstandard; p = pathlib.Path("third_party/tools/make.tar.zst"); p.with_suffix("").write_bytes(zstandard.ZstdDecompressor().decompress(p.read_bytes(), max_output_size=16000000))')
            Run "$env:SystemRoot/System32/tar.exe" @('-xf', 'third_party/tools/make.tar',
                '-C', 'third_party/tools', 'usr/bin/make.exe')
        }
        $perlVersion = Run $bash @('-c', 'perl -e ''print substr($^V, 1)''')
        Get-Source 'https://github.com/Perl/perl5.git' 'third_party/src/perl' '' "v$perlVersion"
        Run $bash @('scripts/build-openssl-windows.sh', $ndk, 'third_party/tools/usr/bin/make.exe')
    }

    if (-not (Test-Path "$prefix/lib/libopus.a")) {
        Run $cmake @('-S', 'third_party/src/opus', '-B', 'third_party/build/opus', '-G', 'Ninja',
            "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake",
            '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-24', '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
            "-DCMAKE_INSTALL_PREFIX=$prefix", '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=OFF',
            '-DOPUS_BUILD_PROGRAMS=OFF', '-DOPUS_BUILD_TESTING=OFF', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON')
        Run $cmake @('--build', 'third_party/build/opus', '--parallel', '8')
        Run $cmake @('--install', 'third_party/build/opus')
    }

    Run .\gradlew.bat @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', '--continue', '--console=plain')
    $apk = Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk'
    Run "$buildTools/apksigner.bat" @('verify', '--verbose', $apk)
    Run "$buildTools/zipalign.exe" @('-c', '-P', '16', '4', $apk)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        foreach ($library in @('libchiaki_jni.so', 'libc++_shared.so')) {
            $entry = $zip.GetEntry("lib/arm64-v8a/$library")
            if (-not $entry -or $entry.Length -eq 0) { throw "Missing ARM64 library $library in $apk." }
        }
    } finally {
        $zip.Dispose()
    }
    Get-FileHash -LiteralPath $apk
} finally {
    Pop-Location
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}

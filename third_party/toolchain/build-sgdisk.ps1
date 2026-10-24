param(
    [string]$NdkBin = $env:BOOTFORGE_NDK_BIN
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($NdkBin)) {
    $ndkRoot = $env:ANDROID_NDK_HOME
    if ([string]::IsNullOrWhiteSpace($ndkRoot)) { $ndkRoot = $env:ANDROID_NDK_ROOT }
    if (-not [string]::IsNullOrWhiteSpace($ndkRoot)) {
        $NdkBin = Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
    }
}
if ([string]::IsNullOrWhiteSpace($NdkBin)) {
    throw 'Set -NdkBin or BOOTFORGE_NDK_BIN, or provide ANDROID_NDK_HOME / ANDROID_NDK_ROOT.'
}
$NdkBin = (Resolve-Path -LiteralPath $NdkBin -ErrorAction Stop).Path
foreach ($tool in @('aarch64-linux-android28-clang.cmd', 'aarch64-linux-android28-clang++.cmd', 'llvm-strip.exe', 'llvm-readelf.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $NdkBin $tool) -PathType Leaf)) {
        throw "Missing NDK tool: $tool"
    }
}
$root = $PSScriptRoot
$sourceRoot = Join-Path $root 'aosp-gptfdisk-source'
$uuidRoot = Join-Path $root 'gptfdisk-source\android-gptfdisk-e1ca834eb471e63945a9c71052a6fbc3d9d15ba6'
$objectRoot = Join-Path $root 'sgdisk-build'
New-Item -ItemType Directory -Path $objectRoot -Force | Out-Null
$env:TMP = $objectRoot
$env:TEMP = $objectRoot
$objects = @()
foreach ($source in (Get-ChildItem -LiteralPath (Join-Path $uuidRoot 'uuid') -Filter '*.c' -File)) {
    $object = Join-Path $objectRoot ($source.BaseName + '.o')
    & "$NdkBin\aarch64-linux-android28-clang.cmd" -O2 -D_FILE_OFFSET_BITS=64 -DHAVE_CONFIG_H "-I$uuidRoot" -c $source.FullName -o $object
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $objects += $object
}
$cppNames = @('sgdisk','gptcl','crc32','support','guid','gptpart','mbrpart','basicmbr','mbr','gpt','bsd','parttypes','attributes','diskio','diskio-unix','android_popt')
foreach ($name in $cppNames) {
    $object = Join-Path $objectRoot ($name + '.o')
    & "$NdkBin\aarch64-linux-android28-clang++.cmd" -std=c++17 -O2 -D_FILE_OFFSET_BITS=64 -Wno-pragma-pack -Wno-macro-redefined "-I$sourceRoot" "-I$uuidRoot" -c (Join-Path $sourceRoot ($name + '.cc')) -o $object
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $objects += $object
}
$binary = Join-Path $root 'sgdisk-arm64'
& "$NdkBin\aarch64-linux-android28-clang++.cmd" -static -static-libstdc++ @objects -o $binary
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$NdkBin\llvm-strip.exe" --strip-all $binary
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$NdkBin\llvm-readelf.exe" -h -l -d $binary
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Get-FileHash -LiteralPath $binary -Algorithm SHA256

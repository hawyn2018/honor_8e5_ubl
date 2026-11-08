param(
    [Parameter(Mandatory = $true)][string]$NdkHome,
    [string]$Out,
    [switch]$Install
)
$ErrorActionPreference = "Stop"
$ublRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $Out) { $Out = Join-Path $ublRoot "build\aph136g142" }
$source = Join-Path $PSScriptRoot "src"
$cc = Join-Path $NdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android35-clang.cmd"
if (-not (Test-Path $cc)) { throw "NDK clang not found: $cc" }
New-Item -ItemType Directory -Force -Path $Out | Out-Null

& $cc -std=gnu11 -O2 -w -pthread -static "-I$source" -DPC98260_TARGET_APH136G142 `
    -o (Join-Path $Out "exploit-android-key-ptr") `
    (Join-Path $source "uaf_plant_61258.c") (Join-Path $source "stubs.c") (Join-Path $source "rootd.c")
if ($LASTEXITCODE -ne 0) { throw "98260 exploit build failed" }
& $cc -std=gnu11 -O2 -w -static -o (Join-Path $Out "pc98260-su") (Join-Path $source "su98260.c")
if ($LASTEXITCODE -ne 0) { throw "98260 su build failed" }
& $cc -std=gnu11 -O2 -w -static -o (Join-Path $Out "kaslr_slide_aph136g142") (Join-Path $source "kaslr_slide_aph136g142.c")
if ($LASTEXITCODE -ne 0) { throw "64560 KASLR build failed" }

foreach ($name in @("exploit-android-key-ptr", "pc98260-su", "kaslr_slide_aph136g142")) {
    $hash = (Get-FileHash -LiteralPath (Join-Path $Out $name) -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "$hash  $name"
}
if ($Install) {
    $profile = Join-Path $ublRoot "app\src\main\assets\root_extra\g142\98260\98260-aph136g142"
    $root64560 = Join-Path $ublRoot "app\src\main\assets\root_extra\g142\64650"
    $root98260 = Join-Path $ublRoot "app\src\main\assets\root_extra\g142\98260"
    New-Item -ItemType Directory -Force -Path $profile, $root64560, $root98260 | Out-Null
    Copy-Item (Join-Path $Out "exploit-android-key-ptr") $profile -Force
    Copy-Item (Join-Path $Out "pc98260-su") $profile -Force
    Copy-Item (Join-Path $Out "kaslr_slide_aph136g142") $root64560 -Force
    Copy-Item (Join-Path $PSScriptRoot "syms_aph136g142.txt") $profile -Force
    Copy-Item (Join-Path $PSScriptRoot "syms_aph136g142.txt") $root64560 -Force
    Copy-Item (Join-Path $PSScriptRoot "syms_aph136g142.txt") $root98260 -Force
    $lines = foreach ($name in @("exploit-android-key-ptr", "pc98260-su", "target.sh")) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $profile $name) -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $name"
    }
    [IO.File]::WriteAllText((Join-Path $profile "SHA256SUMS.txt"), (($lines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    Write-Host "Installed aph136g142 payloads into UBL"
}

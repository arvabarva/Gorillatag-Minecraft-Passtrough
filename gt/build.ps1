# Builds GorillaPassthrough.dll against Gorilla Tag's own assemblies and BepInEx, then copies it into Gorilla Tag's
# BepInEx plugins folder.
param([switch]$NoInstall)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$gt = 'C:\Program Files (x86)\Steam\steamapps\common\Gorilla Tag'
$managed = Join-Path $gt 'Gorilla Tag_Data\Managed'
$bepinex = Join-Path $gt 'BepInEx\core'
$csc = 'C:\Program Files\Microsoft Visual Studio\18\Community\MSBuild\Current\Bin\Roslyn\csc.exe'
$out = Join-Path $here 'bin'
New-Item -ItemType Directory -Force $out | Out-Null

$name = 'GorillaPassthrough'
$references = Get-ChildItem $managed -Filter *.dll | Where-Object { $_.Name -notmatch '^Valve\.Newtonsoft' } | ForEach-Object { "/reference:`"$($_.FullName)`"" }
$references += "/reference:`"$bepinex\BepInEx.dll`""
$references += "/reference:`"$bepinex\0Harmony.dll`""
$sources = Get-ChildItem (Join-Path $here 'src') -Recurse -Filter *.cs | ForEach-Object { "`"$($_.FullName)`"" }
$rsp = Join-Path $out "$name.rsp"
@(
    '/nologo', '/target:library', '/optimize+', '/debug:portable', '/nostdlib+', '/langversion:latest', '/unsafe+',
    '/nowarn:CS1701,CS1702,CS0436', "/out:`"$out\$name.dll`"",
    # the dll names its debug file and the debug file its sources: by this neutral path, not where it was built (no
    # Windows user name in shared files)
    '/deterministic+', "/pathmap:`"$(Split-Path -Parent $here)`"=/GorillaPassthrough"
) + $references + $sources | Set-Content $rsp -Encoding ascii
& $csc "@$rsp"
if ($LASTEXITCODE -ne 0) { throw "compiling $name failed" }
Write-Host "built $out\$name.dll"

if (-not $NoInstall) {
    if (Get-Process -Name 'Gorilla Tag' -ErrorAction SilentlyContinue) { throw 'Close Gorilla Tag first' }
    $plugins = Join-Path $gt 'BepInEx\plugins\GorillaPassthrough'
    New-Item -ItemType Directory -Force $plugins | Out-Null
    Copy-Item "$out\$name.dll", "$out\$name.pdb" $plugins -Force
    Write-Host "installed into $plugins"
}

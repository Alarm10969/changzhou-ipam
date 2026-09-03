$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'test-stage1.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Stage-one regression failed.' }
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$release=Join-Path $root 'release'
$cp=((Get-ChildItem (Join-Path $release 'lib') -Filter *.jar | ForEach-Object FullName) + (Join-Path $release 'classes')) -join ';'
& java -cp $cp com.icbc.changzhou.ipam.StageTwoSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'Stage-two regression failed.' }

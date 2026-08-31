$ErrorActionPreference='Stop'; $root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path; & (Join-Path $PSScriptRoot 'build-release.ps1') | Out-Host
$release=Join-Path $root 'release'; $cp=((Get-ChildItem (Join-Path $release 'lib') -Filter *.jar | ForEach-Object FullName) + (Join-Path $release 'classes')) -join ';'
& java -cp $cp com.icbc.changzhou.ipam.StageOneSmokeTest
if ($LASTEXITCODE -ne 0) { throw '第一阶段 SQLite 烟测失败。' }

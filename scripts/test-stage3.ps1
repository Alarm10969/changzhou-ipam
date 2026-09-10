$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'test-stage1.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Stage 1 regression failed.' }
& (Join-Path $PSScriptRoot 'test-stage2.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Stage 2 regression failed.' }
& (Join-Path $PSScriptRoot 'build-release.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
$release=Join-Path $root 'release'
$cp=((Get-ChildItem (Join-Path $release 'lib') -Filter *.jar | ForEach-Object FullName) + (Join-Path $release 'classes')) -join ';'
& java -cp $cp com.icbc.changzhou.ipam.StageThreeSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'Stage 3 regression failed.' }
& java -cp $cp com.icbc.changzhou.ipam.StageFourBaselineSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'Stage 4 baseline regression failed.' }
& java -cp $cp com.icbc.changzhou.ipam.StageFiveRemediationSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'Stage 5 remediation regression failed.' }
& java -cp $cp com.icbc.changzhou.ipam.StageSixLegacyUpgradeSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'Stage 6 legacy-upgrade regression failed.' }
& java -cp $cp com.icbc.changzhou.ipam.StageSevenSoftDeleteReuseSmokeTest
if ($LASTEXITCODE -ne 0) { throw 'Stage 7 soft-delete reuse regression failed.' }
